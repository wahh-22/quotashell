package dev.wahh.quotashell.core

import java.net.URI

/** Extract only the expected cookie, never a general cookie jar or raw response header record. */
object ClaudeSessionCookies {
    fun renewal(
        url: String,
        status: Int,
        headers: Map<out String?, List<String>?>,
    ): String? {
        val uri = URI(url)
        if (status != 200 || uri.scheme != "https" || uri.host != "claude.ai" || uri.port !in listOf(-1, 443)) return null
        var latest: String? = null
        headers.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.filterNotNull().flatten().forEach { header ->
            if (header.length > 65536) return@forEach
            // Combined headers may contain an Expires comma; split only at a new cookie pair.
            header.split(Regex(",(?=\\s*[A-Za-z0-9_-]+=)")).forEach cookie@{ cookie ->
                val parts = cookie.split(';').map { it.trim() }
                val pair = parts.first()
                if (!pair.startsWith("sessionKey=")) return@cookie
                val attributes = parts.drop(1)
                if (attributes.any {
                        it.substringBefore('=').equals("Domain", true) &&
                            !it
                                .substringAfter('=', "")
                                .trim()
                                .removePrefix(".")
                                .equals("claude.ai", true)
                    }
                ) {
                    return@cookie
                }
                if (attributes.any {
                        it.substringBefore('=').equals("Max-Age", true) &&
                            (it.substringAfter('=', "").toLongOrNull() ?: 0) <= 0
                    }
                ) {
                    return@cookie
                }
                val candidate = runCatching { ManualCredential(pair, claudeKind = ClaudeCredentialKind.SESSION_KEY).token }.getOrNull()
                if (candidate != null) latest = candidate
            }
        }
        return latest
    }
}
