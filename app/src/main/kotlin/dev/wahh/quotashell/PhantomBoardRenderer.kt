package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.wahh.quotashell.core.Board
import dev.wahh.quotashell.core.BoardCard
import dev.wahh.quotashell.core.BoardRow
import dev.wahh.quotashell.core.ChartStyle
import dev.wahh.quotashell.core.DONUT_START_DEGREES
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.allocateColumns
import dev.wahh.quotashell.core.donutSweep
import dev.wahh.quotashell.core.filledSegments
import dev.wahh.quotashell.core.glitchSeed
import dev.wahh.quotashell.core.glitchSlices
import kotlin.math.min

/** One bounded bitmap per update; burst frames never share a RemoteViews payload. */
object PhantomBoardRenderer {
    private const val PAD = 6f
    private const val GAP = 5f
    private const val MAX_BITMAP_BYTES = 6 * 1024 * 1024
    private var regular: Typeface? = null
    private var bold: Typeface? = null

    private class Metrics(
        theme: PhantomTheme,
    ) {
        val extra = theme.fontSizeOffset.coerceAtLeast(0).toFloat()

        // Leaves room below the subtitle baseline (PAD + 22) so the divider never crosses it.
        val header = 28f + extra * 2
        val footer = 12f + extra
        val cardTop = 28f + extra * 2
        val cardHeight = 99f + extra * 8
    }

    fun render(
        context: Context,
        board: Board,
        theme: PhantomTheme,
        widthDp: Float,
        heightDp: Float,
        burstFrame: Int = 0,
    ): Bitmap {
        if (regular == null) {
            regular = Typeface.createFromAsset(context.assets, "fonts/AdwaitaMono-Regular.ttf")
            bold = Typeface.createFromAsset(context.assets, "fonts/AdwaitaMono-Bold.ttf")
        }
        var density = context.resources.displayMetrics.density
        while (widthDp * heightDp * density * density * 4 > MAX_BITMAP_BYTES) density *= 0.85f
        val bitmap =
            Bitmap.createBitmap(
                (widthDp * density).toInt().coerceAtLeast(1),
                (heightDp * density).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val metrics = Metrics(theme)
        val needed = PAD * 2 + metrics.header + metrics.footer + metrics.cardHeight
        val required = requiredWidths(board, theme)
        val neededWidth = required.sum() + PAD * 2 + GAP * (required.size - 1)
        val fit = min(1f, min(heightDp / needed, widthDp / neededWidth))
        val canvas = Canvas(bitmap)
        canvas.scale(density * fit, density * fit)
        val drawing = Drawing(canvas, theme, metrics, widthDp / fit, heightDp / fit, burstFrame)
        drawing.draw(board)
        // Draw clipped, translated slices from the same scene: no additional large bitmap.
        glitchSlices(board.glitchSeed() + burstFrame).forEach { slice ->
            canvas.save()
            val top = slice.topFraction * heightDp / fit
            canvas.clipRect(0f, top, widthDp / fit, top + slice.heightFraction * heightDp / fit)
            canvas.translate(slice.offset * (if (burstFrame == 0) 0.25f else 1f), 0f)
            drawing.draw(board)
            canvas.restore()
        }
        return bitmap
    }

    /** Same font, casing and sizes as drawText; ellipsizing is only a final safety net. */
    private fun requiredWidths(
        board: Board,
        theme: PhantomTheme,
    ): List<Float> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun measure(
            value: String,
            size: Float,
            heavy: Boolean = false,
        ): Float {
            paint.typeface = if (heavy) bold else regular
            paint.textSize = (size + theme.fontSizeOffset).coerceAtLeast(3f)
            return paint.measureText(theme.displayText(value))
        }
        val e = theme.fontSizeOffset.coerceAtLeast(0).toFloat()
        val chartWidth =
            when (theme.chartStyle) {
                ChartStyle.CELLS -> 22f + e * 2
                ChartStyle.TICKS -> 32f + e * 2
                ChartStyle.RING -> 36f + e * 2
            }
        return board.cards.map { card ->
            val slotWidth =
                card.rows.maxOfOrNull { row ->
                    maxOf(
                        chartWidth,
                        measure(row.usage, 8f, true) + 6,
                        measure(row.label, 7.5f, true) + 4,
                        measure(row.reset.substringBeforeLast(' ', row.reset), 6.5f) + 4,
                        measure(row.reset.substringAfterLast(' ', ""), 7f) + 4,
                    )
                } ?: 0f
            maxOf(
                64f,
                slotWidth * card.rows.size + 6,
                measure(card.name, 10f, true) + 17,
                measure(card.status, 7f) + 10,
                measure(card.code, 6f) + 14,
                measure(card.note ?: "", 7f) + 10,
                if (card.rows.isEmpty()) measure("No quota data", 8f) + 10 else 0f,
            )
        }
    }

