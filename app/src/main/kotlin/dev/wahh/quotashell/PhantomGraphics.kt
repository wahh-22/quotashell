package dev.wahh.quotashell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.wahh.quotashell.core.GlitchFrame
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.glitchGlyphIndices
import dev.wahh.quotashell.core.substituteGlitchGlyphs
import kotlin.math.min

/** Shared Canvas primitives. Coordinates and text sizes are caller-owned logical units (dp).
 * Create after scaling a Canvas; usable by a widget renderer or an Activity/custom View.
 */
class PhantomGraphics(
    context: Context,
    private val canvas: Canvas,
    private val theme: PhantomTheme,
    private val frame: GlitchFrame = GlitchFrame(),
    private val seed: Int = 0,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = typeface(context, false)
    private val bold = typeface(context, true)

    fun measure(
        value: String,
        size: Float,
        heavy: Boolean = false,
    ): Float {
        configure(size, heavy, Paint.Align.LEFT)
        return paint.measureText(theme.displayText(value))
    }

    fun text(
        value: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        heavy: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        maxWidth: Float = Float.MAX_VALUE,
    ) {
        configure(size, heavy, align)
        val content = theme.displayText(value)
        var cut = content
        while (cut.isNotEmpty() && paint.measureText(cut) > maxWidth) cut = cut.dropLast(1)
        if (cut != content && cut.isNotEmpty()) cut = cut.dropLast(1) + "…"
        if (frame.substituteGlyphs) {
            val textSeed = seed xor value.hashCode()
            cut = substituteGlitchGlyphs(cut, glitchGlyphIndices(cut.length, textSeed), textSeed)
        }
        val offset =
            if (frame.rgbOffset > 0f) {
                frame.rgbOffset
            } else if (heavy && size >= 10f) {
                0.35f
            } else {
                0f
            }
        val jitter = frame.jitterX
        if (offset > 0f) {
            paint.color = theme.color("accent").argb
            paint.alpha = ((paint.color ushr 24) * 0.45f).toInt()
            canvas.drawText(cut, x - offset + jitter, baseline, paint)
            paint.color = theme.color("notifications").argb
            paint.alpha = ((paint.color ushr 24) * 0.4f).toInt()
            canvas.drawText(cut, x + offset + jitter, baseline + 0.3f, paint)
        }
        paint.color = color
        canvas.drawText(cut, x + jitter, baseline, paint)
    }

    private fun configure(
        size: Float,
        heavy: Boolean,
        align: Paint.Align,
    ) {
        paint.style = Paint.Style.FILL
        paint.textSize = (size + theme.fontSizeOffset).coerceAtLeast(3f)
        paint.typeface = if (heavy) bold else regular
        paint.textAlign = align
    }

    fun fill(
        path: Path,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
    }

    fun stroke(
        path: Path,
        color: Int,
        width: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        canvas.drawPath(path, paint)
    }

    fun buttonPath(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Path = panelPath(left, top, right, bottom, theme.renderButtonShape)

    fun notification(
        x: Float,
        y: Float,
        size: Float,
    ) {
        val shape = theme.renderNotificationShape
        if (shape == "NONE") return
        val path =
            Path().apply {
                when (shape) {
                    "CIRCLE" -> {
                        addCircle(x + size / 2, y + size / 2, size / 2, Path.Direction.CW)
                    }

                    "SQUARE" -> {
                        addRect(x, y, x + size, y + size, Path.Direction.CW)
                    }

                    "TRIANGLE" -> {
                        moveTo(x + size / 2, y)
                        lineTo(x + size, y + size)
                        lineTo(x, y + size)
                        close()
                    }

                    else -> {
                        moveTo(x, y)
                        lineTo(x + size, y)
                        lineTo(x + size / 2, y + size)
                        close()
                    }
                }
            }
        fill(path, theme.color("notifications").argb)
        text("!", x + size / 2, y + size * 0.7f, size * 0.6f, theme.color("window").argb, true, Paint.Align.CENTER)
    }

    companion object {
        private var regular: Typeface? = null
        private var bold: Typeface? = null

        @Synchronized
        fun typeface(
            context: Context,
            heavy: Boolean,
        ): Typeface {
            if (regular == null) {
                regular = Typeface.createFromAsset(context.assets, "fonts/AdwaitaMono-Regular.ttf")
                bold = Typeface.createFromAsset(context.assets, "fonts/AdwaitaMono-Bold.ttf")
            }
            return (if (heavy) bold else regular)!!
        }

        fun panelPath(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            shape: String,
        ): Path =
            Path().apply {
                when (shape) {
                    "TABBED" -> {
                        // Clamp the tab to the panel, including narrow app controls.
                        val tab = min(59f, (right - left) * 0.7f)
                        moveTo(left, top)
                        lineTo(right - tab, top)
                        lineTo(right - tab + 5, top + 5)
                        lineTo(right, top + 5)
                        lineTo(right, bottom)
                        lineTo(left + 7, bottom)
                        lineTo(left, bottom - 7)
                        close()
                    }

                    "BEVEL" -> {
                        val cut = min(7f, min((bottom - top) / 3, (right - left) / 3))
                        moveTo(left + cut, top)
                        lineTo(right - cut, top)
                        lineTo(right, top + cut)
                        lineTo(right, bottom - cut)
                        lineTo(right - cut, bottom)
                        lineTo(left + cut, bottom)
                        lineTo(left, bottom - cut)
                        lineTo(left, top + cut)
                        close()
                    }

                    else -> {
                        addRect(left, top, right, bottom, Path.Direction.CW)
                    }
                }
            }

        /** Repaint displaced scene strips, not decorative lines. Clear each destination first. */
        fun slices(
            canvas: Canvas,
            width: Float,
            height: Float,
            frame: GlitchFrame,
            clear: () -> Unit,
            draw: () -> Unit,
        ) {
            frame.slices.forEach { slice ->
                canvas.save()
                val top = slice.topFraction * height
                canvas.clipRect(0f, top, width, top + slice.heightFraction * height)
                clear()
                canvas.translate(slice.offset.toFloat(), 0f)
                draw()
                canvas.restore()
            }
        }
    }
}
