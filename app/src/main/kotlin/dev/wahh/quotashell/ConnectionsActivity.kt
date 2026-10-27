package dev.wahh.quotashell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.wahh.quotashell.core.*
import dev.wahh.quotashell.core.ui.*
import java.time.Instant
import java.time.ZoneId

/** Manual entry only. Never preload, autofill, save hierarchy state or pass credentials. */
class ConnectionsActivity : Activity() {
    private val services get() = application as QuotaApplication
    private val drafts = mutableListOf<ThemedInput>()
    private var displayedSnapshots = emptyList<ProviderSnapshot>()
    private val onChange: () -> Unit = {
        val snapshotChanged = displayedSnapshots != services.store.snapshots()
        updateStates()
        if (snapshotChanged) window.decorView.post { glitchAppElements() }
    }
    private val states = mutableMapOf<ProviderId, TextView>()
    private val alerts = mutableMapOf<ProviderId, View>()
    private val clearPasteState = mutableListOf<() -> Unit>()
    private var organizationPicker: LinearLayout? = null
    private var renderedOrganizations: ClaudeOrganizationState? = null
    private var claudeKind = ClaudeCredentialKind.OAUTH_TOKEN
    private lateinit var feedback: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        claudeKind = services.claudeCredentialKind()
        val body = panel(code = "LINK-00")
        body.addView(hudHeader("CONNECTIONS"))
        body.addView(text("◢◤◢◤ // SECURE LINK CONSOLE", 18f))
        body.addView(
            text("Enter credentials only on this device. No official login or OAuth token renewal. Never send credentials in chat.", 13f),
        )
        feedback = text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(feedback)
        ProviderId.entries.forEach { body.addView(providerCard(it)) }
        body.addView(
            Switch(this).apply {
                setText(R.string.notify_opt_in)
                minimumHeight = dp(48)
                minHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "Credential expiry notifications"
                isChecked = services.expiryNotifications.optedIn
                setOnCheckedChangeListener { _, checked ->
                    services.expiryNotifications.optedIn = checked
                    if (checked && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                    }
                }
            },
        )
        body.addView(
            text(
                "Warn when a refresh detects rejected credentials. Expiry time is unknown; Android may delay checks. Permission and network errors do not prove expiry.",
                13f,
            ),
        )
        body.addView(
            button("Notification permission / channel settings") {
                startActivity(
                    android.content
                        .Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            },
        )
        body.addView(
            button("Refresh existing connections") {
                feedback.setText(R.string.input_refreshing)
                services.refresh {
                    if (!isDestroyed) {
                        updateStates()
                        feedback.text = it
                    }
                }
            },
        )
        terminalContent(ScrollView(this).apply { addView(body) })
        updateStates()
    }

