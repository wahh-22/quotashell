package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import javax.crypto.KeyGenerator

class ClaudeSessionTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val previous = ProviderSnapshot(ProviderId.CLAUDE, Connection.CONNECTED, now.minusSeconds(60))
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val credential get() = ManualCredential("sessionKey=synthetic-cookie", claudeKind = ClaudeCredentialKind.SESSION_KEY)

    private fun fixture(name: String) = javaClass.getResource("/claude/$name.json")!!.readText()

    @Test fun validatesNormalizesAndRedactsSessionCredentials() {
        assertEquals("synthetic-cookie", credential.token)
        assertEquals("synthetic-cookie", ManualCredential("synthetic-cookie", claudeKind = ClaudeCredentialKind.SESSION_KEY).token)
        for (bad in listOf(
            "",
            " ",
            "bad cookie",
            "bad\n",
            "bad\u007f",
            "bad;cookie",
            "bad,cookie",
            "bad=cookie",
            "x".repeat(16385),
            "sessionKey=",
        )) {
            assertThrows(IllegalArgumentException::class.java) { ManualCredential(bad, claudeKind = ClaudeCredentialKind.SESSION_KEY) }
        }
        assertFalse(credential.toString().contains("synthetic-cookie"))
        assertFalse(HttpReply(200, "synthetic-body", sessionRenewal = "synthetic-cookie").toString().contains("synthetic"))
    }

    @Test fun singleOrganizationUsesCookieAndOnlyActualQuotaWindows() {
        val requests = mutableListOf<QuotaRequest>()
        val result =
            ClaudeSessionFetcher(
                Transport {
                    requests += it
                    if (it.endpoint ==
                        Endpoint.CLAUDE_ORGANIZATIONS
                    ) {
                        HttpReply(200, fixture("single"))
                    } else {
                        HttpReply(200, fixture("usage"))
                    }
                },
            ).fetch(previous, credential, now)
        assertEquals(Connection.CONNECTED, result.snapshot.connection)
        assertEquals(listOf(WindowKind.FIVE_HOUR, WindowKind.WEEK), result.snapshot.windows.map { it.kind })
        assertEquals(listOf(0.0, 72.5), result.snapshot.windows.map { it.usedPercent })
        assertEquals(
            Instant.parse("2026-01-01T05:00:00Z"),
            result.snapshot.windows
                .first()
                .resetsAt,
        )
        assertEquals("https://claude.ai/api/organizations/$first/usage?cedar_ember=1", requests.last().url)
        requests.forEach {
            assertEquals("sessionKey=synthetic-cookie", it.headers["Cookie"])
            assertEquals("QuotaShell/0.1", it.headers["User-Agent"])
            assertFalse(it.headers.containsKey("Authorization"))
        }
    }

    @Test fun severalOrganizationsRequireExplicitChoiceAndSanitizeNames() {
        var calls = 0
        val fetcher =
            ClaudeSessionFetcher(
                Transport {
                    calls++
                    HttpReply(200, fixture("multiple"))
                },
            )
        val result = fetcher.fetch(previous, credential, now)
        assertEquals(1, calls)
        val choice = result.organizations as ClaudeOrganizationState.ChoiceRequired
        assertEquals(listOf(first, second), choice.options.map { it.id })
        assertFalse(
            choice.options
                .first()
                .name
                .any { it.isISOControl() || it == '\u202e' },
        )
        assertEquals(Connection.ERROR, result.snapshot.connection)
        assertEquals(previous.capturedAt, result.snapshot.capturedAt)
    }

    @Test fun storedChoiceMustStillBelongToAccountAndNeverFallsBack() {
        for (id in listOf(second, "33333333-3333-4333-8333-333333333333")) {
            var calls = 0
            val result =
                ClaudeSessionFetcher(
                    Transport {
                        calls++
                        if (it.endpoint ==
                            Endpoint.CLAUDE_ORGANIZATIONS
                        ) {
                            HttpReply(200, fixture("multiple"))
                        } else {
                            HttpReply(200, fixture("usage"))
                        }
                    },
                ).fetch(
                    previous,
                    ManualCredential("synthetic-cookie", claudeKind = ClaudeCredentialKind.SESSION_KEY, organizationId = id),
                    now,
                )
            if (id == second) {
                assertEquals(2, calls)
                assertEquals(Connection.CONNECTED, result.snapshot.connection)
                assertEquals(second, (result.organizations as ClaudeOrganizationState.Selected).organization.id)
            } else {
                assertEquals(1, calls)
                assertTrue(result.organizations is ClaudeOrganizationState.ChoiceRequired)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualCredential("synthetic-cookie", claudeKind = ClaudeCredentialKind.SESSION_KEY, organizationId = "../evil")
        }
    }

    @Test fun authenticationChallengePermissionAndRateLimitsRemainDistinct() {
        for ((reply, expected) in listOf(
            HttpReply(401, "") to Connection.RECONNECT_REQUIRED,
            HttpReply(403, "") to Connection.RECONNECT_REQUIRED,
            HttpReply(403, "", cloudflareChallenge = true) to Connection.ERROR,
            HttpReply(429, "") to Connection.ERROR,
            HttpReply(500, "") to Connection.ERROR,
        )) {
            val result = ClaudeSessionFetcher(Transport { reply }).fetch(previous, credential, now).snapshot
            assertEquals(expected, result.connection)
            assertEquals(previous.capturedAt, result.capturedAt)
            if (reply.status == 429) assertEquals(Failure.RATE_LIMIT, result.failure)
            if (reply.cloudflareChallenge) assertEquals(Failure.PERMISSION, result.failure)
        }
    }

    @Test fun optInRejectionRetriesWithoutQueryButNeverRetriesAuthenticationOrChallenges() {
        val requests = mutableListOf<QuotaRequest>()
        val result =
            ClaudeSessionFetcher(
                Transport {
                    requests += it
                    when {
                        it.endpoint == Endpoint.CLAUDE_ORGANIZATIONS -> HttpReply(200, fixture("single"))
                        it.endpoint == Endpoint.CLAUDE_WEB_USAGE -> HttpReply(403, "")
                        else -> HttpReply(200, fixture("usage"))
                    }
                },
            ).fetch(previous, credential, now)
        assertEquals(Connection.CONNECTED, result.snapshot.connection)
        assertEquals("https://claude.ai/api/organizations/$first/usage", requests.last().url)
        for (status in listOf(401, 429)) {
            var calls = 0
            ClaudeSessionFetcher(
                Transport {
                    calls++
                    if (it.endpoint == Endpoint.CLAUDE_ORGANIZATIONS) HttpReply(200, fixture("single")) else HttpReply(status, "")
                },
            ).fetch(previous, credential, now)
            assertEquals(2, calls)
        }
    }

    @Test fun renewalIsUsedOnNextRequestAndReturnedEvenWhenChoiceIsRequired() {
        val requests = mutableListOf<QuotaRequest>()
        val result =
            ClaudeSessionFetcher(
                Transport {
                    requests += it
                    if (it.endpoint == Endpoint.CLAUDE_ORGANIZATIONS) {
                        HttpReply(200, fixture("single"), sessionRenewal = "synthetic-renewed")
                    } else {
                        HttpReply(200, fixture("usage"), sessionRenewal = "synthetic-latest")
                    }
                },
            ).fetch(previous, credential, now)
        assertEquals("sessionKey=synthetic-renewed", requests.last().headers["Cookie"])
        assertEquals("synthetic-latest", result.renewal!!.token)
        val choice =
            ClaudeSessionFetcher(Transport { HttpReply(200, fixture("multiple"), sessionRenewal = "synthetic-renewed") })
                .fetch(previous, credential, now)
        assertEquals("synthetic-renewed", choice.renewal!!.token)
    }

    @Test fun cookiesAreAcceptedOnlyFromSuccessfulClaudeHostAndExactNameAndDomain() {
        fun renewal(
            url: String,
            status: Int,
            cookie: String,
        ) = ClaudeSessionCookies.renewal(
            url,
            status,
            mapOf("Set-Cookie" to listOf(cookie)),
        )
        assertEquals(
            "synthetic-new",
            renewal("https://claude.ai/api/organizations", 200, "sessionKey=synthetic-new; Domain=.claude.ai; Secure; HttpOnly"),
        )
        for ((url, status, cookie) in listOf(
            Triple("https://evil.test/", 200, "sessionKey=synthetic-new"),
            Triple("http://claude.ai/", 200, "sessionKey=synthetic-new"),
            Triple("https://claude.ai/", 302, "sessionKey=synthetic-new"),
            Triple("https://claude.ai/", 200, "other=synthetic-new"),
            Triple("https://claude.ai/", 200, "SessionKey=synthetic-new"),
            Triple("https://claude.ai/", 200, "sessionKey=synthetic-new; Domain=evil.test"),
            Triple("https://claude.ai/", 200, "sessionKey=bad=value"),
            Triple("https://claude.ai/", 200, "sessionKey=; Max-Age=0"),
        )) {
            assertNull(renewal(url, status, cookie))
        }
    }

    @Test fun cookieRotationHandlesCombinedHeadersAndIgnoresOtherCookies() {
        val headers =
            mapOf(
                "set-cookie" to
                    listOf(
                        "other=ignored; Expires=Wed, 01 Jan 2027 00:00:00 GMT, sessionKey=synthetic-one; Secure; Domain=claude.ai",
                        "sessionKey=synthetic-two; HttpOnly; Secure",
                    ),
            )
        assertEquals("synthetic-two", ClaudeSessionCookies.renewal("https://claude.ai/", 200, headers))
        assertNull(
            ClaudeSessionCookies.renewal(
                "https://claude.ai/",
                200,
                mapOf("Set-Cookie" to listOf("sessionKey=synthetic-delete; Max-Age=0")),
            ),
        )
    }

    @Test fun nullAbsentAndSpendOnlyResponsesNeverInventQuota() {
        for (body in listOf(
            "{}",
            "{\"five_hour\":null,\"seven_day\":null}",
            "{\"extra_usage\":{\"monthly_limit\":100,\"used_credits\":90}}",
        )) {
            val result =
                ClaudeSessionFetcher(
                    Transport {
                        if (it.endpoint == Endpoint.CLAUDE_ORGANIZATIONS) HttpReply(200, fixture("single")) else HttpReply(200, body)
                    },
                ).fetch(previous, credential, now)
            assertEquals(Connection.CONNECTED, result.snapshot.connection)
            assertTrue(result.snapshot.windows.isEmpty())
        }
        val result = ClaudeSessionFetcher(Transport { throw java.io.IOException("Synthetic failure") }).fetch(previous, credential, now)
        assertEquals(Failure.NETWORK, result.snapshot.failure)
        assertEquals(previous.capturedAt, result.snapshot.capturedAt)
    }

    @Test fun usageChallengeDoesNotRetryOrRequestReconnect() {
        var calls = 0
        val result =
            ClaudeSessionFetcher(
                Transport {
                    calls++
                    if (it.endpoint ==
                        Endpoint.CLAUDE_ORGANIZATIONS
                    ) {
                        HttpReply(200, fixture("single"))
                    } else {
                        HttpReply(403, "", cloudflareChallenge = true)
                    }
                },
            ).fetch(previous, credential, now)
        assertEquals(2, calls)
        assertEquals(Connection.ERROR, result.snapshot.connection)
        assertEquals(Failure.PERMISSION, result.snapshot.failure)
    }

    @Test fun encryptedCredentialRoundTripsKindAndOrganization() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val value = ManualCredential("synthetic-cookie", claudeKind = ClaudeCredentialKind.SESSION_KEY, organizationId = second)
        val record = CredentialCipher.encrypt(key, ProviderId.CLAUDE, value)
        val restored = CredentialCipher.decrypt(key, ProviderId.CLAUDE, record)
        assertEquals(value.token, restored.token)
        assertEquals(value.claudeKind, restored.claudeKind)
        assertEquals(second, restored.organizationId)
        assertThrows(IllegalArgumentException::class.java) { CredentialCipher.encrypt(key, ProviderId.CODEX, value) }
    }

    @Test fun singleOrganizationWinsOverObsoleteStoredChoice() {
        val value = ManualCredential("synthetic-cookie", claudeKind = ClaudeCredentialKind.SESSION_KEY, organizationId = second)
        val result =
            ClaudeSessionFetcher(
                Transport {
                    if (it.endpoint ==
                        Endpoint.CLAUDE_ORGANIZATIONS
                    ) {
                        HttpReply(200, fixture("single"))
                    } else {
                        HttpReply(200, fixture("usage"))
                    }
                },
            ).fetch(previous, value, now)
        assertEquals(Connection.CONNECTED, result.snapshot.connection)
        assertEquals(first, (result.organizations as ClaudeOrganizationState.Selected).organization.id)
    }

    @Test fun sessionCookieCanNeverBeSentToOtherProviders() {
        var called = false
        val adapter =
            ProviderAdapter(
                Transport {
                    called = true
                    HttpReply(200, "{}")
                },
            )
        for (provider in listOf(ProviderId.CODEX, ProviderId.OPENCODE_GO)) {
            assertThrows(IllegalArgumentException::class.java) { adapter.fetch(ProviderSnapshot(provider), credential, now) }
        }
        assertFalse(called)
    }

    @Test fun malformedOrganizationsAndUsageFailClosedWithoutInventedQuotas() {
        for (body in listOf(
            "[]",
            "{}",
            "[{\"uuid\":\"../evil\"}]",
            "[{\"uuid\":\"$first\"},{\"uuid\":\"$first\"}]",
            fixture("single") + ",\"injected\":true",
        )) {
            val result = ClaudeSessionFetcher(Transport { HttpReply(200, body) }).fetch(previous, credential, now)
            assertEquals(Connection.ERROR, result.snapshot.connection)
        }
        for (body in listOf(
            "{\"five_hour\":{\"utilization\":101}}",
            "{\"five_hour\":{\"resets_at\":\"bad\"}}",
            "{\"five_hour\":{\"utilization\":\"20\"}}",
        )) {
            val result =
                ClaudeSessionFetcher(
                    Transport {
                        if (it.endpoint == Endpoint.CLAUDE_ORGANIZATIONS) HttpReply(200, fixture("single")) else HttpReply(200, body)
                    },
                ).fetch(previous, credential, now)
            assertEquals(Failure.INVALID_DATA, result.snapshot.failure)
        }
    }
}
