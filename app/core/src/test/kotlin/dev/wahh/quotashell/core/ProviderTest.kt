package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ProviderTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val credential = ManualCredential("fixture-not-a-real-secret")

    private fun fetch(
        id: ProviderId,
        body: String,
        resets: HttpReply = HttpReply(200, "{\"available_count\":0}"),
    ): ProviderSnapshot =
        ProviderAdapter(
            Transport { request: QuotaRequest ->
                if (request.endpoint ==
                    Endpoint.CODEX_RESETS
                ) {
                    resets
                } else {
                    HttpReply(200, body)
                }
            },
        ).fetch(ProviderSnapshot(id), credential, now)

    @Test fun codexMapsActualDurationsAndNeverSpendingCredits() {
        val result =
            fetch(
                ProviderId.CODEX,
                """{"rate_limit":{"primary_window":{"used_percent":0,"reset_at":1767225600,"limit_window_seconds":18000},"secondary_window":{"used_percent":42,"limit_window_seconds":604800}},"credits":{"balance":900}}""",
            )
        assertEquals(Connection.CONNECTED, result.connection)
        assertEquals(listOf(WindowKind.FIVE_HOUR, WindowKind.WEEK), result.windows.map { it.kind })
        assertEquals(0.0, result.windows.first().usedPercent)
        assertEquals(now, result.windows.first().resetsAt)
        assertEquals(0, result.resetInventoryCount)
        val unusual = fetch(ProviderId.CODEX, """{"rate_limit":{"secondary_window":{"used_percent":5,"limit_window_seconds":86400}}}""")
        assertEquals(WindowKind.OTHER, unusual.windows.single().kind)
        assertEquals(86400L, unusual.windows.single().durationSeconds)
        assertTrue(
            present(unusual, now)
                .windows
                .single()
                .title
                .contains("24h"),
        )
        val monthly = fetch(ProviderId.CODEX, """{"rate_limit":{"primary_window":{"limit_window_seconds":2592000,"used_percent":12}}}""")
        assertEquals(WindowKind.MONTH, monthly.windows.single().kind)
    }

    @Test fun resetInventoryFailureDoesNotEraseQuotaSuccessOrClaimFreshCount() {
        for (reply in listOf(HttpReply(401, ""), HttpReply(503, ""), HttpReply(200, "{\"credits\":{\"balance\":99}}"))) {
            val result = fetch(ProviderId.CODEX, "{\"rate_limit\":{}}", reply)
            assertEquals(Connection.CONNECTED, result.connection)
            assertEquals(now, result.capturedAt)
            assertNull(result.resetInventoryCount)
            assertNotNull(result.resetInventoryFailure)
        }
        assertEquals(
            3,
            fetch(
                ProviderId.CODEX,
                "{}",
                HttpReply(200, "{\"available_count\":3,\"credits\":[{\"status\":\"available\"}]}"),
            ).resetInventoryCount,
        )
    }

    @Test fun claudeOnlyUsesSubscriptionWindowsAndMissingIsNotZero() {
        val snapshot =
            fetch(
                ProviderId.CLAUDE,
                """{"five_hour":{"utilization":0,"resets_at":"2026-01-01T01:00:00Z"},"seven_day":{"utilization":null},"extra_usage":{"utilization":99},"future":true}""",
            )
        assertEquals(2, snapshot.windows.size)
        assertEquals(0.0, snapshot.windows[0].usedPercent)
        assertNull(snapshot.windows[1].usedPercent)
        assertEquals(now.plusSeconds(3600), snapshot.windows[0].resetsAt)
        assertTrue(fetch(ProviderId.CLAUDE, "{\"five_hour\":null}").windows.isEmpty())
    }

    @Test fun goPercentAlreadyUsesPercentAndSupportsActualResetKeys() {
        val snapshot =
            fetch(
                ProviderId.OPENCODE_GO,
                """{"usage":{"rolling":{"percent":0.5,"resetInSec":30},"weekly":{"percent":0,"reset_at":1767225600},"monthly":{"percent":100,"resetsAt":"2026-02-01T00:00:00Z"}}}""",
            )
        assertEquals(listOf(0.5, 0.0, 100.0), snapshot.windows.map { it.usedPercent })
        assertEquals("0.5% used", present(snapshot, now).windows.first().usage)
        assertEquals(now.plusSeconds(30), snapshot.windows[0].resetsAt)
        assertEquals(now, snapshot.windows[1].resetsAt)
        val milliseconds = fetch(ProviderId.OPENCODE_GO, "{\"usage\":{\"rolling\":{\"percent\":1,\"resetAt\":1767225600000}}}")
        assertEquals(now, milliseconds.windows.single().resetsAt)
        assertNull(fetch(ProviderId.OPENCODE_GO, "{\"usage\":{\"rolling\":{}}}").windows.single().resetsAt)
        assertTrue(fetch(ProviderId.OPENCODE_GO, "{\"usage\":{}}").windows.isEmpty())
    }

    @Test fun httpFailuresAreSanitizedAndPreservePreviousMeasurements() {
        val previous = ProviderSnapshot(ProviderId.CLAUDE, Connection.CONNECTED, now, listOf(QuotaWindow(WindowKind.WEEK, 42.0)))
        for ((status, failure) in listOf(
            403 to Failure.PERMISSION,
            429 to Failure.RATE_LIMIT,
            500 to Failure.SERVICE,
            302 to Failure.SERVICE,
        )) {
            val actual =
                ProviderAdapter(
                    Transport { HttpReply(status, "secret must not be echoed") },
                ).fetch(previous, credential, now.plusSeconds(30))
            assertEquals(Connection.ERROR, actual.connection)
            assertEquals(failure, actual.failure)
            assertEquals(previous.capturedAt, actual.capturedAt)
            assertEquals(previous.windows, actual.windows)
        }
        assertEquals(
            Connection.RECONNECT_REQUIRED,
            ProviderAdapter(Transport { HttpReply(401, "") }).fetch(previous, credential, now).connection,
        )
        assertEquals(Failure.NETWORK, ProviderAdapter(Transport { throw IOException("private") }).fetch(previous, credential, now).failure)
        assertEquals(Failure.SERVICE, ProviderAdapter(Transport { throw Exception("private") }).fetch(previous, credential, now).failure)
        for (body in listOf("not json", "{\"five_hour\":{\"utilization\":101}}", "{\"five_hour\":{\"utilization\":true}}")) {
            assertEquals(Failure.INVALID_DATA, fetch(ProviderId.CLAUDE, body).failure)
        }
    }

    @Test fun requestsAreFixedAndUseOurOwnIdentity() {
        val requests = mutableListOf<QuotaRequest>()
        val adapter =
            ProviderAdapter(
                Transport {
                    requests += it
                    HttpReply(200, "{}")
                },
            )
        for (id in ProviderId.entries) adapter.fetch(ProviderSnapshot(id), ManualCredential("fixture", "account-fixture"), now)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.endpoint.url.startsWith("https://") && it.headers["User-Agent"] == "QuotaShell/0.1" })
        assertEquals("account-fixture", requests.first().headers["ChatGPT-Account-Id"])
        assertEquals("oauth-2025-04-20", requests.first { it.endpoint == Endpoint.CLAUDE_USAGE }.headers["anthropic-beta"])
        assertTrue(requests.none { it.headers.containsKey("Cookie") })
    }

    @Test fun rejectHeaderInjectionAndRedactCredentialObjects() {
        for (bad in listOf("", "x\r\ny", "x\u0000y", "x y", "x\u007fy")) {
            assertThrows(IllegalArgumentException::class.java) { ManualCredential(bad) }
            assertThrows(IllegalArgumentException::class.java) { ManualCredential("ok", bad) }
        }
        assertFalse(credential.toString().contains("fixture"))
    }

    @Test fun replacementAndDisconnectInvalidateBothSuccessAndAuthResponses() {
        val versions = ConnectionVersions()
        val first = versions.capture()
        versions.changed(ProviderId.CODEX)
        assertFalse(versions.accepts(ProviderId.CODEX, first))
        assertTrue(versions.accepts(ProviderId.CLAUDE, first))
        val replacement = versions.capture()
        versions.changed(ProviderId.CODEX) // disconnect is the same invalidation barrier
        assertFalse(versions.accepts(ProviderId.CODEX, replacement))
        assertTrue(versions.accepts(ProviderId.CODEX, versions.capture()))
    }

    @Test fun missingCodexDurationsAreUnknownNotAssumedFiveHourOrMonthly() {
        val snapshot =
            fetch(ProviderId.CODEX, """{"rate_limit":{"primary_window":{"used_percent":0},"secondary_window":{"used_percent":20}}}""")
        assertEquals(Connection.CONNECTED, snapshot.connection)
        assertEquals(2, snapshot.windows.size)
        assertTrue(snapshot.windows.all { it.durationSeconds == null })
        assertTrue(snapshot.windows.none { it.kind == WindowKind.FIVE_HOUR || it.kind == WindowKind.MONTH || it.kind == WindowKind.WEEK })
        assertEquals(snapshot, SnapshotCodec.decode(ProviderId.CODEX, SnapshotCodec.encode(snapshot)))
    }

    @Test fun fakeTransportMutationCannotPublishOldQuotaOrExpiryWarning() {
        for (status in listOf(200, 401)) {
            val versions = ConnectionVersions()
            val captured = versions.capture()
            val adapter =
                ProviderAdapter(
                    Transport {
                        versions.changed(ProviderId.CLAUDE) // replacement or removal while request is running
                        HttpReply(status, "{\"five_hour\":{\"utilization\":99}}")
                    },
                )
            val delayed = adapter.fetch(ProviderSnapshot(ProviderId.CLAUDE), credential, now)
            val accepted = listOf(delayed).filter { versions.accepts(it.provider, captured) }
            assertTrue(accepted.isEmpty())
            assertFalse(accepted.any { ExpiryPolicy.shouldNotify(it.connection, false, true) })
            val newResult = fetch(ProviderId.CLAUDE, "{\"five_hour\":{\"utilization\":1}}")
            assertTrue(versions.accepts(newResult.provider, versions.capture()))
        }
    }

    @Test fun strictJsonRejectsOversizeDepthDuplicateKeysAndTrailingData() {
        val malformed =
            listOf(
                "{} trailing",
                "{\"five_hour\":{},\"five_hour\":{}}",
                "{\"n\":01}",
                "{\"n\":NaN}",
                " ".repeat(262145),
                "{\"future\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}",
            )
        for (body in malformed) assertEquals(Failure.INVALID_DATA, fetch(ProviderId.CLAUDE, body).failure)
        assertEquals(
            Connection.CONNECTED,
            fetch(ProviderId.CLAUDE, "{\"future\":{\"nested\":[true,false,null,\"escaped\\nvalue\",1e2]}}").connection,
        )
    }

    @Test fun notificationEpisodesDedupeUntilSuccessfulReconnectOrRemoval() {
        assertTrue(ExpiryPolicy.shouldNotify(Connection.RECONNECT_REQUIRED, false, true))
        assertFalse(ExpiryPolicy.shouldNotify(Connection.RECONNECT_REQUIRED, true, true))
        assertFalse(ExpiryPolicy.shouldNotify(Connection.RECONNECT_REQUIRED, false, false))
        for (state in Connection.entries.filter { it != Connection.RECONNECT_REQUIRED }) {
            assertFalse(
                ExpiryPolicy.shouldNotify(state, false, true),
            )
        }
        assertFalse(ExpiryPolicy.nextEpisode(Connection.CONNECTED, true))
        assertFalse(ExpiryPolicy.nextEpisode(Connection.DISCONNECTED, true))
        assertTrue(ExpiryPolicy.nextEpisode(Connection.ERROR, true))
    }

    @Test fun extendedCodecRoundTripsWithoutChangingExistingSnapshotContract() {
        val original =
            ProviderSnapshot(
                ProviderId.CODEX,
                Connection.CONNECTED,
                now,
                listOf(QuotaWindow(WindowKind.OTHER, 12.0, now, 7200)),
                resetInventoryFailure = Failure.PERMISSION,
            )
        assertEquals(original, SnapshotCodec.decode(ProviderId.CODEX, SnapshotCodec.encode(original)))
        val partial = listOf(original, ProviderSnapshot(ProviderId.CLAUDE))
        assertEquals(listOf(original), (RefreshResult.Updated(partial.take(1))).snapshots)
        assertEquals("Codex resets available: Unknown · Permission denied", present(original, now).resetInventory)
    }
}
