package dev.wahh.quotashell

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.RefreshPolicy

/** Also the launcher configuration screen for the all-provider widget. Import edits a draft. */
class SettingsActivity : Activity() {
    private val store get() = (application as QuotaApplication).store
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var editor: ThemeEditor
    private lateinit var stale: EditText
    private lateinit var message: TextView
    private var pendingExport: PhantomTheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider != ComponentName(this, QuotaWidget::class.java)
        ) {
            finish()
            return
        }
        val theme =
            savedInstanceState?.getString("draft")?.let { runCatching { PhantomTheme.parse(it) }.getOrNull() } ?: store.phantomTheme()
        pendingExport = savedInstanceState?.getString("pendingExport")?.let { runCatching { PhantomTheme.parse(it) }.getOrNull() }
        render(theme, savedInstanceState)
    }

    private fun render(
        theme: PhantomTheme,
        state: Bundle? = null,
        notice: String = "",
    ) {
        val body = panel(code = "CFG-00")
        body.addView(text("> Appearance", 24f))
        body.addView(text("One theme · app and all widgets. Save applies appearance and staleness everywhere.", 12f))
        message = text(state?.getString("message") ?: notice)
        message.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(message)
        body.addView(
            button("Import PhantomShell theme") {
                launchDocument(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    },
                    IMPORT,
                )
            },
        )
        body.addView(
            button("Export theme") {
                val draft = draftOrError() ?: return@button
                pendingExport = draft
                launchDocument(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "quotashell-theme.json")
                    },
                    EXPORT,
                )
            },
        )
        body.addView(
            button("Reset to default") {
                val minutes = stale.text.toString()
                render(PhantomTheme.default(), notice = "Default blue theme loaded. Tap Save to apply.")
                stale.setText(minutes)
            },
        )
        editor = ThemeEditor(this, theme, body, state)
        body.addView(
            text(
                "Mark snapshots stale after minutes (${RefreshPolicy.MIN_STALE_MINUTES}–${RefreshPolicy.MAX_STALE_MINUTES}). Background refresh runs about every ${RefreshPolicy.INTERVAL_MINUTES} min.",
            ),
        )
        stale =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(state?.getString("stale") ?: store.staleMinutes().toString())
            }
        body.addView(stale)
        body.addView(
            text(
                "Imports are limited to 64 KiB. Import/reset changes only this draft until Save. Launcher-only settings are preserved on export, not rendered.",
                12f,
            ),
        )
        body.addView(button("Save") { save() })
        body.addView(button("Cancel") { finish() })
        terminalContent(ScrollView(this).apply { addView(body) })
    }

    private fun draftOrError(): PhantomTheme? =
        try {
            editor.theme()
        } catch (error: IllegalArgumentException) {
            message.text = error.message
            null
        }

    @Suppress("DEPRECATION") // Native Activity API: no AndroidX dependency.
    private fun launchDocument(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent, request)
        } catch (_: ActivityNotFoundException) {
            message.setText(R.string.theme_no_picker)
        } catch (_: SecurityException) {
            message.setText(R.string.theme_access_denied)
        }
    }

    @Deprecated("Native Activity result bridge")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val io = ThemeDocumentIo(this)
        when (requestCode) {
            IMPORT -> {
                message.setText(R.string.theme_reading)
                io.import(uri) { result ->
                    result.fold(
                        onSuccess = { theme ->
                            val minutes = stale.text.toString()
                            render(theme, notice = "Theme imported. Tap Save to apply.")
                            stale.setText(minutes)
                        },
                        onFailure = { error ->
                            message.text =
                                if (error is IllegalArgumentException) error.message else "Cannot read theme. Choose a valid UTF-8 PhantomShell JSON file (64 KiB maximum)."
                        },
                    )
                }
            }

            EXPORT -> {
                val theme = pendingExport ?: return
                pendingExport = null
                message.setText(R.string.theme_writing)
                io.export(uri, theme) { result ->
                    message.text = if (result.isSuccess) "Theme exported." else "Cannot write theme. Choose a writable document and retry."
                }
            }
        }
    }

    private fun save() {
        val theme = draftOrError() ?: return
        val minutes = stale.text.toString().toIntOrNull()
        if (minutes == null || !RefreshPolicy.isValidStaleMinutes(minutes)) {
            stale.error = "Enter ${RefreshPolicy.MIN_STALE_MINUTES}–${RefreshPolicy.MAX_STALE_MINUTES} minutes"
            return
        }
        store.saveSettings(theme, minutes)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (::editor.isInitialized) window.decorView.post { glitchAppElements() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) {
            outState.putString("draft", editor.original.toJson())
            editor.saveState(outState)
            outState.putString("stale", stale.text.toString())
            outState.putString("message", message.text.toString())
            outState.putString("pendingExport", pendingExport?.toJson())
        }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val IMPORT = 41
        private const val EXPORT = 42
    }
}
