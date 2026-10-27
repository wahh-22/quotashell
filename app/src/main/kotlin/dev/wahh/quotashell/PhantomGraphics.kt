package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.decodeReveal
import dev.wahh.quotashell.core.signalLossPlan
import dev.wahh.quotashell.core.signalMix
import kotlin.math.min

/** Shared Canvas primitives. Coordinates and text sizes are caller-owned logical units (dp).
 * Create after scaling a Canvas; usable by a widget renderer or an Activity/custom View.
 */
class PhantomGraphics(
    context: Context,
    private val canvas: Canvas,
    private val theme: PhantomTheme,
    private val seed: Long = 0,
    private val previousText: Map<String, String> = emptyMap(),
    private val revealProgress: Double = 1.0,
    appTypeface: Typeface? = null,
) {
    val textRuns = mutableMapOf<String, String>()
    var protectedText = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = appTypeface ?: typeface(context, false)
    private val bold = appTypeface ?: typeface(context, true)

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
        val textWidth = paint.measureText(cut)
        val left =
            when (align) {
                Paint.Align.RIGHT -> x - textWidth
                Paint.Align.CENTER -> x - textWidth / 2
                else -> x
            }
        val key = "$x/$baseline/$size/$heavy/$align"
        textRuns[key] = cut
        val shown = if (protectedText) cut else decodeReveal(previousText[key] ?: "", cut, seed, key.hashCode(), revealProgress)
        paint.color = color
        if (shown == cut) {
            canvas.drawText(cut, x, baseline, paint)
        } else {
            // Preserve cell advances and clipping even with a proportional fallback glyph.
            paint.textAlign = Paint.Align.LEFT
            var cursor = left
            cut.forEachIndexed { index, char ->
                val advance = paint.measureText(char.toString())
                canvas.save()
                canvas.clipRect(cursor, baseline + paint.fontMetrics.top, cursor + advance, baseline + paint.fontMetrics.bottom)
                canvas.drawText(shown[index].toString(), cursor, baseline, paint)
                canvas.restore()
                cursor += advance
            }
        }
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

        /** Shift from an immutable source, clearing the destination first: clip/pad, never smear.
         * Coordinates are bitmap pixels. The caller excludes header/status/footer rows via [region].
         */
        fun signalLoss(
            context: Context,
            canvas: Canvas,
            source: Bitmap,
            theme: PhantomTheme,
            region: RectF,
            cellWidth: Float,
            rowHeight: Float,
            seed: Long,
            frame: Int,
            font: Typeface = typeface(context, false),
        ) {
            val rows = (region.height() / rowHeight).toInt().coerceAtLeast(0)
            val columns = (region.width() / cellWidth).toInt().coerceAtLeast(0)
            val plan = signalLossPlan(seed, frame, rows, columns)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.typeface = font
            paint.textSize = rowHeight * 0.85f

            fun band(row: Int) = RectF(region.left, region.top + row * rowHeight, region.right, region.top + (row + 1) * rowHeight)

            fun clear(rect: RectF) {
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                canvas.drawColor(theme.color("window").argb)
                canvas.restore()
            }
            canvas.save()
            canvas.clipRect(region)
            plan.shifts.forEach { shift ->
                val destination = band(shift.row)
                clear(destination)
                canvas.save()
                canvas.clipRect(destination)
                val dx = shift.cells * cellWidth
                canvas.translate(dx, 0f)
                canvas.clipRect(region)
                // SRC replaces alpha as well as color, so translucent pixels do not accumulate.
                val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
                canvas.drawBitmap(source, 0f, 0f, copyPaint)
                canvas.restore()
            }

            fun glyph(
                row: Int,
                column: Int,
                char: Char,
                role: String,
            ) {
                val rect =
                    RectF(
                        region.left + column * cellWidth,
                        region.top + row * rowHeight,
                        region.left + (column + 1) * cellWidth,
                        region.top + (row + 1) * rowHeight,
                    )
                clear(rect)
                paint.color = theme.color(role).argb
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawText(char.toString(), rect.left, rect.bottom - rowHeight * 0.15f, paint)
                canvas.restore()
            }
            plan.staticRows.forEach { row ->
                clear(band(row))
                repeat(columns) { column ->
                    val hash = signalMix(seed, 108, frame.toLong(), row.toLong(), column.toLong()).toULong()
                    glyph(row, column, "░▒▓"[(hash % 3u).toInt()], "subtext2")
                }
            }
            plan.corruptions.forEach { glyph(it.row, it.column, it.glyph, it.colorRole) }
            canvas.restore()
        }
    }
}
