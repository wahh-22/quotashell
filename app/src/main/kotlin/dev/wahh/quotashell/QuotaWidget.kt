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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.SizeF
import android.widget.RemoteViews
import dev.wahh.quotashell.core.Board
import dev.wahh.quotashell.core.board
import dev.wahh.quotashell.core.shouldGlitchBurst
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
        ids.forEach {
            store.deleteWidget(it)
            previous.remove(it)
            pending.remove(it)?.let(main::removeCallbacks)
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val previous = mutableMapOf<Int, Board>()
        private val pending = mutableMapOf<Int, Runnable>()

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
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post { update(context.applicationContext, manager, id) }
                return
            }
            pending.remove(id)?.let(main::removeCallbacks)
            val store = (context.applicationContext as QuotaApplication).store
            val content = board(store.snapshots(), Instant.now(), store.staleMinutes() * 60L, ZoneId.systemDefault(), allCaps = false)
            val (widthDp, heightDp) = sizeDp(manager.getAppWidgetOptions(id))
            val theme = store.phantomTheme()
            val enabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            val burst = shouldGlitchBurst(previous[id], content, enabled, theme.notificationAnimation)
            previous[id] = content
            val appContext = context.applicationContext

            fun publish(frame: Int) {
                val views = RemoteViews(appContext.packageName, R.layout.widget_quota)
                views.setImageViewBitmap(
                    R.id.widget_board,
                    PhantomBoardRenderer.render(appContext, content, theme, widthDp, heightDp, frame),
                )
                views.setContentDescription(
                    R.id.widget_board,
                    content.cards.joinToString(". ") { card ->
                        (
                            listOf(card.name, card.status) + card.rows.map { "${it.label} ${it.usage}, reset ${it.reset}" } +
                                listOfNotNull(card.note)
                        ).joinToString(", ")
                    },
                )
                views.setOnClickPendingIntent(R.id.widget_board, activityIntent(appContext, id, "dashboard"))
                views.setOnClickPendingIntent(R.id.widget_refresh, activityIntent(appContext, id, "refresh"))
                manager.updateAppWidget(id, views)
            }
            if (!burst) {
                publish(0)
                return
            }
            // Only one pending callback per widget. New data/resize/delete cancels old frames.
            var frame = 1
            val next =
                object : Runnable {
                    override fun run() {
                        val animationsStillEnabled =
                            Settings.Global.getFloat(appContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
                        if (frame <= 3 && animationsStillEnabled) {
                            publish(frame++)
                            main.postDelayed(this, 150L)
                        } else {
                            publish(0)
                            pending.remove(id)
                        }
                    }
                }
            pending[id] = next
            next.run()
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
            return if (width > 0 && height > 0) width.toFloat() to height.toFloat() else 400f to 148f
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
