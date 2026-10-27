package dev.wahh.quotashell.core.ui

import java.security.MessageDigest

/** Ephemeral provenance of the exact pasted input. Never retains plaintext or serializes state. */
class ClipboardProvenance {
    private var pastedFingerprint: ByteArray? = null

    fun pasted(value: String) {
        pastedFingerprint = fingerprint(value)
    }

    fun edited() {
        pastedFingerprint = null
    }

    /** Call only after successful persistence, using the exact submitted field value. */
    fun saved(value: String): SavedPaste? {
        val pasted = pastedFingerprint ?: return null
        return if (MessageDigest.isEqual(pasted, fingerprint(value))) SavedPaste(pasted.copyOf()) else null
    }

    override fun toString() = "ClipboardProvenance([redacted])"
}

/** A successful-save receipt is independent of the now-cleared input draft. Memory only. */
class SavedPaste internal constructor(
    private val pastedFingerprint: ByteArray,
) {
    fun canClear(
        currentClipboard: String?,
        sdk: Int,
    ): Boolean =
        canClearClipboard(
            pasted = true,
            saved = true,
            unchanged = currentClipboard != null && MessageDigest.isEqual(pastedFingerprint, fingerprint(currentClipboard)),
            sdk = sdk,
        )

    override fun toString() = "SavedPaste([redacted])"
}

private fun fingerprint(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
