package dev.wahh.quotashell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import dev.wahh.quotashell.core.*
import dev.wahh.quotashell.core.ui.hudStatus
import java.time.Instant
import java.time.ZoneId

/** Manual entry only. Never preload, reveal, autofill, save hierarchy state or pass credentials. */
class ConnectionsActivity : Activity() {
    private val services get() = application as QuotaApplication
    private val drafts = mutableListOf<EditText>()
    private var displayedSnapshots = emptyList<ProviderSnapshot>()
    private val onChange: () -> Unit = {
        val snapshotChanged = displayedSnapshots != services.store.snapshots()
        updateStates()
        if (snapshotChanged) window.decorView.post { glitchAppElements() }
    }
    private val states = mutableMapOf<ProviderId, android.widget.TextView>()
    private val alerts = mutableMapOf<ProviderId, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = panel(code = "LINK-00")
        body.addView(hudHeader("CONNECTIONS"))
        body.addView(text("◢◤◢◤ // SECURE LINK CONSOLE", 18f))
        body.addView(
            text(
                "Enter credentials only on this device. No official login or token renewal is provided. Replace credentials after renewing through the provider. Never send credentials in chat.",
                13f,
            ),
        )
        body.addView(
            text(
                "Expiry time is unknown. A refresh detecting HTTP 401 reports credentials expired or rejected; other failures do not mean expiry. Android may delay background refresh and notifications.",
                13f,
            ),
        )
        val notify =
            CheckBox(this).apply {
                setText(R.string.notify_opt_in)
                isChecked = services.expiryNotifications.optedIn
                setTextColor(android.graphics.Color.WHITE)
                setOnCheckedChangeListener { _, checked ->
                    services.expiryNotifications.optedIn = checked
                    if (checked && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                    }
                }
            }
        body.addView(notify)
        body.addView(
            button("Notification permission / channel settings") {
                startActivity(
                    android.content
                        .Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            },
        )
        for (provider in ProviderId.entries) {
            val card = panel(code = "LINK-0${provider.ordinal + 1}")
            card.addView(text(provider.title.uppercase(java.util.Locale.ROOT), 19f))
            alerts[provider] = alertGlyph().also { card.addView(it) }
            states[provider] = text("").also { card.addView(it) }
            card.addView(
                text(
                    when (provider) {
                        ProviderId.CODEX -> "Manual Codex OAuth access token (not an OpenAI API key). Optional account/workspace ID below."
                        ProviderId.CLAUDE -> "Manual Claude OAuth access token with user:profile scope. Inference-only/setup-token, API keys and cookies are not supported."
                        ProviderId.OPENCODE_GO -> "Manual OpenCode Go provider API key from your OpenCode account."
                    },
                    13f,
                ),
            )
            val token = secretField("New access token / API key")
            card.addView(token)
            val account = if (provider == ProviderId.CODEX) secretField("Optional ChatGPT account ID").also { card.addView(it) } else null
            card.addView(
                button("Save / replace and refresh") {
                    try {
                        val credential = ManualCredential(token.text.toString(), account?.text?.toString()?.ifEmpty { null })
                        services.replaceCredential(provider, credential)
                        token.text.clear()
                        account?.text?.clear()
                        updateStates()
                        services.refresh {
                            if (!isDestroyed) {
                                updateStates()
                                glitchAppElements()
                                message(it)
                            }
                        }
                    } catch (_: IllegalArgumentException) {
                        message("Enter a nonempty credential without whitespace, controls or non-ASCII characters.")
                    } catch (_: Exception) {
                        message("Secure storage unavailable. Credential was not saved; retry or remove the connection.")
                    }
                },
            )
            card.addView(
                button("Remove connection and cached quotas") {
                    AlertDialog
                        .Builder(this)
                        .setTitle("Remove ${provider.title}?")
                        .setMessage("Removes its credential, quota cache and reconnect notification from this device.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            try {
                                services.replaceCredential(provider, null)
                                token.text.clear()
                                account?.text?.clear()
                                updateStates()
                            } catch (_: Exception) {
                                message("Secure storage could not be updated. Try again.")
                            }
                        }.show()
                        .also { dialog ->
                            val content = dialog.findViewById<android.view.ViewGroup>(android.R.id.content)
                            if (content != null) {
                                content.tag = PanelStyle("LINK-DEL")
                                installPhantomShell(content, openingGlitch = false)
                            }
                        }
                },
            )
            body.addView(card)
        }
        body.addView(
            button("Refresh existing connections") {
                services.refresh {
                    if (!isDestroyed) {
                        updateStates()
                        glitchAppElements()
                        message(it)
                    }
                }
            },
        )
        body.addView(text("└── LINK-00 // LOCAL INPUT ONLY ─╱", 12f))
        terminalContent(ScrollView(this).apply { addView(body) })
        updateStates()
    }

    private fun secretField(label: String) =
        EditText(this).apply {
            hint = "[ $label ]"
            setHintTextColor(0xFFAAAAAA.toInt())
            setTextColor(android.graphics.Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(android.text.InputFilter.LengthFilter(16384))
            drafts += this
        }

    private fun updateStates() {
        displayedSnapshots = services.store.snapshots()
        displayedSnapshots.forEach { snapshot ->
            val alert = snapshot.connection in listOf(Connection.RECONNECT_REQUIRED, Connection.ERROR)
            alerts[snapshot.provider]?.visibility = if (alert) View.VISIBLE else View.GONE
            val display = present(snapshot, Instant.now(), services.store.staleMinutes() * 60L)
            states[snapshot.provider]?.let { label ->
                label.text =
                    getString(
                        R.string.connection_status,
                        hudStatus(snapshot, Instant.now(), services.store.staleMinutes() * 60L),
                        providerSyncStatus(snapshot, display, ZoneId.systemDefault()).uppercase(java.util.Locale.ROOT),
                    )
                val slot = if (alert) "notifications" else "text"
                label.tag = TextStyle(16f, slot)
                label.setTextColor(
                    services.store
                        .phantomTheme()
                        .color(slot)
                        .argb,
                )
            }
        }
    }

    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        services.store.listen(onChange)
        updateStates()
        window.decorView.post { glitchAppElements() }
    }

    override fun onPause() {
        drafts.forEach { it.text.clear() }
        services.store.unlisten(onChange)
        super.onPause()
    }
}
