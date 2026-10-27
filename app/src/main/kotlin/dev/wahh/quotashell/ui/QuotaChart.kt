package dev.wahh.quotashell

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import dev.wahh.quotashell.core.filledSegments

internal fun Activity.quotaChart(percent: Int?) =
    object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(48)).apply { marginEnd = dp(10) }
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
            // One terminal cell per segment, matching ▮▯ without relying on glyph fallback.
            val filled = filledSegments(percent, 12)
            for (index in 0 until 12) {
                paint.color = if (index < filled) active else track
                paint.style = if (index < filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = 1f
                val left = 2f + index * 6.5f
                canvas.drawRect(left, 16f, left + 4.5f, 32f, paint)
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
                        PhantomGraphics(
                            this@alertGlyph,
                            canvas,
                            theme,
                            appTypeface = HudFont.typeface(this@alertGlyph),
                        ).notification(0f, 0f, bounds.width().toFloat())
                    }

                    override fun setAlpha(alpha: Int) = Unit

                    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

                    @Deprecated("Drawable compatibility")
                    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                }
        }
    }
