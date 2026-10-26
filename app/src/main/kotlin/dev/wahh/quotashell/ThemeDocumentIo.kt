package dev.wahh.quotashell

import android.app.Activity
import android.net.Uri
import dev.wahh.quotashell.core.PhantomTheme
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Bounded document reads and parsing/writes run off the UI thread; no URI grants are retained. */
internal class ThemeDocumentIo(
    private val activity: Activity,
) {
    fun import(
        uri: Uri,
        done: (Result<PhantomTheme>) -> Unit,
    ) {
        work(done) {
            val bytes = ByteArrayOutputStream()
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= PhantomTheme.MAX_BYTES) { "Theme exceeds 64 KiB" }
                    bytes.write(buffer, 0, count)
                }
            } ?: error("Cannot open theme document")
            val json =
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            PhantomTheme.parse(json.removePrefix("\uFEFF"))
        }
    }

    fun export(
        uri: Uri,
        theme: PhantomTheme,
        done: (Result<Unit>) -> Unit,
    ) {
        work(done) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(theme.toJson().toByteArray(Charsets.UTF_8))
            } ?: error("Cannot write theme document")
        }
    }

    private fun <T> work(
        done: (Result<T>) -> Unit,
        action: () -> T,
    ) {
        Thread({
            val result = runCatching(action)
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing) done(result)
            }
        }, "phantom-theme-document").start()
    }
}
