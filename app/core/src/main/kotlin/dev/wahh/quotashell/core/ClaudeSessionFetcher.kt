package dev.wahh.quotashell.core

import java.io.IOException
import java.time.Instant

/** Sanitized, ephemeral organization metadata: never a quota snapshot or log record. */
class ClaudeOrganization(
    val id: String,
    val name: String,
) {
    override fun toString() = "ClaudeOrganization([redacted])"

    companion object {
        fun validId(value: String) = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    }
}

sealed class ClaudeOrganizationState(
    val options: List<ClaudeOrganization>,
) {
    class ChoiceRequired(
        options: List<ClaudeOrganization>,
    ) : ClaudeOrganizationState(options)

    class Selected(
        val organization: ClaudeOrganization,
        options: List<ClaudeOrganization>,
    ) : ClaudeOrganizationState(options)

    override fun toString() = "ClaudeOrganizationState([redacted])"
}

/** Stays in memory until guarded main-thread publication; only snapshot may reach SnapshotStore. */
class ClaudeSessionResult(
    val snapshot: ProviderSnapshot,
    val organizations: ClaudeOrganizationState?,
    val renewal: ManualCredential?,
) {
    override fun toString() = "ClaudeSessionResult([redacted])"
}

class ClaudeSessionFetcher(
    private val transport: Transport,
) {
    fun fetch(
        previous: ProviderSnapshot,
        credential: ManualCredential,
        now: Instant,
    ): ClaudeSessionResult {
        require(previous.provider == ProviderId.CLAUDE && credential.claudeKind == ClaudeCredentialKind.SESSION_KEY)
        var current = credential
        var organizations: ClaudeOrganizationState? = null

        fun request(
            endpoint: Endpoint,
            organizationId: String? = null,
        ): HttpReply {
            val reply =
                transport.get(
                    QuotaRequest(
                        endpoint,
                        mapOf(
                            "Cookie" to "sessionKey=${current.token}",
                            "User-Agent" to "QuotaShell/0.1",
                            "Accept" to "application/json",
                        ),
                        organizationId,
                    ),
                )
            if (reply.status == 200) {
                reply.sessionRenewal?.let {
                    current =
                        ManualCredential(it, claudeKind = ClaudeCredentialKind.SESSION_KEY, organizationId = credential.organizationId)
                }
            }
            return reply
        }
        val snapshot =
            try {
                val orgReply = request(Endpoint.CLAUDE_ORGANIZATIONS)
                checkStatus(orgReply)
                val options = parseOrganizations(orgReply.body)
                val selected =
                    options.singleOrNull() ?: credential.organizationId?.let { id -> options.firstOrNull { it.id == id } }
                if (selected == null) {
                    organizations = ClaudeOrganizationState.ChoiceRequired(options)
                    previous.copy(connection = Connection.ERROR, failure = Failure.PERMISSION)
                } else {
                    organizations = ClaudeOrganizationState.Selected(selected, options)
                    var usage = request(Endpoint.CLAUDE_WEB_USAGE, selected.id)
                    // Upstream retries reset-opt-in rejection, but not auth, throttling or challenges.
                    if (usage.status !in listOf(200, 401, 429) && !usage.cloudflareChallenge) {
                        usage = request(Endpoint.CLAUDE_WEB_USAGE_LEGACY, selected.id)
                    }
                    checkStatus(usage)
                    val json = JsonObjectParser.parse(usage.body)
                    val windows =
                        listOf("five_hour" to WindowKind.FIVE_HOUR, "seven_day" to WindowKind.WEEK).mapNotNull { (key, kind) ->
                            val item = json[key] ?: return@mapNotNull null
                            require(item is Map<*, *>)
                            val percent =
                                item["utilization"]?.let {
                                    require(it is Number)
                                    it.toDouble()
                                }
                            val reset =
                                item["resets_at"]?.let {
                                    require(it is String)
                                    Instant.parse(it)
                                }
                            QuotaWindow(kind, percent, reset)
                        }
                    ProviderSnapshot(ProviderId.CLAUDE, Connection.CONNECTED, now, windows)
                }
            } catch (error: Exception) {
                when (error) {
                    is SessionRejected -> {
                        previous.copy(connection = Connection.RECONNECT_REQUIRED, failure = null)
                    }

                    is WebFailure -> {
                        previous.copy(connection = Connection.ERROR, failure = error.failure)
                    }

                    is IOException -> {
                        previous.copy(connection = Connection.ERROR, failure = Failure.NETWORK)
                    }

                    is IllegalArgumentException, is IllegalStateException, is java.time.DateTimeException -> {
                        previous.copy(connection = Connection.ERROR, failure = Failure.INVALID_DATA)
                    }

                    else -> {
                        previous.copy(connection = Connection.ERROR, failure = Failure.SERVICE)
                    }
                }
            }
        return ClaudeSessionResult(snapshot, organizations, current.takeIf { it.token != credential.token })
    }

    private fun parseOrganizations(body: String): List<ClaudeOrganization> {
        val rows = JsonObjectParser.parseArray(body)
        require(rows.size in 1..100)
        val options =
            rows.map { row ->
                require(row is Map<*, *>)
                val id = row["uuid"]
                require(id is String && ClaudeOrganization.validId(id))
                val raw = row["name"]
                require(raw == null || raw is String)
                val name =
                    (raw as String?)
                        .orEmpty()
                        .filter {
                            !it.isISOControl() && Character.getType(it) !in listOf(Character.FORMAT.toInt(), Character.SURROGATE.toInt())
                        }.trim()
                        .take(100)
                        .ifEmpty { "Unnamed organization" }
                ClaudeOrganization(id, name)
            }
        require(options.map { it.id }.distinct().size == options.size)
        return options
    }

    private fun checkStatus(reply: HttpReply) {
        when {
            reply.status == 200 -> Unit
            reply.status == 403 && reply.cloudflareChallenge -> throw WebFailure(Failure.PERMISSION)
            reply.status in listOf(401, 403) -> throw SessionRejected()
            reply.status == 429 -> throw WebFailure(Failure.RATE_LIMIT)
            else -> throw WebFailure(Failure.SERVICE)
        }
    }

    private class SessionRejected : Exception()

    private class WebFailure(
        val failure: Failure,
    ) : Exception()
}
