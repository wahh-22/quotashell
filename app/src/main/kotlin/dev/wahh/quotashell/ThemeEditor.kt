package dev.wahh.quotashell

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import dev.wahh.quotashell.core.ChartStyle
import dev.wahh.quotashell.core.PhantomTheme
import kotlin.math.roundToInt

/** Native-view draft editor; unchanged slots retain their original HSL and fractional alpha. */
internal class ThemeEditor(
    private val activity: Activity,
    val original: PhantomTheme,
    body: LinearLayout,
    state: Bundle?,
) {
    private data class ColorInput(
        val hex: EditText,
        val alpha: EditText,
    )

    private val colors = linkedMapOf<String, ColorInput>()
    private val window: Spinner
    private val button: Spinner
    private val notification: Spinner
    private val animation: Spinner
    private val chart: Spinner
    private val caps: Switch
    private val offset: EditText
    private val initialOffset = original.fontSizeOffset.toString()

    init {
        PhantomTheme.COLOR_SLOTS.forEach { slot ->
            if (slot == "vignette" && runCatching { original.color(slot) }.isFailure) return@forEach
            val color = original.color(slot)
            val hex = "%06X".format(color.argb and 0xFFFFFF)
            val alpha = (color.alpha * 100).roundToInt().toString()
            body.addView(activity.text("$slot · RRGGBB / alpha 0–100%"))
            val hexInput = input(state?.getString("$slot.hex") ?: hex, false).apply { contentDescription = "$slot RGB hex" }
            val alphaInput = input(state?.getString("$slot.alpha") ?: alpha, true).apply { contentDescription = "$slot alpha percent" }
            body.addView(hexInput)
            body.addView(alphaInput)
            colors[slot] = ColorInput(hexInput, alphaInput)
        }
        window = shapes(body, "Window shape", PhantomTheme.WINDOW_SHAPES, state?.getString("windowShape") ?: original.windowShape)
        button = shapes(body, "Button shape", PhantomTheme.BUTTON_SHAPES, state?.getString("buttonShape") ?: original.buttonShape)
        notification =
            shapes(
                body,
                "Notification shape",
                PhantomTheme.NOTIFICATION_SHAPES,
                state?.getString("notificationShape") ?: original.notificationShape,
            )
        animation =
            shapes(
                body,
                "Notification animation (periodic glitch + update burst)",
                PhantomTheme.NOTIFICATION_ANIMATIONS,
                state?.getString("notificationAnimation") ?: original.notificationAnimation,
            )
        chart =
            shapes(
                body,
                "Quota chart style",
                ChartStyle.entries.map { it.name },
                state?.getString("chartStyle") ?: original.chartStyle.name,
            )
        caps =
            Switch(activity).apply {
                setText(R.string.theme_all_caps)
                isChecked = state?.getBoolean("allCaps") ?: original.isAllCaps
            }
        body.addView(caps)
        body.addView(activity.text("Font size offset (−4 to +6 steps)"))
        offset =
            input(state?.getString("offset") ?: initialOffset, true).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        body.addView(offset)
        body.addView(
            activity.text(
                "Unknown shapes are preserved: window → rectangle; button → brackets; alert → inverted triangle. Vignette is export-only.",
                12f,
            ),
        )
    }

    private fun input(
        value: String,
        number: Boolean,
    ) = EditText(activity).apply {
        isSingleLine = true
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setText(value)
    }

    private fun shapes(
        body: LinearLayout,
        label: String,
        known: List<String>,
        current: String,
    ): Spinner {
        body.addView(activity.text(label))
        val values = if (current in known) known else listOf(current) + known
        return Spinner(activity).apply {
            contentDescription = label
            adapter =
                object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, values) {
                    private fun label(position: Int): android.view.View {
                        val active = (activity.application as QuotaApplication).store.phantomTheme()
                        return activity.text(values[position]).apply {
                            typeface = PhantomGraphics.typeface(activity, false)
                            text = active.displayText(values[position])
                            textSize = (16f + active.fontSizeOffset).coerceAtLeast(12f)
                            setTextColor(active.color("text").argb)
                            setBackgroundColor(active.color("window").argb)
                            minHeight = activity.dp(48)
                            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
                        }
                    }

                    override fun getView(
                        position: Int,
                        convertView: android.view.View?,
                        parent: android.view.ViewGroup,
                    ) = label(position)

                    override fun getDropDownView(
                        position: Int,
                        convertView: android.view.View?,
                        parent: android.view.ViewGroup,
                    ) = label(position)
                }
            setSelection(values.indexOf(current))
            body.addView(this)
        }
    }

    fun theme(): PhantomTheme {
        var theme = original
        colors.forEach { (slot, input) ->
            val hex =
                input.hex.text
                    .toString()
                    .trim()
                    .removePrefix("#")
            val alpha =
                input.alpha.text
                    .toString()
                    .toIntOrNull()
            require(hex.matches(Regex("[0-9a-fA-F]{6}"))) { "$slot: use six hex digits" }
            require(alpha != null && alpha in 0..100) { "$slot: alpha must be 0–100" }
            theme = theme.editColor(slot, hex, alpha)
        }
        val step = offset.text.toString().toIntOrNull()
        require(step != null && step in -4..6) { "Font offset must be −4 to +6" }
        // Preserve an imported out-of-render-range value until the user actually edits it.
        val changedOptions =
            window.selectedItem.toString() != original.windowShape ||
                button.selectedItem.toString() != original.buttonShape || notification.selectedItem.toString() != original.notificationShape ||
                caps.isChecked != original.isAllCaps || offset.text.toString() != initialOffset
        if (changedOptions) {
            theme =
                theme.withOptions(
                    window.selectedItem.toString(),
                    button.selectedItem.toString(),
                    notification.selectedItem.toString(),
                    caps.isChecked,
                    if (offset.text.toString() == initialOffset) null else step,
                )
        }
        val style = ChartStyle.valueOf(chart.selectedItem.toString())
        if (style != original.chartStyle) theme = theme.withChartStyle(style)
        return theme.withNotificationAnimation(animation.selectedItem.toString())
    }

    fun saveState(state: Bundle) {
        colors.forEach { (slot, input) ->
            state.putString("$slot.hex", input.hex.text.toString())
            state.putString("$slot.alpha", input.alpha.text.toString())
        }
        state.putString("windowShape", window.selectedItem.toString())
        state.putString("buttonShape", button.selectedItem.toString())
        state.putString("notificationShape", notification.selectedItem.toString())
        state.putString("notificationAnimation", animation.selectedItem.toString())
        state.putString("chartStyle", chart.selectedItem.toString())
        state.putBoolean("allCaps", caps.isChecked)
        state.putString("offset", offset.text.toString())
    }
}
