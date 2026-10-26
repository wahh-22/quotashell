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
    ) : HttpsURLConnection(url) {
        var closed = false

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

    @Test fun readsOnlySuccessfulBoundedResponses() {
        val connection = FakeConnection(URL(request.endpoint.url), 200, "{}".toByteArray())
        assertEquals("{}", HttpsQuotaTransport { connection }.get(request).body)
        assertTrue(connection.closed)
    }
}
