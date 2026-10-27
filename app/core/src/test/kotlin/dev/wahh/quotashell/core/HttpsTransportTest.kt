package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class HttpsTransportTest {
    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val content: ByteArray,
        private val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpsURLConnection(url) {
        var closed = false
        var errorReads = 0
        var errorStreamClosed = false

        override fun getHeaderFields(): Map<String, List<String>> = headers

        override fun getHeaderField(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, true) }
                ?.value
                ?.firstOrNull()

        override fun getErrorStream() =
            object : ByteArrayInputStream(content) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, length).also { if (it > 0) errorReads += it }

                override fun close() {
                    errorStreamClosed = true
                    super.close()
                }
            }

        override fun connect() = Unit

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream() = ByteArrayInputStream(content)

        override fun getCipherSuite() = "fixture"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()

        override fun getPeerPrincipal(): Principal? = null

        override fun getLocalPrincipal(): Principal? = null
    }

    private val request = QuotaRequest(Endpoint.CLAUDE_USAGE, mapOf("Authorization" to "Bearer fixture"))

    @Test fun boundsTimeoutsAndDisablesAllRedirectsAndCaching() {
        val connection = FakeConnection(URL(request.endpoint.url), 302, "ignored".toByteArray())
        var opens = 0
        val reply =
            HttpsQuotaTransport {
                opens++
                connection
            }.get(request)
        assertEquals(302, reply.status)
        assertEquals("", reply.body)
        assertEquals(1, opens)
        assertEquals(10000, connection.connectTimeout)
        assertEquals(10000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.closed)
    }

    @Test fun oversizedSuccessFailsClosedAndAlwaysDisconnects() {
        val connection = FakeConnection(URL(request.endpoint.url), 200, ByteArray(262145) { 'a'.code.toByte() })
        assertThrows(IllegalArgumentException::class.java) { HttpsQuotaTransport { connection }.get(request) }
        assertTrue(connection.closed)
    }

    @Test fun webCookieUsesExactFixedUrlAndNeverFollowsRedirectOrRotatesFromRedirect() {
        val request = QuotaRequest(Endpoint.CLAUDE_ORGANIZATIONS, mapOf("Cookie" to "sessionKey=synthetic-cookie"))
        val connection = FakeConnection(URL(request.url), 302, ByteArray(0), mapOf("Set-Cookie" to listOf("sessionKey=synthetic-new")))
        val reply =
            HttpsQuotaTransport {
                assertEquals("https://claude.ai/api/organizations", it.toString())
                connection
            }.get(request)
        assertNull(reply.sessionRenewal)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("sessionKey=synthetic-cookie", connection.getRequestProperty("Cookie"))
        assertEquals(0, connection.errorReads)
        assertTrue(connection.closed)
    }

    @Test fun onlySuccessfulClaudeResponsesExposeSanitizedRenewal() {
        val headers = mapOf("Set-Cookie" to listOf("unrelated=synthetic-ignored", "sessionKey=synthetic-new; Secure; HttpOnly"))
        for (endpoint in listOf(Endpoint.CLAUDE_ORGANIZATIONS, Endpoint.CLAUDE_USAGE, Endpoint.CODEX_USAGE)) {
            val request = QuotaRequest(endpoint, emptyMap())
            val connection = FakeConnection(URL(request.url), 200, "{}".toByteArray(), headers)
            val reply = HttpsQuotaTransport { connection }.get(request)
            assertEquals(if (endpoint == Endpoint.CLAUDE_ORGANIZATIONS) "synthetic-new" else null, reply.sessionRenewal)
            assertFalse(reply.toString().contains("synthetic"))
        }
    }

    @Test fun challengeDetectionUsesHeaderOrBoundedTransientBodyOnlyForWeb403() {
        for (header in listOf(true, false)) {
            val content = ("Just a moment " + "x".repeat(100000)).toByteArray()
            val connection =
                FakeConnection(
                    URL(Endpoint.CLAUDE_ORGANIZATIONS.url),
                    403,
                    content,
                    if (header) mapOf("cf-mitigated" to listOf("challenge")) else emptyMap(),
                )
            val reply = HttpsQuotaTransport { connection }.get(QuotaRequest(Endpoint.CLAUDE_ORGANIZATIONS, emptyMap()))
            assertTrue(reply.cloudflareChallenge)
            assertEquals("", reply.body)
            assertEquals(if (header) 0 else 65536, connection.errorReads)
            if (!header) assertTrue(connection.errorStreamClosed)
            assertTrue(connection.closed)
        }
        val oauth = FakeConnection(URL(request.url), 403, "Just a moment".toByteArray())
        assertFalse(HttpsQuotaTransport { oauth }.get(request).cloudflareChallenge)
        assertEquals(0, oauth.errorReads)
        val ordinary = FakeConnection(URL(Endpoint.CLAUDE_ORGANIZATIONS.url), 403, "{\"error\":\"unauthorized\"}".toByteArray())
        assertFalse(HttpsQuotaTransport { ordinary }.get(QuotaRequest(Endpoint.CLAUDE_ORGANIZATIONS, emptyMap())).cloudflareChallenge)
    }

    @Test fun organizationPathCannotInjectHostQueryOrRedirect() {
        for (id in listOf("../other", "//evil.test", "a?next=evil", "a\\r\\nCookie:evil")) {
            assertThrows(IllegalArgumentException::class.java) { QuotaRequest(Endpoint.CLAUDE_WEB_USAGE, emptyMap(), id) }
        }
    }

    @Test fun readsOnlySuccessfulBoundedResponses() {
        val connection = FakeConnection(URL(request.endpoint.url), 200, "{}".toByteArray())
        assertEquals("{}", HttpsQuotaTransport { connection }.get(request).body)
        assertTrue(connection.closed)
    }
}
