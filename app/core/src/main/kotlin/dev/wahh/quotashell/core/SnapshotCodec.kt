package dev.wahh.quotashell.core

import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.util.Properties

/** Versioned, nonsecret cache format. A malformed provider does not invalidate other providers. */
object SnapshotCodec {
    fun encode(snapshot: ProviderSnapshot): String {
        val props = Properties()
        props.setProperty("version", "1")
        props.setProperty("connection", snapshot.connection.name)
        snapshot.capturedAt?.let { props.setProperty("capturedAt", it.toString()) }
        snapshot.failure?.let { props.setProperty("failure", it.name) }
        snapshot.resetInventoryCount?.let { props.setProperty("resetInventory", it.toString()) }
        snapshot.resetInventoryFailure?.let { props.setProperty("resetInventoryFailure", it.name) }
        snapshot.windows.forEach {
            props.setProperty("${it.kind.name}.present", "true")
            it.usedPercent?.let { value -> props.setProperty("${it.kind.name}.used", value.toString()) }
            it.resetsAt?.let { value -> props.setProperty("${it.kind.name}.reset", value.toString()) }
            it.durationSeconds?.let { value -> props.setProperty("${it.kind.name}.duration", value.toString()) }
        }
        return StringWriter().also { props.store(it, "QuotaShell nonsecret snapshot") }.toString()
    }

    fun decode(
        provider: ProviderId,
        encoded: String?,
    ): ProviderSnapshot {
        if (encoded == null) return ProviderSnapshot(provider)
        return try {
            val props = Properties().apply { load(StringReader(encoded)) }
            require(props.getProperty("version") == "1")
            ProviderSnapshot(
                provider = provider,
                connection = Connection.valueOf(requireNotNull(props.getProperty("connection"))),
                capturedAt = props.getProperty("capturedAt")?.let(Instant::parse),
                windows =
                    WindowKind.entries.mapNotNull { kind ->
                        val used = props.getProperty("${kind.name}.used")?.toDouble()
                        val reset = props.getProperty("${kind.name}.reset")?.let(Instant::parse)
                        // Reject invalid fields even if the presence marker was damaged.
                        val duration = props.getProperty("${kind.name}.duration")?.toLong()
                        val window = QuotaWindow(kind, used, reset, duration)
                        if (props.getProperty("${kind.name}.present") == "true") {
                            window
                        } else {
                            require(used == null && reset == null && duration == null)
                            null
                        }
                    },
                resetInventoryCount = props.getProperty("resetInventory")?.toInt(),
                failure = props.getProperty("failure")?.let(Failure::valueOf),
                resetInventoryFailure = props.getProperty("resetInventoryFailure")?.let(Failure::valueOf),
            )
        } catch (_: IllegalArgumentException) {
            ProviderSnapshot(provider, connection = Connection.ERROR, failure = Failure.STORAGE)
        } catch (_: java.time.DateTimeException) {
            ProviderSnapshot(provider, connection = Connection.ERROR, failure = Failure.STORAGE)
        }
    }
}
