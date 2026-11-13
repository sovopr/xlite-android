package com.sovopr.xlite

import android.content.Context
import android.webkit.WebResourceResponse
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP/2-backed local Range streaming proxy and look-ahead head-chunk cache for X videos.
 * Prebuffers the opening MP4 segment (moov atom + initial frames) onto disk so scroll-to-play
 * begins immediately, then streams tail bytes over OkHttp's warm HTTP/2 connection pool.
 */
internal class VideoStreamProxy(private val context: Context) {

    companion object {
        private const val HEAD_PREFETCH_BYTES = 768L * 1024L
        private const val MAX_DISK_VIDEO_CACHE_BYTES = 64L * 1024L * 1024L

        private val videoClient: OkHttpClient = TwitterBridge.sharedBaseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        private val prefetchExecutor = Executors.newFixedThreadPool(2)
        private val cacheTrimExecutor = Executors.newSingleThreadExecutor()
        private val inFlightPrefetches = ConcurrentHashMap<String, Boolean>()
        private val cacheWriteCount = AtomicInteger(0)

        private val BASE_HEADERS = mapOf(
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=86400"
        )

        @Volatile private var sharedInstance: VideoStreamProxy? = null

        fun get(context: Context): VideoStreamProxy {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: VideoStreamProxy(context.applicationContext).also { sharedInstance = it }
            }
        }
    }

    private val videoCacheDir = File(context.cacheDir, "videos").apply {
        if (!exists()) mkdirs()
    }

    private data class CachedMeta(val totalLength: Long, val contentType: String)

    fun prefetchHead(rawUrl: String) {
        if (!XLitePolicy.isAllowedVideoUrl(rawUrl)) return
        val hash = XLitePolicy.sha256Hex(rawUrl.toByteArray())
        val metaFile = File(videoCacheDir, "$hash.meta")
        val headFile = File(videoCacheDir, "$hash.head")
        if (metaFile.exists() && headFile.exists() && headFile.length() > 0L) return
        if (inFlightPrefetches.putIfAbsent(hash, true) != null) return

        prefetchExecutor.execute {
            val headTmp = File(videoCacheDir, "$hash.head.tmp")
            val metaTmp = File(videoCacheDir, "$hash.meta.tmp")
            try {
                val request = Request.Builder()
                    .url(rawUrl)
                    .header("User-Agent", TwitterBridge.USER_AGENT)
                    .header("Range", "bytes=0-${HEAD_PREFETCH_BYTES - 1L}")
                    .build()
                videoClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) return@execute
                    val body = resp.body ?: return@execute
                    val contentType = sanitizeVideoMime(resp.header("Content-Type"))
                    val totalLen = XLitePolicy.parseTotalLengthFromContentRange(resp.header("Content-Range"))
                        ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L && resp.code == 200 }
                        ?: return@execute

                    FileOutputStream(headTmp).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            var written = 0L
                            while (written < HEAD_PREFETCH_BYTES) {
                                val read = input.read(buf)
                                if (read == -1) break
                                val toWrite = minOf(read.toLong(), HEAD_PREFETCH_BYTES - written).toInt()
                                out.write(buf, 0, toWrite)
                                written += toWrite
                            }
                        }
                    }
                    if (!headTmp.exists() || headTmp.length() == 0L) return@execute
                    metaTmp.writeText("$totalLen\n$contentType", Charsets.UTF_8)
                    if (!headTmp.renameTo(headFile)) {
                        headTmp.copyTo(headFile, overwrite = true)
                    }
                    if (!metaTmp.renameTo(metaFile)) {
                        metaTmp.copyTo(metaFile, overwrite = true)
                    }
                    if (cacheWriteCount.incrementAndGet() % 10 == 0) {
                        cacheTrimExecutor.execute { trimCacheIfNeeded() }
                    }
                }
            } catch (_: Exception) {
            } finally {
                headTmp.delete()
                metaTmp.delete()
                inFlightPrefetches.remove(hash)
            }
        }
    }

    fun handleRequest(rawUrl: String, rangeHeader: String?): WebResourceResponse {
        if (!XLitePolicy.isAllowedVideoUrl(rawUrl)) {
            return errorResponse(403, "Blocked video URL")
        }
        val hash = XLitePolicy.sha256Hex(rawUrl.toByteArray())
        val headFile = File(videoCacheDir, "$hash.head")
        val meta = readMeta(File(videoCacheDir, "$hash.meta"))
        val byteRange = XLitePolicy.parseByteRange(rangeHeader)

        if (meta != null && headFile.exists()) {
            val headLen = headFile.length()
            val totalLen = meta.totalLength
            if (headLen > 0L && totalLen > 0L && (rangeHeader.isNullOrBlank() || byteRange != null)) {
                val start = byteRange?.start ?: 0L
                val end = byteRange?.end
                if (start in 0 until headLen) {
                    val effectiveEnd = when {
                        end != null -> minOf(end, totalLen - 1L)
                        else -> totalLen - 1L
                    }
                    if (effectiveEnd < headLen) {
                        val len = effectiveEnd - start + 1L
                        val fis = FileInputStream(headFile)
                        skipFully(fis, start)
                        val stream = BoundedInputStream(fis, len)
                        return rangeResponse(
                            code = if (byteRange != null) 206 else 200,
                            mime = meta.contentType,
                            start = start,
                            end = effectiveEnd,
                            total = totalLen,
                            hasRangeHeader = byteRange != null,
                            stream = stream
                        )
                    } else if (end == null || effectiveEnd >= headLen) {
                        val stream = HeadWithNetworkTailInputStream(
                            headFile = headFile,
                            startOffset = start,
                            headLen = headLen,
                            endOffset = effectiveEnd,
                            targetUrl = rawUrl,
                            client = videoClient
                        )
                        return rangeResponse(
                            code = if (byteRange != null) 206 else 200,
                            mime = meta.contentType,
                            start = start,
                            end = effectiveEnd,
                            total = totalLen,
                            hasRangeHeader = byteRange != null,
                            stream = stream
                        )
                    }
                }
            }
        }

        // Direct HTTP/2 proxy over warm shared OkHttp pool when uncached or seeking past head.
        return try {
            val reqBuilder = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", TwitterBridge.USER_AGENT)
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }
            val call = videoClient.newCall(reqBuilder.build())
            val resp = call.execute()
            if (!resp.isSuccessful && resp.code != 206) {
                val code = resp.code.takeIf { it in 400..599 } ?: 502
                resp.close()
                return errorResponse(code, "Upstream error")
            }
            val body = resp.body
            if (body == null) {
                resp.close()
                return errorResponse(502, "Empty upstream body")
            }
            val headers = HashMap(BASE_HEADERS)
            resp.header("Content-Range")?.let { headers["Content-Range"] = it }
            resp.header("Content-Length")?.let { headers["Content-Length"] = it }
            val mime = sanitizeVideoMime(resp.header("Content-Type"))
            WebResourceResponse(
                mime,
                null,
                resp.code,
                resp.message.ifBlank { "OK" },
                headers,
                UpstreamResponseStream(call, resp, body.byteStream())
            )
        } catch (_: Exception) {
            errorResponse(504, "Video request failed")
        }
    }

    private fun readMeta(metaFile: File): CachedMeta? {
        return try {
            if (!metaFile.exists()) return null
            val lines = metaFile.readLines(Charsets.UTF_8)
            val totalLen = lines.getOrNull(0)?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val mime = sanitizeVideoMime(lines.getOrNull(1))
            CachedMeta(totalLen, mime)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeVideoMime(raw: String?): String {
        val clean = raw?.substringBefore(';')?.trim()?.lowercase() ?: ""
        return if (clean in setOf("video/mp4", "video/webm", "application/mp4")) clean else "video/mp4"
    }

    private fun rangeResponse(
        code: Int,
        mime: String,
        start: Long,
        end: Long,
        total: Long,
        hasRangeHeader: Boolean,
        stream: InputStream
    ): WebResourceResponse {
        val headers = HashMap(BASE_HEADERS)
        val length = (end - start + 1L).coerceAtLeast(0L)
        headers["Content-Length"] = length.toString()
        if (hasRangeHeader) {
            headers["Content-Range"] = "bytes $start-$end/$total"
        }
        val reason = if (code == 206) "Partial Content" else "OK"
        return WebResourceResponse(mime, null, code, reason, headers, stream)
    }

    private fun errorResponse(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "video/mp4",
            "UTF-8",
            code,
            reason,
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) break
                remaining -= 1L
            } else {
                remaining -= skipped
            }
        }
    }

    private fun trimCacheIfNeeded() {
        try {
            val files = videoCacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_DISK_VIDEO_CACHE_BYTES) return
            val target = (MAX_DISK_VIDEO_CACHE_BYTES * 7L) / 10L
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= target) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {}
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b != -1) remaining -= 1L
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n.toLong()
            return n
        }

        override fun close() {
            delegate.close()
        }
    }

    private class HeadWithNetworkTailInputStream(
        headFile: File,
        startOffset: Long,
        private val headLen: Long,
        private val endOffset: Long,
        private val targetUrl: String,
        private val client: OkHttpClient
    ) : InputStream() {
        private var headStream: InputStream? = FileInputStream(headFile).also { fis ->
            var skip = startOffset
            while (skip > 0L) {
                val n = fis.skip(skip)
                if (n <= 0L) {
                    if (fis.read() == -1) break
                    skip -= 1L
                } else {
                    skip -= n
                }
            }
        }
        private var pos: Long = startOffset
        private var tailCall: Call? = null
        private var tailResp: Response? = null
        private var tailStream: InputStream? = null

        private fun ensureTailStream(): InputStream? {
            if (tailStream != null) return tailStream
            if (pos > endOffset) return null
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", TwitterBridge.USER_AGENT)
                .header("Range", "bytes=$pos-$endOffset")
                .build()
            val call = client.newCall(request)
            tailCall = call
            val resp = call.execute()
            if (!resp.isSuccessful && resp.code != 206) {
                resp.close()
                throw IOException("Tail upstream HTTP ${resp.code}")
            }
            val body = resp.body ?: run {
                resp.close()
                throw IOException("Empty tail stream")
            }
            tailResp = resp
            tailStream = body.byteStream()
            return tailStream
        }

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n == -1) -1 else (buf[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos > endOffset) return -1
            if (pos < headLen && headStream != null) {
                val maxFromHead = minOf(len.toLong(), headLen - pos, endOffset - pos + 1L).toInt()
                val n = headStream!!.read(b, off, maxFromHead)
                if (n > 0) {
                    pos += n.toLong()
                    if (pos >= headLen) {
                        try { headStream?.close() } catch (_: Exception) {}
                        headStream = null
                    }
                    return n
                }
                try { headStream?.close() } catch (_: Exception) {}
                headStream = null
            }
            if (pos > endOffset) return -1
            val ts = ensureTailStream() ?: return -1
            val maxFromTail = minOf(len.toLong(), endOffset - pos + 1L).toInt()
            val n = ts.read(b, off, maxFromTail)
            if (n > 0) pos += n.toLong()
            return n
        }

        override fun close() {
            try { headStream?.close() } catch (_: Exception) {}
            headStream = null
            try { tailStream?.close() } catch (_: Exception) {}
            tailStream = null
            try { tailResp?.close() } catch (_: Exception) {}
            tailResp = null
            try { tailCall?.cancel() } catch (_: Exception) {}
            tailCall = null
        }
    }

    private class UpstreamResponseStream(
        private val call: Call,
        private val resp: Response,
        private val delegate: InputStream
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            try { delegate.close() } catch (_: Exception) {}
            try { resp.close() } catch (_: Exception) {}
            try { call.cancel() } catch (_: Exception) {}
        }
    }
}
