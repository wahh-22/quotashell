package dev.wahh.quotashell

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.wahh.quotashell.core.glitchGlyphIndices
import dev.wahh.quotashell.core.substituteGlitchGlyphs
import dev.wahh.quotashell.core.ui.appGlitchFrame
import java.util.WeakHashMap

private val bursts = WeakHashMap<Activity, View>()

private data class GlitchLabel(
    val clip: Rect,
    val x: Float,
    val baseline: Float,
    val size: Float,
    val color: Int,
    val value: String,
)

/** A transparent, non-interactive element-only overlay. Never sample a credential field. */
internal fun Activity.glitchAppElements() {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    bursts.remove(this)?.let { root.overlay.remove(it) }
    val store = (application as QuotaApplication).store
    val theme = store.phantomTheme()
    val start = SystemClock.uptimeMillis()

    fun frame() =
        appGlitchFrame(
            42,
            SystemClock.uptimeMillis() - start,
            store.phantomTheme().notificationAnimation,
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f,
        )
    if (!frame().substituteGlyphs) return
    val origin = IntArray(2)
    root.getLocationOnScreen(origin)
    val labels = mutableListOf<GlitchLabel>()

    fun collect(view: View) {
        if (!view.isShown || view is EditText) return
        if (view is TextView && view.layout != null) {
            val rect = Rect()
            if (!view.getGlobalVisibleRect(rect)) return
            rect.offset(-origin[0], -origin[1])
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val layout = view.layout
            for (line in 0 until layout.lineCount) {
                val value =
                    theme.displayText(
                        layout.text
                            .subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                            .toString()
                            .trimEnd('\n'),
                    )
                labels +=
                    GlitchLabel(
                        rect,
                        position[0] - origin[0] + view.totalPaddingLeft - view.scrollX + layout.getLineLeft(line),
                        (position[1] - origin[1] + view.totalPaddingTop - view.scrollY + layout.getLineBaseline(line)).toFloat(),
                        view.textSize,
                        view.currentTextColor,
                        value,
                    )
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
    }
    collect(root)
    val effect =
        object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = PhantomGraphics.typeface(this@glitchAppElements, false) }
            private var current = frame()
            private var values = emptyList<String>()

            fun prepare() {
                current = frame()
                values = labels.map { substituteGlitchGlyphs(it.value, glitchGlyphIndices(it.value.length, 42), 42) }
                invalidate()
            }

            private fun glyphs(canvas: Canvas) {
                labels.forEachIndexed { index, label ->
                    canvas.save()
                    canvas.clipRect(label.clip)
                    paint.textSize = label.size
                    paint.color = theme.color("accent").argb
                    canvas.drawText(values[index], label.x - current.rgbOffset, label.baseline, paint)
                    paint.color = theme.color("notifications").argb
                    canvas.drawText(values[index], label.x + current.rgbOffset, label.baseline + current.jitterX, paint)
                    paint.color = label.color
                    canvas.drawText(values[index], label.x + current.jitterX, label.baseline, paint)
                    canvas.restore()
                }
            }

            override fun onDraw(canvas: Canvas) {
                if (!current.substituteGlyphs || !root.isShown || !frame().substituteGlyphs) return
                glyphs(canvas)
                PhantomGraphics.slices(canvas, width.toFloat(), height.toFloat(), current, {}, { glyphs(canvas) })
            }
        }
    effect.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    effect.layout(0, 0, root.width, root.height)
    root.overlay.add(effect)
    bursts[this] = effect
    effect.prepare()
    root.postDelayed({ effect.prepare() }, 150)
    root.postDelayed({
        root.overlay.remove(effect)
        if (bursts[this] === effect) bursts.remove(this)
    }, 300)
}
