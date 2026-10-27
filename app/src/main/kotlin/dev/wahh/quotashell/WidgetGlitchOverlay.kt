package dev.wahh.quotashell

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import dev.wahh.quotashell.core.GlitchSchedule
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.periodicGlitchEnabled

/** Base ImageView + empty children avoids relying on RemoteViews bitmap-cache deduplication.
 * Only two opaque full-board glitch frames cover the base during the periodic pulse.
 */
internal object WidgetGlitchOverlay {
    fun apply(
        context: Context,
        views: RemoteViews,
        theme: PhantomTheme,
        animationsEnabled: Boolean,
        renderFrame: (Int) -> Bitmap,
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
                child.setImageViewBitmap(R.id.glitch_frame, renderFrame(index))
            }
            views.addView(R.id.widget_glitch, child)
        }
    }
}
