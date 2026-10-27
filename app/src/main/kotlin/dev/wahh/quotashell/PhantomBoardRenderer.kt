package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import dev.wahh.quotashell.core.Board
import dev.wahh.quotashell.core.BoardCard
import dev.wahh.quotashell.core.BoardRow
import dev.wahh.quotashell.core.ChartStyle
import dev.wahh.quotashell.core.DONUT_START_DEGREES
import dev.wahh.quotashell.core.GlitchFrame
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.allocateColumns
import dev.wahh.quotashell.core.boardFit
import dev.wahh.quotashell.core.donutSweep
import dev.wahh.quotashell.core.filledSegments
import dev.wahh.quotashell.core.glitchFrame
import dev.wahh.quotashell.core.glitchSeed
import dev.wahh.quotashell.core.quotaItemWidth

/** Three full frames together remain below 6 MiB, even for large widget hosts. */
object PhantomBoardRenderer {
    private const val PAD = 6f
    private const val GAP = 5f
    private const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    private class Metrics(
        theme: PhantomTheme,
    ) {
        val extra = theme.fontSizeOffset.coerceAtLeast(0).toFloat()
        val header = 28f + extra * 2
        val footer = 12f + extra
        val cardTop = 28f + extra * 2
        val cardHeight = 104f + extra * 8
        val neededHeight = PAD * 2 + header + footer + cardHeight

        fun chartWidth(style: ChartStyle): Float = if (style == ChartStyle.CELLS) 10f + extra * 2 else 26f + extra * 2
    }

    /** Logical geometry shared by Canvas rendering and the RemoteViews Sync hit target. */
    data class Layout(
        val fit: Float,
        val syncWidth: Float,
        val syncHeight: Float,
        val syncTop: Float,
        val syncEnd: Float,
    )

    fun layout(
        context: Context,
        board: Board,
        theme: PhantomTheme,
        widthDp: Float,
        heightDp: Float,
    ): Layout {
        val metrics = Metrics(theme)
        val graphics = PhantomGraphics(context, Canvas(), theme)
        val required = requiredWidths(board, theme, graphics)
        val fit = boardFit(widthDp, heightDp, required.sum() + PAD * 2 + GAP * (required.size - 1), metrics.neededHeight)
        val sync = if (board.syncing) "[ Syncing… ]" else "[ Sync ]"
        val buttonWidth = maxOf(48f + metrics.extra * 4, graphics.measure(sync, 7.5f, true) + 8)
        return Layout(fit, buttonWidth * fit, (14 + metrics.extra) * fit, (PAD + 11 + metrics.extra) * fit, (PAD + 4) * fit)
    }

