package dev.wahh.quotashell

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import dev.wahh.quotashell.core.RefreshPolicy
import dev.wahh.quotashell.core.TerminalTheme

/** Also the launcher configuration screen for the all-provider widget. */
class SettingsActivity : Activity() {
    private val store get() = (application as QuotaApplication).store
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var accent: EditText
    private lateinit var opacity: SeekBar
    private lateinit var stale: EditText

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
        val theme = store.theme()
        val body = column()
        body.addView(text("> Appearance", 24f, theme.accentArgb))
        body.addView(text("Appearance and stale threshold apply to all widgets and the dashboard.", 12f))
        body.addView(text("Accent · six hex digits (PhantomShell blue ${TerminalTheme.DEFAULT_ACCENT_HEX})"))
        accent =
            EditText(this).apply {
                isSingleLine = true
                setText(savedInstanceState?.getString("accent") ?: "%06X".format(theme.accentArgb and 0xFFFFFF))
            }
        body.addView(accent)
        val opacityLabel = text("")
        body.addView(opacityLabel)
        opacity =
            SeekBar(this).apply {
                max = 100
                progress = savedInstanceState?.getInt("opacity") ?: theme.opacityPercent.coerceIn(0, 100)
                opacityLabel.text = getString(R.string.background_opacity, progress)
                contentDescription = "Background opacity percent"
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: SeekBar?,
                            value: Int,
                            fromUser: Boolean,
                        ) {
                            opacityLabel.text = getString(R.string.background_opacity, value)
                        }

                        override fun onStartTrackingTouch(bar: SeekBar?) {}

                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    },
                )
            }
        body.addView(opacity)
        body.addView(
            text(
                "Mark snapshots stale after minutes (${RefreshPolicy.MIN_STALE_MINUTES}–${RefreshPolicy.MAX_STALE_MINUTES}). " +
                    "Background refresh runs about every ${RefreshPolicy.INTERVAL_MINUTES} min.",
            ),
        )
        stale =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(savedInstanceState?.getString("stale") ?: store.staleMinutes().toString())
            }
        body.addView(stale)
        body.addView(
            text(
                "Transparency affects only panel backgrounds. Use a contrasting accent for your wallpaper. Launcher updates may be delayed by Android battery policy.",
                12f,
            ),
        )
        body.addView(button("Save") { save() })
        body.addView(button("Cancel") { finish() })
        terminalContent(ScrollView(this).apply { addView(body) })
    }

    private fun save() {
        val hex =
            accent.text
                .toString()
                .trim()
                .removePrefix("#")
        if (!hex.matches(Regex("[0-9a-fA-F]{6}"))) {
            accent.error = "Use six hex digits, e.g. ${TerminalTheme.DEFAULT_ACCENT_HEX}"
            return
        }
        val minutes = stale.text.toString().toIntOrNull()
        if (minutes == null || !RefreshPolicy.isValidStaleMinutes(minutes)) {
            stale.error = "Enter ${RefreshPolicy.MIN_STALE_MINUTES}–${RefreshPolicy.MAX_STALE_MINUTES} minutes"
            return
        }
        store.saveSettings(TerminalTheme(hex, opacity.progress), minutes)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            QuotaWidget.updateAll(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::accent.isInitialized) {
            outState.putString("accent", accent.text.toString())
            outState.putInt("opacity", opacity.progress)
            outState.putString("stale", stale.text.toString())
        }
        super.onSaveInstanceState(outState)
    }
}
