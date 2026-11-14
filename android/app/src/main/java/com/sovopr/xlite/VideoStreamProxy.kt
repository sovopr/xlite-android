package com.sovopr.xlite

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Localhost HTTP/1.1 loopback Range streaming proxy and disk head-chunk cache for X videos.
 *
 * Serves progressive MP4 streams over a real 127.0.0.1 TCP socket rather than
 * WebViewClient.shouldInterceptRequest, bypassing Chromium's AndroidStreamReaderURLLoader
 * Range double-skipping bug while preserving HTTP/2 pooling and 1 MB instant head prebuffering.
 */
internal class VideoStreamProxy internal constructor(
    cacheRootDir: File,
    private val customClient: OkHttpClient? = null
) {
    constructor(context: Context) : this(context.cacheDir, null)

    companion object {
        private const val HEAD_PREFETCH_BYTES = 1024L * 1024L
        private const val MAX_DISK_VIDEO_CACHE_BYTES = 64L * 1024L * 1024L
        private const val MAX_LINE_BYTES = 8192
        private const val MAX_HEADERS = 64
        private const val SOCKET_BUFFER_BYTES = 32 * 1024
        private const val SOCKET_HEADER_TIMEOUT_MS = 10_000
        private const val TMP_STALE_MS = 5L * 60L * 1000L

        private val videoClient: OkHttpClient = TwitterBridge.sharedBaseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        private val proxyThreadCounter = AtomicInteger(0)
        private val daemonFactory = ThreadFactory { runnable ->
            Thread(runnable, "xlite-vid-worker-${proxyThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

        private val connectionExecutor = ThreadPoolExecutor(
            16,
            32,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(128),
            daemonFactory
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        private val prefetchExecutor = Executors.newFixedThreadPool(2, daemonFactory)
        private val cacheTrimExecutor = Executors.newSingleThreadExecutor(daemonFactory)
        private val inFlightPrefetches = ConcurrentHashMap<String, Boolean>()
        private val cacheWriteCount = AtomicInteger(0)

        @Volatile
        private var sharedInstance: VideoStreamProxy? = null

        fun get(context: Context): VideoStreamProxy {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: VideoStreamProxy(context.applicationContext).also { sharedInstance = it }
            }
        }
    }

    private val effectiveClient: OkHttpClient
        get() = customClient ?: videoClient

    private val videoCacheDir = File(cacheRootDir, "videos").apply {
        if (!exists()) mkdirs()
    }

    init {
        val initCutoffMs = System.currentTimeMillis() - 1_000L
        cacheTrimExecutor.execute { purgeStaleTmpFiles(initCutoffMs) }
    }

    private val serverLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    private data class CachedMeta(val totalLength: Long, val contentType: String)

    /** Ensures the 127.0.0.1 HTTP/1.1 proxy socket is bound and returns its local port, or 0 on error. */
    fun getProxyPort(): Int {
        val currentSocket = serverSocket
        val currentPort = boundPort
        if (currentSocket != null && !currentSocket.isClosed && currentPort > 0) {
            return currentPort
        }
        return synchronized(serverLock) {
            val recheck = serverSocket
            if (recheck != null && !recheck.isClosed && boundPort > 0) {
                return@synchronized boundPort
            }
            try {
                val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                val socket = ServerSocket(0, 50, loopback)
                val port = socket.localPort
                serverSocket = socket
                boundPort = port
                Thread({ acceptLoop(socket) }, "xlite-vid-accept").apply {
                    isDaemon = true
                    start()
                }
                port
            } catch (_: Exception) {
                boundPort = 0
                serverSocket = null
                0
            }
        }
    }

    internal fun closeForTesting() {
        synchronized(serverLock) {
            closeQuietly(serverSocket)
            serverSocket = null
            boundPort = 0
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            if (client.inetAddress?.isLoopbackAddress != true) {
                closeQuietly(client)
                continue
            }
            try {
                connectionExecutor.execute {
                    serveClientSocket(client)
                }
            } catch (_: RejectedExecutionException) {
                try {
                    BufferedOutputStream(client.getOutputStream()).use { out ->
                        writeErrorResponse(out, 503, "Service Unavailable", includeBody = false)
                    }
                } catch (_: Exception) {
                } finally {
                    closeQuietly(client)
                }
            }
        }
    }

    private fun serveClientSocket(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_HEADER_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.sendBufferSize = 64 * 1024
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER_BYTES)

            val requestLine = readHttpLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            var headerCount = 0
            while (headerCount < MAX_HEADERS) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                headerCount++
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    headers[name] = value
                }
            }

            when (val parsed = XLitePolicy.parseLoopbackVideoRequest(requestLine, headers)) {
                is XLitePolicy.LoopbackParseResult.Preflight -> {
                    writePreflightResponse(output, parsed.allowMethods)
                }
                is XLitePolicy.LoopbackParseResult.Error -> {
                    val sendBody = !requestLine.trimStart().startsWith("HEAD", ignoreCase = true)
                    writeErrorResponse(output, parsed.statusCode, parsed.reason, includeBody = sendBody)
                }
                is XLitePolicy.LoopbackParseResult.Ok -> {
                    socket.soTimeout = 30_000
                    handleVideoHttpRequest(parsed.request, output)
                }
            }
        } catch (_: IOException) {
            // Normal client abort/seek disconnect.
        } catch (_: Exception) {
            // Ensure malformed clients cannot crash daemon workers.
        } finally {
            closeQuietly(socket)
        }
    }

    private fun handleVideoHttpRequest(req: XLitePolicy.LoopbackVideoRequest, out: OutputStream) {
        if (!XLitePolicy.isAllowedVideoUrl(req.targetUrl)) {
            writeErrorResponse(out, 403, "Forbidden", includeBody = req.method != "HEAD")
            return
        }
        val hash = XLitePolicy.sha256Hex(req.targetUrl.toByteArray())
        val meta = readCachedMeta(hash)
        val headFile = File(videoCacheDir, "$hash.head")

        if (meta != null && headFile.exists()) {
            val headLen = headFile.length()
            if (headLen > 0L) {
                when (val resolution = XLitePolicy.resolveCachedByteRange(req.rangeHeader, meta.totalLength)) {
                    is XLitePolicy.RangeResolution.Unsatisfiable -> {
                        val headerText = buildString {
                            append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                            append("Content-Range: bytes */").append(resolution.totalLength).append("\r\n")
                            append("Content-Length: 0\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(headerText.toByteArray(Charsets.US_ASCII))
                        out.flush()
                        return
                    }
                    is XLitePolicy.RangeResolution.Full -> {
                        if (tryServeWithHeadCache(
                                req.targetUrl, req.method, meta.contentType,
                                resolution.start, resolution.end, resolution.contentLength,
                                null, headFile, out
                            )
                        ) return
                    }
                    is XLitePolicy.RangeResolution.Partial -> {
                        if (resolution.start < headLen && tryServeWithHeadCache(
                                req.targetUrl, req.method, meta.contentType,
                                resolution.start, resolution.end, resolution.contentLength,
                                resolution.totalLength, headFile, out
                            )
                        ) return
                    }
                    XLitePolicy.RangeResolution.InvalidHeaderFormat -> {
                        // Fall through to upstream so edge-case Range forms (e.g. suffix ranges) still work.
                    }
                }
            }
        }

        serveUpstream(req.targetUrl, hash, req.method, req.rangeHeader, out)
    }

    private fun tryServeWithHeadCache(
        rawUrl: String,
        method: String,
        contentType: String,
        start: Long,
        end: Long,
        contentLength: Long,
        totalLengthFor206: Long?,
        headFile: File,
        out: OutputStream
    ): Boolean {
        val fis = try {
            FileInputStream(headFile)
        } catch (_: Exception) {
            return false
        }

        var closed = false
        try {
            val actualHeadLen = try {
                fis.channel.size()
            } catch (_: Exception) {
                headFile.length()
            }
            if (actualHeadLen <= start) {
                return false
            }

            if (totalLengthFor206 != null) {
                val headers = buildResponseHeaders(
                    statusCode = 206,
                    reason = "Partial Content",
                    contentType = contentType,
                    contentLength = contentLength,
                    contentRange = "bytes $start-$end/$totalLengthFor206"
                )
                out.write(headers)
            } else {
                val headers = buildResponseHeaders(
                    statusCode = 200,
                    reason = "OK",
                    contentType = contentType,
                    contentLength = contentLength,
                    contentRange = null
                )
                out.write(headers)
            }
            out.flush()
            if (method == "HEAD") {
                return true
            }

            val headCopyEnd = minOf(end, actualHeadLen - 1L)
            val headBytesWanted = headCopyEnd - start + 1L
            fis.channel.position(start)
            copyFixed(fis, out, headBytesWanted)
            fis.close()
            closed = true
            out.flush()

            if (end < actualHeadLen) {
                return true
            }

            val tailStart = actualHeadLen
            val tailWanted = end - tailStart + 1L
            streamUpstreamTail(rawUrl, tailStart, end, tailWanted, out)
            return true
        } finally {
            if (!closed) {
                closeQuietly(fis)
            }
        }
    }

    private fun streamUpstreamTail(
        rawUrl: String,
        tailStart: Long,
        end: Long,
        bytesWanted: Long,
        out: OutputStream
    ) {
        if (!XLitePolicy.isAllowedVideoUrl(rawUrl) || bytesWanted <= 0L) return
        val request = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", TwitterBridge.USER_AGENT)
            .header("Range", "bytes=$tailStart-$end")
            .build()
        val call = effectiveClient.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IOException("Upstream tail HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("Upstream tail empty body")
                val stream = body.byteStream()
                if (resp.code == 200) {
                    if (!skipBytesExact(stream, tailStart)) {
                        throw IOException("Upstream tail EOF while skipping to $tailStart")
                    }
                } else {
                    val upstreamRangeStart = parseRangeStart(resp.header("Content-Range"))
                    if (upstreamRangeStart != null && upstreamRangeStart < tailStart) {
                        if (!skipBytesExact(stream, tailStart - upstreamRangeStart)) {
                            throw IOException("Upstream tail EOF while aligning range to $tailStart")
                        }
                    } else if (upstreamRangeStart != null && upstreamRangeStart > tailStart) {
                        throw IOException("Upstream returned start $upstreamRangeStart past requested $tailStart")
                    }
                }
                copyUpTo(stream, out, bytesWanted)
                out.flush()
            }
        } catch (e: IOException) {
            call.cancel()
            throw e
        }
    }

    private fun serveUpstream(
        rawUrl: String,
        hash: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream
    ) {
        if (!XLitePolicy.isAllowedVideoUrl(rawUrl)) {
            writeErrorResponse(out, 403, "Forbidden", includeBody = method != "HEAD")
            return
        }
        val reqBuilder = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", TwitterBridge.USER_AGENT)
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }
        if (method == "HEAD") reqBuilder.head() else reqBuilder.get()

        val call = effectiveClient.newCall(reqBuilder.build())
        try {
            call.execute().use { resp ->
                val code = resp.code
                val contentType = sanitizeVideoMime(resp.header("Content-Type"))
                val upstreamRange = resp.header("Content-Range")
                val body = resp.body
                val bodyLen = body?.contentLength()?.takeIf { it >= 0L }
                    ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }

                if (code != 200 && code != 206) {
                    val reason = resp.message.ifBlank { httpReasonPhrase(code) }
                    val headerText = buildString {
                        append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                        if (!upstreamRange.isNullOrBlank()) {
                            append("Content-Range: ").append(upstreamRange).append("\r\n")
                        }
                        append("Content-Length: 0\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(headerText.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                val responseHeaders = buildResponseHeaders(
                    statusCode = code,
                    reason = if (code == 206) "Partial Content" else "OK",
                    contentType = contentType,
                    contentLength = bodyLen,
                    contentRange = upstreamRange
                )
                out.write(responseHeaders)
                out.flush()
                if (method == "HEAD" || body == null) return

                val parsedRange = XLitePolicy.parseByteRange(rangeHeader)
                val startsAtZero = rangeHeader.isNullOrBlank() || parsedRange?.start == 0L
                val totalLen = when (code) {
                    206 -> XLitePolicy.parseTotalLengthFromContentRange(upstreamRange)
                    200 -> bodyLen
                    else -> null
                }
                val canCacheHead = startsAtZero &&
                    totalLen != null && totalLen > 0L &&
                    (code == 200 || (upstreamRange?.startsWith("bytes 0-", ignoreCase = true) == true)) &&
                    !File(videoCacheDir, "$hash.meta").exists() &&
                    inFlightPrefetches.putIfAbsent(hash, true) == null

                if (canCacheHead && totalLen != null) {
                    streamAndTeeHeadCache(body.byteStream(), out, hash, totalLen, contentType)
                } else {
                    copyToEOF(body.byteStream(), out)
                    out.flush()
                }
            }
        } catch (e: IOException) {
            call.cancel()
            throw e
        }
    }

    private fun streamAndTeeHeadCache(
        input: InputStream,
        out: OutputStream,
        hash: String,
        totalLen: Long,
        contentType: String
    ) {
        val targetHeadBytes = minOf(HEAD_PREFETCH_BYTES, totalLen)
        val suffix = System.nanoTime()
        val headTmp = File(videoCacheDir, "$hash.head.tmp.$suffix")
        val metaTmp = File(videoCacheDir, "$hash.meta.tmp.$suffix")
        var headOut: FileOutputStream? = null
        var teed = 0L
        var lockReleased = false

        try {
            headOut = FileOutputStream(headTmp)
            val buf = ByteArray(SOCKET_BUFFER_BYTES)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                out.write(buf, 0, read)
                if (teed < targetHeadBytes && headOut != null) {
                    val toTee = minOf(read.toLong(), targetHeadBytes - teed).toInt()
                    headOut.write(buf, 0, toTee)
                    teed += toTee
                    if (teed >= targetHeadBytes) {
                        headOut.flush()
                        headOut.close()
                        headOut = null
                        // Persist head immediately so subsequent seek/abort does not discard 1 MB.
                        if (commitHeadCacheFiles(hash, headTmp, metaTmp, targetHeadBytes, totalLen, contentType)) {
                            inFlightPrefetches.remove(hash)
                            lockReleased = true
                        }
                    }
                }
            }
            out.flush()
            if (!lockReleased && teed == targetHeadBytes) {
                commitHeadCacheFiles(hash, headTmp, metaTmp, targetHeadBytes, totalLen, contentType)
            }
        } finally {
            closeQuietly(headOut)
            headTmp.delete()
            metaTmp.delete()
            if (!lockReleased) {
                inFlightPrefetches.remove(hash)
            }
        }
    }

    fun prefetchHead(rawUrl: String) {
        if (!XLitePolicy.isAllowedVideoUrl(rawUrl)) return
        val hash = XLitePolicy.sha256Hex(rawUrl.toByteArray())
        val metaFile = File(videoCacheDir, "$hash.meta")
        val headFile = File(videoCacheDir, "$hash.head")
        if (metaFile.exists() && headFile.exists() && headFile.length() > 0L) return
        if (inFlightPrefetches.putIfAbsent(hash, true) != null) return

        prefetchExecutor.execute {
            val suffix = System.nanoTime()
            val headTmp = File(videoCacheDir, "$hash.head.tmp.$suffix")
            val metaTmp = File(videoCacheDir, "$hash.meta.tmp.$suffix")
            try {
                val request = Request.Builder()
                    .url(rawUrl)
                    .header("User-Agent", TwitterBridge.USER_AGENT)
                    .header("Range", "bytes=0-${HEAD_PREFETCH_BYTES - 1L}")
                    .build()
                effectiveClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) return@execute
                    val body = resp.body ?: return@execute
                    val contentType = sanitizeVideoMime(resp.header("Content-Type"))
                    val totalLen = XLitePolicy.parseTotalLengthFromContentRange(resp.header("Content-Range"))
                        ?: resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L && resp.code == 200 }
                        ?: return@execute

                    FileOutputStream(headTmp).use { fileOut ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            var written = 0L
                            while (written < HEAD_PREFETCH_BYTES) {
                                val read = input.read(buf)
                                if (read == -1) break
                                val toWrite = minOf(read.toLong(), HEAD_PREFETCH_BYTES - written).toInt()
                                fileOut.write(buf, 0, toWrite)
                                written += toWrite
                            }
                        }
                    }

                    val expectedHeadBytes = minOf(HEAD_PREFETCH_BYTES, totalLen)
                    commitHeadCacheFiles(hash, headTmp, metaTmp, expectedHeadBytes, totalLen, contentType)
                }
            } catch (_: Exception) {
            } finally {
                headTmp.delete()
                metaTmp.delete()
                inFlightPrefetches.remove(hash)
            }
        }
    }

    private fun commitHeadCacheFiles(
        hash: String,
        headTmp: File,
        metaTmp: File,
        expectedHeadBytes: Long,
        totalLen: Long,
        contentType: String
    ): Boolean {
        if (expectedHeadBytes <= 0L || headTmp.length() != expectedHeadBytes) return false
        val headFile = File(videoCacheDir, "$hash.head")
        val metaFile = File(videoCacheDir, "$hash.meta")
        metaTmp.writeText("$totalLen\n$contentType", Charsets.UTF_8)
        headFile.delete()
        if (!headTmp.renameTo(headFile)) return false
        metaFile.delete()
        if (!metaTmp.renameTo(metaFile)) {
            headFile.delete()
            return false
        }
        scheduleDiskTrim()
        return true
    }

    private fun purgeStaleTmpFiles(cutoffMs: Long) {
        try {
            videoCacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.contains(".tmp") && f.lastModified() < cutoffMs) {
                    f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun readCachedMeta(hash: String): CachedMeta? {
        val metaFile = File(videoCacheDir, "$hash.meta")
        if (!metaFile.exists()) return null
        return try {
            val lines = metaFile.readLines(Charsets.UTF_8)
            val totalLen = lines.getOrNull(0)?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val mime = sanitizeVideoMime(lines.getOrNull(1))
            CachedMeta(totalLen, mime)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeVideoMime(raw: String?): String {
        val base = raw?.substringBefore(';')?.trim()?.lowercase() ?: "video/mp4"
        return when {
            base == "video/mp4" || base == "video/webm" || base == "application/mp4" -> base
            else -> "video/mp4"
        }
    }

    private fun scheduleDiskTrim() {
        if (cacheWriteCount.incrementAndGet() % 12 == 0) {
            cacheTrimExecutor.execute { trimDiskCacheIfNeeded() }
        }
    }

    private fun trimDiskCacheIfNeeded() {
        try {
            val now = System.currentTimeMillis()
            purgeStaleTmpFiles(now - TMP_STALE_MS)

            val committedHeads = videoCacheDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".head") }
                ?.sortedBy { it.lastModified() }
                ?: return
            var total = videoCacheDir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".head") || it.name.endsWith(".meta")) }
                ?.sumOf { it.length() }
                ?: 0L
            if (total <= MAX_DISK_VIDEO_CACHE_BYTES) return

            val target = (MAX_DISK_VIDEO_CACHE_BYTES * 7) / 10
            for (head in committedHeads) {
                if (total <= target) break
                val hash = head.name.removeSuffix(".head")
                val meta = File(videoCacheDir, "$hash.meta")
                val headLen = head.length()
                val metaLen = if (meta.exists()) meta.length() else 0L
                if (head.delete()) total -= headLen
                if (meta.exists() && meta.delete()) total -= metaLen
            }
        } catch (_: Exception) {
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArray(MAX_LINE_BYTES)
        var len = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (len == 0) null else String(bytes, 0, len, Charsets.US_ASCII)
            }
            if (b == '\n'.code) {
                val trimLen = if (len > 0 && bytes[len - 1] == '\r'.code.toByte()) len - 1 else len
                return String(bytes, 0, trimLen, Charsets.US_ASCII)
            }
            if (len < MAX_LINE_BYTES) {
                bytes[len++] = b.toByte()
            } else {
                throw IOException("HTTP line too long")
            }
        }
    }

    private fun buildResponseHeaders(
        statusCode: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        contentRange: String?
    ): ByteArray {
        val text = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength != null) {
                append("Content-Length: ").append(contentLength).append("\r\n")
            }
            if (!contentRange.isNullOrBlank()) {
                append("Content-Range: ").append(contentRange).append("\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("Cache-Control: public, max-age=86400\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    private fun writePreflightResponse(out: OutputStream, allowMethods: String) {
        val text = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: ").append(allowMethods).append("\r\n")
            append("Access-Control-Allow-Headers: Range\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeErrorResponse(out: OutputStream, statusCode: Int, reason: String, includeBody: Boolean) {
        val bodyBytes = if (includeBody) reason.toByteArray(Charsets.US_ASCII) else ByteArray(0)
        val text = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ").append(if (includeBody) bodyBytes.size else 0).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.US_ASCII))
        if (includeBody && bodyBytes.isNotEmpty()) {
            out.write(bodyBytes)
        }
        out.flush()
    }

    private fun parseRangeStart(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val match = Regex("^bytes\\s+(\\d+)-", RegexOption.IGNORE_CASE).find(contentRange.trim())
            ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun httpReasonPhrase(code: Int): String = when (code) {
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private fun copyFixed(input: InputStream, out: OutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(SOCKET_BUFFER_BYTES)
        while (remaining > 0L) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read == -1) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun copyUpTo(input: InputStream, out: OutputStream, maxBytes: Long) {
        var remaining = maxBytes
        val buf = ByteArray(SOCKET_BUFFER_BYTES)
        while (remaining > 0L) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read == -1) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun copyToEOF(input: InputStream, out: OutputStream) {
        val buf = ByteArray(SOCKET_BUFFER_BYTES)
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            out.write(buf, 0, read)
        }
    }

    private fun skipBytesExact(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val discard = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read == -1) return false
                remaining -= read
            }
        }
        return true
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