    private class Drawing(
        private val canvas: Canvas,
        private val theme: PhantomTheme,
        private val metrics: Metrics,
        private val width: Float,
        private val height: Float,
        private val burstFrame: Int,
    ) {
        private val accent = theme.color("accent").argb
        private val fill = theme.color("window").argb
        private val border = theme.color("outline").argb
        private val primary = theme.color("text").argb
        private val secondary = theme.color("subtext").argb
        private val decoration = theme.color("subtext2").argb
        private val warning = theme.color("notifications").argb
        private val background = theme.color("background").argb
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun draw(board: Board) {
            val frameShape = if (theme.renderWindowShape == "TABBED") "BEVEL" else theme.renderWindowShape
            val frame = panelPath(0f, 0f, width, height, frameShape)
            fill(frame, background)
            stroke(frame, border, 1.3f)
            fill(
                Path().apply {
                    moveTo(width - 3, height - 12)
                    lineTo(width - 3, height - 6)
                    lineTo(width - 9, height - 6)
                    close()
                },
                decoration,
            )
            header(board)
            val top = PAD + metrics.header
            val chartWeights = board.cards.map { it.rows.size.coerceAtLeast(1).toFloat() }
            val columnWidths = allocateColumns(requiredWidths(board, theme), width - PAD * 2, GAP, chartWeights)
            board.cards.zip(columnWidths).forEach { (card, rect) ->
                card(card, PAD + rect.left, PAD + rect.right, top, height - PAD - metrics.footer)
            }
            scanlines()
            text(board.stamp, PAD + 4, height - PAD - 2, 7.5f, secondary)
            val alerts = if (board.alertCount == 0) "All links nominal" else "Alerts ${board.alertCount}"
            text(alerts, width - PAD - 12, height - PAD - 2, 7.5f, if (board.alertCount == 0) secondary else warning, right = true)
        }

        private fun header(board: Board) {
            val left = PAD + 4
            val e = metrics.extra
            text("01110001", left, PAD + 8, 5.5f, decoration)
            text("QS.SYS", left, PAD + 14 + e, 5.5f, decoration)
            text("QuotaShell", left + 32 + e * 4, PAD + 14 + e, 13.5f, primary, heavy = true)
            text("Subscription quota", left + 32 + e * 4, PAD + 22 + e * 2, 7f, secondary)
            val right = width - PAD - 4
            for (i in 0 until 5) {
                val x = right - 5 * 4.5f + i * 4.5f
                fill(
                    Path().apply {
                        moveTo(x + 2.5f, PAD + 3)
                        lineTo(x + 4.5f, PAD + 3)
                        lineTo(x + 2f, PAD + 10)
                        lineTo(x, PAD + 10)
                        close()
                    },
                    decoration,
                )
            }
            val syncY = PAD + 21 + e * 2
            val sync = if (board.syncing) "Syncing…" else "Sync"
            if (theme.renderButtonShape == "BEVEL") {
                stroke(panelPath(right - 48 - e * 4, syncY - 9 - e, right, syncY + 3, "BEVEL"), border, 1f)
                text(sync, right - 5, syncY, 7.5f, accent, heavy = true, right = true)
            } else {
                text("[ $sync ]", right, syncY, 7.5f, accent, heavy = true, right = true)
            }
            if (board.alertCount > 0) alert(right - 30, PAD + 3, 8f)
            paint.color = border
            paint.strokeWidth = 1f
            canvas.drawLine(PAD, PAD + metrics.header - 3, width - PAD, PAD + metrics.header - 3, paint)
        }

        private fun card(
            card: BoardCard,
            left: Float,
            right: Float,
            top: Float,
            bottom: Float,
        ) {
            val e = metrics.extra
            val path = panelPath(left, top, right, bottom, theme.renderWindowShape)
            fill(path, fill)
            // NONE hides only the glyph: alert cards retain an outlined highlight.
            stroke(path, if (card.alert) warning else border, if (card.alert) 2.2f else 1.1f)
            val codeY = if (theme.renderWindowShape == "TABBED") top + 4 + e else top + 10 + e
            text(card.code, right - 3, codeY, 6f, decoration, right = true)
            text(card.name, left + 5, top + 14 + e, 10f, primary, heavy = true, maxWidth = right - left - 17)
            // Keep state first: age is expendable when a narrow column is ellipsized.
            text(card.status, left + 5, top + 24 + e * 2, 7f, secondary, maxWidth = right - left - 10)
            if (card.alert) alert(right - 11, top + 8 + e, 6f)
            val y = top + metrics.cardTop
            if (card.rows.isEmpty()) {
                text("No quota data", (left + right) / 2, y + 25, 8f, secondary, center = true, maxWidth = right - left - 10)
            }
            val slot = (right - left - 6) / card.rows.size.coerceAtLeast(1)
            card.rows.forEachIndexed { index, row ->
                val cx = left + 3 + slot * (index + 0.5f)
                val radius = min(15f + e, slot / 2 - 3)
                val cy = y + radius + 2
                chart(row, cx, cy, radius, y, slot)
                val labelY = y + 42 + e * 3
                text(row.label, cx, labelY, 7.5f, secondary, heavy = true, center = true, maxWidth = slot - 2)
                val date = row.reset.substringBeforeLast(' ', row.reset)
                val time = row.reset.substringAfterLast(' ', "")
                text(date, cx, labelY + 9 + e, 6.5f, secondary, center = true, maxWidth = slot - 2)
                text(time, cx, labelY + 17 + e * 2, 7f, secondary, center = true, maxWidth = slot - 2)
            }
            card.note?.let { text(it, left + 5, bottom - 4, 7f, secondary, maxWidth = right - left - 10) }
        }

        private fun chart(
            row: BoardRow,
            cx: Float,
            cy: Float,
            radius: Float,
            top: Float,
            slot: Float,
        ) {
            val color = if (row.hot) warning else accent
            val segments = 12
            val filled = filledSegments(row.percent, segments)
            when (theme.chartStyle) {
                ChartStyle.CELLS -> {
                    val step = (25f + metrics.extra * 2) / segments
                    paint.style = Paint.Style.FILL
                    repeat(segments) { index ->
                        paint.color = if (index < filled) color else border
                        val bottom = top + 26 + metrics.extra * 2 - index * step
                        canvas.drawRect(cx - 5 - metrics.extra, bottom - step + 0.7f, cx + 5 + metrics.extra, bottom, paint)
                    }
                    text(
                        row.usage,
                        cx,
                        top + 35 + metrics.extra * 2,
                        8f,
                        if (row.hot) warning else primary,
                        heavy = true,
                        center = true,
                        maxWidth = slot - 2,
                    )
                }

                ChartStyle.TICKS, ChartStyle.RING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f
                    if (theme.chartStyle == ChartStyle.RING) {
                        paint.color = border
                        canvas.drawCircle(cx, cy, radius, paint)
                        paint.color = color
                        canvas.drawArc(
                            cx - radius,
                            cy - radius,
                            cx + radius,
                            cy + radius,
                            DONUT_START_DEGREES,
                            donutSweep(row.percent),
                            false,
                            paint,
                        )
                    } else {
                        repeat(segments) { index ->
                            paint.color = if (index < filled) color else border
                            canvas.drawArc(
                                cx - radius,
                                cy - radius,
                                cx + radius,
                                cy + radius,
                                -225f + index * 22.5f,
                                15f,
                                false,
                                paint,
                            )
                        }
                    }
                    text(
                        row.usage,
                        cx,
                        cy + 3,
                        8f,
                        if (row.hot) warning else primary,
                        heavy = true,
                        center = true,
                        maxWidth = slot - 2,
                    )
                }
            }
        }

