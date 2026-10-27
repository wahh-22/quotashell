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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
    private var organizationPicker: LinearLayout? = null
    private var renderedOrganizations: ClaudeOrganizationState? = null
    private var claudeMode: Spinner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = panel(code = "LINK-00")
        body.addView(hudHeader("CONNECTIONS"))
        body.addView(text("◢◤◢◤ // SECURE LINK CONSOLE", 18f))
        body.addView(
            text(
                "Enter credentials only on this device. No official login or OAuth token renewal is provided. Returned Claude session-cookie renewals are retained only for the current connection. Never send credentials in chat.",
                13f,
            ),
        )
        body.addView(
            text(
                "Expiry time is unknown. Refresh detects rejected credentials (HTTP 401; also ordinary claude.ai web 403, not Cloudflare challenges). Permission, rate limit and network errors do not prove expiry. Android may delay checks.",
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
                        ProviderId.CLAUDE -> "Choose OAuth token (user:profile scope) or a claude.ai sessionKey. Inference-only/setup-token and API keys cannot read usage."
                        ProviderId.OPENCODE_GO -> "Manual OpenCode Go provider API key from your OpenCode account."
                    },
                    13f,
                ),
            )
            val token = secretField("New access token / API key")
            val mode = if (provider == ProviderId.CLAUDE) claudeModeSelector(card, token) else null
            card.addView(token)
            if (provider == ProviderId.CLAUDE) {
                organizationPicker =
                    LinearLayout(this)
                        .apply {
                            orientation = LinearLayout.VERTICAL
                            isSaveEnabled = false
                            visibility = View.GONE
                        }.also { card.addView(it) }
            }
            val account = if (provider == ProviderId.CODEX) secretField("Optional ChatGPT account ID").also { card.addView(it) } else null
            card.addView(
                button("Save / replace and refresh") {
                    try {
                        val kind =
                            if (mode?.selectedItemPosition ==
                                1
                            ) {
                                ClaudeCredentialKind.SESSION_KEY
                            } else {
                                ClaudeCredentialKind.OAUTH_TOKEN
                            }
                        val credential = ManualCredential(token.text.toString(), account?.text?.toString()?.ifEmpty { null }, kind)
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
                        message(
                            "Enter a nonempty credential without whitespace or controls. Session cookie values must not contain semicolons, commas or equals signs (except a leading sessionKey=).",
                        )
                    } catch (_: Exception) {
                        message("Secure persistence is unconfirmed. Retry save or removal; do not assume rollback.")
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

    private fun claudeModeSelector(
        card: LinearLayout,
        token: EditText,
    ): Spinner {
        card.addView(text("// CLAUDE CONNECTION MODE", 13f))
        val labels = listOf("OAuth token", "claude.ai sessionKey")
        val help =
            text(
                "WARNING: sessionKey grants full claude.ai web account access, not just quota reads. Stored encrypted on this device only. Never share it. Usually lasts about 28 days, extended by claude.ai activity; expiry is detected on refresh.\n" +
                    "On your trusted browser: DevTools > Application/Storage > Cookies > https://claude.ai > sessionKey. Copy only its value (or sessionKey=value), not the whole Cookie header.",
                13f,
            )
        val spinner =
            Spinner(this).apply {
                isSaveEnabled = false
                contentDescription = "Claude connection mode"
                adapter =
                    object : ArrayAdapter<String>(this@ConnectionsActivity, android.R.layout.simple_spinner_dropdown_item, labels) {
                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: android.view.ViewGroup,
                        ): View =
                            this@ConnectionsActivity.text("[ ${labels[position]} ]").apply {
                                val theme = services.store.phantomTheme()
                                typeface = HudFont.typeface(this@ConnectionsActivity, false)
                                setTextColor(theme.color("text").argb)
                                textSize = (16f + theme.fontSizeOffset).coerceAtLeast(12f)
                                minHeight = dp(48)
                            }

                        override fun getDropDownView(
                            position: Int,
                            convertView: View?,
                            parent: android.view.ViewGroup,
                        ): View = getView(position, convertView, parent)
                    }
                setSelection(if (services.claudeCredentialKind() == ClaudeCredentialKind.SESSION_KEY) 1 else 0)
            }
        claudeMode = spinner
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    token.text.clear()
                    token.hint = if (position == 1) "[ New claude.ai sessionKey ]" else "[ New OAuth access token ]"
                    help.visibility = if (position == 1) View.VISIBLE else View.GONE
                    updateOrganizationPicker()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        help.visibility = if (spinner.selectedItemPosition == 1) View.VISIBLE else View.GONE
        card.addView(spinner)
        card.addView(help)
        return spinner
    }

    private fun updateOrganizationPicker() {
        val picker = organizationPicker ?: return
        val state = services.claudeOrganizations()
        val visible = claudeMode?.selectedItemPosition == 1 && state != null && state.options.size > 1
        picker.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || state === renderedOrganizations) return
        picker.removeAllViews()
        renderedOrganizations = state
        val selected = (state as? ClaudeOrganizationState.Selected)?.organization?.id
        picker.addView(
            text(
                if (selected ==
                    null
                ) {
                    "Choose an organization explicitly to read quotas:"
                } else {
                    "Organization selected. Choose another to switch:"
                },
                13f,
            ),
        )
        state!!.options.forEach { organization ->
            picker.addView(
                button("${if (organization.id == selected) "Selected: " else "Use: "}${organization.name}\n${organization.id}") {
                    try {
                        services.selectClaudeOrganization(organization.id)
                        updateStates()
                        services.refresh {
                            if (!isDestroyed) {
                                updateStates()
                                message(it)
                            }
                        }
                    } catch (_: Exception) {
                        message("Organization choice or storage is no longer current. Refresh or retry in Connections.")
                    }
                }.apply { isSaveEnabled = false },
            )
        }
        // Style new controls without installing duplicate theme listeners or motion loops.
        // The existing root listener restyles them on subsequent theme changes.
        val theme = services.store.phantomTheme()
        for (index in 0 until picker.childCount) {
            val label = picker.getChildAt(index) as? android.widget.TextView ?: continue
            val size = if (label is android.widget.Button) 16f else 13f
            label.tag = TextStyle(size, if (label is android.widget.Button) "accent" else "subtext")
            label.textSize = (size + theme.fontSizeOffset).coerceAtLeast(12f)
            label.setTextColor(theme.color(if (label is android.widget.Button) "accent" else "subtext").argb)
            if (label is android.widget.Button) {
                label.background = PhantomSurface(this, theme, button = true)
                label.backgroundTintList = null
            }
        }
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
        updateOrganizationPicker()
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
        organizationPicker?.removeAllViews()
        renderedOrganizations = null
        services.store.unlisten(onChange)
        super.onPause()
    }
}
