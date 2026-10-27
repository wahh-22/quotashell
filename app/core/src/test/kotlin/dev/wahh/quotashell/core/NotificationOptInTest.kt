package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test

class NotificationOptInTest {
    @Test fun expiryAlertsAreOnUntilTheUserTurnsThemOff() {
        // No explicit choice yet (fresh install, or a legacy default-off install): alerts are on.
        assertTrue(NotificationOptIn.enabled(choice = null))
        assertTrue(NotificationOptIn.enabled(choice = true))
        assertFalse(NotificationOptIn.enabled(choice = false))
    }

    @Test fun permissionIsRequestedOnceAndOnlyWhenItCanHelp() {
        assertTrue(NotificationOptIn.shouldRequestPermission(enabled = true, granted = false, alreadyAsked = false, sdk = 33))
        assertFalse(NotificationOptIn.shouldRequestPermission(enabled = true, granted = false, alreadyAsked = true, sdk = 36))
        assertFalse(NotificationOptIn.shouldRequestPermission(enabled = true, granted = true, alreadyAsked = false, sdk = 36))
        assertFalse(NotificationOptIn.shouldRequestPermission(enabled = false, granted = false, alreadyAsked = false, sdk = 36))
        // Below Android 13 notifications need no runtime permission.
        assertFalse(NotificationOptIn.shouldRequestPermission(enabled = true, granted = false, alreadyAsked = false, sdk = 32))
    }
}