        private fun scanlines() {
            paint.style = Paint.Style.FILL
            paint.color = decoration
            paint.alpha = ((decoration ushr 24) * 0.07f).toInt()
            var y = 2f
            while (y < height) {
                canvas.drawRect(PAD, y, width - PAD, y + 0.35f, paint)
                y += 4f
            }
        }

        private fun alert(
            x: Float,
            y: Float,
            baseSize: Float,
        ) {
            val size = baseSize + metrics.extra
            val shape = theme.renderNotificationShape
            if (shape == "NONE") return
            paint.style = Paint.Style.FILL
            paint.color = warning
            when (shape) {
                "CIRCLE" -> {
                    canvas.drawCircle(x + size / 2, y + size / 2, size / 2, paint)
                }

                "SQUARE" -> {
                    canvas.drawRect(x, y, x + size, y + size, paint)
                }

                else -> {
                    fill(
                        Path().apply {
                            if (shape == "TRIANGLE") {
                                moveTo(x + size / 2, y)
                                lineTo(x + size, y + size)
                                lineTo(x, y + size)
                            } else {
                                moveTo(x, y)
                                lineTo(x + size, y)
                                lineTo(x + size / 2, y + size)
                            }
                            close()
                        },
                        warning,
                    )
                }
            }
            text("!", x + size / 2, y + size * 0.7f, baseSize * 0.6f, fill, heavy = true, center = true)
        }

