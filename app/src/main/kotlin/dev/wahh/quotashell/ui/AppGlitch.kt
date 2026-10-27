package dev.wahh.quotashell

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import dev.wahh.quotashell.core.glitchElementIndices
import dev.wahh.quotashell.core.ui.appSignalActive
import java.util.WeakHashMap

private data class AppBurst(
    val view: View,
    val restore: () -> Unit,
)

private val bursts = WeakHashMap<Activity, AppBurst>()

/** Sample only visible leaves, never a parent containing credential drafts. Two loss frames, 300 ms. */
internal fun Activity.glitchAppElements() {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    bursts.remove(this)?.let {
        it.restore()
        root.overlay.remove(it.view)
    }
    val store = (application as QuotaApplication).store
    val start = SystemClock.uptimeMillis()
    val seed = start.toInt()

    fun active() =
        appSignalActive(
            SystemClock.uptimeMillis() - start,
            store.phantomTheme().notificationAnimation,
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f,
        )
    if (!active()) return
    val views = mutableListOf<View>()

    fun collect(view: View) {
        if (!view.isShown || view is EditText) return
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index))
        } else if (view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(Rect())) {
            views += view
        }
    }
    collect(root)
    val effect =
        object : View(this) {
            private val origin = IntArray(2)
            private val position = IntArray(2)
            private val clip = Rect()
            private var frame = 0
            private var stopped = false
            private val selectedViews = mutableMapOf<View, Bitmap>()
            private val originalAlphas = mutableMapOf<View, Float>()

            fun restore() {
                originalAlphas.forEach { (view, alpha) -> view.alpha = alpha }
                originalAlphas.clear()
                selectedViews.clear()
            }

            fun stop() {
                stopped = true
                restore()
            }

            fun prepare() {
                if (stopped) return
                restore()
                if (!active()) return
                frame = ((SystemClock.uptimeMillis() - start) / 150).toInt()
                val density = resources.displayMetrics.density
                // Capture once per frame, never allocate in onDraw. Originals are hidden briefly
                // so displaced transparent text cannot smear over its undisplaced source.
                for (index in glitchElementIndices(seed + frame, views.size)) {
                    val view = views[index]
                    if (!view.isShown || !view.getGlobalVisibleRect(clip)) continue
                    val source = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(source))
                    val output = source.copy(Bitmap.Config.ARGB_8888, true)
                    PhantomGraphics.signalLoss(
                        this@glitchAppElements,
                        Canvas(output),
                        source,
                        store.phantomTheme(),
                        RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                        7f * density,
                        12f * density,
                        seed.toLong(),
                        frame,
                    )
                    selectedViews[view] = output
                    originalAlphas[view] = view.alpha
                    view.alpha = 0f
                }
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                if (!active() || !root.isShown) {
                    restore()
                    return
                }
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
        }
    effect.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    effect.layout(0, 0, root.width, root.height)
    root.overlay.add(effect)
    val burst = AppBurst(effect) { effect.stop() }
    bursts[this] = burst
    effect.prepare()
    root.postDelayed({ effect.prepare() }, 150)
    root.postDelayed({
        effect.stop()
        root.overlay.remove(effect)
        if (bursts[this] === burst) bursts.remove(this)
    }, 300)
}
