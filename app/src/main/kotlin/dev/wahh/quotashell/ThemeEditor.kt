package dev.wahh.quotashell

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import dev.wahh.quotashell.core.ChartStyle
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.ui.hexField
import kotlin.math.roundToInt

/** Draft-only controls. Unchanged fields retain original HSL, fractional alpha and unknown options. */
internal class ThemeEditor(
    private val activity: Activity,
    val original: PhantomTheme,
    body: LinearLayout,
    state: Bundle?,
    private val changed: () -> Unit,
) {
    private data class ColorInput(
        val hex: ThemedInput,
        val alpha: SeekBar,
    )

    private val colors = linkedMapOf<String, ColorInput>()
    private val window: ChoiceControl
    private val button: ChoiceControl
    private val notification: ChoiceControl
    private val animation: ChoiceControl
    private val chart: ChoiceControl
    private val caps: Switch
    private var offset = state?.takeIf { it.containsKey("offset") }?.getInt("offset") ?: original.fontSizeOffset

    init {
        body.addView(activity.text("// COLORS", 19f))
        PhantomTheme.COLOR_SLOTS.forEach { slot ->
            if (slot == "vignette" && runCatching { original.color(slot) }.isFailure) return@forEach
            val color = original.color(slot)
            val hex = "%06X".format(color.argb and 0xFFFFFF)
            val active = (activity.application as QuotaApplication).store.phantomTheme()
            val row =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(8) }
                    background =
                        GradientDrawable().apply {
                            setColor(active.color("window").argb)
                            setStroke(activity.dp(1), active.color("outline").argb)
                            cornerRadius = activity.dp(4).toFloat()
                        }
                }
            val header =
                activity.text(slot.replaceFirstChar { it.uppercase() }, 13f).apply {
                    typeface = HudFont.typeface(activity, true)
                }
            row.addView(header)
            val inputLine =
                LinearLayout(activity).apply {
                    gravity = android.view.Gravity.TOP
                    setPadding(0, activity.dp(4), 0, 0)
                }
            val swatch = View(activity).apply { contentDescription = "$slot color preview" }
            inputLine.addView(
                swatch,
                LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                    marginEnd = activity.dp(12)
                },
            )
            val field =
                ThemedInput(
                    activity,
                    "$slot hex color",
                    "RRGGBB",
                    "Six hex digits · no # prefix",
                    type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    maxLength = 6,
                    showLabel = false,
                )
            header.labelFor = field.edit.id
            field.edit.setText(state?.getString("$slot.hex") ?: hex)
            inputLine.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(inputLine)
            val opacityLine = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val alphaLabel = activity.text("", 12f)
            opacityLine.addView(alphaLabel)
            val alpha =
                SeekBar(activity).apply {
                    max = 100
                    progress = state?.takeIf { it.containsKey("$slot.alpha") }?.getInt("$slot.alpha") ?: (color.alpha * 100).roundToInt()
                    minimumHeight = activity.dp(48)
                    contentDescription = "$slot opacity percent"
                }
            opacityLine.addView(alpha, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(opacityLine)

            fun update() {
                val result = hexField(field.edit.text.toString())
                field.validate(result)
                alphaLabel.text = activity.getString(R.string.theme_opacity, alpha.progress)
                if (result.valid) {
                    val edited = original.editColor(slot, field.edit.text.toString(), alpha.progress).color(slot)
                    swatch.background =
                        GradientDrawable().apply {
                            setColor(edited.argb)
                            setStroke(
                                activity.dp(2),
                                (activity.application as QuotaApplication)
                                    .store
                                    .phantomTheme()
                                    .color("outline")
                                    .argb,
                            )
                        }
                    swatch.contentDescription = "$slot color #${field.edit.text}, opacity ${alpha.progress}%"
                }
            }
            field.onChanged = {
                update()
                changed()
            }
            alpha.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        update()
                        if (fromUser) changed()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                },
            )
            colors[slot] = ColorInput(field, alpha)
            update()
            body.addView(row)
        }
        body.addView(activity.text("// SHAPES", 19f))
        window = shapes(body, "Window shape", PhantomTheme.WINDOW_SHAPES, state?.getString("windowShape") ?: original.windowShape)
        button = shapes(body, "Button shape", PhantomTheme.BUTTON_SHAPES, state?.getString("buttonShape") ?: original.buttonShape)
        notification =
            shapes(
                body,
                "Notification shape",
                PhantomTheme.NOTIFICATION_SHAPES,
                state?.getString("notificationShape") ?: original.notificationShape,
            )
        chart =
            shapes(
                body,
                "Quota chart style",
                ChartStyle.entries.map { it.name },
                state?.getString("chartStyle") ?: original.chartStyle.name,
            )
        body.addView(activity.text("// TEXT", 19f))
        caps =
            Switch(activity).apply {
                setText(R.string.theme_all_caps)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                minimumHeight = activity.dp(48)
                minHeight = activity.dp(48)
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                contentDescription = "All-caps widget labels"
                isChecked = state?.takeIf { it.containsKey("allCaps") }?.getBoolean("allCaps") ?: original.isAllCaps
                setOnCheckedChangeListener { _, _ -> changed() }
            }
        body.addView(caps)
        body.addView(activity.text("Font size offset · −4 to +6"))
        val stepper = LinearLayout(activity)
        val value =
            activity.text(offset.toString()).apply {
                gravity = android.view.Gravity.CENTER
                minHeight = activity.dp(48)
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        lateinit var minus: android.widget.Button
        lateinit var plus: android.widget.Button

        fun updateStep() {
            value.text = activity.getString(R.string.theme_offset, offset)
            value.contentDescription = "Font size offset $offset"
            minus.isEnabled = offset > -4
            plus.isEnabled = offset < 6
            minus.alpha = if (minus.isEnabled) 1f else 0.45f
            plus.alpha = if (plus.isEnabled) 1f else 0.45f
        }
        minus =
            activity
                .button("−") {
                    offset--
                    updateStep()
                    changed()
                }.apply { contentDescription = "Decrease font size" }
        plus =
            activity
                .button("+") {
                    offset++
                    updateStep()
                    changed()
                }.apply { contentDescription = "Increase font size" }
        stepper.addView(minus, LinearLayout.LayoutParams(0, -2, 1f))
        stepper.addView(value, LinearLayout.LayoutParams(0, -2, 1f))
        stepper.addView(plus, LinearLayout.LayoutParams(0, -2, 1f))
        updateStep()
        body.addView(stepper)
        body.addView(activity.text("// BEHAVIOR", 19f))
        animation =
            shapes(
                body,
                "Notification animation",
                PhantomTheme.NOTIFICATION_ANIMATIONS,
                state?.getString("notificationAnimation") ?: original.notificationAnimation,
            )
        body.addView(
            activity.text(
                "NONE disables periodic glitch and decode reveal. Unknown options survive unchanged export. Vignette is export-only.",
                12f,
            ),
        )
    }

    private fun shapes(
        body: LinearLayout,
        label: String,
        known: List<String>,
        current: String,
    ): ChoiceControl = ChoiceControl(activity, label, known, current) { changed() }.also { body.addView(it) }

    fun theme(): PhantomTheme {
        var theme = original
        colors.forEach { (slot, input) ->
            val hex =
                input.hex.edit.text
                    .toString()
            val validation = hexField(hex)
            input.hex.validate(validation)
            require(validation.valid) { "$slot: ${validation.message}" }
            theme = theme.editColor(slot, hex, input.alpha.progress)
        }
        if (window.selected != original.windowShape || button.selected != original.buttonShape ||
            notification.selected != original.notificationShape || caps.isChecked != original.isAllCaps || offset != original.fontSizeOffset
        ) {
            theme =
                theme.withOptions(
                    window.selected,
                    button.selected,
                    notification.selected,
                    caps.isChecked,
                    if (offset == original.fontSizeOffset) null else offset,
                )
        }
        val style = ChartStyle.valueOf(chart.selected)
        if (style != original.chartStyle) theme = theme.withChartStyle(style)
        return if (animation.selected == original.notificationAnimation) theme else theme.withNotificationAnimation(animation.selected)
    }

    fun saveState(state: Bundle) {
        colors.forEach { (slot, input) ->
            state.putString(
                "$slot.hex",
                input.hex.edit.text
                    .toString(),
            )
            state.putInt("$slot.alpha", input.alpha.progress)
        }
        state.putString("windowShape", window.selected)
        state.putString("buttonShape", button.selected)
        state.putString("notificationShape", notification.selected)
        state.putString("notificationAnimation", animation.selected)
        state.putString("chartStyle", chart.selected)
        state.putBoolean("allCaps", caps.isChecked)
        state.putInt("offset", offset)
    }
}
