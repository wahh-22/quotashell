package dev.wahh.quotashell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import dev.wahh.quotashell.core.ProviderId
import dev.wahh.quotashell.core.present
import java.time.Instant

/** No network work in a BroadcastReceiver. Refresh opens the dashboard's integration point. */
class QuotaWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: android.os.Bundle,
    ) {
        update(context, manager, id)
    }

    override fun onDeleted(
        context: Context,
        ids: IntArray,
    ) {
        val store = (context.applicationContext as QuotaApplication).store
        ids.forEach(store::deleteWidget)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, QuotaWidget::class.java)).forEach {
                update(context, manager, it)
            }
        }

        private fun update(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
        ) {
            val store = (context.applicationContext as QuotaApplication).store
            val theme = store.theme()
            val provider = store.widgetProvider(id)
            val snapshot = store.snapshots().first { it.provider == provider }
            val display = present(snapshot, Instant.now(), store.staleMinutes() * 60L)
            val views = RemoteViews(context.packageName, R.layout.widget_quota)
            views.setInt(R.id.widget_root, "setBackgroundColor", theme.backgroundArgb)
            views.setTextViewText(R.id.widget_title, "> ${display.title}")
            views.setTextColor(R.id.widget_title, theme.accentArgb)
            views.setTextViewText(R.id.widget_state, "${display.state} · ${display.age}")
            views.setViewVisibility(R.id.widget_empty, if (display.windows.isEmpty()) View.VISIBLE else View.GONE)
            views.removeAllViews(R.id.widget_windows)
            for (window in display.windows) {
                val row = RemoteViews(context.packageName, R.layout.widget_window)
                row.setTextViewText(R.id.window_label, "${window.title} · ${window.usage}")
                row.setTextViewText(R.id.window_reset, window.reset)
                row.setViewVisibility(R.id.window_bar, if (window.progress == null) View.GONE else View.VISIBLE)
                window.progress?.let {
                    row.setImageViewBitmap(R.id.window_bar, barBitmap(it, theme.accentArgb))
                }
                views.addView(R.id.widget_windows, row)
            }
            views.setViewVisibility(R.id.widget_inventory, if (provider == ProviderId.CODEX) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_inventory, display.resetInventory ?: "Codex resets available: Unknown")
            views.setOnClickPendingIntent(R.id.widget_title, activityIntent(context, id, "dashboard"))
            views.setOnClickPendingIntent(R.id.widget_state, activityIntent(context, id, "connections"))
            views.setOnClickPendingIntent(R.id.widget_refresh, activityIntent(context, id, "refresh"))
            views.setOnClickPendingIntent(R.id.widget_settings, activityIntent(context, id, "settings"))
            manager.updateAppWidget(id, views)
        }

        private fun activityIntent(
            context: Context,
            id: Int,
            action: String,
        ): PendingIntent {
            val intent =
                if (action == "connections") {
                    Intent(context, ConnectionsActivity::class.java)
                } else if (action == "settings") {
                    Intent(context, SettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                } else {
                    Intent(context, MainActivity::class.java).apply {
                        this.action = if (action == "refresh") MainActivity.ACTION_REFRESH else Intent.ACTION_MAIN
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                }
            intent.data = Uri.parse("quotashell://widget/$id/$action")
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Tiny bitmap supports arbitrary accents on RemoteViews even before API 31. */
        private fun barBitmap(
            percent: Int,
            accent: Int,
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(400, 8, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF506070.toInt())
            canvas.drawRect(0f, 0f, 4f * percent, 8f, Paint().apply { color = accent })
            return bitmap
        }
    }
}
