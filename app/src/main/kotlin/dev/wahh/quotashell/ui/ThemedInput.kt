package dev.wahh.quotashell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.ui.ClipboardProvenance
import dev.wahh.quotashell.core.ui.FieldState
import dev.wahh.quotashell.core.ui.FieldValidation

/** Native editing with persistent labels; no input state or clipboard contents are saved. */
@SuppressLint("ViewConstructor") // Activity-bound programmatic control, never XML-inflated.
internal class ThemedInput(
    private val activity: Activity,
    label: String,
    placeholder: String,
    private val helper: String,
    private val secret: Boolean = false,
    type: Int = InputType.TYPE_CLASS_TEXT,
    maxLength: Int? = null,
    paste: Boolean = false,
    showLabel: Boolean = true,
) : LinearLayout(activity) {
    private val caption = activity.text(label, 13f)
    val edit = EditText(activity)
    private val support = activity.text(helper, 12f)
    private var validation: FieldValidation? = null
    private var activeTheme = (activity.application as QuotaApplication).store.phantomTheme()
    val pasteProvenance = ClipboardProvenance()
    var onChanged: () -> Unit = {}
    private var revealed = false
    private val reveal = if (secret) activity.button("Show") { toggleSecret() } else null

    init {
        orientation = VERTICAL
        val spacing = if (showLabel) activity.dp(8) else 0
        setPadding(0, spacing, 0, spacing)
        caption.visibility = if (showLabel) View.VISIBLE else View.GONE
        addView(caption)
        edit.apply {
            id = View.generateViewId()
            hint = placeholder
            contentDescription = label
            typeface = HudFont.typeface(activity, false)
            inputType =
                if (secret) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    type
                }
            isSingleLine = true
            minHeight = activity.dp(48)
            minimumHeight = activity.dp(48)
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (maxLength != null) filters = arrayOf(InputFilter.LengthFilter(maxLength))
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            setOnFocusChangeListener { _, _ -> applyTheme(activeTheme) }
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        pasteProvenance.edited()
                        onChanged()
                    }

                    override fun afterTextChanged(s: Editable?) = Unit
                },
            )
        }
        caption.labelFor = edit.id
        addView(edit, LayoutParams(-1, -2))
        if (secret || paste) {
            val actions = LinearLayout(activity)
            reveal?.let {
                it.contentDescription = "Show $label"
                actions.addView(it, LayoutParams(0, -2, 1f))
            }
            if (paste) {
                actions.addView(
                    activity
                        .button("Paste") {
                            val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
                            // Text only; do not coerce a URI or launch a content provider.
                            val value =
                                clip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.text
                                    ?.toString()
                            if (value != null) {
                                edit.setText(value)
                                edit.setSelection(edit.length())
                                pasteProvenance.pasted(value)
                            } else {
                                support.setText(R.string.input_clipboard_empty)
                            }
                        }.apply { contentDescription = "Paste $label from clipboard" },
                    LayoutParams(0, -2, 1f),
                )
            }
            addView(actions)
        }
        support.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(support)
        applyTheme(activeTheme)
    }

    fun validate(result: FieldValidation?) {
        validation = result
        support.text = if (result?.valid == false) result.message else helper
        applyTheme(activeTheme)
    }

    fun hideSecret() {
        if (!secret) return
        revealed = false
        edit.transformationMethod = PasswordTransformationMethod.getInstance()
        reveal?.setText(R.string.input_show)
        reveal?.contentDescription = "Show ${edit.contentDescription}"
    }

    private fun toggleSecret() {
        revealed = !revealed
        edit.transformationMethod = if (revealed) null else PasswordTransformationMethod.getInstance()
        edit.setSelection(edit.length())
        reveal?.setText(if (revealed) R.string.input_hide else R.string.input_show)
        reveal?.contentDescription = "${if (revealed) "Hide" else "Show"} ${edit.contentDescription}"
    }

    fun applyTheme(theme: PhantomTheme) {
        activeTheme = theme
        val error = validation?.state == FieldState.INVALID
        val role =
            if (error) {
                "notifications"
            } else if (edit.isFocused) {
                "accent"
            } else {
                "outline"
            }
        caption.setTextColor(
            theme
                .color(
                    if (error) {
                        "notifications"
                    } else if (edit.isFocused) {
                        "accent"
                    } else {
                        "text"
                    },
                ).argb,
        )
        support.setTextColor(theme.color(if (error) "notifications" else "subtext").argb)
        edit.setTextColor(theme.color("text").argb)
        edit.setHintTextColor(theme.color("subtext").argb)
        edit.backgroundTintList = null
        edit.background =
            GradientDrawable().apply {
                setColor(theme.color("window").argb)
                cornerRadius = activity.dp(4).toFloat()
                setStroke(activity.dp(if (edit.isFocused || error) 3 else 2), theme.color(role).argb)
            }
        edit.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
    }
}
