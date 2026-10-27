package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import dev.wahh.quotashell.core.GlitchSchedule
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.glitchSlices
import dev.wahh.quotashell.core.periodicGlitchEnabled

/** Two 128×48 transparent overlays: 48 KiB total, never another full-sized board. */
internal object WidgetGlitchOverlay {
    fun apply(
        context: Context,
        views: RemoteViews,
        theme: PhantomTheme,
        animationsEnabled: Boolean,
    ) {
        val enabled = periodicGlitchEnabled(theme.notificationAnimation, animationsEnabled)
        views.setViewVisibility(R.id.widget_glitch, if (enabled) View.VISIBLE else View.GONE)
        views.removeAllViews(R.id.widget_glitch)
        if (!enabled) return
        val schedule = GlitchSchedule()
        views.setInt(R.id.widget_glitch, "setFlipInterval", schedule.intervalMillis)
        repeat(schedule.children) { index ->
            val child = RemoteViews(context.packageName, R.layout.widget_glitch_frame)
            if (index in schedule.glitchIndices) {
                child.setImageViewBitmap(R.id.glitch_frame, frame(theme, index))
            }
            views.addView(R.id.widget_glitch, child)
        }
    }

    private fun frame(
        theme: PhantomTheme,
        seed: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        glitchSlices(seed).forEachIndexed { index, slice ->
            val top = slice.topFraction * 48
            paint.color = theme.color(if (index % 2 == 0) "accent" else "notifications").argb
            paint.alpha = 100
            canvas.drawRect(8f + slice.offset, top, 115f + slice.offset, top + 0.7f, paint)
            paint.color = theme.color("subtext2").argb
            paint.alpha = 70
            canvas.drawRect(20f, top + 1, 105f, top + 1.5f, paint)
        }
        return bitmap
    }
}
