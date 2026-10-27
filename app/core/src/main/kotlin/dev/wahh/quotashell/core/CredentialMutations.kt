package dev.wahh.quotashell.core

/** Main-thread connection mutation and publication seam; platform storage remains injected. */
class CredentialMutations(
    private val versions: ConnectionVersions,
    private val writeCredential: (ProviderId, ManualCredential?) -> Unit,
    private val saveSnapshot: (ProviderSnapshot) -> Unit,
    private val clearNotification: (ProviderId) -> Unit,
) {
    private val blocked = mutableSetOf<ProviderId>()

    @Synchronized fun replace(
        provider: ProviderId,
        credential: ManualCredential?,
    ) {
        // commit() can mutate active memory before reporting disk failure. Fence every attempt.
        versions.changed(provider)
        blocked.add(provider)
        clearNotification(provider)
        saveSnapshot(ProviderSnapshot(provider))
        try {
            writeCredential(provider, credential)
        } catch (_: Exception) {
            saveSnapshot(ProviderSnapshot(provider, Connection.ERROR, failure = Failure.STORAGE))
            // Do not roll back, retry automatically, or claim which credential reached disk.
            throw IllegalStateException("Credential persistence unconfirmed; retry save or removal")
        }
        blocked.remove(provider)
    }

    /** Same serialized storage path, but renewal must not clear valid quotas or reconnect episodes. */
    @Synchronized fun rotateClaudeSession(
        captured: Map<ProviderId, Long>,
        credential: ManualCredential,
    ): Boolean {
        val provider = ProviderId.CLAUDE
        require(credential.claudeKind == ClaudeCredentialKind.SESSION_KEY)
        if (!accepts(provider, captured)) return false
        // Fence duplicate renewals and any outcome belonging to this pre-renewal generation.
        versions.changed(provider)
        blocked.add(provider)
        try {
            writeCredential(provider, credential)
        } catch (_: Exception) {
            clearNotification(provider)
            saveSnapshot(ProviderSnapshot(provider, Connection.ERROR, failure = Failure.STORAGE))
            throw IllegalStateException("Credential persistence unconfirmed; retry save or removal")
        }
        blocked.remove(provider)
        return true
    }

    /** Process-local quarantine; a failed disk write cannot durably promise a restart state. */
    @Synchronized fun isBlocked(provider: ProviderId): Boolean = provider in blocked

    @Synchronized fun accepts(
        provider: ProviderId,
        captured: Map<ProviderId, Long>,
    ): Boolean = provider !in blocked && versions.accepts(provider, captured)
}
