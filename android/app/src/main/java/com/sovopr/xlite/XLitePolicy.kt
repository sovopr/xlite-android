package com.sovopr.xlite

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * Pure JVM policy, URL validation, semver, checksum, and image candidate helpers
 * shared across Android runtime classes and unit tests without duplicating rules.
 */
object XLitePolicy {
    private val ALLOWED_POST_PATH_REGEX = Regex(
        "^/i/api/graphql/[A-Za-z0-9_-]+/(CreateTweet|FavoriteTweet|UnfavoriteTweet|CreateRetweet|DeleteRetweet|CreateBookmark|DeleteBookmark|HomeTimeline|HomeLatestTimeline)$"
    )
    private val ALLOWED_FORM_POST_PATH_REGEX = Regex(
        "^/i/api/1\\.1/(?:jot/client_event|friendships/(?:create|destroy))\\.json$"
    )
    private val ALLOWED_SIGNED_GET_PATH_REGEX = Regex(
        "^/i/api/(?:graphql/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+|2/notifications/[A-Za-z0-9_.-]+\\.json)$"
    )
    private val TRANSACTION_ID_REGEX = Regex("^[A-Za-z0-9+/]{80,120}$")
    private val ALLOWED_APK_PATH_REGEX = Regex(
        "^/sovopr/xlite-(?:android|releases)/releases/(?:latest/download|download/v[0-9]+\\.[0-9]+\\.[0-9]+)/xlite-fup-release\\.apk$"
    )
    private val SEMVER_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val TRAILING_TWITTER_SIZE_REGEX = Regex(":(thumb|small|medium|large|orig)$")
    private val PROFILE_BANNER_SIZE_REGEX = Regex("/\\d+x\\d+$")
    private val PROFILE_IMAGE_SIZE_REGEX = Regex("_(mini|normal|bigger|200x200|400x400)(\\.[a-z0-9]+)$", RegexOption.IGNORE_CASE)
    private val SHA256_LINE_REGEX = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
    private val BYTE_RANGE_REGEX = Regex("^bytes=(\\d+)-(\\d*)$", RegexOption.IGNORE_CASE)
    private val CONTENT_RANGE_TOTAL_REGEX = Regex("^bytes\\s+\\d+-\\d+/(\\d+)$", RegexOption.IGNORE_CASE)

    data class ByteRange(val start: Long, val end: Long?)

    data class LoopbackVideoRequest(
        val method: String,
        val targetUrl: String,
        val rangeHeader: String?
    )

    sealed class LoopbackParseResult {
        data class Ok(val request: LoopbackVideoRequest) : LoopbackParseResult()
        data class Preflight(val allowMethods: String = "GET, HEAD, OPTIONS") : LoopbackParseResult()
        data class Error(val statusCode: Int, val reason: String) : LoopbackParseResult()
    }

    sealed class RangeResolution {
        data class Full(val start: Long, val end: Long, val contentLength: Long) : RangeResolution()
        data class Partial(val start: Long, val end: Long, val contentLength: Long, val totalLength: Long) : RangeResolution()
        data class Unsatisfiable(val totalLength: Long) : RangeResolution()
        object InvalidHeaderFormat : RangeResolution()
    }

