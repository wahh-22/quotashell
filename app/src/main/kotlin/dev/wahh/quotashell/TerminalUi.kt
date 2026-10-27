package dev.wahh.quotashell

import android.app.Activity
import android.view.View
import android.widget.*

internal fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Activity.column() =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

internal data class TextStyle(
    val size: Float,
    val slot: String,
)

internal data class PanelStyle(
    val code: String,
)

internal fun Activity.text(
    value: String,
    size: Float = 16f,
    color: Int = 0,
) = TextView(this).apply {
    text = value
    textSize = size
    typeface = PhantomGraphics.typeface(this@text, size >= 18f)
    tag =
        TextStyle(
            size,
            if (size >= 18f || color != 0) {
                "accent"
            } else if (size <= 13f) {
                "subtext"
            } else {
                "text"
            },
        )
    setPadding(0, dp(7), 0, dp(7))
}

internal fun Activity.button(
    label: String,
    action: () -> Unit,
) = Button(this).apply {
    text = label
    typeface = PhantomGraphics.typeface(this@button, true)
    isAllCaps = false
    minHeight = dp(48)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    layoutParams =
        android.widget.LinearLayout
            .LayoutParams(-1, -2)
            .apply { bottomMargin = dp(8) }
    setOnClickListener { action() }
}

@Suppress("UNUSED_PARAMETER") // Retain callers while all appearance comes from the active PhantomTheme.
internal fun Activity.panel(
    accent: Int = 0,
    background: Int = 0,
    code: String = "SYS-00",
) = column().apply {
    tag = PanelStyle(code)
    setPadding(dp(18), dp(30), dp(18), dp(18))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
}

internal fun Activity.terminalContent(
    view: View,
    openingGlitch: Boolean = true,
) {
    // Keep the gutter outside panel backgrounds, inside the scrollable viewport.
    if (view is ScrollView) {
        view.setPadding(dp(16), dp(16), dp(16), dp(16))
        view.clipToPadding = false
        view.getChildAt(0)?.let { child ->
            if (child.tag !is PanelStyle) child.setPadding(0, 0, 0, 0)
        }
    }
    val frame =
        FrameLayout(this).apply {
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
    installPhantomShell(frame, openingGlitch)
    frame.requestApplyInsets()
}
