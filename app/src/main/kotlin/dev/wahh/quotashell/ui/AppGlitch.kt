package dev.wahh.quotashell

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import dev.wahh.quotashell.core.decodeReveal
import dev.wahh.quotashell.core.glitchElementIndices
import dev.wahh.quotashell.core.periodicGlitchEnabled
import dev.wahh.quotashell.core.ui.hudBurstDelay
import dev.wahh.quotashell.core.ui.hudBurstDuration
import java.util.WeakHashMap

private val motions = WeakHashMap<ViewGroup, HudMotion>()

// Non-input labels only. Preserve comparisons when a dashboard render replaces its native root.
private val settledScreens = WeakHashMap<Activity, Map<String, String>>()

internal fun Activity.installHudMotion(
    root: ViewGroup,
    openingReveal: Boolean,
) {
    if (motions.containsKey(root)) return
    motions[root] = HudMotion(this, root, openingReveal)
}

/** Existing activity callbacks request a comparison; only changed non-input cells reveal. */
internal fun Activity.glitchAppElements(opening: Boolean = false) {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    for (index in 0 until root.childCount) {
        (root.getChildAt(index) as? ViewGroup)?.let { motions[it]?.compare(opening) }
    }
}

/** App-local, noninteractive overlay. Never capture a parent, an EditText or a credential draft. */
private class HudMotion(
    private val activity: Activity,
    private val root: ViewGroup,
    openingReveal: Boolean,
) {
    private val store = (activity.application as QuotaApplication).store
    private val previous = settledScreens[activity].orEmpty().toMutableMap()
    private val changed = linkedMapOf<TextView, Pair<String, String>>()
    private val selectedViews = linkedMapOf<View, Bitmap>()
    private val originalAlphas = linkedMapOf<View, Float>()
    private var started = 0L
    private var duration = 900L
    private var loss = false
    private var stopped = false
    private var intro = openingReveal
    private var seed = SystemClock.uptimeMillis()
    private val origin = IntArray(2)
    private val position = IntArray(2)
    private val clip = Rect()
    private val effect =
        object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                if (!enabled()) return
                root.getLocationOnScreen(origin)
                for ((view, output) in selectedViews) {
                    if (!view.isShown || !view.getGlobalVisibleRect(clip)) continue
                    clip.offset(-origin[0], -origin[1])
                    view.getLocationOnScreen(position)
                    canvas.save()
                    canvas.clipRect(clip)
                    canvas.drawBitmap(output, (position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat(), null)
                    canvas.restore()
                }
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }
    private val frame = Runnable { prepare() }
    private val periodic =
        Runnable {
            if (enabled()) {
                changed.clear()
                loss = true
                started = SystemClock.uptimeMillis()
                seed = started
                duration = hudBurstDuration(seed)
                prepare()
            }
            schedule()
        }
    private val predraw =
        ViewTreeObserver.OnPreDrawListener {
            compare()
            true
        }
    private val focus =
        ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            cancel()
            if (focused) {
                intro = true
                compare()
                schedule()
            }
        }

    init {
        root.overlay.add(effect)
        root.viewTreeObserver.addOnPreDrawListener(predraw)
        root.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    stopped = true
                    cancel()
                    root.overlay.remove(effect)
                    if (root.viewTreeObserver.isAlive) {
                        root.viewTreeObserver.removeOnPreDrawListener(predraw)
                        root.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                    }
                    motions.remove(root)
                }
            },
        )
        schedule()
    }

    private fun enabled(): Boolean =
        !stopped && root.isShown && root.hasWindowFocus() &&
            periodicGlitchEnabled(
                store.phantomTheme().notificationAnimation,
                Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f,
            )

    private fun labels(): List<TextView> {
        val views = mutableListOf<TextView>()

        fun collect(view: View) {
            if (!view.isShown || view is EditText || view is CompoundButton) return
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            } else if (view is TextView && view.width > 0 && view.height > 0 &&
                view.contentDescription != "Local clock" && view.getGlobalVisibleRect(Rect())
            ) {
                views += view
            }
        }
        collect(root)
        return views
    }

    private fun labelKey(view: View): String {
        val path = mutableListOf<Int>()
        var child = view
        while (child !== root) {
            val parent = child.parent as? ViewGroup ?: break
            path += parent.indexOfChild(child)
            child = parent
        }
        return path.asReversed().joinToString("/")
    }

    fun compare(opening: Boolean = false) {
        if (stopped) return
        if (!enabled()) {
            restore()
            changed.clear()
            root.removeCallbacks(frame)
            return
        }
        val updates = linkedMapOf<TextView, Pair<String, String>>()
        for (view in labels()) {
            val value = view.text.toString()
            val key = labelKey(view)
            val old = if (intro || opening) "" else previous[key].orEmpty()
            if (old != value) updates[view] = old to value
            previous[key] = value
        }
        settledScreens[activity] = previous.toMap()
        intro = false
        if (updates.isEmpty()) return
        restore()
        changed.putAll(updates)
        loss = false
        duration = 900L
        seed = SystemClock.uptimeMillis()
        started = seed
        root.removeCallbacks(frame)
        prepare()
    }

    private fun schedule() {
        root.removeCallbacks(periodic)
        if (!stopped) root.postDelayed(periodic, hudBurstDelay(SystemClock.uptimeMillis()))
    }

    private fun restore() {
        val hadFrames = selectedViews.isNotEmpty()
        originalAlphas.forEach { (view, alpha) -> view.alpha = alpha }
        originalAlphas.clear()
        selectedViews.clear()
        if (hadFrames) effect.invalidate()
    }

    private fun cancel() {
        root.removeCallbacks(frame)
        root.removeCallbacks(periodic)
        restore()
        changed.clear()
    }

    private fun prepare() {
        if (stopped) return
        restore()
        val elapsed = SystemClock.uptimeMillis() - started
        if (!enabled() || elapsed >= duration) {
            changed.clear()
            return
        }
        effect.layout(0, 0, root.width, root.height)
        if (loss) {
            val views = labels()
            for (index in glitchElementIndices(seed.toInt(), views.size).take(1)) {
                val view = views[index]
                val source = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(source))
                val output = source.copy(Bitmap.Config.ARGB_8888, true)
                PhantomGraphics.signalLoss(
                    activity,
                    Canvas(output),
                    source,
                    store.phantomTheme(),
                    RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                    view.paint.measureText("M"),
                    view.lineHeight.toFloat(),
                    seed,
                    (elapsed / 67).toInt(),
                    font = view.typeface,
                )
                selectedViews[view] = output
                originalAlphas[view] = view.alpha
                view.alpha = 0f
            }
        } else {
            for ((row, entry) in changed.entries.withIndex()) {
                val (view, values) = entry
                if (!view.isShown || !view.getGlobalVisibleRect(clip)) continue
                // Render a detached native label: originals and accessibility text never scramble.
                val proxy =
                    TextView(activity).apply {
                        text = decodeReveal(values.first, values.second, seed, row, elapsed / 900.0)
                        typeface = view.typeface
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize)
                        setTextColor(view.textColors)
                        gravity = view.gravity
                        includeFontPadding = view.includeFontPadding
                        transformationMethod = view.transformationMethod
                        setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                        background =
                            view.background
                                ?.constantState
                                ?.newDrawable()
                                ?.mutate()
                                ?: if (view is Button) PhantomSurface(activity, store.phantomTheme(), button = true) else null
                        setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier)
                        measure(
                            View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
                        )
                        layout(0, 0, view.width, view.height)
                    }
                val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                proxy.draw(Canvas(output))
                selectedViews[view] = output
                originalAlphas[view] = view.alpha
                view.alpha = 0f
            }
        }
        effect.invalidate()
        root.postDelayed(frame, if (loss) 67 else 50)
    }
}
