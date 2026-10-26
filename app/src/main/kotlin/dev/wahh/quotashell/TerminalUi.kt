package dev.wahh.quotashell

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

internal fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Activity.column() =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

internal fun Activity.text(
    value: String,
    size: Float = 14f,
    color: Int = 0xFFE2EAF2.toInt(),
) = TextView(this).apply {
    text = value
    textSize = size
    typeface = Typeface.MONOSPACE
    setTextColor(color)
    setPadding(0, dp(5), 0, dp(5))
}

internal fun Activity.button(
    label: String,
    action: () -> Unit,
) = Button(this).apply {
    text = label
    typeface = Typeface.MONOSPACE
    isAllCaps = false
    minHeight = dp(48)
    setOnClickListener { action() }
}

internal fun Activity.panel(
    accent: Int,
    background: Int,
) = column().apply {
    this.background =
        GradientDrawable().apply {
            setColor(background)
            setStroke(dp(1), accent)
        }
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
}

internal fun Activity.bar(
    percent: Int,
    accent: Int,
) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 100
    progress = percent
    progressTintList = ColorStateList.valueOf(accent)
    progressBackgroundTintList = ColorStateList.valueOf(0xFF506070.toInt())
    layoutParams = LinearLayout.LayoutParams(-1, dp(12))
    contentDescription = "$percent percent used"
}

internal fun Activity.terminalContent(view: View) {
    val frame =
        FrameLayout(this).apply {
            setBackgroundColor(0xFF0A1018.toInt())
            addView(view)
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
        }
    setContentView(frame)
    frame.requestApplyInsets()
}
