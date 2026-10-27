package dev.wahh.quotashell

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup

/** Native single-choice semantics, including checked state for TalkBack. */
@SuppressLint("ViewConstructor") // Activity-bound programmatic control, never XML-inflated.
internal class ChoiceControl(
    activity: Activity,
    label: String,
    values: List<String>,
    current: String,
    segmented: Boolean = false,
    preserveUnknown: Boolean = true,
    private val changed: (String) -> Unit,
) : LinearLayout(activity) {
    var selected: String = current
        private set

    init {
        orientation = VERTICAL
        val theme = (activity.application as QuotaApplication).store.phantomTheme()
        addView(
            activity.text(label, 13f).apply {
                setTextColor(theme.color("subtext").argb)
            },
        )
        val options = if (current in values || !preserveUnknown) values else listOf(current) + values
        val useSegments = segmented || options.size in 2..4
        val group =
            RadioGroup(activity).apply {
                orientation = if (useSegments) HORIZONTAL else VERTICAL
                contentDescription = label
                isSaveEnabled = false
            }
        options.forEach { value ->
            val radio =
                (if (useSegments) SegmentButton(activity) else RadioButton(activity)).apply {
                    id = View.generateViewId()
                    text = value
                    contentDescription = "$label: $value"
                    typeface = HudFont.typeface(activity, false)
                    setTextColor(theme.color("text").argb)
                    textSize = (16f + theme.fontSizeOffset).coerceAtLeast(12f)
                    buttonTintList =
                        android.content.res.ColorStateList
                            .valueOf(theme.color("accent").argb)
                    minHeight = activity.dp(48)
                    minimumHeight = activity.dp(48)
                    isSaveEnabled = false
                    setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                }
            val params = if (useSegments) RadioGroup.LayoutParams(0, -2, 1f) else RadioGroup.LayoutParams(-1, -2)
            params.setMargins(0, activity.dp(2), if (useSegments) activity.dp(2) else 0, activity.dp(2))
            group.addView(radio, params)
            radio.setOnCheckedChangeListener { _, checked ->
                radio.isSelected = checked
                (radio as? SegmentButton)?.applyTheme((activity.application as QuotaApplication).store.phantomTheme())
                if (checked && selected != value) {
                    selected = value
                    changed(value)
                }
            }
            radio.isChecked = value == current
            (radio as? SegmentButton)?.applyTheme(theme)
        }
        addView(group)
    }
}

/** A filled segment with native radio semantics, not a tiny radio glyph hit target. */
@SuppressLint("ViewConstructor") // Activity-bound, programmatic only.
internal class SegmentButton(private val activity: Activity) : RadioButton(activity) {
    init {
        buttonDrawable = null
        gravity = android.view.Gravity.CENTER
        minimumHeight = activity.dp(48)
        minHeight = activity.dp(48)
        isSingleLine = false
        setPadding(activity.dp(8), activity.dp(10), activity.dp(8), activity.dp(10))
    }

    fun applyTheme(theme: dev.wahh.quotashell.core.PhantomTheme) {
        isSelected = isChecked
        backgroundTintList = null
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(theme.color(if (isChecked) "accent" else "window").argb)
            setStroke(activity.dp(2), theme.color("accent").argb)
            cornerRadius = activity.dp(4).toFloat()
        }
        // Background alpha describes surfaces, not the legibility of selected-segment text.
        setTextColor(if (isChecked) theme.color("background").argb or (0xFF shl 24) else theme.color("text").argb)
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = RadioButton::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
        info.isSelected = isChecked
    }
}

internal fun Activity.disclosure(
    label: String,
    content: View,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        content.visibility = View.GONE
        addView(
            button("+ $label") {
                content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                val control = getChildAt(0) as android.widget.Button
                control.text = getString(R.string.input_disclosure, if (content.visibility == View.VISIBLE) "−" else "+", label)
                control.contentDescription = "$label, ${if (content.visibility == View.VISIBLE) "expanded" else "collapsed"}"
            }.apply { contentDescription = "$label, collapsed" },
        )
        addView(content)
    }
