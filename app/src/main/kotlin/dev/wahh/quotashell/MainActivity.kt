package dev.wahh.quotashell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.Toast
import dev.wahh.quotashell.core.ProviderId
import dev.wahh.quotashell.core.present
import java.time.Instant

class MainActivity : Activity() {
    private val services get() = application as QuotaApplication
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private val onChange: () -> Unit = { render() }
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
            }
        }
    }

    private fun render() {
        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        val theme = services.store.theme()
        val body = column()
        body.addView(text("> QuotaShell", 24f, theme.accentArgb))
        body.addView(text("Subscription quota · read only"))
        body.addView(button("Refresh now") { refresh() })
        body.addView(button("Connections / reconnect") { startActivity(Intent(this, ConnectionsActivity::class.java)) })
        body.addView(
            button("Settings / widget appearance") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        val now = Instant.now()
        for (snapshot in services.store.snapshots()) {
            val display = present(snapshot, now, services.store.staleMinutes() * 60L)
            val card = panel(theme.accentArgb, theme.backgroundArgb)
            card.addView(text(display.title, 18f, theme.accentArgb))
            card.addView(text("${display.state} · ${display.age}"))
            if (display.windows.isEmpty()) card.addView(text("No reported quota windows"))
            for (window in display.windows) {
                card.addView(text("${window.title} · ${window.usage}"))
                window.progress?.let { card.addView(bar(it, theme.accentArgb)) }
                card.addView(text(window.reset, 12f))
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
        terminalContent(scroll)
        scroll.post { scroll.scrollTo(0, y) }
    }

    companion object {
        const val ACTION_REFRESH = "dev.wahh.quotashell.REFRESH"
    }
}
