package dev.wahh.quotashell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.wahh.quotashell.core.Connection
import dev.wahh.quotashell.core.ProviderId
import dev.wahh.quotashell.core.ProviderSnapshot
import dev.wahh.quotashell.core.present
import java.time.Instant

class MainActivity : Activity() {
    private val services get() = application as QuotaApplication
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private var displayedSnapshots = emptyList<ProviderSnapshot>()
    private val onChange: () -> Unit = {
        val snapshotChanged = displayedSnapshots != services.store.snapshots()
        render()
        if (snapshotChanged) scroll.post { glitchAppElements() }
    }
    private val tick =
        object : Runnable {
            override fun run() {
                render()
                handler.postDelayed(this, 60_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        consumeRefreshIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRefreshIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        services.store.listen(onChange)
        render()
        scroll.post { glitchAppElements() }
        handler.postDelayed(tick, 60_000)
    }

    override fun onPause() {
        services.store.unlisten(onChange)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun consumeRefreshIntent(intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            intent.action = Intent.ACTION_MAIN
            refresh()
        }
    }

    private fun refresh() {
        services.refresh { message ->
            if (!isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
                scroll.post { glitchAppElements() }
            }
        }
    }

    private fun render() {
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        val body = column()
        val header = panel(code = "QSH-00")
        header.addView(text("> QuotaShell", 28f))
        header.addView(text("Subscription quota · read only", 13f))
        body.addView(header)
        body.addView(button("Refresh now") { refresh() })
        body.addView(button("Connections / reconnect") { startActivity(Intent(this, ConnectionsActivity::class.java)) })
        body.addView(
            button("Settings / widget appearance") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        val now = Instant.now()
        displayedSnapshots = services.store.snapshots()
        for (snapshot in displayedSnapshots) {
            val display = present(snapshot, now, services.store.staleMinutes() * 60L)
            val card =
                panel(
                    code =
                        when (snapshot.provider) {
                            ProviderId.CODEX -> "CDX-01"
                            ProviderId.CLAUDE -> "CLD-02"
                            ProviderId.OPENCODE_GO -> "OCG-03"
                        },
                )
            card.addView(text(display.title, 22f))
            if (snapshot.connection == Connection.RECONNECT_REQUIRED || snapshot.connection == Connection.ERROR) {
                card.addView(alertGlyph())
            }
            card.addView(text("${display.state} · ${display.age}"))
            if (display.windows.isEmpty()) card.addView(text("No reported quota windows"))
            for (window in display.windows) {
                val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(quotaChart(window.progress))
                val labels =
                    column().apply {
                        setPadding(0, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                labels.addView(text("${window.title} · ${window.usage}", 20f))
                labels.addView(text(window.reset, 14f))
                row.addView(labels)
                card.addView(row)
            }
            if (snapshot.provider == ProviderId.CODEX) {
                card.addView(text(display.resetInventory ?: "Codex resets available: Unknown"))
            }
            body.addView(card)
        }
        body.addView(
            text(
                "Manual credentials · no official login. Expiry/rejection is detected only on refresh; Android may delay background checks. No quotas are simulated.",
                12f,
            ),
        )
        scroll = ScrollView(this).apply { addView(body) }
        terminalContent(scroll, openingGlitch = false)
        scroll.post { scroll.scrollTo(0, y) }
    }

    companion object {
        const val ACTION_REFRESH = "dev.wahh.quotashell.REFRESH"
    }
}