    fun render(
        context: Context,
        board: Board,
        theme: PhantomTheme,
        widthDp: Float,
        heightDp: Float,
        burstFrame: Int = 0,
    ): Bitmap {
        var density = context.resources.displayMetrics.density
        while (widthDp * heightDp * density * density * 4 > MAX_FRAME_BYTES) density *= 0.85f
        val bitmap =
            Bitmap.createBitmap(
                (widthDp * density).toInt().coerceAtLeast(1),
                (heightDp * density).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        val geometry = layout(context, board, theme, widthDp, heightDp)
        val fit = geometry.fit
        canvas.scale(density * fit, density * fit)
        val seed = board.glitchSeed() + burstFrame
        val frame = glitchFrame(seed, burstFrame != 0)
        val graphics = PhantomGraphics(context, canvas, theme, frame, seed)
        val drawing = Drawing(canvas, graphics, theme, Metrics(theme), widthDp / fit, heightDp / fit, frame, geometry.syncWidth / fit)
        // Glitch overlays must completely cover the clean base, including alpha-themed panels.
        val clearColor = if (burstFrame == 0) 0 else theme.color("background").argb or (0xff shl 24)
        canvas.drawColor(clearColor, PorterDuff.Mode.SRC)
        drawing.draw(board)
        PhantomGraphics.slices(
            canvas,
            widthDp / fit,
            heightDp / fit,
            frame,
            clear = { canvas.drawColor(clearColor, PorterDuff.Mode.SRC) },
            draw = { drawing.draw(board) },
        )
        return bitmap
    }

    private fun requiredWidths(
        board: Board,
        theme: PhantomTheme,
        graphics: PhantomGraphics,
    ): List<Float> {
        val metrics = Metrics(theme)
        return board.cards.map { card ->
            val items =
                card.rows
                    .sumOf { row ->
                        quotaItemWidth(
                            metrics.chartWidth(theme.chartStyle),
                            listOf(
                                graphics.measure(row.usage, 13f, true),
                                graphics.measure(row.label, 9f, true),
                                graphics.measure(row.reset.substringBeforeLast(' ', row.reset), 7f),
                                graphics.measure(row.reset.substringAfterLast(' ', ""), 8f),
                            ),
                        ).toDouble()
                    }.toFloat()
            maxOf(
                64f,
                items + 10 + (card.rows.size - 1).coerceAtLeast(0) * GAP,
                graphics.measure(card.name, 10f, true) + 17,
                graphics.measure(card.status, 7f) + 10,
                graphics.measure(card.code, 6f) + 14,
                graphics.measure(card.note ?: "", 7f) + 10,
                if (card.rows.isEmpty()) graphics.measure("No quota data", 8f) + 10 else 0f,
            )
        }
    }

    private class Drawing(
        private val canvas: Canvas,
        private val graphics: PhantomGraphics,
        private val theme: PhantomTheme,
        private val metrics: Metrics,
        private val width: Float,
        private val height: Float,
        private val frame: GlitchFrame,
        private val syncWidth: Float,
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
            val path = PhantomGraphics.panelPath(0f, 0f, width, height, frameShape)
            graphics.fill(path, background)
            graphics.stroke(path, border, 1.3f)
            header(board)
            val columnWidths =
                allocateColumns(
                    requiredWidths(board, theme, graphics),
                    width - PAD * 2,
                    GAP,
                    board.cards.map {
                        it.rows.size
                            .coerceAtLeast(1)
                            .toFloat()
                    },
                )
            board.cards.zip(columnWidths).forEach { (card, rect) ->
                card(card, PAD + rect.left, PAD + rect.right, PAD + metrics.header, height - PAD - metrics.footer)
            }
            text(board.stamp, PAD + 4, height - PAD - 2, 7.5f, secondary)
            val alerts = if (board.alertCount == 0) "All links nominal" else "Alerts ${board.alertCount}"
            text(alerts, width - PAD - 4, height - PAD - 2, 7.5f, if (board.alertCount == 0) secondary else warning, right = true)
        }

        private fun header(board: Board) {
            val left = PAD + 4
            val e = metrics.extra
            text("01110001", left, PAD + 8, 5.5f, decoration)
            text("QS.SYS", left, PAD + 14 + e, 5.5f, decoration)
            text("QuotaShell", left + 32 + e * 4, PAD + 14 + e, 13.5f, primary, heavy = true)
            text("Subscription quota", left + 32 + e * 4, PAD + 22 + e * 2, 7f, secondary)
            val right = width - PAD - 4
            repeat(5) { i ->
                val x = right - 5 * 4.5f + i * 4.5f
                graphics.fill(
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
            graphics.stroke(graphics.buttonPath(right - syncWidth, syncY - 10 - e, right, syncY + 4), border, 1f)
            text(if (theme.renderButtonShape == "BEVEL") sync else "[ $sync ]", right - 4, syncY, 7.5f, accent, heavy = true, right = true)
            if (board.alertCount > 0) graphics.notification(right - 30, PAD + 3, 8f + e)
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
            val path = PhantomGraphics.panelPath(left, top, right, bottom, theme.renderWindowShape)
            graphics.fill(path, fill)
            graphics.stroke(path, if (card.alert) warning else border, if (card.alert) 2.2f else 1.1f)
            val codeY = if (theme.renderWindowShape == "TABBED") top + 4 + e else top + 10 + e
            text(card.code, right - 3, codeY, 6f, decoration, right = true)
            text(card.name, left + 5, top + 14 + e, 10f, primary, heavy = true, maxWidth = right - left - 17)
            text(card.status, left + 5, top + 24 + e * 2, 7f, secondary, maxWidth = right - left - 10)
            if (card.alert) graphics.notification(right - 11, top + 8 + e, 6f + e)
            val y = top + metrics.cardTop
            if (card.rows.isEmpty()) text("No quota data", left + 5, y + 25, 8f, secondary, maxWidth = right - left - 10)
            val slot = (right - left - 10 - GAP * (card.rows.size - 1).coerceAtLeast(0)) / card.rows.size.coerceAtLeast(1)
            val chartWidth = metrics.chartWidth(theme.chartStyle)
            val chartBottom = bottom - if (card.note != null) 15 + e else 5f
            card.rows.forEachIndexed { index, row ->
                val x = left + 5 + (slot + GAP) * index
                chart(row, x, y + 3, chartWidth, chartBottom)
                val tx = x + chartWidth + 5
                val available = slot - chartWidth - 5
                val textHeight = 51 + e * 4
                val baseline = y + 15 + e + ((chartBottom - y - textHeight) / 2).coerceAtLeast(0f)
                text(row.usage, tx, baseline, 13f, if (row.hot) warning else primary, true, maxWidth = available)
                text(row.label, tx, baseline + 13 + e, 9f, secondary, true, maxWidth = available)
                text(row.reset.substringBeforeLast(' ', row.reset), tx, baseline + 25 + e * 2, 7f, secondary, maxWidth = available)
                text(row.reset.substringAfterLast(' ', ""), tx, baseline + 36 + e * 3, 8f, secondary, maxWidth = available)
            }
            card.note?.let { text(it, left + 5, bottom - 4, 7f, secondary, maxWidth = right - left - 10) }
        }

        private fun chart(
            row: BoardRow,
            left: Float,
            top: Float,
            chartWidth: Float,
            bottom: Float,
        ) {
            val color = if (row.hot) warning else accent

            fun drawChart(
                tint: Int?,
                dx: Float,
            ) {
                canvas.save()
                canvas.translate(dx + frame.jitterX, 0f)
                val filled = filledSegments(row.percent, 12)
                if (theme.chartStyle == ChartStyle.CELLS) {
                    paint.style = Paint.Style.FILL
                    val step = (bottom - top) / 12
                    repeat(12) { index ->
                        paint.color = tint ?: if (index < filled) color else border
                        val b = bottom - index * step
                        canvas.drawRect(left, b - step + 0.8f, left + chartWidth, b, paint)
                    }
                } else {
                    val radius = chartWidth / 2 - 2
                    val cx = left + chartWidth / 2
                    val cy = (top + bottom) / 2
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f
                    if (theme.chartStyle == ChartStyle.RING) {
                        paint.color = tint ?: border
                        canvas.drawCircle(cx, cy, radius, paint)
                        paint.color = tint ?: color
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
                    } else if (theme.chartStyle == ChartStyle.TICKS) {
                        repeat(12) { index ->
                            paint.color = tint ?: if (index < filled) color else border
                            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -225f + index * 22.5f, 15f, false, paint)
                        }
                    }
                }
                canvas.restore()
            }
            if (frame.rgbOffset > 0f) {
                drawChart(accent, -frame.rgbOffset)
                drawChart(warning, frame.rgbOffset)
            }
            drawChart(null, 0f)
        }

        private fun text(
            value: String,
            x: Float,
            baseline: Float,
            size: Float,
            color: Int,
            heavy: Boolean = false,
            right: Boolean = false,
            maxWidth: Float = Float.MAX_VALUE,
        ) {
            graphics.text(value, x, baseline, size, color, heavy, if (right) Paint.Align.RIGHT else Paint.Align.LEFT, maxWidth)
        }
    }
}
