package dev.wahh.quotashell

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import dev.wahh.quotashell.core.ProviderId
import dev.wahh.quotashell.core.ui.hudClock
import dev.wahh.quotashell.core.ui.hudSignal
import dev.wahh.quotashell.core.ui.hudStatus
import java.time.Instant
import java.time.ZoneId

internal fun Activity.hudHeader(screen: String): LinearLayout {
    val store = (application as QuotaApplication).store
    val header = panel(code = "QSH // $screen")
    header.addView(text("QUOTA/SHELL", 24f).apply { tag = TextStyle(24f, "notifications") })
    val clock = text("", 13f).apply { tag = TextStyle(13f, "subtext2") }
    // The clock is kept outside the reveal comparison so a ticking HUD stays clean.
    clock.contentDescription = "Local clock"
    val links = text("", 12f)
    val update: () -> Unit = {
        val now = Instant.now()
        val snapshots = store.snapshots()
        val count = snapshots.count { hudStatus(it, now, store.staleMinutes() * 60L) == "LINK OK" }
        clock.text = getString(R.string.hud_clock, hudSignal(count), hudClock(now, ZoneId.systemDefault()))
        links.text =
            snapshots.joinToString("\n") { snapshot ->
                val code =
                    when (snapshot.provider) {
                        ProviderId.CODEX -> "CDX"
                        ProviderId.CLAUDE -> "CLD"
                        ProviderId.OPENCODE_GO -> "OCG"
                    }
                val status = hudStatus(snapshot, now, store.staleMinutes() * 60L)
                val auth =
                    when (status) {
                        "RECONNECT" -> "RENEW"
                        "NO CARRIER" -> "--"
                        else -> "OK"
                    }
                "$code // AUTH $auth // $status"
            }
    }
    val tick =
        object : Runnable {
            override fun run() {
                if (header.isShown && header.hasWindowFocus()) update()
                header.postDelayed(this, 1000)
            }
        }
    header.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                store.listen(update)
                update()
                header.post(tick)
            }

            override fun onViewDetachedFromWindow(view: View) {
                store.unlisten(update)
                header.removeCallbacks(tick)
            }
        },
    )
    header.addView(clock)
    header.addView(links)
    update()
    return header
}
