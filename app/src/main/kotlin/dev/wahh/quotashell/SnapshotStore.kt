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

    fun phantomTheme(): PhantomTheme {
        val json = prefs.getString("phantomTheme", null)
        if (json != null) {
            return runCatching { PhantomTheme.parse(json) }.getOrElse { PhantomTheme.default() }
        }
        return PhantomTheme.migrateLegacy(
            prefs.getString("accent", null),
            if (prefs.contains("opacity")) prefs.getInt("opacity", 90) else null,
        )
    }

    /** Dashboard retains its native-view layout; board-only shapes/type remain widget settings. */
    fun theme(): TerminalTheme {
        val theme = phantomTheme()
        return TerminalTheme(
            "%06X".format(theme.color("accent").argb and 0xFFFFFF),
            kotlin.math.round(theme.color("window").alpha * 100).toInt(),
        )
    }

    fun staleMinutes() = RefreshPolicy.staleMinutes(if (prefs.contains("staleMinutes")) prefs.getInt("staleMinutes", 0) else null)

    /** Partial updates are allowed; omitted providers retain their snapshot. */
    fun saveSnapshots(snapshots: List<ProviderSnapshot>) {
        require(snapshots.map { it.provider }.distinct().size == snapshots.size)
        val editor = prefs.edit()
        snapshots.forEach { editor.putString("snapshot.${it.provider.name}", SnapshotCodec.encode(it)) }
        editor.apply()
        changed()
    }

    fun saveSettings(
        theme: PhantomTheme,
        staleMinutes: Int,
    ) {
        prefs
            .edit()
            .putString("phantomTheme", theme.toJson())
            .remove("accent")
            .remove("opacity")
            .putInt("staleMinutes", RefreshPolicy.staleMinutes(staleMinutes))
            .apply()
        changed()
    }

    /** Removes the per-widget provider key written by earlier single-provider builds. */
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
