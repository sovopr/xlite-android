package com.sovopr.xlite

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class XLiteUnitTest {

    @Test
    fun testAllowedGetUrlValidation() {
        assertTrue(XLitePolicy.isAllowedGetUrl("https://x.com/home"))
        assertTrue(XLitePolicy.isAllowedGetUrl("https://x.com/i/api/graphql/abc123/HomeTimeline"))
        assertTrue(XLitePolicy.isAllowedGetUrl("https://api.twitter.com/1.1/search/typeahead.json?q=test"))
        assertTrue(XLitePolicy.isAllowedGetUrl("https://abs.twimg.com/responsive-web/client-web/ondemand.s.deadbeef01a.js"))
        assertTrue(XLitePolicy.isAllowedGetUrl("https://gist.githubusercontent.com/sovopr/c52e8a1490b3a5415525b27063726bbe/raw/index.html"))

        // Reject HTTP downgrade, host spoofing, userinfo credentials, custom ports, and traversal
        assertFalse(XLitePolicy.isAllowedGetUrl("http://x.com/home"))
        assertFalse(XLitePolicy.isAllowedGetUrl("https://x.com.evil.example/home"))
        assertFalse(XLitePolicy.isAllowedGetUrl("https://user@x.com/home"))
        assertFalse(XLitePolicy.isAllowedGetUrl("https://x.com:8443/home"))
        assertFalse(XLitePolicy.isAllowedGetUrl("https://x.com/i/api/../settings"))
        assertFalse(XLitePolicy.isAllowedGetUrl("https://x.com/settings"))
        assertFalse(XLitePolicy.isAllowedGetUrl("https://gist.githubusercontent.com/other/repo/raw/index.html"))
    }

    @Test
    fun testPostSignedAndFormAllowlists() {
        assertTrue(XLitePolicy.isAllowedPostUrl("https://x.com/i/api/graphql/xyz9/CreateTweet"))
        assertTrue(XLitePolicy.isAllowedPostUrl("https://x.com/i/api/graphql/xyz9/FavoriteTweet"))
        assertFalse(XLitePolicy.isAllowedPostUrl("https://x.com/i/api/graphql/xyz9/DeleteUser"))

        assertTrue(XLitePolicy.isAllowedFormPostUrl("https://x.com/i/api/1.1/jot/client_event.json"))
        assertTrue(XLitePolicy.isAllowedFormPostUrl("https://x.com/i/api/1.1/friendships/create.json"))
        assertFalse(XLitePolicy.isAllowedFormPostUrl("https://x.com/i/api/1.1/account/settings.json"))

        assertTrue(XLitePolicy.isAllowedSignedGetUrl("https://x.com/i/api/graphql/qid123/SearchTimeline?variables=%7B%7D"))
        assertTrue(XLitePolicy.isAllowedSignedGetUrl("https://x.com/i/api/graphql/qid123/SearchTimeline?variables={}"))
        assertTrue(XLitePolicy.isAllowedSignedGetUrl("https://x.com/i/api/2/notifications/all.json"))
        assertFalse(XLitePolicy.isAllowedSignedGetUrl("https://api.twitter.com/i/api/2/notifications/all.json"))

        assertEquals(
            "https://x.com/notifications",
            XLitePolicy.resolveSignedReferer("/i/api/2/notifications/all.json")
        )
        assertEquals(
            "https://x.com/search",
            XLitePolicy.resolveSignedReferer("/i/api/graphql/abc/SearchTimeline")
        )
        assertEquals(
            "https://x.com/home",
            XLitePolicy.resolveSignedReferer("/i/api/graphql/abc/HomeTimeline")
        )
    }

    @Test
    fun testTransactionIdValidation() {
        val validTid = "A".repeat(94)
        assertTrue(XLitePolicy.isValidTransactionId(validTid))
        assertFalse(XLitePolicy.isValidTransactionId("short"))
        assertFalse(XLitePolicy.isValidTransactionId("A".repeat(121)))
        assertFalse(XLitePolicy.isValidTransactionId("bad-char-" + "A".repeat(90)))
    }

    @Test
    fun testApkAndExternalUrlPolicy() {
        assertTrue(XLitePolicy.isAllowedApkUrl("https://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk"))
        assertTrue(XLitePolicy.isAllowedApkUrl("https://github.com/sovopr/xlite-android/releases/download/v1.0.24/xlite-fup-release.apk"))
        assertTrue(XLitePolicy.isAllowedApkUrl("https://github.com/sovopr/xlite-releases/releases/download/v1.0.24/xlite-fup-release.apk"))

        assertFalse(XLitePolicy.isAllowedApkUrl("http://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk"))
        assertFalse(XLitePolicy.isAllowedApkUrl("https://github.com/attacker/xlite-android/releases/latest/download/xlite-fup-release.apk"))
        assertFalse(XLitePolicy.isAllowedApkUrl("https://github.com/sovopr/xlite-android/releases/download/v1.0.24/other.apk"))

        assertTrue(XLitePolicy.isAllowedExternalUrl("https://github.com/sovopr/xlite-android/releases/tag/v1.0.24"))
        assertTrue(XLitePolicy.isAllowedExternalUrl("https://github.com/sovopr/xlite-releases/releases/latest"))
        assertTrue(XLitePolicy.isAllowedExternalUrl("https://x.com/someuser/status/123"))
        assertFalse(XLitePolicy.isAllowedExternalUrl("https://evil.example/phish"))
    }

    @Test
    fun testSemverComparisonAndCompatibility() {
        assertTrue(XLitePolicy.compareSemver("1.0.10", "1.0.9") > 0)
        assertTrue(XLitePolicy.compareSemver("1.0.24", "1.0.23") > 0)
        assertEquals(0, XLitePolicy.compareSemver("1.0.24", "v1.0.24"))
        assertTrue(XLitePolicy.isNewerSemver("1.0.9", "1.0.10"))
        assertFalse(XLitePolicy.isNewerSemver("1.0.24", "1.0.24"))
        assertFalse(XLitePolicy.isNewerSemver("1.0.24", "1.0.23"))

        assertTrue(XLitePolicy.isApkVersionCompatible(20L, 19L, "1.0.24", "1.0.24"))
        assertFalse(XLitePolicy.isApkVersionCompatible(18L, 19L, "1.0.24", "1.0.24"))
        assertFalse(XLitePolicy.isApkVersionCompatible(20L, 19L, "1.0.24", "1.0.23"))
    }

    @Test
    fun testChecksumsManifestParsingAndDerivation() {
        assertEquals(
            "https://github.com/sovopr/xlite-android/releases/download/v1.0.24/SHA256SUMS.txt",
            XLitePolicy.resolveChecksumsUrl("https://github.com/sovopr/xlite-android/releases/download/v1.0.24/xlite-fup-release.apk")
        )
        assertEquals(
            "https://github.com/sovopr/xlite-android/releases/latest/download/SHA256SUMS.txt",
            XLitePolicy.resolveChecksumsUrl("https://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk")
        )
        assertNull(XLitePolicy.resolveChecksumsUrl("https://example.com/xlite.apk"))

        val fakeSha = "a".repeat(64)
        val manifest = """
            # Release checksums
            ${"b".repeat(64)}  other-file.zip
            ${fakeSha.uppercase()}  ./xlite-fup-release.apk
        """.trimIndent()
        assertEquals(fakeSha, XLitePolicy.parseSha256Sums(manifest))
        assertNull(XLitePolicy.parseSha256Sums("${"c".repeat(64)}  wrong.apk"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", XLitePolicy.sha256Hex(ByteArray(0)))
    }

    @Test
    fun testImageCandidatesAndMimeDetection() {
        val mediaThumb = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/media/AbCdEf.png?name=large", "thumb", "png")
        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/AbCdEf.png?format=webp&name=thumb",
                "https://pbs.twimg.com/media/AbCdEf.png?format=png&name=thumb"
            ),
            mediaThumb
        )

        val mediaHd = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/media/AbCdEf.jpg", "hd", "jpeg")
        assertEquals("https://pbs.twimg.com/media/AbCdEf.jpg?format=webp&name=large", mediaHd.first())
        assertTrue(mediaHd.contains("https://pbs.twimg.com/media/AbCdEf.jpg?format=jpg&name=large"))

        val mediaRich = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/media/AbCdEf.jpg", "rich", "jpeg")
        assertEquals("https://pbs.twimg.com/media/AbCdEf.jpg?format=webp&name=large", mediaRich.first())
        assertTrue(mediaRich.contains("https://pbs.twimg.com/media/AbCdEf.jpg?format=webp&name=medium"))

        val bannerCandidates = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/profile_banners/123/456", "thumb", "jpg")
        assertEquals(
            listOf(
                "https://pbs.twimg.com/profile_banners/123/456/600x200",
                "https://pbs.twimg.com/profile_banners/123/456"
            ),
            bannerCandidates
        )

        val richBannerCandidates = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/profile_banners/123/456", "rich", "jpg")
        assertEquals(
            listOf(
                "https://pbs.twimg.com/profile_banners/123/456/1500x500",
                "https://pbs.twimg.com/profile_banners/123/456/600x200",
                "https://pbs.twimg.com/profile_banners/123/456"
            ),
            richBannerCandidates
        )

        val videoThumb = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/ext_tw_video_thumb/99/pu/img/vid.jpg:large", "rich", "jpg")
        assertEquals(
            listOf(
                "https://pbs.twimg.com/ext_tw_video_thumb/99/pu/img/vid.jpg:large",
                "https://pbs.twimg.com/ext_tw_video_thumb/99/pu/img/vid.jpg:medium",
                "https://pbs.twimg.com/ext_tw_video_thumb/99/pu/img/vid.jpg:small",
                "https://pbs.twimg.com/ext_tw_video_thumb/99/pu/img/vid.jpg"
            ),
            videoThumb
        )

        val richAvatar = XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/profile_images/42/face_normal.jpg", "rich", "jpg")
        assertEquals(
            listOf(
                "https://pbs.twimg.com/profile_images/42/face.jpg",
                "https://pbs.twimg.com/profile_images/42/face_400x400.jpg",
                "https://pbs.twimg.com/profile_images/42/face_200x200.jpg",
                "https://pbs.twimg.com/profile_images/42/face_normal.jpg"
            ),
            richAvatar
        )
        assertEquals(
            listOf("https://pbs.twimg.com/profile_images/42/face_mini.jpg"),
            XLitePolicy.buildImageCandidateUrls("https://pbs.twimg.com/profile_images/42/face_mini.jpg", "thumb", "jpg")
        )

        assertEquals("image/webp", XLitePolicy.detectImageMime(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0, 'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())))
        assertEquals("image/png", XLitePolicy.detectImageMime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("image/jpeg", XLitePolicy.detectImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun testImageModeNormalization() {
        assertEquals("auto", XLitePolicy.normalizeImageMode("auto"))
        assertEquals("auto", XLitePolicy.normalizeImageMode("  AUTO "))
        assertEquals("off", XLitePolicy.normalizeImageMode("off"))
        assertEquals("tap", XLitePolicy.normalizeImageMode("tap"))
        assertEquals("tap", XLitePolicy.normalizeImageMode(null))
        assertEquals("tap", XLitePolicy.normalizeImageMode("invalid"))
    }

    @Test
    fun testVideoPolicyAndRangeParsing() {
        assertTrue(XLitePolicy.isAllowedVideoUrl("https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/720x1280/abc.mp4"))
        assertTrue(XLitePolicy.isAllowedVideoUrl("https://video.twimg.com/tweet_video/abc.mp4"))
        assertFalse(XLitePolicy.isAllowedVideoUrl("http://video.twimg.com/ext_tw_video/123/a.mp4"))
        assertFalse(XLitePolicy.isAllowedVideoUrl("https://evil.example/a.mp4"))
        assertFalse(XLitePolicy.isAllowedVideoUrl("https://video.twimg.com/..%2fsecret.mp4"))

        assertEquals(XLitePolicy.ByteRange(0L, null), XLitePolicy.parseByteRange("bytes=0-"))
        assertEquals(XLitePolicy.ByteRange(1024L, 2047L), XLitePolicy.parseByteRange("bytes=1024-2047"))
        assertNull(XLitePolicy.parseByteRange("bytes=500-100"))
        assertNull(XLitePolicy.parseByteRange("bytes=0-10, 20-30"))
        assertNull(XLitePolicy.parseByteRange(null))

        assertEquals(3456789L, XLitePolicy.parseTotalLengthFromContentRange("bytes 0-786431/3456789"))
        assertNull(XLitePolicy.parseTotalLengthFromContentRange("bytes */*"))
    }

    @Test
    fun testLoopbackVideoRequestParsingAndRangeResolution() {
        val validUrl = "https://video.twimg.com/ext_tw_video/123/pu/vid/720x1280/clip.mp4?tag=12"
        val encoded = java.net.URLEncoder.encode(validUrl, "UTF-8")
        val ok = XLitePolicy.parseLoopbackVideoRequest(
            "GET /vid?url=$encoded HTTP/1.1",
            mapOf("Host" to "127.0.0.1:41234", "range" to "bytes=0-65535")
        )
        assertTrue(ok is XLitePolicy.LoopbackParseResult.Ok)
        val okReq = (ok as XLitePolicy.LoopbackParseResult.Ok).request
        assertEquals("GET", okReq.method)
        assertEquals(validUrl, okReq.targetUrl)
        assertEquals("bytes=0-65535", okReq.rangeHeader)

        val headOk = XLitePolicy.parseLoopbackVideoRequest("HEAD /vid?url=$encoded HTTP/1.1", emptyMap())
        assertTrue(headOk is XLitePolicy.LoopbackParseResult.Ok)
        assertEquals("HEAD", (headOk as XLitePolicy.LoopbackParseResult.Ok).request.method)

        val badTarget = java.net.URLEncoder.encode("https://evil.example/video.mp4", "UTF-8")
        val forbidden = XLitePolicy.parseLoopbackVideoRequest("GET /vid?url=$badTarget HTTP/1.1", emptyMap())
        assertEquals(XLitePolicy.LoopbackParseResult.Error(403, "Forbidden"), forbidden)

        val wrongPath = XLitePolicy.parseLoopbackVideoRequest("GET /other?url=$encoded HTTP/1.1", emptyMap())
        assertEquals(XLitePolicy.LoopbackParseResult.Error(404, "Not Found"), wrongPath)

        val postMethod = XLitePolicy.parseLoopbackVideoRequest("POST /vid?url=$encoded HTTP/1.1", emptyMap())
        assertEquals(XLitePolicy.LoopbackParseResult.Error(405, "Method Not Allowed"), postMethod)

        val full = XLitePolicy.resolveCachedByteRange(null, 2_000_000L)
        assertEquals(XLitePolicy.RangeResolution.Full(0L, 1_999_999L, 2_000_000L), full)

        val openRange = XLitePolicy.resolveCachedByteRange("bytes=0-", 2_000_000L)
        assertEquals(XLitePolicy.RangeResolution.Partial(0L, 1_999_999L, 2_000_000L, 2_000_000L), openRange)

        val clamped = XLitePolicy.resolveCachedByteRange("bytes=1500000-9999999", 2_000_000L)
        assertEquals(XLitePolicy.RangeResolution.Partial(1_500_000L, 1_999_999L, 500_000L, 2_000_000L), clamped)

        val unsatisfiable = XLitePolicy.resolveCachedByteRange("bytes=2000000-", 2_000_000L)
        assertEquals(XLitePolicy.RangeResolution.Unsatisfiable(2_000_000L), unsatisfiable)
    }

    private data class RawHttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun fetchLoopbackHttp(port: Int, pathAndQuery: String, rangeHeader: String? = null, maxBodyBytes: Int = Int.MAX_VALUE): RawHttpResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val req = buildString {
                append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(port).append("\r\n")
                if (rangeHeader != null) append("Range: ").append(rangeHeader).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val input = socket.getInputStream().buffered()
            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }
            val headerLines = headerBytes.toString(Charsets.US_ASCII.name()).split("\r\n").filter { it.isNotEmpty() }
            val statusCode = headerLines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
            val headers = mutableMapOf<String, String>()
            for (line in headerLines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val bodyOut = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var total = 0
            while (total < maxBodyBytes) {
                val toRead = minOf(buf.size, maxBodyBytes - total)
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                bodyOut.write(buf, 0, n)
                total += n
            }
            return RawHttpResponse(statusCode, headers, bodyOut.toByteArray())
        }
    }

    private fun buildMockVideoClient(
        payload: ByteArray,
        upstreamHits: AtomicInteger,
        failTail: AtomicBoolean
    ): OkHttpClient {
        val mediaType = "video/mp4".toMediaType()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val reqRange = req.header("Range")
                if (failTail.get() && reqRange != null && reqRange.startsWith("bytes=1048576-")) {
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body(ByteArray(0).toResponseBody(mediaType))
                        .build()
                }
                upstreamHits.incrementAndGet()
                val range = XLitePolicy.parseByteRange(reqRange)
                if (range != null) {
                    val start = range.start.coerceIn(0L, (payload.size - 1).toLong()).toInt()
                    val end = (range.end ?: (payload.size - 1).toLong())
                        .coerceIn(start.toLong(), (payload.size - 1).toLong()).toInt()
                    val slice = payload.copyOfRange(start, end + 1)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Type", "video/mp4")
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Range", "bytes $start-$end/${payload.size}")
                        .header("Content-Length", slice.size.toString())
                        .body(slice.toResponseBody(mediaType))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "video/mp4")
                        .header("Content-Length", payload.size.toString())
                        .body(payload.toResponseBody(mediaType))
                        .build()
                }
            }
            .build()
    }

    @Test
    fun testVideoProxyCommitsHeadOnEarlyAbortAndServesCachedRange() {
        val tempRoot = Files.createTempDirectory("xlite_proxy_head").toFile()
        val videosDir = File(tempRoot, "videos").apply { mkdirs() }
        val staleTmp = File(videosDir, "orphan.head.tmp.12345").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(System.currentTimeMillis() - 10_000L)
        }
        val payload = ByteArray(2_500_000) { ((it * 31) and 0xFF).toByte() }
        val upstreamHits = AtomicInteger(0)
        val failTail = AtomicBoolean(false)
        val proxy = VideoStreamProxy(tempRoot, buildMockVideoClient(payload, upstreamHits, failTail))
        try {
            val port = proxy.getProxyPort()
            assertTrue(port > 0)
            val videoUrl = "https://video.twimg.com/ext_tw_video/999/pu/vid/clip.mp4"
            val queryPath = "/vid?url=" + URLEncoder.encode(videoUrl, "UTF-8")

            // Client reads past 1 MiB threshold then closes socket mid-stream (simulating Chromium seek/abort)
            val early = fetchLoopbackHttp(port, queryPath, "bytes=0-", maxBodyBytes = 1_150_000)
            assertEquals(206, early.statusCode)
            assertEquals(1_150_000, early.body.size)

            // Wait for background tee commit + startup stale tmp cleanup
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                val files = videosDir.listFiles().orEmpty()
                val hasHead = files.any { it.name.endsWith(".head") && it.length() == 1_048_576L }
                val hasMeta = files.any { it.name.endsWith(".meta") }
                if (hasHead && hasMeta && !staleTmp.exists()) break
                Thread.sleep(40L)
            }
            val filesAfter = videosDir.listFiles().orEmpty()
            assertFalse("Stale tmp file should be purged on startup", staleTmp.exists())
            assertTrue("1 MiB head file must be committed despite early disconnect", filesAfter.any { it.name.endsWith(".head") && it.length() == 1_048_576L })
            assertTrue("Meta file must be committed alongside head", filesAfter.any { it.name.endsWith(".meta") })
            assertFalse("No orphan tmp files should remain", filesAfter.any { it.name.contains(".tmp.") })

            val hitsBeforeCacheRead = upstreamHits.get()
            val cachedRange = fetchLoopbackHttp(port, queryPath, "bytes=0-65535")
            assertEquals(206, cachedRange.statusCode)
            assertArrayEquals(payload.copyOfRange(0, 65536), cachedRange.body)
            assertEquals("Cached range <= 1 MiB must trigger zero upstream calls", hitsBeforeCacheRead, upstreamHits.get())
        } finally {
            proxy.closeForTesting()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testVideoProxyTailFailureAndConcurrentBurst() {
        val tempRoot = Files.createTempDirectory("xlite_proxy_burst").toFile()
        val videosDir = File(tempRoot, "videos").apply { mkdirs() }
        val payload = ByteArray(2_000_000) { ((it * 17) and 0xFF).toByte() }
        val upstreamHits = AtomicInteger(0)
        val failTail = AtomicBoolean(false)
        val proxy = VideoStreamProxy(tempRoot, buildMockVideoClient(payload, upstreamHits, failTail))
        val pool = Executors.newFixedThreadPool(16)
        try {
            val port = proxy.getProxyPort()
            assertTrue(port > 0)
            val videoUrl = "https://video.twimg.com/ext_tw_video/888/pu/vid/burst.mp4"
            val queryPath = "/vid?url=" + URLEncoder.encode(videoUrl, "UTF-8")

            proxy.prefetchHead(videoUrl)
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                val files = videosDir.listFiles().orEmpty()
                if (files.any { it.name.endsWith(".head") } && files.any { it.name.endsWith(".meta") }) break
                Thread.sleep(40L)
            }
            assertTrue(videosDir.listFiles().orEmpty().any { it.name.endsWith(".head") })

            // Upstream tail error must truncate/abort socket before advertised 2 MB length
            failTail.set(true)
            val abortedTail = fetchLoopbackHttp(port, queryPath, "bytes=0-")
            assertEquals(206, abortedTail.statusCode)
            assertTrue("Truncated upstream tail must not pretend complete 2 MB body", abortedTail.body.size < payload.size)
            assertEquals(1_048_576, abortedTail.body.size)

            // 16 concurrent range fetches succeed without thread-pool 503 starvation
            failTail.set(false)
            val tasks = (0 until 16).map { idx ->
                Callable {
                    val start = idx * 65536
                    val end = start + 65535
                    val res = fetchLoopbackHttp(port, queryPath, "bytes=$start-$end")
                    assertEquals(206, res.statusCode)
                    assertArrayEquals(payload.copyOfRange(start, end + 1), res.body)
                    true
                }
            }
            val futures = pool.invokeAll(tasks)
            for (f in futures) assertTrue(f.get())
        } finally {
            pool.shutdownNow()
            proxy.closeForTesting()
            tempRoot.deleteRecursively()
        }
    }
}
