package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CredentialMutationsTest {
    private val provider = ProviderId.CLAUDE
    private val oldCredential = ManualCredential("synthetic-old")
    private val replacement = ManualCredential("synthetic-new")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    /** Mirrors commitToMemory followed by a failed disk result, not an Android runtime test. */
    private class CommitFake(
        initial: ManualCredential,
    ) {
        var memory: ManualCredential? = initial
        val disk: ManualCredential = initial

        fun commit(value: ManualCredential?): Boolean {
            memory = value
            return false
        }
    }

    @Test fun failedReplacementRejectsOld200() = failedCommit(replacement, 200)

    @Test fun failedReplacementRejectsOld401() = failedCommit(replacement, 401)

    @Test fun failedRemovalRejectsOld200() = failedCommit(null, 200)

    @Test fun failedRemovalRejectsOld401() = failedCommit(null, 401)

    private fun failedCommit(
        value: ManualCredential?,
        status: Int,
    ) {
        val versions = ConnectionVersions()
        val captured = versions.capture()
        val fake = CommitFake(oldCredential)
        var snapshot = ProviderSnapshot(provider, Connection.CONNECTED, now, listOf(QuotaWindow(WindowKind.WEEK, 80.0)), 3)
        var notified = true
        val mutations =
            CredentialMutations(
                versions,
                { _, credential -> check(fake.commit(credential)) },
                { snapshot = it },
                { notified = false },
            )
        val oldResponse =
            ProviderAdapter(Transport { HttpReply(status, "{\"five_hour\":{\"utilization\":99}}") })
                .fetch(snapshot, oldCredential, now)

        assertThrows(IllegalStateException::class.java) { mutations.replace(provider, value) }
        assertSame(value, fake.memory) // false does NOT roll back the active credential
        assertSame(oldCredential, fake.disk) // restart persistence was not confirmed
        assertEquals(captured.getValue(provider) + 1, versions.capture().getValue(provider))
        assertFalse(notified)
        assertEquals(ProviderSnapshot(provider, Connection.ERROR, failure = Failure.STORAGE), snapshot)
        assertTrue(mutations.isBlocked(provider))
        assertFalse(mutations.accepts(provider, captured))
        assertFalse(mutations.accepts(provider, versions.capture())) // new refresh must not hide failed persistence
        if (mutations.accepts(oldResponse.provider, captured)) {
            snapshot = oldResponse
            notified = oldResponse.connection == Connection.RECONNECT_REQUIRED
        }
        assertTrue(snapshot.windows.isEmpty())
        assertEquals(Failure.STORAGE, snapshot.failure)
        assertFalse(notified)
        assertTrue(mutations.accepts(ProviderId.CODEX, captured)) // unrelated connections retained
    }

    @Test fun invalidationAndClearingPrecedeCredentialCommit() {
        val versions = ConnectionVersions()
        val captured = versions.capture()
        var snapshot = ProviderSnapshot(provider, Connection.CONNECTED, now)
        var notified = true
        val mutations =
            CredentialMutations(
                versions,
                { _, _ ->
                    assertFalse(versions.accepts(provider, captured))
                    assertEquals(ProviderSnapshot(provider), snapshot)
                    assertFalse(notified)
                },
                { snapshot = it },
                { notified = false },
            )
        mutations.replace(provider, replacement)
        assertFalse(mutations.isBlocked(provider))
    }

    @Test fun successfulRetryReplacementAndRemovalRecoverWithoutRestoringOldData() {
        for (value in listOf(replacement, null)) {
            val versions = ConnectionVersions()
            var memory: ManualCredential? = oldCredential
            var fail = true
            var snapshot = ProviderSnapshot(provider, Connection.CONNECTED, now)
            var notified = true
            val mutations =
                CredentialMutations(
                    versions,
                    { _, credential ->
                        memory = credential
                        check(!fail)
                    },
                    { snapshot = it },
                    { notified = false },
                )
            assertThrows(IllegalStateException::class.java) { mutations.replace(provider, value) }
            val failedGeneration = versions.capture()
            fail = false
            mutations.replace(provider, value)
            assertSame(value, memory)
            assertFalse(mutations.isBlocked(provider))
            assertEquals(ProviderSnapshot(provider), snapshot)
            assertFalse(notified)
            assertFalse(mutations.accepts(provider, failedGeneration))
            assertTrue(mutations.accepts(provider, versions.capture()))
        }
    }
}
