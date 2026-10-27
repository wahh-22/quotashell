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
import dev.wahh.quotashell.core.glitchElementIndices
import dev.wahh.quotashell.core.glitchGlyphIndices
import dev.wahh.quotashell.core.substituteGlitchGlyphs
import dev.wahh.quotashell.core.ui.appGlitchFrame
import java.util.WeakHashMap

private val bursts = WeakHashMap<Activity, View>()

/** Sample only visible leaf views. Never draw a parent that might contain credential drafts. */
internal fun Activity.glitchAppElements() {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    bursts.remove(this)?.let { root.overlay.remove(it) }
    val store = (application as QuotaApplication).store
    val start = SystemClock.uptimeMillis()
    val seed = start.toInt()

    fun frame() =
        appGlitchFrame(
            seed,
            SystemClock.uptimeMillis() - start,
            store.phantomTheme().notificationAnimation,
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f,
        )
    if (!frame().substituteGlyphs) return
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
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val origin = IntArray(2)
            private val position = IntArray(2)
            private val clip = Rect()
            private var current = frame()
            private var frameSeed = seed
            private var selectedViews = emptyList<View>()

            fun prepare() {
                current = frame()
                frameSeed = seed + ((SystemClock.uptimeMillis() - start) / 150).toInt()
                selectedViews = glitchElementIndices(frameSeed, views.size).map { views[it] }
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                if (!current.substituteGlyphs || !root.isShown || !frame().substituteGlyphs) return
                root.getLocationOnScreen(origin)
                for (view in selectedViews) {
                    if (!view.isShown) continue
                    if (!view.getGlobalVisibleRect(clip)) continue
                    clip.offset(-origin[0], -origin[1])
                    view.getLocationOnScreen(position)
                    canvas.save()
                    canvas.clipRect(clip)
                    canvas.translate((position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat())

                    fun drawElement() {
                        canvas.save()
                        canvas.translate(current.jitterX, 0f)
                        view.draw(canvas)
                        canvas.restore()
                        if (view is TextView && view.layout != null) {
                            val layout = view.layout
                            paint.set(view.paint)
                            paint.color = store.phantomTheme().color("accent").argb
                            for (line in 0 until layout.lineCount) {
                                val value =
                                    layout.text
                                        .subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                                        .toString()
                                        .trimEnd('\n')
                                val changed = substituteGlitchGlyphs(value, glitchGlyphIndices(value.length, frameSeed), frameSeed)
                                canvas.drawText(
                                    changed,
                                    view.totalPaddingLeft - view.scrollX + layout.getLineLeft(line) + current.rgbOffset,
                                    (view.totalPaddingTop - view.scrollY + layout.getLineBaseline(line)).toFloat(),
                                    paint,
                                )
                            }
                        }
                    }
                    drawElement()
                    PhantomGraphics.slices(canvas, view.width.toFloat(), view.height.toFloat(), current, {}, { drawElement() })
                    canvas.restore()
                }
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
