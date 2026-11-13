package com.sovopr.xlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertEquals("image/webp", XLitePolicy.detectImageMime(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0, 'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())))
        assertEquals("image/png", XLitePolicy.detectImageMime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("image/jpeg", XLitePolicy.detectImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }
}
