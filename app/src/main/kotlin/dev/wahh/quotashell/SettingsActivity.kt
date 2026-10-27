package dev.wahh.quotashell

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.board
import dev.wahh.quotashell.core.ui.SettingsDraft
import dev.wahh.quotashell.core.ui.staleField
import java.time.Instant
import java.time.ZoneId

/** Launcher configuration and draft editor. Only Save crosses the persistence boundary. */
class SettingsActivity : Activity() {
    private val store get() = (application as QuotaApplication).store
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var editor: ThemeEditor
    private lateinit var stale: ThemedInput
    private lateinit var message: TextView
    private lateinit var dirtyLabel: TextView
    private lateinit var saveButton: Button
    private lateinit var preview: ImageView
    private lateinit var draft: SettingsDraft
    private var baseline: PhantomTheme = PhantomTheme.default()
    private var baselineMinutes = 60
    private var pendingExport: PhantomTheme? = null
    private var rendering = false
    private val refreshPreview = Runnable { updatePreview() }
    private val onSnapshotsChanged: () -> Unit = { schedulePreview() }

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
        baseline = savedInstanceState?.getString("baseline")?.let { PhantomTheme.parse(it) } ?: store.phantomTheme()
        baselineMinutes = savedInstanceState?.getInt("baselineMinutes") ?: store.staleMinutes()
        draft = SettingsDraft(baseline, baselineMinutes)
        val theme = savedInstanceState?.getString("draft")?.let { runCatching { PhantomTheme.parse(it) }.getOrNull() } ?: baseline
        pendingExport = savedInstanceState?.getString("pendingExport")?.let { runCatching { PhantomTheme.parse(it) }.getOrNull() }
        render(theme, savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                confirmDiscard()
            }
        }
    }

    private fun render(
        theme: PhantomTheme,
        state: Bundle? = null,
        notice: String = "",
    ) {
        rendering = true
        if (::preview.isInitialized) preview.removeCallbacks(refreshPreview)
        val body = panel(code = "CFG-00")
        body.addView(hudHeader("CONFIG"))
        body.addView(text("◢◤◢◤ // APPEARANCE MATRIX", 18f))
        body.addView(text("Preview · unsaved theme on current snapshots", 13f))
        preview =
            ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Live widget preview"
            }
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(180)))
        message = text(state?.getString("message") ?: notice).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(message)
        body.addView(text("// THEME FILE", 19f))
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
                val valid = draftOrError() ?: return@button
                pendingExport = valid
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
        body.addView(button("Reset to default") { replaceDraft(PhantomTheme.default(), "Default blue theme loaded. Save to apply.") })
        body.addView(text("Import/reset edits only the draft. Export writes the valid draft without saving. JSON limit: 64 KiB.", 12f))
        editor = ThemeEditor(this, theme, body, state) { updateDraft() }
        stale = ThemedInput(this, "Stale threshold (minutes)", "60", staleField("").message, type = InputType.TYPE_CLASS_NUMBER)
        stale.edit.setText(state?.getString("stale") ?: draft.stale)
        stale.onChanged = { updateDraft() }
        body.addView(stale)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll =
            ScrollView(this).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                clipToPadding = false
                addView(body)
            }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom =
            column().apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
        dirtyLabel =
            text("", 12f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        bottom.addView(dirtyLabel)
        val actions = LinearLayout(this)
        saveButton = button("Save") { save() }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Discard") { confirmDiscard() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(actions)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            bottom.addView(button("Add widget with this theme") { save(configure = true) })
        }
        root.addView(bottom)
        terminalContent(root, openingGlitch = false)
        rendering = false
        updateDraft()
    }

    private fun replaceDraft(
        theme: PhantomTheme,
        notice: String,
    ) {
        val minutes = stale.edit.text.toString()
        render(
            theme,
            Bundle().apply {
                putString("stale", minutes)
                putString("message", notice)
            },
        )
    }

    private fun updateDraft() {
        if (rendering || !::editor.isInitialized || !::saveButton.isInitialized) return
        val theme = runCatching { editor.theme() }.getOrNull()
        val validation = staleField(stale.edit.text.toString())
        stale.validate(validation)
        draft.update(theme, stale.edit.text.toString())
        saveButton.isEnabled = draft.validToSave
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.45f
        dirtyLabel.text =
            when {
                !draft.dirty -> "No unsaved changes"
                !draft.validToSave -> "Unsaved changes · correct the highlighted fields"
                else -> "Unsaved changes · preview only"
            }
        schedulePreview()
    }

    private fun schedulePreview() {
        if (!::preview.isInitialized) return
        preview.removeCallbacks(refreshPreview)
        preview.postDelayed(refreshPreview, 180)
    }

    private fun updatePreview() {
        val theme = draft.theme ?: return
        val minutes = draft.stale.toIntOrNull()?.takeIf { staleField(draft.stale).valid } ?: baselineMinutes
        val board = board(store.snapshots(), Instant.now(), minutes * 60L, ZoneId.systemDefault(), theme.isAllCaps)
        val width = (preview.width / resources.displayMetrics.density).coerceAtLeast(180f)
        preview.setImageBitmap(PhantomBoardRenderer.render(this, board, theme, width, 180f))
        preview.contentDescription = "Widget preview · " + board.cards.joinToString("; ") { "${it.name}: ${it.status}" }
    }

    private fun draftOrError(): PhantomTheme? =
        try {
            editor.theme()
        } catch (error: IllegalArgumentException) {
            message.text = error.message
            null
        }

    @Suppress("DEPRECATION")
    private fun launchDocument(
        intent: Intent,
        request: Int,
    ) {
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
                        onSuccess = { replaceDraft(it, "Theme imported. Save to apply.") },
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
                    message.text =
                        if (result.isSuccess) "Theme exported." else "Cannot write theme. Choose a writable document and retry."
                }
            }
        }
    }

    private fun save(configure: Boolean = false) {
        updateDraft()
        val theme = draftOrError() ?: return
        if (!staleField(draft.stale).valid || (!draft.validToSave && !configure)) return
        try {
            store.saveSettings(theme, draft.stale.toInt())
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            }
            finish()
        } catch (_: Exception) {
            message.setText(R.string.theme_save_failed)
        }
    }

    private fun confirmDiscard() {
        updateDraft()
        if (!draft.dirty) {
            finish()
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle("Discard unsaved changes?")
            .setMessage("Your saved theme and stale threshold will not change.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard") { _, _ ->
                draft.discard()
                finish()
            }.show()
    }

    @Deprecated("Native Activity back bridge")
    override fun onBackPressed() {
        confirmDiscard()
    }

    override fun onResume() {
        super.onResume()
        store.listen(onSnapshotsChanged)
        schedulePreview()
    }

    override fun onPause() {
        store.unlisten(onSnapshotsChanged)
        if (::preview.isInitialized) preview.removeCallbacks(refreshPreview)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) {
            outState.putString("baseline", baseline.toJson())
            outState.putInt("baselineMinutes", baselineMinutes)
            outState.putString("draft", editor.original.toJson())
            editor.saveState(outState)
            outState.putString("stale", stale.edit.text.toString())
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
