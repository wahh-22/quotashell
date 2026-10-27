package dev.wahh.quotashell.core.ui

import dev.wahh.quotashell.core.ClaudeCredentialKind
import dev.wahh.quotashell.core.ManualCredential
import dev.wahh.quotashell.core.PhantomColor
import dev.wahh.quotashell.core.PhantomTheme
import dev.wahh.quotashell.core.RefreshPolicy

enum class FieldState { EMPTY, INVALID, VALID }

data class FieldValidation(
    val state: FieldState,
    val message: String,
) {
    val valid: Boolean get() = state == FieldState.VALID
}

/** Format acceptance belongs to ManualCredential; UI errors never contain submitted values. */
fun credentialField(
    value: String,
    account: String? = null,
    kind: ClaudeCredentialKind = ClaudeCredentialKind.OAUTH_TOKEN,
): FieldValidation {
    if (value.isEmpty()) return FieldValidation(FieldState.EMPTY, "Enter a new credential")
    val valid = runCatching { ManualCredential(value, account?.ifEmpty { null }, kind) }.isSuccess
    return if (valid) {
        FieldValidation(FieldState.VALID, "Ready to save · format only, not verified")
    } else {
        FieldValidation(
            FieldState.INVALID,
            "Use a credential value without whitespace or controls. Web cookies must be a single cookie value.",
        )
    }
}

/** Validate the optional secondary field independently, without inventing account-ID rules. */
fun accountField(value: String): FieldValidation {
    val valid = runCatching { ManualCredential("format-check", value.ifEmpty { null }) }.isSuccess
    return if (valid) {
        FieldValidation(FieldState.VALID, "Optional · leave empty for the default account")
    } else {
        FieldValidation(FieldState.INVALID, "Use an account ID without whitespace or controls")
    }
}

fun hexField(value: String): FieldValidation =
    when {
        value.isEmpty() -> FieldValidation(FieldState.EMPTY, "Enter six hex digits")
        runCatching { PhantomColor.fromHex(value, 100) }.isSuccess -> FieldValidation(FieldState.VALID, "Six hex digits · RRGGBB")
        else -> FieldValidation(FieldState.INVALID, "Use exactly six hex digits (0–9, A–F)")
    }

fun staleField(value: String): FieldValidation {
    val minutes = value.toIntOrNull()
    val message = "${RefreshPolicy.MIN_STALE_MINUTES}–${RefreshPolicy.MAX_STALE_MINUTES} minutes · refresh about every ${RefreshPolicy.INTERVAL_MINUTES} min"
    return FieldValidation(
        when {
            value.isEmpty() -> FieldState.EMPTY
            minutes != null && RefreshPolicy.isValidStaleMinutes(minutes) -> FieldState.VALID
            else -> FieldState.INVALID
        },
        message,
    )
}

/** Null theme represents invalid raw fields; the editor retains those fields for correction. */
class SettingsDraft(
    private val baseline: PhantomTheme,
    private val baselineMinutes: Int,
) {
    var theme: PhantomTheme? = baseline
        private set
    var stale: String = baselineMinutes.toString()
        private set
    val dirty: Boolean get() = theme?.toJson() != baseline.toJson() || stale != baselineMinutes.toString()
    val validToSave: Boolean get() = dirty && theme != null && staleField(stale).valid

    fun update(
        theme: PhantomTheme?,
        stale: String,
    ) {
        this.theme = theme
        this.stale = stale
    }

    fun discard() {
        theme = baseline
        stale = baselineMinutes.toString()
    }
}

fun secretAfterModeChange(
    previous: ClaudeCredentialKind,
    next: ClaudeCredentialKind,
    secret: String,
): String = if (previous == next) secret else ""

fun canClearClipboard(
    pasted: Boolean,
    saved: Boolean,
    unchanged: Boolean,
    sdk: Int,
): Boolean = pasted && saved && unchanged && sdk >= 28
