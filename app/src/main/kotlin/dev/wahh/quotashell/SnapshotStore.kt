package dev.wahh.quotashell

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.wahh.quotashell.core.*
import java.util.concurrent.CopyOnWriteArraySet

/** App-private, nonsecret cache only. T3 MUST use a separate secure credential repository. */
class SnapshotStore(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("nonsecret_snapshot_v1", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun snapshots(): List<ProviderSnapshot> =
        ProviderId.entries.map {
            SnapshotCodec.decode(it, prefs.getString("snapshot.${it.name}", null))
        }

    fun theme() = TerminalTheme(prefs.getString("accent", "2196F3") ?: "2196F3", prefs.getInt("opacity", 90))

    fun staleMinutes() = RefreshPolicy.staleMinutes(if (prefs.contains("staleMinutes")) prefs.getInt("staleMinutes", 0) else null)

    fun widgetProvider(id: Int): ProviderId =
        ProviderId.entries.firstOrNull {
            it.name == prefs.getString("widget.$id", null)
        } ?: ProviderId.CODEX

    /** Partial updates are allowed; omitted providers retain their snapshot. */
    fun saveSnapshots(snapshots: List<ProviderSnapshot>) {
        require(snapshots.map { it.provider }.distinct().size == snapshots.size)
        val editor = prefs.edit()
        snapshots.forEach { editor.putString("snapshot.${it.provider.name}", SnapshotCodec.encode(it)) }
        editor.apply()
        changed()
    }

    fun saveSettings(
        theme: TerminalTheme,
        staleMinutes: Int,
    ) {
        prefs
            .edit()
            .putString("accent", "%06X".format(theme.accentArgb and 0xFFFFFF))
            .putInt("opacity", theme.opacityPercent.coerceIn(0, 100))
            .putInt("staleMinutes", RefreshPolicy.staleMinutes(staleMinutes))
            .apply()
        changed()
    }

    fun setWidgetProvider(
        id: Int,
        provider: ProviderId,
    ) {
        prefs.edit().putString("widget.$id", provider.name).apply()
        changed()
    }

    fun deleteWidget(id: Int) {
        prefs.edit().remove("widget.$id").apply()
    }

    fun listen(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unlisten(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun changed() {
        main.post {
            QuotaWidget.updateAll(context)
            listeners.forEach { it() }
        }
    }
}
