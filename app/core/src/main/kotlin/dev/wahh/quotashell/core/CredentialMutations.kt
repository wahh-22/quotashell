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

    /** Process-local quarantine; a failed disk write cannot durably promise a restart state. */
    @Synchronized fun isBlocked(provider: ProviderId): Boolean = provider in blocked

    @Synchronized fun accepts(
        provider: ProviderId,
        captured: Map<ProviderId, Long>,
    ): Boolean = provider !in blocked && versions.accepts(provider, captured)
}
