package dev.wahh.quotashell.core

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** No redirect (even same-origin), cookie jar, disk response cache, retries or custom TLS trust. */
class HttpsQuotaTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : Transport {
    override fun get(request: QuotaRequest): HttpReply {
        val connection = open(URL(request.url))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-store")
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val deadline = System.nanoTime() + 25_000_000_000L
            val status = connection.responseCode
            if (status !in 200..299) {
                // Claude challenges are not expired sessions. Inspect only a bounded transient prefix;
                // no error body or header is exposed to snapshots, callers, storage or logs.
                val challenge =
                    if (status == 403 && URL(request.url).host == "claude.ai") {
                        if (connection.getHeaderField("cf-mitigated").equals("challenge", ignoreCase = true)) {
                            true
                        } else {
                            val prefix = connection.errorStream?.use { it.readNBytesBounded(65536, deadline) }
                            prefix?.toString(Charsets.UTF_8)?.contains("Just a moment", ignoreCase = true) == true
                        }
                    } else {
                        false
                    }
                return HttpReply(status, "", cloudflareChallenge = challenge)
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 262144) { "Response too large" }
                    bytes.write(buffer, 0, count)
                }
            }
            val renewal =
                if (URL(request.url).host == "claude.ai") {
                    ClaudeSessionCookies.renewal(request.url, status, connection.headerFields)
                } else {
                    null
                }
            return HttpReply(status, bytes.toString("UTF-8"), renewal)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesBounded(
        limit: Int,
        deadline: Long,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (bytes.size() < limit) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
            val count = read(buffer, 0, minOf(buffer.size, limit - bytes.size()))
            if (count < 0) break
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
}