        private fun panelPath(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            shape: String,
        ): Path =
            Path().apply {
                when (shape) {
                    "TABBED" -> {
                        moveTo(left, top)
                        lineTo(right - 59, top)
                        lineTo(right - 54, top + 5)
                        lineTo(right, top + 5)
                        lineTo(right, bottom)
                        lineTo(left + 7, bottom)
                        lineTo(left, bottom - 7)
                        close()
                    }

                    "BEVEL" -> {
                        val cut = min(7f, (bottom - top) / 3)
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

        private fun fill(
            path: Path,
            color: Int,
        ) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawPath(path, paint)
        }

        private fun stroke(
            path: Path,
            color: Int,
            width: Float,
        ) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.color = color
            canvas.drawPath(path, paint)
        }

        private fun text(
            value: String,
            x: Float,
            baseline: Float,
            size: Float,
            color: Int,
            heavy: Boolean = false,
            right: Boolean = false,
            center: Boolean = false,
            maxWidth: Float? = null,
        ) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = (size + theme.fontSizeOffset).coerceAtLeast(3f)
            paint.typeface = if (heavy) bold else regular
            paint.textAlign =
                when {
                    center -> Paint.Align.CENTER
                    right -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
            val content = theme.displayText(value)
            val available = maxWidth ?: if (right || center) x - PAD else width - x - PAD
            var cut = content
            while (cut.isNotEmpty() && paint.measureText(cut) > available) cut = cut.dropLast(1)
            if (cut != content && cut.isNotEmpty()) cut = cut.dropLast(1) + "…"
            // RGB split only on titles: on small labels it smears the glyphs into illegibility.
            if (heavy && size >= 10f) {
                val offset = if (burstFrame == 0) 0.4f else 0.8f + burstFrame * 0.4f
                paint.color = accent
                paint.alpha = ((accent ushr 24) * 0.45f).toInt()
                canvas.drawText(cut, x - offset, baseline, paint)
                paint.color = warning
                paint.alpha = ((warning ushr 24) * 0.4f).toInt()
                canvas.drawText(cut, x + offset, baseline + 0.3f, paint)
                paint.color = decoration
                canvas.drawText(cut, x, baseline - 0.3f, paint)
                paint.color = color
            }
            canvas.drawText(cut, x, baseline, paint)
        }
    }
}
