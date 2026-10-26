package dev.wahh.quotashell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import dev.wahh.quotashell.core.board
import java.time.Instant
import java.time.ZoneId

/**
 * One widget showing every provider, drawn as a PhantomShell-style board. No network work in a
 * BroadcastReceiver; refresh opens the dashboard's integration point.
 */
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
            val content = board(store.snapshots(), Instant.now(), store.staleMinutes() * 60L, ZoneId.systemDefault())
            val (widthDp, heightDp) = sizeDp(manager.getAppWidgetOptions(id))
            val views = RemoteViews(context.packageName, R.layout.widget_quota)
            views.setImageViewBitmap(R.id.widget_board, PhantomBoardRenderer.render(context, content, store.theme(), widthDp, heightDp))
            views.setContentDescription(
                R.id.widget_board,
                content.cards.joinToString(". ") { card ->
                    (listOf(card.name, card.status) + card.rows.map { "${it.label} ${it.usage}" }).joinToString(", ")
                },
            )
            views.setOnClickPendingIntent(R.id.widget_board, activityIntent(context, id, "dashboard"))
            views.setOnClickPendingIntent(R.id.widget_refresh, activityIntent(context, id, "refresh"))
            manager.updateAppWidget(id, views)
        }

        /** Portrait size the launcher gave this instance; falls back to the declared default. */
        private fun sizeDp(options: Bundle): Pair<Float, Float> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    }
                sizes?.firstOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let { return it.width to it.height }
            }
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return if (width > 0 && height > 0) width.toFloat() to height.toFloat() else 250f to 180f
        }

        private fun activityIntent(
            context: Context,
            id: Int,
            action: String,
        ): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    this.action = if (action == "refresh") MainActivity.ACTION_REFRESH else Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            intent.data = Uri.parse("quotashell://widget/$id/$action")
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
