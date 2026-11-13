package com.sovopr.xlite

import java.net.URI
import java.net.URISyntaxException
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
    private val SHA256_LINE_REGEX = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    private fun parseHttpsUri(url: String): URI? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (uri.rawUserInfo != null) return null
            if (uri.host.isNullOrBlank()) return null
            if (uri.port != -1 && uri.port != 443) return null
            uri
        } catch (_: URISyntaxException) {
            null
        }
    }

    fun isAllowedGetUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val path = uri.rawPath ?: return false
        if (path.contains("..")) return false
        return when (uri.host.lowercase()) {
            "x.com" -> path == "/home" || path.startsWith("/i/api/")
            "api.twitter.com" -> path.startsWith("/i/api/") || path.startsWith("/1.1/search/")
            "abs.twimg.com" -> Regex("^/responsive-web/client-web/ondemand\\.s\\.[0-9a-f]+a\\.js$").matches(path)
            "gist.githubusercontent.com" -> path.startsWith("/sovopr/c52e8a1490b3a5415525b27063726bbe/")
            else -> false
        }
    }

    fun isAllowedPostUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val path = uri.rawPath ?: return false
        return uri.host.equals("x.com", ignoreCase = true) && ALLOWED_POST_PATH_REGEX.matches(path)
    }

    fun isAllowedFormPostUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val path = uri.rawPath ?: return false
        return uri.host.equals("x.com", ignoreCase = true) && ALLOWED_FORM_POST_PATH_REGEX.matches(path)
    }

    fun isAllowedSignedGetUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val path = uri.rawPath ?: return false
        return uri.host.equals("x.com", ignoreCase = true) && ALLOWED_SIGNED_GET_PATH_REGEX.matches(path)
    }

    fun isValidTransactionId(transactionId: String): Boolean =
        TRANSACTION_ID_REGEX.matches(transactionId)

    fun resolveSignedReferer(encodedPath: String): String = when {
        encodedPath.startsWith("/i/api/2/notifications/") -> "https://x.com/notifications"
        encodedPath.contains("/SearchTimeline") -> "https://x.com/search"
        else -> "https://x.com/home"
    }

    fun isAllowedApkUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val path = uri.rawPath ?: return false
        return uri.host.equals("github.com", ignoreCase = true) && ALLOWED_APK_PATH_REGEX.matches(path)
    }

    fun isAllowedExternalUrl(url: String): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        val host = uri.host.lowercase()
        val path = uri.rawPath ?: ""
        return (host == "github.com" && (path.startsWith("/sovopr/xlite-android") || path.startsWith("/sovopr/xlite-releases"))) ||
            host == "x.com"
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
        val uri = parseHttpsUri(apkUrl) ?: return null
        val path = uri.rawPath ?: return null
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return null
        return "https://${uri.host}${path.substring(0, slash + 1)}SHA256SUMS.txt"
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
        val uri = parseHttpsUri(rawUrl) ?: return listOf(rawUrl)
        val rawPath = uri.rawPath ?: ""
        val cleanPath = rawPath.replace(TRAILING_TWITTER_SIZE_REGEX, "")
        val baseFormat = if (imageFormat.equals("jpeg", ignoreCase = true)) "jpg" else imageFormat.lowercase()
        val origin = "https://${uri.host}"

        if (cleanPath.startsWith("/media/")) {
            val withQuery = { fmt: String, sizeName: String -> "$origin$cleanPath?format=$fmt&name=$sizeName" }
            return when (mode) {
                "hd" -> listOf(
                    withQuery("webp", "large"),
                    withQuery("webp", "medium"),
                    withQuery(baseFormat, "medium"),
                    withQuery("webp", "small"),
                    withQuery(baseFormat, "small")
                ).distinct()
                "rich" -> listOf(
                    withQuery("webp", "small"),
                    withQuery(baseFormat, "small"),
                    withQuery("webp", "medium"),
                    withQuery(baseFormat, "medium")
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
            } else {
                listOf("$origin$cleanPath/600x200", "$origin$cleanPath").distinct()
            }
        }

        val isVideoThumb = cleanPath.startsWith("/amplify_video_thumb/") ||
            cleanPath.startsWith("/ext_tw_video_thumb/") ||
            cleanPath.startsWith("/tweet_video_thumb/")
        if (isVideoThumb) {
            return when (mode) {
                "hd" -> listOf(
                    "$origin$cleanPath:large",
                    "$origin$cleanPath:medium",
                    "$origin$cleanPath:small",
                    "$origin$cleanPath"
                ).distinct()
                "rich" -> listOf(
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
