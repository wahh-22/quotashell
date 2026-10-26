package dev.wahh.quotashell

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.wahh.quotashell.core.*
import java.util.concurrent.Executors

/** T3 composition root: install a RefreshGateway here; no provider or credential code in the UI. */
class QuotaApplication : Application() {
    lateinit var store: SnapshotStore
        private set

    lateinit var credentials: SecureCredentialStore
        private set
    lateinit var expiryNotifications: ExpiryNotifications
        private set
    private val versions = ConnectionVersions()
    private lateinit var credentialMutations: CredentialMutations

    @Volatile var refreshGateway: RefreshGateway = RefreshGateway { RefreshResult.NotConfigured }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var refreshing = false

    override fun onCreate() {
        super.onCreate()
        store = SnapshotStore(this)
        credentials = SecureCredentialStore(this)
        expiryNotifications = ExpiryNotifications(this)
        credentialMutations =
            CredentialMutations(
                versions,
                credentials::replace,
                { store.saveSnapshots(listOf(it)) },
                expiryNotifications::clear,
            )
        val adapter = ProviderAdapter(HttpsQuotaTransport())
        refreshGateway =
            RefreshGateway { current ->
                val updated =
                    current.mapNotNull { previous ->
                        if (credentialMutations.isBlocked(previous.provider)) return@mapNotNull null
                        try {
                            credentials.read(previous.provider)?.let { adapter.fetch(previous, it, java.time.Instant.now()) }
                        } catch (_: Exception) {
                            previous.copy(connection = Connection.ERROR, failure = Failure.STORAGE)
                        }
                    }
                if (updated.isEmpty()) RefreshResult.NotConfigured else RefreshResult.Updated(updated)
            }
        RefreshJob.schedule(this)
    }

    /** Main-thread mutation and publication are serialized, including the version check. */
    fun replaceCredential(
        provider: ProviderId,
        credential: ManualCredential?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        credentialMutations.replace(provider, credential)
    }

    /** Called on main; serializes refreshes. Never advances a snapshot timestamp on failure. */
    fun refresh(callback: (String) -> Unit) {
        if (refreshing) {
            callback("Refresh already in progress")
            return
        }
        refreshing = true
        val current = store.snapshots()
        val capturedVersions = versions.capture()
        executor.execute {
            val result =
                try {
                    refreshGateway.refresh(current)
                } catch (_: Exception) {
                    // Do not log provider exceptions: they may contain sensitive response data.
                    RefreshResult.Updated(current.map { it.copy(connection = Connection.ERROR, failure = Failure.SERVICE) })
                }
            main.post {
                refreshing = false
                when (result) {
                    is RefreshResult.Updated -> {
                        val accepted = result.snapshots.filter { credentialMutations.accepts(it.provider, capturedVersions) }
                        store.saveSnapshots(accepted)
                        accepted.forEach(expiryNotifications::observe)
                        callback("Refresh complete")
                    }

                    RefreshResult.NotConfigured -> {
                        callback(
                            if (current.any { credentialMutations.isBlocked(it.provider) }) {
                                "Credential persistence unconfirmed. Retry save or removal in Connections."
                            } else {
                                "No credentials configured. Open Connections."
                            },
                        )
                    }
                }
            }
        }
    }
}
