package dev.wahh.quotashell

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import dev.wahh.quotashell.core.ChartStyle
import dev.wahh.quotashell.core.filledSegments

internal fun Activity.quotaChart(percent: Int?) =
    object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(104)).apply { marginEnd = dp(16) }
            contentDescription = percent?.let { "$it percent used" } ?: "Usage unknown"
        }

        override fun onDraw(canvas: Canvas) {
            val theme = (application as QuotaApplication).store.phantomTheme()
            val density = resources.displayMetrics.density
            canvas.save()
            canvas.scale(density, density)
            paint.style = Paint.Style.FILL
            val active = theme.color(if ((percent ?: 0) >= 85) "notifications" else "accent").argb
            val track = theme.color("outline").argb
            when (theme.chartStyle) {
                ChartStyle.CELLS -> {
                    val filled = filledSegments(percent, 12)
                    for (index in 0 until 12) {
                        paint.color = if (index < filled) active else track
                        val bottom = 98f - index * 7.5f
                        canvas.drawRect(12f, bottom - 5.5f, 65f, bottom, paint)
                    }
                }

                ChartStyle.TICKS, ChartStyle.RING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 6f
                    if (theme.chartStyle == ChartStyle.RING) {
                        paint.color = track
                        canvas.drawCircle(40f, 52f, 30f, paint)
                        paint.color = active
                        canvas.drawArc(10f, 22f, 70f, 82f, -90f, (percent ?: 0) * 3.6f, false, paint)
                    } else {
                        for (index in 0 until 12) {
                            paint.color = if (index < filledSegments(percent, 12)) active else track
                            canvas.drawArc(10f, 22f, 70f, 82f, -90f + index * 30f, 22f, false, paint)
                        }
                    }
                }
            }
            canvas.restore()
        }
    }

internal fun Activity.alertGlyph() =
    object : View(this) {
        init {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            contentDescription = "Connection alert"
            background =
                object : android.graphics.drawable.Drawable() {
                    override fun draw(canvas: Canvas) {
                        val theme = (application as QuotaApplication).store.phantomTheme()
                        PhantomGraphics(this@alertGlyph, canvas, theme).notification(0f, 0f, bounds.width().toFloat())
                    }

                    override fun setAlpha(alpha: Int) = Unit

                    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

                    @Deprecated("Drawable compatibility")
                    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                }
        }
    }
