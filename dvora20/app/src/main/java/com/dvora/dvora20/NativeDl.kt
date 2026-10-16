package com.dvora.dvora20

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dvora.dvora20.adblock.Media
import com.dvora.dvora20.adblock.Sniff
import com.dvora.dvora20.adblock.SniffName
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser

/**
 * The native fallback download engine, replicating 1DM's approach:
 *  - direct files → multi-thread ranged download (1DM's aspsine multithread
 *    pattern — parallel block writes into one pre-sized file, no merge pass,
 *    206-verified so a Range-ignoring server falls back to a single stream)
 *  - HLS (m3u8) → master playlist → highest-BANDWIDTH variant → every segment
 *    fetched in order with retries, AES-128-CBC decrypted when the playlist
 *    carries #EXT-X-KEY, appended into one container
 *  - ts/aac outputs get remuxed to mp4/m4a by [MediaRemux] — 1DM's converter
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
        cached: Map<String, String>? = null,
        isCancelled: () -> Boolean = { false },
        onPct: (Int) -> Unit
    ): File? {
        val clean = url.substringBefore('#')
        if (isCancelled()) return null
        val playlist = clean.substringBefore('?').endsWith(".m3u8", true) || Media.isM3U8(clean)
        val dash = !playlist && Media.isMPD(clean)
        return when {
            dash -> mpd(clean, dir, name, referer, ua, cookie, cached, isCancelled, onPct)
                ?: direct(clean, dir, name, referer, ua, cookie, cached, isCancelled, onPct)   // mpd-shaped but not parseable — try it as a file
            playlist -> hls(clean, dir, name, referer, ua, cookie, cached, isCancelled, onPct)
                ?: direct(clean, dir, name, referer, ua, cookie, cached, isCancelled, onPct)  // playlist-shaped but not one — try it as a file
            else -> direct(clean, dir, name, referer, ua, cookie, cached, isCancelled, onPct)
        }
    }

    // ── shared ────────────────────────────────────────────────────────────────

    /**
     * 1DM's engine stack: OkHttp — HTTP/2 + a modern TLS fingerprint. Gateways that
     * 403/502 HttpURLConnection and python-urllib requests accept OkHttp traffic,
     * which is exactly why 1DM's engine gets through where others don't.
     */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)          // http/2 comes automatically over TLS
        .build()

    /** the page's exact request headers (urlHeadersCache) + our Range/UA/Referer/Cookie. */
    private fun build(
        url: String, referer: String?, ua: String?, cookie: String?,
        range: String?, cached: Map<String, String>?
    ): Request {
        val b = Request.Builder().url(url)
        ua?.let { b.header("User-Agent", it) }
        referer?.let { b.header("Referer", it) }
        cookie?.let { b.header("Cookie", it) }
        // the page's EXACT request headers OVERRIDE our guesses — the cached Referer/Cookie/
        // UA are what the server actually accepted (hotlink gates check exactly these).
        // Accept-Encoding stays out — OkHttp negotiates its own and transparently unzips.
        cached?.forEach { (k, v) ->
            if (!k.equals("Range", true) && !k.equals("Host", true) &&
                !k.equals("Accept-Encoding", true) && !k.equals("Content-Length", true) &&
                v.isNotEmpty()
            ) b.header(k, v)
        }
        range?.let { b.header("Range", it) }     // ours always wins the range
        return b.build()
    }

    private fun call(
        url: String, referer: String?, ua: String?, cookie: String?,
        range: String?, cached: Map<String, String>?
    ): Response? = runCatching { http.newCall(build(url, referer, ua, cookie, range, cached)).execute() }.getOrNull()

    private fun fetchBytes(
        url: String, referer: String?, ua: String?, cookie: String?,
        cached: Map<String, String>?, cap: Int = 64 shl 20
    ): ByteArray? {
        val r = call(url, referer, ua, cookie, null, cached) ?: return null
        try {
            if (r.code !in 200..299) return null
            val body = r.body?.byteStream() ?: return null
            val out = java.io.ByteArrayOutputStream()
            body.use { ins ->
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
            r.close()
        }
    }

    private fun fetchText(url: String, referer: String?, ua: String?, cookie: String?, cached: Map<String, String>?): String? =
        fetchBytes(url, referer, ua, cookie, cached, 2 shl 20)?.toString(Charsets.UTF_8)

    /** sanitized base name + a real extension from the url path / mime. */
    private fun outName(url: String, mime: String, name: String?, fallbackExt: String): String {
        // '%' must die too — yt-dlp treats it as a template char
        val base = (name ?: SniffName.resolve(url, mime, null)?.substringBeforeLast('.'))
            ?.replace(Regex("[\\\\/:*?\"<>|%]"), "_")?.trim()?.take(120)?.takeIf { it.isNotBlank() }
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
        referer: String?, ua: String?, cookie: String?, cached: Map<String, String>?,
        isCancelled: () -> Boolean, onPct: (Int) -> Unit
    ): File? {
        // ranged header probe — total size, range support, real mime. A 403 here = the
        // hotlink gate: retry with the media's OWN host as referer (proven on nginx CDNs)
        if (isCancelled()) return null
        var effReferer = referer
        var head = call(url, referer, ua, cookie, "bytes=0-0", cached)
        if (head == null || head.code == 403) {
            val alt = Sniff.sameHostReferer(url)
            if (alt != null && !alt.equals(referer, true)) {
                head?.close()
                head = call(url, alt, ua, cookie, "bytes=0-0", cached)
                if (head != null && head.code in 200..299) effReferer = alt
            }
        }
        val headR = head ?: return null
        var size = -1L
        var mime = ""
        var ranges = false
        try {
            if (headR.code !in 200..299) return null
            mime = (headR.header("Content-Type") ?: "").substringBefore(';').trim()
            size = headR.header("Content-Range")?.let {
                Regex("""bytes\s+\S+/(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toLongOrNull()
            } ?: headR.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            ranges = headR.code == 206 || (headR.header("Accept-Ranges") ?: "").contains("bytes", true)
        } finally {
            headR.close()
        }
        val fileName = outName(url, mime, name, "bin")
        val out = File(dir, fileName)
        val part = File(dir, "$fileName.dvoraPart")
        val written = AtomicLong(0)
        try {
            part.delete()
            if (ranges && size >= PARALLEL_MIN && parallel(url, part, size, effReferer, ua, cookie, cached, isCancelled, written, onPct)) {
                return finish(rename(part, out))
            }
            // Range-ignoring server or small file — one stream, verified written bytes
            part.delete()
            written.set(0)
            if (!single(url, part, size, effReferer, ua, cookie, cached, isCancelled, written, onPct)) {
                part.delete()
                return null
            }
            return finish(rename(part, out))
        } catch (_: Exception) {
            part.delete()
            return null
        }
    }

    /** a finished file — ts/aac get remuxed to mp4/m4a (1DM's converter). */
    private fun finish(f: File): File {
        val ext = f.extension.lowercase()
        if (ext == "ts" || ext == "aac") {
            val out = File(f.parentFile ?: return f, f.nameWithoutExtension + if (ext == "aac") ".m4a" else ".mp4")
            MediaRemux.toMp4(f, out)?.let { return it }        // remux failure keeps the playable original
        }
        return f
    }

    /**
     * 4 parallel ranged blocks into one pre-sized file. TRUE only when every block
     * answered 206 and the byte count matches — a 200 means the server ignored the
     * Range and would stream the whole file into one block's window (corruption).
     */
    private fun parallel(
        url: String, part: File, size: Long,
        referer: String?, ua: String?, cookie: String?, cached: Map<String, String>?,
        isCancelled: () -> Boolean, written: AtomicLong, onPct: (Int) -> Unit
    ): Boolean {
        try {
            RandomAccessFile(part, "rw").use { it.setLength(size) }
            val block = size / THREADS
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                val jobs = (0 until THREADS).map { i ->
                    val start = i * block
                    val end = if (i == THREADS - 1) size - 1 else start + block - 1
                    pool.submit {
                        val conn = call(url, referer, ua, cookie, "bytes=$start-$end", cached) ?: return@submit
                        try {
                            if (conn.code != 206) return@submit
                            val body = conn.body?.byteStream() ?: return@submit
                            RandomAccessFile(part, "rw").use { raf ->
                                raf.seek(start)
                                body.use { ins ->
                                    val buf = ByteArray(16384)
                                    while (true) {
                                        if (isCancelled()) return@submit
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
                            conn.close()
                        }
                    }
                }
                jobs.forEach { it.get() }
            } finally {
                pool.shutdown()
            }
            return part.length() == size
        } catch (_: Exception) {
            return false
        }
    }

    private fun single(
        url: String, part: File, size: Long,
        referer: String?, ua: String?, cookie: String?, cached: Map<String, String>?,
        isCancelled: () -> Boolean, written: AtomicLong, onPct: (Int) -> Unit
    ): Boolean {
        val conn = call(url, referer, ua, cookie, null, cached) ?: return false
        try {
            if (conn.code !in 200..299) return false
            val body = conn.body?.byteStream() ?: return false
            FileOutputStream(part).use { fos ->
                body.use { ins ->
                    val buf = ByteArray(16384)
                    while (true) {
                        if (isCancelled()) return false
                        val n = ins.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        written.addAndGet(n.toLong())
                        if (size > 0) onPct(((written.get() * 99) / size).toInt().coerceIn(0, 99))
                    }
                }
            }
            return written.get() > 0
        } catch (_: Exception) {
            return false
        } finally {
            conn.close()
        }
    }

    // ── HLS — playlist walk + AES-128 segment assembly (1DM's approach) ───────

    private fun hls(
        url: String, dir: File, name: String?,
        referer: String?, ua: String?, cookie: String?, cached: Map<String, String>?,
        isCancelled: () -> Boolean, onPct: (Int) -> Unit
    ): File? {
        // hotlink-403 gate: CDNs like this one only serve requests whose referer is the
        // MEDIA HOST ITSELF — retry with it once and keep whichever referer worked
        if (isCancelled()) return null
        var ref = referer
        fun text(u: String): String? {
            if (isCancelled()) return null
            fetchText(u, ref, ua, cookie, cached)?.let { return it }
            val alt = Sniff.sameHostReferer(u) ?: return null
            if (alt.equals(ref, true)) return null
            return fetchText(u, alt, ua, cookie, cached)?.also { ref = alt }
        }
        var body = text(url) ?: return null
        var base = url
        // master playlist → the variant with the highest BANDWIDTH (1DM picks the best stream)
        if (body.contains("#EXT-X-STREAM-INF", true)) {
            var best: Pair<Long, String>? = null
            val lines = body.lines()
            for (i in lines.indices) {
                val ln = lines[i]
                // only variant tags — the i-frame cousin (#EXT-X-I-FRAME-STREAM-INF) never counts
                if (!ln.startsWith("#EXT-X-STREAM-INF", true)) continue
                val bw = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(ln)?.groupValues?.get(1)?.toLongOrNull()
                    ?: Regex("""AVERAGE-BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(ln)?.groupValues?.get(1)?.toLongOrNull()
                    ?: 0L
                // the variant url = the next NON-COMMENT line after the tag
                var j = i + 1
                var child: String? = null
                while (j < lines.size) {
                    val l = lines[j].trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) { child = l; break }
                    j++
                }
                if (child == null) continue
                if (best == null || bw > best.first) best = bw to Sniff.resolveUrl(base, child)
            }
            val v = best ?: return null
            base = v.second
            body = text(base) ?: return null
        }
        // media playlist → segments + AES key/iv + fMP4 init section
        val segments = ArrayList<String>()
        var keyUrl: String? = null
        var keyIv: String? = null
        var mapUrl: String? = null
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
                t.startsWith("#EXT-X-MAP:", true) ->          // fMP4 init section — must precede every segment
                    mapUrl = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
                        ?.let { Sniff.resolveUrl(base, it) }
                t.isNotEmpty() && !t.startsWith("#") -> segments.add(Sniff.resolveUrl(base, t))
            }
        }
        if (segments.isEmpty()) return null
        val keyBytes = keyUrl?.let { fetchBytes(it, ref, ua, cookie, cached, 64) }?.takeIf { it.size == 16 }
        val fileName = outName(url, "application/vnd.apple.mpegurl", name, "ts")
        val out = File(dir, fileName)
        val part = File(dir, "$fileName.dvoraPart")
        part.delete()
        try {
            FileOutputStream(part).use { fos ->
                // fMP4 streams: the init section goes in front, unencrypted (HLS spec)
                if (mapUrl != null) {
                    fetchBytes(mapUrl, ref, ua, cookie, cached)?.let { fos.write(it) }
                    onPct(1)
                }
                for ((i, seg) in segments.withIndex()) {
                    if (isCancelled()) throw Exception("cancelled")
                    // CDNs flake under bursts — 1DM retries segments, so does this
                    var bytes: ByteArray? = null
                    for (attempt in 1..3) {
                        bytes = fetchBytes(seg, ref, ua, cookie, cached)
                        if (bytes != null) break
                    }
                    if (bytes == null) throw Exception("segment $i failed after 3 attempts")
                    var data = bytes
                    if (keyBytes != null) {
                        // explicit playlist IV, else the segment's media-sequence number (HLS spec default)
                        val iv = keyIv?.let { hexToBytes(it) } ?: seqIv(seq + i)
                        data = decrypt(keyBytes, iv, data)
                    }
                    fos.write(data)
                    onPct((((i + 1) * 99) / segments.size).coerceIn(0, 99))
                }
            }
            // the assembled .ts converts to .mp4 (1DM's ts→mp4 feature) — remux
            // failure keeps the playable .ts
            val merged = rename(part, out)
            val mp4 = File(dir, merged.nameWithoutExtension + ".mp4")
            return MediaRemux.toMp4(merged, mp4) ?: merged
        } catch (_: Exception) {
            part.delete()
            return null
        }
    }

    // ── DASH (mpd) — 1DM downloads these natively, so does this ─────────────────

    /** one representation of one adaptation set (SegmentTemplate / SegmentList). */
    private class Rep(
        val isVideo: Boolean, val id: String, val bw: Long, var base: String,
        var init: String?, var media: String?, var timescale: Long, var dur: Long,
        var startNumber: Long, var timeline: List<Pair<Long, Long>>, var listUrls: List<String>
    )

    private fun mpd(
        url: String, dir: File, name: String?,
        referer: String?, ua: String?, cookie: String?, cached: Map<String, String>?,
        isCancelled: () -> Boolean, onPct: (Int) -> Unit
    ): File? {
        if (isCancelled()) return null
        // same hotlink gate as hls — retry with the media's own host as referer, keep the winner
        var ref = referer
        val xml = fetchText(url, ref, ua, cookie, cached)
            ?: Sniff.sameHostReferer(url)?.takeIf { !it.equals(ref, true) }?.let { alt ->
                fetchText(url, alt, ua, cookie, cached)?.also { ref = alt }
            }
            ?: return null
        val reps = parseMpd(xml, url) ?: return null
        // best video + best audio representation — 1DM's stream picker
        val chosen = listOfNotNull(
            reps.filter { it.isVideo }.maxByOrNull { it.bw },
            reps.filter { !it.isVideo }.maxByOrNull { it.bw }
        )
        if (chosen.isEmpty()) return null
        val durMs = Regex("""mediaPresentationDuration\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.let { isoToMs(it) } ?: -1L
        val fileName = outName(url, "application/dash+xml", name, "mp4")
            .replaceFirst(Regex("(?i)\\.mpd$"), ".mp4")
        val out = File(dir, fileName)
        val parts = ArrayList<File>()
        try {
            val segs = chosen.map { it to segmentUrls(it, durMs) }
            if (segs.any { it.second.isEmpty() }) throw Exception("no dash segments")
            val total = (segs.sumOf { it.second.size } + chosen.size).toLong()
            var done = 0L
            for ((rep, urls) in segs) {
                val part = File(dir, "$fileName.${if (rep.isVideo) "video" else "audio"}.dvoraPart")
                part.delete()
                parts.add(part)
                FileOutputStream(part).use { fos ->
                    // fMP4 init section in front of every media segment
                    rep.init?.let { t ->
                        fetchBytes(subTemplate(t, rep, 0, 0), ref, ua, cookie, cached)?.let { fos.write(it) }
                        done++
                        onPct(((done * 99) / total).toInt().coerceIn(0, 99))
                    }
                    for (su in urls) {
                        if (isCancelled()) throw Exception("cancelled")
                        var bytes: ByteArray? = null
                        for (attempt in 1..3) {                       // same retry discipline as HLS
                            bytes = fetchBytes(su, ref, ua, cookie, cached)
                            if (bytes != null) break
                        }
                        if (bytes == null) throw Exception("dash segment failed")
                        fos.write(bytes)
                        done++
                        onPct(((done * 99) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            return when (parts.size) {
                1 -> MediaRemux.toMp4(parts[0], out) ?: rename(parts[0], out)   // remux failed → raw fMP4 still plays
                else -> MediaRemux.mergeToMp4(parts, out) ?: run {
                    parts.forEach { it.delete() }
                    null
                }
            }
        } catch (_: Exception) {
            parts.forEach { it.delete() }
            return null
        }
    }

    /** mpd xml → representations (adaptation-set scoped template/list state). */
    private fun parseMpd(xml: String, mpdUrl: String): List<Rep>? {
        try {
            val out = ArrayList<Rep>()
            val p = android.util.Xml.newPullParser()
            p.setInput(xml.reader())
            var base = ""
            var adaptVideo = false
            var adaptInit: String? = null
            var adaptMedia: String? = null
            var adaptTs = -1L
            var adaptDur = -1L
            var adaptStart = 1L
            var adaptTimeline = emptyList<Pair<Long, Long>>()
            var adaptList = emptyList<String>()
            var rep: Rep? = null
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                        "baseurl" -> runCatching { p.nextText() }.getOrNull()?.trim()
                            ?.takeIf { it.isNotEmpty() }?.let { base = it }
                        "adaptationset" -> {
                            val m = (p.getAttributeValue(null, "mimeType")
                                ?: p.getAttributeValue(null, "contentType") ?: "").lowercase()
                            adaptVideo = m.startsWith("video")
                            adaptInit = null; adaptMedia = null; adaptTs = -1L; adaptDur = -1L
                            adaptStart = 1L; adaptTimeline = emptyList(); adaptList = emptyList()
                        }
                        "segmenttemplate" -> {
                            val i = p.getAttributeValue(null, "initialization")
                            val m = p.getAttributeValue(null, "media")
                            val ts = p.getAttributeValue(null, "timescale")?.toLongOrNull()
                            val d = p.getAttributeValue(null, "duration")?.toLongOrNull()
                            val sn = p.getAttributeValue(null, "startNumber")?.toLongOrNull()
                            adaptInit = i ?: adaptInit; adaptMedia = m ?: adaptMedia
                            ts?.let { adaptTs = it }; d?.let { adaptDur = it }; sn?.let { adaptStart = it }
                            rep?.let { r ->
                                i?.let { r.init = it }; m?.let { r.media = it }
                                ts?.let { r.timescale = it }; d?.let { r.dur = it }; sn?.let { r.startNumber = it }
                            }
                        }
                        "segmenttimeline" -> {
                            val tl = ArrayList<Pair<Long, Long>>()
                            var next = 0L
                            var ee = p.next()
                            while (ee != XmlPullParser.END_TAG || p.name.lowercase() != "segmenttimeline") {
                                if (ee == XmlPullParser.START_TAG && p.name.lowercase() == "s") {
                                    val t = p.getAttributeValue(null, "t")?.toLongOrNull() ?: next
                                    val d = p.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                                    val r = p.getAttributeValue(null, "r")?.toLongOrNull() ?: 0L
                                    var tt = t
                                    repeat((r + 1).toInt()) { tl.add(tt to d); tt += d }
                                    next = tt
                                }
                                ee = p.next()
                            }
                            adaptTimeline = tl
                            rep?.let { it.timeline = tl }
                        }
                        "segmentlist" -> {
                            p.getAttributeValue(null, "duration")?.toLongOrNull()?.let {
                                adaptDur = it; rep?.let { r -> r.dur = it }
                            }
                            p.getAttributeValue(null, "timescale")?.toLongOrNull()?.let {
                                adaptTs = it; rep?.let { r -> r.timescale = it }
                            }
                            val urls = ArrayList<String>()
                            var initSrc: String? = null
                            var ee = p.next()
                            while (ee != XmlPullParser.END_TAG || p.name.lowercase() != "segmentlist") {
                                if (ee == XmlPullParser.START_TAG) when (p.name.lowercase()) {
                                    "segmenturl" -> p.getAttributeValue(null, "media")?.let { urls.add(it) }
                                    "initialization" -> p.getAttributeValue(null, "sourceURL")?.let { initSrc = it }
                                }
                                ee = p.next()
                            }
                            adaptList = urls
                            rep?.let { r ->
                                r.listUrls = urls
                                initSrc?.let { r.init = it }
                            }
                        }
                        "initialization" -> p.getAttributeValue(null, "sourceURL")?.let {
                            adaptInit = it
                            rep?.let { r -> r.init = it }
                        }
                        "representation" -> {
                            val id = p.getAttributeValue(null, "id") ?: ""
                            val bw = p.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                            val mime = (p.getAttributeValue(null, "mimeType") ?: "").lowercase()
                            rep = Rep(
                                isVideo = if (mime.isNotEmpty()) mime.startsWith("video") else adaptVideo,
                                id = id, bw = bw, base = base,
                                init = adaptInit, media = adaptMedia,
                                timescale = if (adaptTs > 0) adaptTs else 1L,
                                dur = adaptDur, startNumber = adaptStart,
                                timeline = adaptTimeline, listUrls = adaptList
                            )
                        }
                    }
                    XmlPullParser.END_TAG ->
                        if (p.name.lowercase() == "representation" && rep != null) {
                            out.add(rep!!)
                            rep = null
                        }
                }
                e = p.next()
            }
            for (r in out) r.base = if (r.base.isEmpty()) mpdUrl else Sniff.resolveUrl(mpdUrl, r.base)
            return out.ifEmpty { null }
        } catch (_: Exception) {
            return null
        }
    }

    /** template/list → the representation's concrete segment urls. */
    private fun segmentUrls(rep: Rep, mpdDurMs: Long): List<String> {
        if (rep.listUrls.isNotEmpty()) return rep.listUrls.map { Sniff.resolveUrl(rep.base, it) }
        val media = rep.media ?: return emptyList()
        if (rep.timeline.isNotEmpty()) {
            // $Time$-addressed — one url per timeline entry
            return rep.timeline.map { (t, _) -> Sniff.resolveUrl(rep.base, subTemplate(media, rep, 0, t)) }
        }
        if (rep.dur <= 0) return emptyList()
        // $Number$-addressed — duration/timescale grid over the presentation
        val segMs = rep.dur * 1000 / rep.timescale
        if (segMs <= 0) return emptyList()
        val n = if (mpdDurMs > 0) ((mpdDurMs + segMs - 1) / segMs).toInt() else 0
        if (n <= 0 || n > 20000) return emptyList()
        return (0 until n).map { i ->
            Sniff.resolveUrl(rep.base, subTemplate(media, rep, rep.startNumber + i, (rep.startNumber - 1 + i) * rep.dur))
        }
    }

    /** $RepresentationID$ / $Bandwidth$ / $Number$ / $Time$ substitution. */
    private fun subTemplate(t: String, rep: Rep, number: Long, time: Long): String {
        var s = t.replace("\$RepresentationID\$", rep.id, ignoreCase = true)
        s = s.replace("\$Bandwidth\$", rep.bw.toString(), ignoreCase = true)
        // \${'$'} in a raw string = the two chars `\$` — regex for a literal dollar
        s = Regex("""\${'$'}Number(?:%0(\d+)d)?\${'$'}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            val w = m.groupValues[1].toIntOrNull() ?: 0
            if (w > 0) number.toString().padStart(w, '0') else number.toString()
        }
        return Regex("""\${'$'}Time(?:%0(\d+)d)?\${'$'}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            val w = m.groupValues[1].toIntOrNull() ?: 0
            if (w > 0) time.toString().padStart(w, '0') else time.toString()
        }
    }

    /** ISO8601 "PT#H#M#S" → milliseconds. */
    private fun isoToMs(iso: String): Long {
        val m = Regex("""PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:([\d.]+)S)?""", RegexOption.IGNORE_CASE).find(iso)
            ?: return -1
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val mi = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 3600 + mi * 60 + s
        return if (total <= 0) -1 else (total * 1000).toLong()
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

/**
 * 1DM's ts/aac→mp4 conversion, the native way: Android's own MediaExtractor +
 * MediaMuxer copy the h264/aac (or ADTS aac) streams into an mp4/m4a container —
 * no re-encode, no ffmpeg process, seconds even for long files.
 */
internal object MediaRemux {

    fun toMp4(src: File, out: File): File? {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(src.path)
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val tracks = HashMap<Int, Int>()
            for (t in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(t)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) tracks[t] = mux.addTrack(fmt)
            }
            if (tracks.isEmpty()) {
                out.delete()
                return null
            }
            mux.start()
            val buf = ByteBuffer.allocate(2 shl 20)
            val info = MediaCodec.BufferInfo()
            for ((t, mt) in tracks) {
                ex.selectTrack(t)
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, ex.sampleTime, ex.sampleFlags)
                    mux.writeSampleData(mt, buf, info)
                    ex.advance()
                }
                ex.unselectTrack(t)
            }
            mux.stop()
            if (out.length() > 0) {
                src.delete()
                out
            } else {
                out.delete()
                null
            }
        } catch (_: Exception) {
            runCatching { out.delete() }
            null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }

    /**
     * several single-track files (DASH video adaptation + audio adaptation) →
     * ONE interleaved mp4 — samples written in presentation-time order.
     */
    fun mergeToMp4(parts: List<File>, out: File): File? {
        if (parts.isEmpty()) return null
        var muxer: MediaMuxer? = null
        val extractors = ArrayList<MediaExtractor>()
        return try {
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val srcs = ArrayList<Src>()
            for (f in parts) {
                val ex = MediaExtractor().also { extractors.add(it) }
                ex.setDataSource(f.path)
                var added = false          // one track per adaptation file
                for (t in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(t)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if ((mime.startsWith("video/") || mime.startsWith("audio/")) && !added) {
                        ex.selectTrack(t)
                        srcs.add(Src(ex, mux.addTrack(fmt)))
                        added = true
                    }
                }
            }
            if (srcs.isEmpty()) {
                out.delete()
                return null
            }
            mux.start()
            srcs.forEach { it.load() }
            val info = MediaCodec.BufferInfo()
            while (true) {
                // the earliest pending sample across tracks keeps the streams interleaved
                val s = srcs.filter { !it.done }.minByOrNull { it.pts } ?: break
                info.set(0, s.size, s.pts, s.flags)
                mux.writeSampleData(s.track, s.buf, info)
                s.advance()
                s.load()
            }
            mux.stop()
            if (out.length() > 0) {
                parts.forEach { it.delete() }
                out
            } else {
                out.delete()
                null
            }
        } catch (_: Exception) {
            runCatching { out.delete() }
            null
        } finally {
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    /** one source stream — one buffered pending sample at a time. */
    private class Src(val ex: MediaExtractor, val track: Int) {
        val buf = ByteBuffer.allocate(2 shl 20)
        var size = -1
        var pts = Long.MAX_VALUE
        var flags = 0
        var done = false

        fun load() {
            if (done) return
            val n = ex.readSampleData(buf, 0)
            if (n < 0) {
                done = true
                pts = Long.MAX_VALUE
                return
            }
            size = n
            pts = ex.sampleTime
            flags = ex.sampleFlags
        }

        fun advance() {
            ex.advance()
        }
    }
}
