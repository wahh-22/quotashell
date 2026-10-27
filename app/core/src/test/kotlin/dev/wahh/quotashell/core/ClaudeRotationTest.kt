package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test

class ClaudeRotationTest {
    private val provider = ProviderId.CLAUDE

    private fun cookie(value: String) = ManualCredential(value, claudeKind = ClaudeCredentialKind.SESSION_KEY)

    @Test fun normalRotationPersistsWithoutClearingSnapshotOrNotificationAndFencesDuplicate() {
        val versions = ConnectionVersions()
        var stored: ManualCredential? = cookie("synthetic-old")
        val mutations =
            CredentialMutations(versions, { _, value -> stored = value }, {
                fail("Rotation cleared snapshot")
            }, { fail("Rotation cleared notification") })
        val captured = versions.capture()
        assertTrue(mutations.rotateClaudeSession(captured, cookie("synthetic-new")))
        assertEquals("synthetic-new", stored!!.token)
        assertFalse(mutations.rotateClaudeSession(captured, cookie("synthetic-stale")))
        assertEquals("synthetic-new", stored!!.token)
    }

    @Test fun rotationCannotOverwriteReplacement() = staleRotation(cookie("synthetic-replacement"))

    @Test fun rotationCannotRecreateRemovedCredential() = staleRotation(null)

    private fun staleRotation(replacement: ManualCredential?) {
        val versions = ConnectionVersions()
        var stored: ManualCredential? = cookie("synthetic-old")
        var writes = 0
        val mutations =
            CredentialMutations(versions, { _, value ->
                stored = value
                writes++
            }, {}, {})
        val captured = versions.capture()
        mutations.replace(provider, replacement)
        assertFalse(mutations.rotateClaudeSession(captured, cookie("synthetic-late")))
        assertSame(replacement, stored)
        assertEquals(1, writes)
    }

    @Test fun staleGenerationCannotRotateEvenWhenCredentialWasReentered() {
        val versions = ConnectionVersions()
        val original = cookie("synthetic-original")
        var stored: ManualCredential? = original
        val mutations = CredentialMutations(versions, { _, value -> stored = value }, {}, {})
        val captured = versions.capture()
        mutations.replace(provider, null)
        mutations.replace(provider, original)
        assertFalse(mutations.rotateClaudeSession(captured, cookie("synthetic-stale")))
        assertSame(original, stored)
    }

    @Test fun failedRotationQuarantinesAndRejectsPublicationAndFurtherRotation() {
        val versions = ConnectionVersions()
        var snapshot = ProviderSnapshot(provider)
        var cleared = false
        var writes = 0
        val mutations =
            CredentialMutations(versions, { _, _ ->
                writes++
                error("Synthetic commit failure")
            }, { snapshot = it }, {
                cleared =
                    true
            })
        val captured = versions.capture()
        assertThrows(IllegalStateException::class.java) { mutations.rotateClaudeSession(captured, cookie("synthetic-new")) }
        assertTrue(mutations.isBlocked(provider))
        assertFalse(mutations.accepts(provider, captured))
        assertEquals(Failure.STORAGE, snapshot.failure)
        assertTrue(cleared)
        assertFalse(mutations.rotateClaudeSession(versions.capture(), cookie("synthetic-late")))
        assertEquals(1, writes)
    }
}
