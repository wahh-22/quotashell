package dev.wahh.quotashell.core

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** No redirect (even same-origin), cookies, disk response cache, retries or custom TLS trust. */
class HttpsQuotaTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : Transport {
    override fun get(request: QuotaRequest): HttpReply {
        val connection = open(URL(request.endpoint.url))
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
            if (status !in 200..299) return HttpReply(status, "") // Never retain raw error bodies.
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
            return HttpReply(status, bytes.toString("UTF-8"))
        } finally {
            connection.disconnect()
        }
    }
}
