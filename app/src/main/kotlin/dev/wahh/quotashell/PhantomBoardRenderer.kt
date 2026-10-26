package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.wahh.quotashell.core.Board
import dev.wahh.quotashell.core.BoardCard
import dev.wahh.quotashell.core.TerminalTheme
import kotlin.math.min

/**
 * Draws the all-provider widget in PhantomShell's visual language: Adwaita Mono, chamfered
 * outlined panels, tab labels, segmented bars, purple stripes and a cyan alert triangle.
 * RemoteViews cannot express these shapes or a bundled font, so the board is a bitmap.
 */
object PhantomBoardRenderer {
    private const val LIGHT = 0xFFDBE9FF.toInt()
    private const val PURPLE = 0xFF533FAE.toInt()
    private const val WARNING = 0xFF5CE1FF.toInt()

    // Design units are dp; everything is scaled down together when the widget is small.
    private const val PAD = 6f
    private const val HEADER = 28f
    private const val FOOTER = 13f
    private const val GAP = 5f
    private const val CARD_TOP = 32f
    private const val ROW = 14f
    private const val NOTE = 11f
    private const val CARD_BOTTOM = 6f
    private const val MAX_BITMAP_BYTES = 6 * 1024 * 1024

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun render(
        context: Context,
        board: Board,
        theme: TerminalTheme,
        widthDp: Float,
        heightDp: Float,
    ): Bitmap {
        loadFonts(context)
        var density = context.resources.displayMetrics.density
        // Stay well inside the RemoteViews bitmap budget on very large widgets.
        while (widthDp * heightDp * density * density * 4 > MAX_BITMAP_BYTES) density *= 0.85f
        val bitmap =
            Bitmap.createBitmap(
                (widthDp * density).toInt().coerceAtLeast(1),
                (heightDp * density).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        val needed = PAD * 2 + HEADER + FOOTER + board.cards.sumOf { cardHeight(it).toDouble() }.toFloat() + GAP * (board.cards.size - 1)
        val fit = min(1f, heightDp / needed)
        canvas.scale(density * fit, density * fit)
        Drawing(canvas, theme, widthDp / fit, heightDp / fit).draw(board)
        return bitmap
    }

    private fun cardHeight(card: BoardCard): Float =
        CARD_TOP + ROW * maxOf(card.rows.size, 1) + (if (card.note != null) NOTE else 0f) + CARD_BOTTOM

    private fun loadFonts(context: Context) {
        if (regular == null) {
            regular = Typeface.createFromAsset(context.assets, "fonts/AdwaitaMono-Regular.ttf")
            bold = Typeface.createFromAsset(context.assets, "fonts/AdwaitaMono-Bold.ttf")
        }
    }

    private class Drawing(
        private val canvas: Canvas,
        theme: TerminalTheme,
        private val width: Float,
        private val height: Float,
    ) {
        private val accent = theme.accentArgb
        private val fill = theme.backgroundArgb
        private val border = blend(accent, TerminalTheme.PANEL_RGB, 0.48f)
        private val dim = blend(accent, TerminalTheme.PANEL_RGB, 0.62f)
        private val empty = blend(accent, TerminalTheme.PANEL_RGB, 0.2f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun draw(board: Board) {
            frame()
            header(board)
            var top = PAD + HEADER
            val extra =
                (
                    height - PAD * 2 - HEADER - FOOTER - board.cards.sumOf { cardHeight(it).toDouble() }.toFloat() -
                        GAP * (board.cards.size - 1)
                ).coerceAtLeast(0f)
            val spread = extra / board.cards.size
            board.cards.forEach { card ->
                val cardBottom = top + cardHeight(card) + spread
                card(card, top, cardBottom)
                top = cardBottom + GAP
            }
            text(board.stamp, PAD + 4, height - PAD - 2, 7.5f, dim, false)
            val alerts = if (board.alertCount == 0) "ALL LINKS NOMINAL" else "ALERTS ${board.alertCount}"
            text(alerts, width - PAD - 12, height - PAD - 2, 7.5f, if (board.alertCount == 0) dim else WARNING, false, right = true)
        }

        private fun frame() {
            val cut = 10f
            val path =
                Path().apply {
                    moveTo(cut, 0f)
                    lineTo(width, 0f)
                    lineTo(width, height - cut)
                    lineTo(width - cut, height)
                    lineTo(0f, height)
                    lineTo(0f, cut)
                    close()
                }
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawPath(path, paint)
            stroke(path, border, 1.3f)
            // Purple corner mark, as on PhantomShell's terminal panel.
            paint.style = Paint.Style.FILL
            paint.color = PURPLE
            canvas.drawPath(
                Path().apply {
                    moveTo(width - 3f, height - 12f)
                    lineTo(width - 3f, height - 6f)
                    lineTo(width - 9f, height - 6f)
                    close()
                },
                paint,
            )
        }

        private fun header(board: Board) {
            val left = PAD + 4
            text("01110001", left, PAD + 8, 5.5f, dim, false)
            text("QS.SYS", left, PAD + 14, 5.5f, dim, false)
            text("QUOTASHELL", left + 32, PAD + 14, 13.5f, LIGHT, true)
            text("SUBSCRIPTION QUOTA", left + 32, PAD + 22, 7f, dim, false)
            // Stripe block.
            paint.style = Paint.Style.FILL
            paint.color = PURPLE
            val stripeRight = width - PAD - 4
            for (i in 0 until 5) {
                val x = stripeRight - 5 * 4.5f + i * 4.5f
                canvas.drawPath(
                    Path().apply {
                        moveTo(x + 2.5f, PAD + 3)
                        lineTo(x + 4.5f, PAD + 3)
                        lineTo(x + 2f, PAD + 10)
                        lineTo(x, PAD + 10)
                        close()
                    },
                    paint,
                )
            }
            text("[ SYNC ]", stripeRight, PAD + 21, 7.5f, accent, true, right = true)
            if (board.alertCount > 0) triangle(stripeRight - 30, PAD + 3, 8f)
            paint.color = border
            paint.strokeWidth = 1f
            canvas.drawLine(PAD, PAD + HEADER - 3, width - PAD, PAD + HEADER - 3, paint)
        }

        private fun card(
            card: BoardCard,
            top: Float,
            bottom: Float,
        ) {
            val left = PAD + 2
            val right = width - PAD - 2
            val tab = 54f
            val step = 5f
            val path =
                Path().apply {
                    moveTo(left, top)
                    lineTo(right - tab - step, top)
                    lineTo(right - tab, top + step)
                    lineTo(right, top + step)
                    lineTo(right, bottom)
                    lineTo(left + 7, bottom)
                    lineTo(left, bottom - 7)
                    close()
                }
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawPath(path, paint)
            if (card.alert) {
                stroke(path, (accent and 0x00FFFFFF) or 0x55000000, 3.2f)
                stroke(path, accent, 1.3f)
            } else {
                stroke(path, border, 1.1f)
            }
            text(card.code, right - 3, top + 4f, 6f, dim, false, right = true)
            text(card.name, left + 7, top + 15, 12f, if (card.alert) accent else LIGHT, true)
            text(card.status, left + 7, top + 24, 7f, if (card.alert) WARNING else dim, false)
            if (card.alert) triangle(right - 14, top + 10, 8f)
            val labelX = left + 7
            val barLeft = left + 42
            val resetRight = right - 6
            val resetWidth = 70f
            val percentRight = resetRight - resetWidth - 4
            val barRight = percentRight - 30
            var y = top + CARD_TOP
            if (card.rows.isEmpty()) {
                val hint = if (card.status == "NOT CONNECTED") "NO LINK · OPEN QUOTASHELL > CONNECTIONS" else "NO REPORTED QUOTA WINDOWS"
                text(hint, labelX, y + 8, 7.5f, dim, false)
                y += ROW
            }
            card.rows.forEach { row ->
                text(row.label, labelX, y + 8.5f, 9f, accent, true)
                segments(barLeft, y + 3.5f, barRight, row.percent, row.hot)
                text(row.usage, percentRight, y + 8.5f, 9f, if (row.hot) WARNING else LIGHT, true, right = true)
                text(row.reset, resetRight, y + 8.5f, 8f, dim, false, right = true)
                y += ROW
            }
            card.note?.let { text(it, labelX, y + 7.5f, 7f, dim, false) }
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
                paint.color = if (i < filled) (if (hot) WARNING else accent) else empty
                val x = left + i * (w + gap)
                canvas.drawRect(x, top, x + w, top + 6f, paint)
            }
        }

        private fun triangle(
            x: Float,
            y: Float,
            size: Float,
        ) {
            paint.style = Paint.Style.FILL
            paint.color = WARNING
            canvas.drawPath(
                Path().apply {
                    moveTo(x, y)
                    lineTo(x + size, y)
                    lineTo(x + size / 2, y + size * 0.9f)
                    close()
                },
                paint,
            )
            text("!", x + size / 2, y + size * 0.62f, size * 0.6f, Color.rgb(7, 11, 25), true, center = true)
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
            heavy: Boolean,
            right: Boolean = false,
            center: Boolean = false,
        ) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = size
            paint.typeface = if (heavy) bold else regular
            paint.textAlign =
                when {
                    center -> Paint.Align.CENTER
                    right -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
            canvas.drawText(fitText(value, if (right || center) x - PAD else width - x - PAD), x, baseline, paint)
        }

        private fun fitText(
            value: String,
            available: Float,
        ): String {
            if (paint.measureText(value) <= available) return value
            var cut = value
            while (cut.isNotEmpty() && paint.measureText("$cut…") > available) cut = cut.dropLast(1)
            return "$cut…"
        }
    }

    private fun blend(
        color: Int,
        backgroundRgb: Int,
        amount: Float,
    ): Int {
        fun mix(
            a: Int,
            b: Int,
        ) = (a * amount + b * (1 - amount)).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(color), (backgroundRgb shr 16) and 0xFF),
            mix(Color.green(color), (backgroundRgb shr 8) and 0xFF),
            mix(Color.blue(color), backgroundRgb and 0xFF),
        )
    }
}