    private fun providerCard(provider: ProviderId): LinearLayout {
        val card = panel(code = "LINK-0${provider.ordinal + 1}")
        card.addView(text(provider.title, 19f))
        alerts[provider] = alertGlyph().also { card.addView(it) }
        states[provider] = text("").also { card.addView(it) }
        val token =
            secretField(
                "New ${provider.title} credential",
                "Paste or type a new credential",
                "Never prefilled · stored encrypted on this device",
                paste = true,
            )
        if (provider == ProviderId.CLAUDE) {
            card.addView(
                ChoiceControl(
                    this,
                    "Claude connection mode",
                    listOf("OAuth token", "claude.ai sessionKey"),
                    if (claudeKind == ClaudeCredentialKind.SESSION_KEY) "claude.ai sessionKey" else "OAuth token",
                    segmented = true,
                ) { label ->
                    val next = if (label == "OAuth token") ClaudeCredentialKind.OAUTH_TOKEN else ClaudeCredentialKind.SESSION_KEY
                    val cleared = secretAfterModeChange(claudeKind, next, token.edit.text.toString())
                    claudeKind = next
                    token.edit.text.replace(0, token.edit.length(), cleared)
                    hideSecrets()
                    updateOrganizationPicker()
                },
            )
            card.addView(
                text(
                    "WARNING: sessionKey grants full claude.ai web account access, not just quota reads. Usually about 28 days, extended by site activity; not guaranteed. Treat it as a password.",
                    13f,
                ),
            )
        }
        card.addView(token)
        val account =
            if (provider == ProviderId.CODEX) {
                secretField(
                    "Optional ChatGPT account ID",
                    "Account / workspace ID",
                    "Leave empty for the token's default account",
                ).also {
                    card.addView(disclosure("Advanced · optional account ID", it))
                }
            } else {
                null
            }
        if (provider == ProviderId.CLAUDE) {
            organizationPicker =
                LinearLayout(this)
                    .apply {
                        orientation = LinearLayout.VERTICAL
                        isSaveEnabled = false
                        visibility = View.GONE
                    }.also { card.addView(it) }
        }
        card.addView(
            disclosure(
                "How to get this",
                text(
                    when (provider) {
                        ProviderId.CODEX -> "On your trusted computer, read an existing Codex OAuth access token:\njq -r '.tokens.access_token' ~/.codex/auth.json\nCopy only the value. Not an OpenAI API key or refresh token. Never send it through chat or adb."
                        ProviderId.CLAUDE -> "OAuth: use an existing access token with user:profile scope; inference-only/setup-token and API keys cannot read usage.\nWeb: trusted browser > DevTools > Application/Storage > Cookies > https://claude.ai > sessionKey. Copy only its value (or sessionKey=value), never the full Cookie header."
                        ProviderId.OPENCODE_GO -> "Open the OpenCode Go console on opencode.ai, then your account's key-management page. Copy your provider-issued API key."
                    },
                    13f,
                ),
            ),
        )
        val result = text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        card.addView(result)
        var busy = false
        var savedPaste: SavedPaste? = null
        val clear =
            button("Clear clipboard") {
                if (savedPaste?.canClear(clipboardText(), Build.VERSION.SDK_INT) == true && Build.VERSION.SDK_INT >= 28) {
                    getSystemService(ClipboardManager::class.java).clearPrimaryClip()
                    result.setText(R.string.input_clipboard_cleared)
                } else {
                    result.setText(R.string.input_clipboard_changed)
                }
                savedPaste = null
            }.apply { visibility = View.GONE }
        lateinit var save: Button

        fun validate() {
            val validation =
                credentialField(
                    token.edit.text.toString(),
                    kind =
                        if (provider ==
                            ProviderId.CLAUDE
                        ) {
                            claudeKind
                        } else {
                            ClaudeCredentialKind.OAUTH_TOKEN
                        },
                )
            token.validate(validation)
            val accountValidation = account?.let { accountField(it.edit.text.toString()).also(it::validate) }
            save.isEnabled = validation.valid && accountValidation?.valid != false && !busy
            save.alpha = if (save.isEnabled) 1f else 0.45f
        }
        save =
            button("Save & refresh") {
                savedPaste = null
                clear.visibility = View.GONE
                hideSecrets()
                busy = true
                validate()
                result.setText(R.string.input_saving)
                try {
                    val submittedToken = token.edit.text.toString()
                    val credential =
                        ManualCredential(
                            submittedToken,
                            account
                                ?.edit
                                ?.text
                                ?.toString()
                                ?.ifEmpty { null },
                            if (provider ==
                                ProviderId.CLAUDE
                            ) {
                                claudeKind
                            } else {
                                ClaudeCredentialKind.OAUTH_TOKEN
                            },
                        )
                    services.replaceCredential(provider, credential)
                    savedPaste = token.pasteProvenance.saved(submittedToken)
                    hideSecrets()
                    token.edit.text.clear()
                    account?.edit?.text?.clear()
                    clear.visibility = if (savedPaste != null && Build.VERSION.SDK_INT >= 28) View.VISIBLE else View.GONE
                    result.setText(R.string.input_saved_refreshing)
                    updateStates()
                    services.refresh {
                        if (!isDestroyed) {
                            busy = false
                            validate()
                            updateStates()
                            result.text = getString(R.string.input_saved_result, it)
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    busy = false
                    validate()
                    result.setText(R.string.input_invalid)
                } catch (_: Exception) {
                    busy = false
                    validate()
                    result.setText(R.string.input_storage_unconfirmed)
                }
            }
        token.onChanged = { validate() }
        account?.onChanged = { validate() }
        validate()
        card.addView(save)
        card.addView(clear)
        clearPasteState += {
            savedPaste = null
            clear.visibility = View.GONE
        }
        card.addView(text("// DANGER ZONE · removes this device's connection", 12f))
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
                            hideSecrets()
                            savedPaste = null
                            token.edit.text.clear()
                            account?.edit?.text?.clear()
                            clear.visibility = View.GONE
                            result.setText(R.string.input_removed)
                            updateStates()
                        } catch (_: Exception) {
                            result.setText(R.string.input_remove_failed)
                        }
                    }.show()
            }.apply { tag = TextStyle(16f, "notifications") },
        )
        return card
    }

    /** All secret inputs must be constructed here so every lifecycle boundary covers them. */
    private fun secretField(
        label: String,
        placeholder: String,
        helper: String,
        paste: Boolean = false,
    ): ThemedInput = ThemedInput(this, label, placeholder, helper, secret = true, paste = paste).also { drafts += it }

    private fun hideSecrets() {
        drafts.forEach { it.hideSecret() }
    }

    private fun clearDrafts() {
        hideSecrets()
        drafts.forEach { it.edit.text.clear() }
        clearPasteState.forEach { it() }
    }

    private fun clipboardText(): String? {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        return clip
            .takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    }

    private fun updateOrganizationPicker() {
        val picker = organizationPicker ?: return
        val state = services.claudeOrganizations()
        val visible = claudeKind == ClaudeCredentialKind.SESSION_KEY && state != null && state.options.size > 1
        picker.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || state === renderedOrganizations) return
        picker.removeAllViews()
        renderedOrganizations = state
        val selected = (state as? ClaudeOrganizationState.Selected)?.organization?.id
        val options = state!!.options
        val labels = options.map { "${it.name}\n${it.id}" }
        picker.addView(
            ChoiceControl(
                this,
                "Choose an organization explicitly to read quotas",
                labels,
                options.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { labels[it] } ?: "No organization selected",
                preserveUnknown = false,
            ) { label ->
                val organization = options.getOrNull(labels.indexOf(label)) ?: return@ChoiceControl
                try {
                    services.selectClaudeOrganization(organization.id)
                    hideSecrets()
                    updateStates()
                    services.refresh {
                        if (!isDestroyed) {
                            updateStates()
                            feedback.text = it
                        }
                    }
                } catch (_: Exception) {
                    feedback.setText(R.string.input_org_failed)
                    renderedOrganizations = null
                    updateOrganizationPicker()
                }
            },
        )
    }

    private fun updateStates() {
        updateOrganizationPicker()
        displayedSnapshots = services.store.snapshots()
        displayedSnapshots.forEach { snapshot ->
            val alert = snapshot.connection in listOf(Connection.RECONNECT_REQUIRED, Connection.ERROR)
            alerts[snapshot.provider]?.visibility = if (alert) View.VISIBLE else View.GONE
            val display = present(snapshot, Instant.now(), services.store.staleMinutes() * 60L)
            states[snapshot.provider]?.let { label ->
                val status =
                    when (snapshot.connection) {
                        Connection.DISCONNECTED -> "NOT CONNECTED"
                        Connection.ERROR -> "ERROR"
                        else -> hudStatus(snapshot, Instant.now(), services.store.staleMinutes() * 60L)
                    }
                label.text = getString(R.string.connection_status, status, providerSyncStatus(snapshot, display, ZoneId.systemDefault()))
                label.tag = TextStyle(16f, if (alert) "notifications" else "text")
                label.setTextColor(
                    services.store
                        .phantomTheme()
                        .color(if (alert) "notifications" else "text")
                        .argb,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        services.store.listen(onChange)
        updateStates()
    }

    override fun onPause() {
        clearDrafts()
        organizationPicker?.removeAllViews()
        renderedOrganizations = null
        services.store.unlisten(onChange)
        super.onPause()
    }

    override fun onStop() {
        clearDrafts()
        super.onStop()
    }
}
