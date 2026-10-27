package dev.wahh.quotashell.core

import java.io.IOException
import java.time.Instant

enum class ClaudeCredentialKind { OAUTH_TOKEN, SESSION_KEY }

/** Manual credentials only; never serialize these through the snapshot boundary. */
class ManualCredential(
    token: String,
    val accountId: String? = null,
    val claudeKind: ClaudeCredentialKind = ClaudeCredentialKind.OAUTH_TOKEN,
    val organizationId: String? = null,
) {
    val token = if (claudeKind == ClaudeCredentialKind.SESSION_KEY) token.removePrefix("sessionKey=") else token

    init {
        require(valid(this.token) && (accountId == null || valid(accountId))) { "Invalid credential format" }
        if (claudeKind == ClaudeCredentialKind.SESSION_KEY) {
            require(this.token.none { it in ";,=\"\\" } && accountId == null) { "Invalid credential format" }
            require(organizationId == null || ClaudeOrganization.validId(organizationId)) { "Invalid organization format" }
        } else {
            require(organizationId == null) { "Invalid credential format" }
        }
    }

    override fun toString() = "ManualCredential([redacted])"

    companion object {
        private fun valid(value: String) = value.length in 1..16384 && value.all { it.code in 33..126 }
    }
}

enum class Endpoint(
    val url: String,
) {
    CODEX_USAGE("https://chatgpt.com/backend-api/wham/usage"),
    CODEX_RESETS("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"),
    CLAUDE_USAGE("https://api.anthropic.com/api/oauth/usage?cedar_ember=1"),
    CLAUDE_ORGANIZATIONS("https://claude.ai/api/organizations"),
    CLAUDE_WEB_USAGE("https://claude.ai/api/organizations"),
    CLAUDE_WEB_USAGE_LEGACY("https://claude.ai/api/organizations"),
    GO_USAGE("https://opencode.ai/zen/go/v1/usage"),
}

class QuotaRequest(
    val endpoint: Endpoint,
    val headers: Map<String, String>,
    organizationId: String? = null,
) {
    val url: String

    init {
        val usage = endpoint in listOf(Endpoint.CLAUDE_WEB_USAGE, Endpoint.CLAUDE_WEB_USAGE_LEGACY)
        require(if (usage) organizationId != null && ClaudeOrganization.validId(organizationId) else organizationId == null)
        url =
            if (usage) {
                endpoint.url + "/$organizationId/usage" + if (endpoint == Endpoint.CLAUDE_WEB_USAGE) "?cedar_ember=1" else ""
            } else {
                endpoint.url
            }
    }

    override fun toString() = "QuotaRequest([redacted])"
}

class HttpReply(
    val status: Int,
    val body: String,
    val sessionRenewal: String? = null,
    val cloudflareChallenge: Boolean = false,
) {
    override fun toString() = "HttpReply([redacted])"
}

fun interface Transport {
    @Throws(IOException::class)
    fun get(request: QuotaRequest): HttpReply
}

