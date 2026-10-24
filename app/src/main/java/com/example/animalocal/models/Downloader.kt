package com.example.animalocal.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Resumable (.part + Range), tries each URL in order, verifies SHA-256 when given. */
object Downloader {
    suspend fun download(urls: List<String>, dest: File, sha256: String?, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = File(dest.path + ".part")
            var last: Exception? = null
            var ok = false
            for (u in urls) {
                try { fetch(u, part, onProgress); ok = true; break }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { last = e }
            }
            if (!ok) throw last ?: IOException("no download URL")
            if (sha256 != null) {
                val got = sha256Of(part)
                if (!got.equals(sha256, ignoreCase = true)) {
                    part.delete()
                    throw IOException("SHA-256 mismatch (got ${got.take(12)}…); file discarded")
                }
            }
            if (!part.renameTo(dest)) throw IOException("could not move file into place")
        }

    private suspend fun fetch(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "AnimaLocal/0.1")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            var append = false
            val total: Long = when (code) {
                206 -> {
                    append = true
                    c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (have + c.contentLengthLong)
                }
                200 -> c.contentLengthLong
                416 -> { onProgress(have, have); return }
                else -> throw IOException("HTTP $code from $url")
            }
            var done = if (append) have else 0L
            c.inputStream.use { ins ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastT = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastT > 250_000_000L) { onProgress(done, total); lastT = now }
                    }
                }
            }
            onProgress(done, total)
        } finally {
            c.disconnect()
        }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
