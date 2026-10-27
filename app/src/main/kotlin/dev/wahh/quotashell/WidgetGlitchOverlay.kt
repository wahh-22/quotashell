package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import dev.wahh.quotashell.core.GlitchSchedule
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.periodicGlitchEnabled

/** Replace, rather than cover, the base so theme transparency stays stable.
 * Clean children share one Bitmap instance; RemoteViews interns it in its bitmap cache.
 */
internal object WidgetGlitchOverlay {
    fun apply(
        context: Context,
        views: RemoteViews,
        theme: PhantomTheme,
        animationsEnabled: Boolean,
        cleanFrame: Bitmap,
        renderFrame: (Int) -> Bitmap,
    ) {
        val enabled = periodicGlitchEnabled(theme.notificationAnimation, animationsEnabled)
        views.setViewVisibility(R.id.widget_glitch, if (enabled) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_board, if (enabled) View.INVISIBLE else View.VISIBLE)
        views.removeAllViews(R.id.widget_glitch)
        if (!enabled) return
        val schedule = GlitchSchedule()
        views.setInt(R.id.widget_glitch, "setFlipInterval", schedule.intervalMillis)
        repeat(schedule.children) { index ->
            val child = RemoteViews(context.packageName, R.layout.widget_glitch_frame)
            child.setImageViewBitmap(R.id.glitch_frame, if (index in schedule.glitchIndices) renderFrame(index) else cleanFrame)
            views.addView(R.id.widget_glitch, child)
        }
    }
}