/** All upstream text/exception details stop here. A failed optional inventory is not failed usage. */
class ProviderAdapter(
    private val transport: Transport,
) {
    fun fetch(
        previous: ProviderSnapshot,
        credential: ManualCredential,
        now: Instant,
    ): ProviderSnapshot {
        require(previous.provider == ProviderId.CLAUDE || credential.claudeKind != ClaudeCredentialKind.SESSION_KEY)
        if (previous.provider == ProviderId.CLAUDE && credential.claudeKind == ClaudeCredentialKind.SESSION_KEY) {
            return ClaudeSessionFetcher(transport).fetch(previous, credential, now).snapshot
        }
        val endpoint =
            when (previous.provider) {
                ProviderId.CODEX -> Endpoint.CODEX_USAGE
                ProviderId.CLAUDE -> Endpoint.CLAUDE_USAGE
                ProviderId.OPENCODE_GO -> Endpoint.GO_USAGE
            }
        return try {
            val json = get(endpoint, credential)
            val windows =
                when (previous.provider) {
                    ProviderId.CODEX -> {
                        codex(json)
                    }

                    ProviderId.CLAUDE -> {
                        listOfNotNull(
                            window(json.obj("five_hour"), WindowKind.FIVE_HOUR, "utilization", "resets_at"),
                            window(json.obj("seven_day"), WindowKind.WEEK, "utilization", "resets_at"),
                        )
                    }

                    ProviderId.OPENCODE_GO -> {
                        go(json.obj("usage"), now)
                    }
                }
            var count: Int? = null
            var inventoryFailure: Failure? = null
            if (previous.provider == ProviderId.CODEX) {
                try {
                    // available_count is the server's inventory count; credits.balance is spending.
                    val value = get(Endpoint.CODEX_RESETS, credential).number("available_count")
                    require(value != null && value >= 0 && value <= Int.MAX_VALUE && value % 1.0 == 0.0)
                    count = value.toInt()
                } catch (error: Exception) {
                    inventoryFailure = failure(error)
                }
            }
            ProviderSnapshot(previous.provider, Connection.CONNECTED, now, windows, count, resetInventoryFailure = inventoryFailure)
        } catch (error: Exception) {
            previous.copy(
                connection = if (error is HttpFailure && error.status == 401) Connection.RECONNECT_REQUIRED else Connection.ERROR,
                failure = if (error is HttpFailure && error.status == 401) null else failure(error),
            )
        }
    }

    private fun get(
        endpoint: Endpoint,
        credential: ManualCredential,
    ): Map<String, Any?> {
        val headers =
            mutableMapOf(
                "Authorization" to "Bearer ${credential.token}",
                "User-Agent" to "QuotaShell/0.1",
                "Accept" to "application/json",
            )
        if (endpoint == Endpoint.CODEX_USAGE ||
            endpoint == Endpoint.CODEX_RESETS
        ) {
            credential.accountId?.let { headers["ChatGPT-Account-Id"] = it }
        }
        if (endpoint == Endpoint.CLAUDE_USAGE) headers["anthropic-beta"] = "oauth-2025-04-20"
        val response = transport.get(QuotaRequest(endpoint, headers))
        if (response.status !in 200..299) throw HttpFailure(response.status)
        return JsonObjectParser.parse(response.body)
    }

    private fun codex(json: Map<String, Any?>): List<QuotaWindow> {
        val rate = json.obj("rate_limit") ?: return emptyList()
        var unknownSeen = false
        return listOf("primary_window", "secondary_window").mapNotNull { key ->
            rate.obj(key)?.let { item ->
                val duration = item.integer("limit_window_seconds")
                val kind =
                    when (duration) {
                        18000L -> WindowKind.FIVE_HOUR
                        604800L -> WindowKind.WEEK
                        2592000L -> WindowKind.MONTH
                        else -> (if (unknownSeen) WindowKind.OTHER_SECONDARY else WindowKind.OTHER).also { unknownSeen = true }
                    }
                require(duration == null || duration > 0)
                QuotaWindow(kind, item.number("used_percent"), item.integer("reset_at")?.let(Instant::ofEpochSecond), duration)
            }
        }
    }

    private fun window(
        item: Map<String, Any?>?,
        kind: WindowKind,
        percent: String,
        reset: String,
    ): QuotaWindow? = item?.let { QuotaWindow(kind, it.number(percent), it[reset]?.let { value -> Instant.parse(value as String) }) }

    private fun go(
        usage: Map<String, Any?>?,
        now: Instant,
    ): List<QuotaWindow> =
        listOf("rolling" to WindowKind.FIVE_HOUR, "weekly" to WindowKind.WEEK, "monthly" to WindowKind.MONTH).mapNotNull { (key, kind) ->
            usage?.obj(key)?.let { item ->
                val relativeKey =
                    listOf(
                        "resetInSec",
                        "resetInSeconds",
                        "resetSeconds",
                        "reset_sec",
                        "reset_in_sec",
                        "resetsInSec",
                        "resetsInSeconds",
                        "resetIn",
                        "resetSec",
                    ).firstOrNull {
                        item[it] !=
                            null
                    }
                val absoluteKey =
                    listOf("resetAt", "resetsAt", "reset_at", "resets_at", "nextReset", "next_reset", "renewAt", "renew_at").firstOrNull {
                        item[it] !=
                            null
                    }
                val reset =
                    if (relativeKey != null) {
                        val seconds = requireNotNull(item.integer(relativeKey))
                        require(seconds >= 0)
                        // Relative reset handled against the same successful observation below.
                        now.plusSeconds(seconds)
                    } else {
                        absoluteKey?.let { field ->
                            when (val value = item[field]) {
                                is Number -> {
                                    val epoch = requireNotNull(item.integer(field))
                                    // Upstream OpenCodeWebParsing accepts both epoch seconds and milliseconds.
                                    if (epoch > 1_000_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
                                }

                                is String -> {
                                    Instant.parse(value)
                                }

                                else -> {
                                    error("Invalid date")
                                }
                            }
                        }
                    }
                QuotaWindow(kind, item.number("percent"), reset)
            }
        }

    private class HttpFailure(
        val status: Int,
    ) : Exception()

    private fun failure(error: Exception) =
        when (error) {
            is HttpFailure -> {
                when (error.status) {
                    403 -> Failure.PERMISSION
                    429 -> Failure.RATE_LIMIT
                    else -> Failure.SERVICE
                }
            }

            is IOException -> {
                Failure.NETWORK
            }

            is IllegalArgumentException, is IllegalStateException, is ClassCastException, is java.time.DateTimeException -> {
                Failure.INVALID_DATA
            }

            else -> {
                Failure.SERVICE
            }
        }
}

private fun Map<String, Any?>.obj(key: String): Map<String, Any?>? {
    val value = this[key] ?: return null
    require(value is Map<*, *>)
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

private fun Map<String, Any?>.number(key: String): Double? {
    val value = this[key] ?: return null
    require(value is Number)
    return value.toDouble().also { require(it.isFinite()) }
}

private fun Map<String, Any?>.integer(key: String): Long? =
    number(key)?.let {
        require(it % 1.0 == 0.0 && it >= Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble())
        it.toLong()
    }

/** Main-thread publication barrier. A credential mutation invalidates *all* old outcomes. */
class ConnectionVersions {
    private val versions = ProviderId.entries.associateWith { 0L }.toMutableMap()

    fun capture(): Map<ProviderId, Long> = versions.toMap()

    fun changed(provider: ProviderId) {
        versions[provider] = versions.getValue(provider) + 1
    }

    fun accepts(
        provider: ProviderId,
        captured: Map<ProviderId, Long>,
    ) = versions[provider] == captured[provider]
}

object ExpiryPolicy {
    fun shouldNotify(
        state: Connection,
        alreadyNotified: Boolean,
        allowed: Boolean,
    ) = state == Connection.RECONNECT_REQUIRED && !alreadyNotified && allowed

    fun nextEpisode(
        state: Connection,
        previous: Boolean,
    ) = when (state) {
        Connection.CONNECTED, Connection.DISCONNECTED -> false
        else -> previous
    }
}
