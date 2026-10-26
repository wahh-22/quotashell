package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.wahh.quotashell.core.Board
import dev.wahh.quotashell.core.BoardCard
import dev.wahh.quotashell.core.PhantomTheme
import kotlin.math.min

/** A static RemoteViews bitmap: all board colors and presentation options come from the export. */
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
        val header = 28f + extra * 2
        val footer = 13f + extra
        val cardTop = 32f + extra * 2
        val row = 14f + extra * 2
        val note = 11f + extra * 2

        fun cardHeight(card: BoardCard) = cardTop + row * maxOf(card.rows.size, 1) + (if (card.note != null) note else 0f) + 6f
    }

    fun render(
        context: Context,
        board: Board,
        theme: PhantomTheme,
        widthDp: Float,
        heightDp: Float,
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
        val needed =
            PAD * 2 + metrics.header + metrics.footer + board.cards.sumOf { metrics.cardHeight(it).toDouble() }.toFloat() +
                GAP * (board.cards.size - 1)
        val fit = min(1f, heightDp / needed)
        val canvas = Canvas(bitmap)
        canvas.scale(density * fit, density * fit)
        Drawing(canvas, theme, metrics, widthDp / fit, heightDp / fit).draw(board)
        return bitmap
    }

    private class Drawing(
        private val canvas: Canvas,
        private val theme: PhantomTheme,
        private val metrics: Metrics,
        private val width: Float,
        private val height: Float,
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
            var top = PAD + metrics.header
            val extra =
                (
                    height - PAD * 2 - metrics.header - metrics.footer -
                        board.cards.sumOf { metrics.cardHeight(it).toDouble() }.toFloat() -
                        GAP * (board.cards.size - 1)
                ).coerceAtLeast(0f)
            val spread = extra / board.cards.size.coerceAtLeast(1)
            board.cards.forEach { card ->
                val bottom = top + metrics.cardHeight(card) + spread
                card(card, top, bottom)
                top = bottom + GAP
            }
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
            if (theme.renderButtonShape == "BEVEL") {
                stroke(panelPath(right - 48 - e * 4, syncY - 9 - e, right, syncY + 3, "BEVEL"), border, 1f)
                text("Sync", right - 5, syncY, 7.5f, accent, heavy = true, right = true)
            } else {
                text("[ Sync ]", right, syncY, 7.5f, accent, heavy = true, right = true)
            }
            if (board.alertCount > 0) alert(right - 30, PAD + 3, 8f)
            paint.color = border
            paint.strokeWidth = 1f
            canvas.drawLine(PAD, PAD + metrics.header - 3, width - PAD, PAD + metrics.header - 3, paint)
        }

        private fun card(
            card: BoardCard,
            top: Float,
            bottom: Float,
        ) {
            val left = PAD + 2
            val right = width - PAD - 2
            val e = metrics.extra
            val path = panelPath(left, top, right, bottom, theme.renderWindowShape)
            fill(path, fill)
            // NONE hides only the glyph: alert cards retain an outlined highlight.
            stroke(path, if (card.alert) warning else border, if (card.alert) 2.2f else 1.1f)
            val codeY = if (theme.renderWindowShape == "TABBED") top + 4 + e else top + 10 + e
            text(card.code, right - 3, codeY, 6f, decoration, right = true)
            text(card.name, left + 7, top + 15 + e, 12f, primary, heavy = true)
            text(card.status, left + 7, top + 24 + e * 2, 7f, secondary)
            if (card.alert) alert(right - 14, top + 13 + e, 8f)
            val labelX = left + 7
            val barLeft = left + 42 + e * 4
            val resetRight = right - 6
            val percentRight = resetRight - 74 - e * 6
            val barRight = percentRight - 30 - e * 2
            var y = top + metrics.cardTop
            if (card.rows.isEmpty()) {
                val hint =
                    if (card.status.equals(
                            "Not connected",
                            ignoreCase = true,
                        )
                    ) {
                        "No link · open QuotaShell > Connections"
                    } else {
                        "No reported quota windows"
                    }
                text(hint, labelX, y + 8 + e, 7.5f, secondary)
                y += metrics.row
            }
            card.rows.forEach { row ->
                text(row.label, labelX, y + 8.5f + e, 9f, secondary, heavy = true)
                segments(barLeft, y + 3.5f + e, barRight, row.percent, row.hot)
                text(row.usage, percentRight, y + 8.5f + e, 9f, if (row.hot) warning else primary, heavy = true, right = true)
                text(row.reset, resetRight, y + 8.5f + e, 8f, secondary, right = true)
                y += metrics.row
            }
            card.note?.let { text(it, labelX, y + 7.5f + e, 7f, secondary) }
        }

        private fun segments(
            left: Float,
            top: Float,
            right: Float,
            percent: Int?,
            hot: Boolean,
        ) {
            val count = 20
            val gap = 1.2f
            val w = ((right - left) - gap * (count - 1)) / count
            if (w <= 0) return
            val filled = percent?.let { (it.coerceIn(0, 100) * count + 99) / 100 } ?: 0
            paint.style = Paint.Style.FILL
            for (i in 0 until count) {
                paint.color = if (i < filled) (if (hot) warning else accent) else border
                val x = left + i * (w + gap)
                canvas.drawRect(x, top, x + w, top + 6, paint)
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
            val available = if (right || center) x - PAD else width - x - PAD
            var cut = content
            while (cut.isNotEmpty() && paint.measureText(cut) > available) cut = cut.dropLast(1)
            if (cut != content && cut.isNotEmpty()) cut = cut.dropLast(1) + "…"
            canvas.drawText(cut, x, baseline, paint)
        }
    }
}
