package com.dvora.dvora20

import com.dvora.dvora20.adblock.Media
import com.dvora.dvora20.adblock.Sniff
import com.dvora.dvora20.adblock.SniffName
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The native fallback download engine, replicating 1DM's approach:
 *  - direct files → multi-thread ranged download (1DM's aspsine multithread
 *    pattern — parallel block writes into one pre-sized file, no merge pass)
 *  - HLS (m3u8) → master playlist → highest-BANDWIDTH variant → every segment
 *    fetched in order, AES-128-CBC decrypted when the playlist carries
 *    #EXT-X-KEY, appended into one .ts container
 * Runs only when yt-dlp already failed — unsupported page, broken extractor,
 * geo-block. DASH (mpd) stays yt-dlp-only: native DASH assembly is out of scope.
 */
object NativeDl {

    private const val THREADS = 4
    private const val PARALLEL_MIN = 8L shl 20     // only parallelize files ≥ 8MB

    /** one attempt — the saved file, or null when every path failed. [onPct] gets 0..99. */
    fun download(
        url: String, dir: File, name: String?,
        referer: String?, ua: String?, cookie: String?,
        onPct: (Int) -> Unit
    ): File? {
        val clean = url.substringBefore('#')
        val playlist = clean.substringBefore('?').endsWith(".m3u8", true) || Media.isM3U8(clean)
        if (!playlist && Media.isMPD(clean)) return null          // DASH — yt-dlp territory
        return if (playlist) hls(clean, dir, name, referer, ua, cookie, onPct)
        else direct(clean, dir, name, referer, ua, cookie, onPct)
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private fun open(url: String, referer: String?, ua: String?, cookie: String?, range: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            range?.let { setRequestProperty("Range", it) }
            referer?.let { setRequestProperty("Referer", it) }
            ua?.let { setRequestProperty("User-Agent", it) }
            cookie?.let { setRequestProperty("Cookie", it) }
        }

    private fun fetchBytes(url: String, referer: String?, ua: String?, cookie: String?, cap: Int = 64 shl 20): ByteArray? {
        val conn = open(url, referer, ua, cookie)
        try {
            if (conn.responseCode !in 200..299) return null
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (out.size() < cap) {
                    val n = ins.read(buf, 0, minOf(buf.size, cap - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String, referer: String?, ua: String?, cookie: String?): String? =
        fetchBytes(url, referer, ua, cookie, 2 shl 20)?.toString(Charsets.UTF_8)

    /** sanitized base name + a real extension from the url path / mime. */
    private fun outName(url: String, mime: String, name: String?, fallbackExt: String): String {
        val base = (name ?: SniffName.resolve(url, mime, null)?.substringBeforeLast('.'))
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()?.take(120)?.takeIf { it.isNotBlank() }
        val path = url.substringBefore('?').substringBefore('#')
        val urlExt = path.substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        val ext = urlExt ?: Sniff.extForMime(mime)?.removePrefix(".") ?: fallbackExt
        return "${base ?: "dvora-media"}.$ext"
    }

    private fun rename(part: File, out: File): File {
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) {
            part.copyTo(out, overwrite = true)
            part.delete()
        }
        return out
    }

    // ── direct files — 1DM's aspsine multithread pattern ───────────────────────

    private fun direct(
        url: String, dir: File, name: String?,
        referer: String?, ua: String?, cookie: String?,
        onPct: (Int) -> Unit
    ): File? {
        // ranged header probe — total size, range support, real mime
        val head = open(url, referer, ua, cookie, "bytes=0-0")
        var size = -1L
        var mime = ""
        var ranges = false
        try {
            if (head.responseCode !in 200..299) return null
            mime = (head.contentType ?: "").substringBefore(';').trim()
            size = head.getHeaderField("Content-Range")?.let {
                Regex("""bytes\s+\S+/(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toLongOrNull()
            } ?: head.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            ranges = head.responseCode == 206 || (head.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)
        } finally {
            head.disconnect()
        }
        val fileName = outName(url, mime, name, "bin")
        val out = File(dir, fileName)
        val part = File(dir, "$fileName.dvoraPart")
        part.delete()
        val written = AtomicLong(0)
        try {
            if (ranges && size >= PARALLEL_MIN) {
                // pre-size the file; every thread writes its own block window — no merge pass
                RandomAccessFile(part, "rw").use { it.setLength(size) }
                val block = size / THREADS
                val pool = Executors.newFixedThreadPool(THREADS)
                try {
                    val jobs = (0 until THREADS).map { i ->
                        val start = i * block
                        val end = if (i == THREADS - 1) size - 1 else start + block - 1
                        pool.submit {
                            val conn = open(url, referer, ua, cookie, "bytes=$start-$end")
                            try {
                                if (conn.responseCode !in 200..299) return@submit
                                RandomAccessFile(part, "rw").use { raf ->
                                    raf.seek(start)
                                    conn.inputStream.use { ins ->
                                        val buf = ByteArray(16384)
                                        while (true) {
                                            val n = ins.read(buf)
                                            if (n < 0) break
                                            raf.write(buf, 0, n)
                                            written.addAndGet(n.toLong())
                                            onPct(((written.get() * 99) / size).toInt().coerceIn(0, 99))
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                conn.disconnect()
                            }
                        }
                    }
                    jobs.forEach { it.get() }
                } finally {
                    pool.shutdown()
                }
                // a partial parallel download is a corrupt file — verify the byte count
                if (part.length() != size) {
                    part.delete()
                    return null
                }
                return rename(part, out)
            }
            // single stream (server ignores ranges / small file)
            val conn = open(url, referer, ua, cookie)
            try {
                if (conn.responseCode !in 200..299) return null
                FileOutputStream(part).use { fos ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            written.addAndGet(n.toLong())
                            if (size > 0) onPct(((written.get() * 99) / size).toInt().coerceIn(0, 99))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (written.get() == 0L) {
                part.delete()
                return null
            }
            return rename(part, out)
        } catch (_: Exception) {
            part.delete()
            return null
        }
    }

    // ── HLS — playlist walk + AES-128 segment assembly (1DM's approach) ───────

    private fun hls(
        url: String, dir: File, name: String?,
        referer: String?, ua: String?, cookie: String?,
        onPct: (Int) -> Unit
    ): File? {
        var body = fetchText(url, referer, ua, cookie) ?: return null
        var base = url
        // master playlist → the variant with the highest BANDWIDTH (1DM picks the best stream)
        if (body.contains("#EXT-X-STREAM-INF", true)) {
            var best: Pair<Long, String>? = null
            val lines = body.lines()
            for (i in lines.indices) {
                val bw = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: continue
                val child = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                if (best == null || bw > best.first) best = bw to Sniff.resolveUrl(base, child)
            }
            val v = best ?: return null
            base = v.second
            body = fetchText(base, referer, ua, cookie) ?: return null
        }
        // media playlist → segments + AES key/iv
        val segments = ArrayList<String>()
        var keyUrl: String? = null
        var keyIv: String? = null
        var seq = 0L
        for (line in body.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) ->
                    seq = t.substringAfter(':').trim().toLongOrNull() ?: 0L
                t.startsWith("#EXT-X-KEY:", true) && t.contains("AES-128", true) -> {
                    keyUrl = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                        ?.let { Sniff.resolveUrl(base, it) }
                    keyIv = Regex("""IV=0x([0-9a-fA-F]+)""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                }
                t.isNotEmpty() && !t.startsWith("#") -> segments.add(Sniff.resolveUrl(base, t))
            }
        }
        if (segments.isEmpty()) return null
        val keyBytes = keyUrl?.let { fetchBytes(it, referer, ua, cookie, 64) }?.takeIf { it.size == 16 }
        val fileName = outName(url, "application/vnd.apple.mpegurl", name, "ts")
        val out = File(dir, fileName)
        val part = File(dir, "$fileName.dvoraPart")
        part.delete()
        try {
            FileOutputStream(part).use { fos ->
                for ((i, seg) in segments.withIndex()) {
                    var bytes = fetchBytes(seg, referer, ua, cookie) ?: throw Exception("segment $i failed")
                    if (keyBytes != null) {
                        // explicit playlist IV, else the segment's media-sequence number (HLS spec default)
                        val iv = keyIv?.let { hexToBytes(it) } ?: seqIv(seq + i)
                        bytes = decrypt(keyBytes, iv, bytes)
                    }
                    fos.write(bytes)
                    onPct((((i + 1) * 99) / segments.size).coerceIn(0, 99))
                }
            }
            return rename(part, out)
        } catch (_: Exception) {
            part.delete()
            return null
        }
    }

    /** HLS AES-128 = AES/CBC with PKCS7 padding (PKCS5 on Android — same thing). */
    private fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /** the segment's media sequence number as a 16-byte big-endian IV (HLS spec default). */
    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = ((seq shr (8 * i)) and 0xFF).toByte()
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = if (hex.length % 2 == 1) "0$hex" else hex
        var out = ByteArray(h.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(h[i * 2], 16) shl 4) or Character.digit(h[i * 2 + 1], 16)).toByte()
        }
        if (out.size > 16) out = out.copyOfRange(out.size - 16, out.size)
        if (out.size < 16) out = out + ByteArray(16 - out.size)
        return out
    }
}
