package dev.wahh.quotashell.core

/**
 * Credential-expiry alerts are on unless the user explicitly turned them off. A missing choice
 * covers fresh installs and installs from the earlier default-off builds alike.
 */
object NotificationOptIn {
    fun enabled(choice: Boolean?): Boolean = choice ?: true

    /** Ask for the Android 13+ runtime permission once, and only when alerts are on and blocked by it. */
    fun shouldRequestPermission(
        enabled: Boolean,
        granted: Boolean,
        alreadyAsked: Boolean,
        sdk: Int,
    ): Boolean = enabled && !granted && !alreadyAsked && sdk >= 33
}