    private fun parseHttpsUrl(url: String): HttpUrl? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val pathPortion = trimmed.substringBefore('?').substringBefore('#')
        if (pathPortion.contains("..") || pathPortion.contains('\\') || pathPortion.lowercase().contains("%2e")) {
            return null
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.host.isBlank() || parsed.port != 443) return null
        return parsed
    }

    fun isAllowedGetUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        val path = parsed.encodedPath
        return when (parsed.host.lowercase()) {
            "x.com" -> path == "/home" || path.startsWith("/i/api/")
            "api.twitter.com" -> path.startsWith("/i/api/") || path.startsWith("/1.1/search/")
            "abs.twimg.com" -> Regex("^/responsive-web/client-web/ondemand\\.s\\.[0-9a-f]+a\\.js$").matches(path)
            "gist.githubusercontent.com" -> path.startsWith("/sovopr/c52e8a1490b3a5415525b27063726bbe/")
            else -> false
        }
    }

    fun isAllowedPostUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        val path = parsed.encodedPath
        return parsed.host.equals("x.com", ignoreCase = true) && ALLOWED_POST_PATH_REGEX.matches(path)
    }

    fun isAllowedFormPostUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        val path = parsed.encodedPath
        return parsed.host.equals("x.com", ignoreCase = true) && ALLOWED_FORM_POST_PATH_REGEX.matches(path)
    }

    fun isAllowedSignedGetUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        val path = parsed.encodedPath
        return parsed.host.equals("x.com", ignoreCase = true) && ALLOWED_SIGNED_GET_PATH_REGEX.matches(path)
    }

    fun isValidTransactionId(transactionId: String): Boolean =
        TRANSACTION_ID_REGEX.matches(transactionId)

    fun resolveSignedReferer(encodedPath: String): String = when {
        encodedPath.startsWith("/i/api/2/notifications/") -> "https://x.com/notifications"
        encodedPath.contains("/SearchTimeline") -> "https://x.com/search"
        else -> "https://x.com/home"
    }

    fun isAllowedApkUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        val path = parsed.encodedPath
        return parsed.host.equals("github.com", ignoreCase = true) && ALLOWED_APK_PATH_REGEX.matches(path)
    }

    fun isAllowedExternalUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        val host = parsed.host.lowercase()
        val path = parsed.encodedPath
        return (host == "github.com" && (path.startsWith("/sovopr/xlite-android") || path.startsWith("/sovopr/xlite-releases"))) ||
            host == "x.com"
    }

    /** Validates X CDN video stream URLs permitted for native prebuffering and Range proxying. */
    fun isAllowedVideoUrl(url: String): Boolean {
        val parsed = parseHttpsUrl(url) ?: return false
        return parsed.host.equals("video.twimg.com", ignoreCase = true) && parsed.encodedPath.length > 1
    }

    /** Parses an HTTP single-span byte range header ("bytes=start-" or "bytes=start-end"). */
    fun parseByteRange(rangeHeader: String?): ByteRange? {
        val trimmed = rangeHeader?.trim() ?: return null
        val match = BYTE_RANGE_REGEX.matchEntire(trimmed) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val endRaw = match.groupValues[2]
        val end = if (endRaw.isEmpty()) null else (endRaw.toLongOrNull() ?: return null)
        if (end != null && end < start) return null
        return ByteRange(start, end)
    }

    /** Extracts total resource length from a standard "bytes start-end/total" response header. */
    fun parseTotalLengthFromContentRange(header: String?): Long? {
        val trimmed = header?.trim() ?: return null
        val match = CONTENT_RANGE_TOTAL_REGEX.matchEntire(trimmed) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
    }

    /** Parses and authorizes an HTTP/1.1 loopback proxy request line ("METHOD /vid?url=... HTTP/1.1"). */
    fun parseLoopbackVideoRequest(requestLine: String?, headers: Map<String, String>): LoopbackParseResult {
        val line = requestLine?.trim() ?: return LoopbackParseResult.Error(400, "Bad Request")
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.", ignoreCase = true)) {
            return LoopbackParseResult.Error(400, "Bad Request")
        }
        val method = parts[0].uppercase()
        if (method == "OPTIONS") {
            return LoopbackParseResult.Preflight()
        }
        if (method != "GET" && method != "HEAD") {
            return LoopbackParseResult.Error(405, "Method Not Allowed")
        }
        val target = parts[1]
        if (!target.startsWith("/")) {
            return LoopbackParseResult.Error(400, "Bad Request")
        }
        val parsed = "http://127.0.0.1$target".toHttpUrlOrNull()
            ?: return LoopbackParseResult.Error(400, "Bad Request")
        if (parsed.encodedPath != "/vid") {
            return LoopbackParseResult.Error(404, "Not Found")
        }
        val targetUrl = parsed.queryParameter("url")?.trim().orEmpty()
        if (!isAllowedVideoUrl(targetUrl)) {
            return LoopbackParseResult.Error(403, "Forbidden")
        }
        val rangeHeader = headers.entries
            .lastOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
        return LoopbackParseResult.Ok(LoopbackVideoRequest(method, targetUrl, rangeHeader))
    }

    /** Resolves an optional Range request against a known positive resource length. */
    fun resolveCachedByteRange(rangeHeader: String?, totalLength: Long): RangeResolution {
        if (totalLength <= 0L) return RangeResolution.InvalidHeaderFormat
        if (rangeHeader.isNullOrBlank()) {
            return RangeResolution.Full(0L, totalLength - 1L, totalLength)
        }
        val parsed = parseByteRange(rangeHeader) ?: return RangeResolution.InvalidHeaderFormat
        if (parsed.start >= totalLength) {
            return RangeResolution.Unsatisfiable(totalLength)
        }
        val end = minOf(parsed.end ?: (totalLength - 1L), totalLength - 1L)
        return RangeResolution.Partial(parsed.start, end, end - parsed.start + 1L, totalLength)
    }

    fun isValidSemver(version: String): Boolean = SEMVER_REGEX.matches(version.trim())

    fun compareSemver(left: String, right: String): Int {
        fun parse(v: String): List<Int>? {
            val clean = v.trim().removePrefix("v").removePrefix("V")
            if (!SEMVER_REGEX.matches(clean)) return null
            return clean.split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }
        }
        val a = parse(left) ?: return 0
        val b = parse(right) ?: return 0
        for (i in 0..2) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun isNewerSemver(currentVersion: String, candidateVersion: String): Boolean =
        compareSemver(candidateVersion, currentVersion) > 0

    /**
     * Normalizes persisted or JS-supplied image loading mode to one of the supported presets.
     */
    fun normalizeImageMode(mode: String?): String = when (mode?.trim()?.lowercase()) {
        "auto", "off", "tap" -> mode.trim().lowercase()
        else -> "tap"
    }

    fun isApkVersionCompatible(
        candidateVersionCode: Long,
        installedVersionCode: Long,
        expectedVersion: String,
        candidateVersionName: String?
    ): Boolean {
        val expected = expectedVersion.trim()
        if (expected.isNotBlank() && isValidSemver(expected) && candidateVersionName != expected) {
            return false
        }
        return candidateVersionCode >= installedVersionCode
    }

    fun resolveChecksumsUrl(apkUrl: String): String? {
        if (!isAllowedApkUrl(apkUrl)) return null
        val parsed = parseHttpsUrl(apkUrl) ?: return null
        val path = parsed.encodedPath
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return null
        return "https://${parsed.host}${path.substring(0, slash + 1)}SHA256SUMS.txt"
    }

    fun parseSha256Sums(manifestText: String, assetName: String = "xlite-fup-release.apk"): String? {
        if (manifestText.isBlank()) return null
        for (rawLine in manifestText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val match = SHA256_LINE_REGEX.matchEntire(line) ?: continue
            val digest = match.groupValues[1].lowercase()
            val fileToken = match.groupValues[2].trim().substringAfterLast('/')
            if (fileToken == assetName) {
                return digest
            }
        }
        return null
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun detectImageMime(bytes: ByteArray, fallbackFormat: String = "webp"): String {
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        return when (fallbackFormat.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/webp"
        }
    }

    fun buildImageCandidateUrls(rawUrl: String, mode: String, imageFormat: String): List<String> {
        val parsed = parseHttpsUrl(rawUrl) ?: return listOf(rawUrl)
        val rawPath = parsed.encodedPath
        val cleanPath = rawPath.replace(TRAILING_TWITTER_SIZE_REGEX, "")
        val baseFormat = if (imageFormat.equals("jpeg", ignoreCase = true)) "jpg" else imageFormat.lowercase()
        val origin = "https://${parsed.host}"

        if (cleanPath.startsWith("/media/")) {
            val withQuery = { fmt: String, sizeName: String -> "$origin$cleanPath?format=$fmt&name=$sizeName" }
            return when (mode) {
                "hd" -> listOf(
                    withQuery("webp", "large"),
                    withQuery(baseFormat, "large"),
                    withQuery("webp", "medium"),
                    withQuery(baseFormat, "medium"),
                    withQuery("webp", "small"),
                    withQuery(baseFormat, "small")
                ).distinct()
                "rich" -> listOf(
                    withQuery("webp", "large"),
                    withQuery("webp", "medium"),
                    withQuery(baseFormat, "large"),
                    withQuery(baseFormat, "medium"),
                    withQuery("webp", "small"),
                    withQuery(baseFormat, "small")
                ).distinct()
                else -> listOf(
                    withQuery("webp", "thumb"),
                    withQuery(baseFormat, "thumb")
                ).distinct()
            }
        }

        if (cleanPath.startsWith("/profile_banners/")) {
            return if (PROFILE_BANNER_SIZE_REGEX.containsMatchIn(cleanPath)) {
                listOf(rawUrl)
            } else if (mode == "rich" || mode == "hd") {
                listOf("$origin$cleanPath/1500x500", "$origin$cleanPath/600x200", "$origin$cleanPath").distinct()
            } else {
                listOf("$origin$cleanPath/600x200", "$origin$cleanPath").distinct()
            }
        }

        if (cleanPath.startsWith("/profile_images/") && (mode == "rich" || mode == "hd")) {
            val origPath = cleanPath.replace(PROFILE_IMAGE_SIZE_REGEX, "$2")
            val dotIdx = origPath.lastIndexOf('.')
            val base = if (dotIdx > 0) origPath.substring(0, dotIdx) else origPath
            val ext = if (dotIdx > 0) origPath.substring(dotIdx) else ""
            return listOf(
                "$origin$origPath",
                "$origin${base}_400x400$ext",
                "$origin${base}_200x200$ext",
                rawUrl
            ).distinct()
        }

        val isVideoThumb = cleanPath.startsWith("/amplify_video_thumb/") ||
            cleanPath.startsWith("/ext_tw_video_thumb/") ||
            cleanPath.startsWith("/tweet_video_thumb/")
        if (isVideoThumb) {
            return when (mode) {
                "hd", "rich" -> listOf(
                    "$origin$cleanPath:large",
                    "$origin$cleanPath:medium",
                    "$origin$cleanPath:small",
                    "$origin$cleanPath"
                ).distinct()
                else -> listOf(
                    "$origin$cleanPath:thumb",
                    "$origin$cleanPath"
                ).distinct()
            }
        }

        return listOf(rawUrl)
    }
}
