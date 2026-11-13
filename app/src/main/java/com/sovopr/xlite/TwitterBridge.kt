package com.sovopr.xlite

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TwitterBridge(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("xlite_prefs", Context.MODE_PRIVATE)
    private val jsonStoreDir = File(context.cacheDir, "json_store").apply { if (!exists()) mkdirs() }
    private val pendingTempResults = ConcurrentHashMap<String, String>()
    private val client: OkHttpClient = sharedBaseClient.newBuilder()
        .addInterceptor(BrotliInterceptor)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val apkClient: OkHttpClient = sharedBaseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val requestExecutor = Executors.newCachedThreadPool()
    private val apkExecutor = Executors.newSingleThreadExecutor()
    private val apkDownloadInFlight = AtomicBoolean(false)
    @Volatile private var activeApkCall: Call? = null
    @Volatile private var pendingApkFile: File? = null
    @Volatile private var pendingApkVersion: String = ""

    companion object {
        const val BEARER_TOKEN = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        private val ALLOWED_POST_PATH_REGEX = Regex("^/i/api/graphql/[A-Za-z0-9_-]+/(CreateTweet|FavoriteTweet|UnfavoriteTweet|CreateRetweet|DeleteRetweet|CreateBookmark|DeleteBookmark|HomeTimeline|HomeLatestTimeline)$")
        private val ALLOWED_FORM_POST_PATH_REGEX = Regex("^/i/api/1\\.1/(?:jot/client_event|friendships/(?:create|destroy))\\.json$")
        private val ALLOWED_SIGNED_GET_PATH_REGEX = Regex("^/i/api/(?:graphql/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+|2/notifications/[A-Za-z0-9_.-]+\\.json)$")
        private val TRANSACTION_ID_REGEX = Regex("^[A-Za-z0-9+/]{80,120}$")
        private val ALLOWED_APK_PATH_REGEX = Regex("^/sovopr/xlite-android/releases/(?:latest/download|download/v[0-9]+\\.[0-9]+\\.[0-9]+)/xlite-fup-release\\.apk$")
        private val SEMVER_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        private const val MIN_APK_BYTES = 1_000_000L
        private const val MAX_APK_BYTES = 64L * 1024L * 1024L

        internal val sharedBaseClient: OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun buildRequest(url: String): Request {
        val parsedUrl = url.toHttpUrlOrNull()
        val isTwitter = parsedUrl?.isHttps == true && parsedUrl.host in setOf("x.com", "api.twitter.com", "twitter.com")
        val builder = Request.Builder().url(url)
        if (parsedUrl?.host == "x.com" && parsedUrl.encodedPath == "/home") {
            val authToken = prefs.getString("auth_token", "") ?: ""
            val ct0 = prefs.getString("ct0", "") ?: ""
            builder.addHeader("cookie", "auth_token=$authToken; ct0=$ct0")
                .addHeader("user-agent", USER_AGENT)
                .addHeader("accept", "text/html")
        } else if (isTwitter) {
            val authToken = prefs.getString("auth_token", "") ?: ""
            val ct0 = prefs.getString("ct0", "") ?: ""
            val cookieHeader = "auth_token=$authToken; ct0=$ct0"
            builder.addHeader("authorization", "Bearer $BEARER_TOKEN")
                .addHeader("x-csrf-token", ct0)
                .addHeader("cookie", cookieHeader)
                .addHeader("user-agent", USER_AGENT)
                .addHeader("x-twitter-active-user", "yes")
                .addHeader("x-twitter-client-language", "en")
        } else {
            builder.addHeader("user-agent", USER_AGENT)
                .addHeader("accept", "*/*")
        }
        return builder.build()
    }

    @JavascriptInterface
    fun getCookies(): String {
        val auth = prefs.getString("auth_token", "") ?: ""
        val ct0 = prefs.getString("ct0", "") ?: ""
        val imgMode = prefs.getString("image_mode", "tap") ?: "tap"
        return JSONObject().put("auth_token", auth).put("ct0", ct0).put("image_mode", imgMode).toString()
    }

    @JavascriptInterface
    fun saveConfig(authToken: String, ct0: String, imageMode: String) {
        prefs.edit()
            .putString("auth_token", authToken.trim())
            .putString("ct0", ct0.trim())
            .putString("image_mode", imageMode.trim())
            .apply()
    }

    private fun storeFileForKey(key: String): File {
        val safePrefix = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(jsonStoreDir, "${safePrefix}_$digest.json")
    }

    @JavascriptInterface
    fun takeTempResult(callbackId: String): String {
        return pendingTempResults.remove(callbackId)
            ?: getCache("temp_res_$callbackId").also { if (it.isNotEmpty()) prefs.edit().remove("cache_temp_res_$callbackId").apply() }
    }

    @JavascriptInterface
    fun getConnectionProfile(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
            val network = cm.activeNetwork ?: return "offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "online"
            }
        } catch (_: Exception) { "unknown" }
    }

    @JavascriptInterface
    fun saveCache(key: String, data: String) {
        if (key.startsWith("temp_res_")) {
            val cbId = key.removePrefix("temp_res_")
            if (data.isEmpty()) pendingTempResults.remove(cbId) else pendingTempResults[cbId] = data
            return
        }
        try {
            val target = storeFileForKey(key)
            if (data.isEmpty()) {
                if (target.exists()) target.delete()
            } else {
                val tmp = File.createTempFile("store_", ".tmp", jsonStoreDir)
                try {
                    tmp.writeText(data, Charsets.UTF_8)
                    tmp.renameTo(target)
                } finally {
                    tmp.delete()
                }
            }
            if (prefs.contains("cache_$key")) {
                prefs.edit().remove("cache_$key").apply()
            }
        } catch (_: Exception) {
            prefs.edit().putString("cache_$key", data).apply()
        }
    }

    @JavascriptInterface
    fun getCache(key: String): String {
        if (key.startsWith("temp_res_")) {
            val cbId = key.removePrefix("temp_res_")
            return pendingTempResults[cbId] ?: (prefs.getString("cache_$key", "") ?: "")
        }
        return try {
            val target = storeFileForKey(key)
            if (target.exists()) {
                target.readText(Charsets.UTF_8)
            } else {
                val legacy = prefs.getString("cache_$key", "") ?: ""
                if (legacy.isNotEmpty()) saveCache(key, legacy)
                legacy
            }
        } catch (_: Exception) {
            prefs.getString("cache_$key", "") ?: ""
        }
    }

    @JavascriptInterface
    fun clearCache() {
        pendingTempResults.clear()
        jsonStoreDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("feed_") || file.name.startsWith("seen_")) {
                try { file.delete() } catch (_: Exception) {}
            }
        }
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith("cache_feed_") || k.startsWith("cache_temp_res_")) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    @JavascriptInterface
    fun executeGet(url: String): String {
        if (!isAllowedGetUrl(url)) return errorPayload("Unsupported request URL")
        val request = buildRequest(url)
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    body
                } else {
                    """{"error": true, "status": ${response.code}, "message": ${JSONObject.quote(body.take(200).ifBlank { "HTTP ${response.code} from X" })}}"""
                }
            }
        } catch (e: Exception) {
            """{"error": true, "message": ${org.json.JSONObject.quote(e.message ?: "Network error")}}"""
        }
    }

    @JavascriptInterface
    fun executeGetAsync(url: String, callbackId: String) {
        if (!isAllowedGetUrl(url)) {
            saveCache("temp_res_$callbackId", errorPayload("Unsupported request URL"))
            (context as? MainActivity)?.dispatchJsCallback(callbackId)
            return
        }
        runAsyncRequest(buildRequest(url), callbackId)
    }

    @JavascriptInterface
    fun executeSignedGetAsync(url: String, transactionId: String, callbackId: String) {
        val parsed = url.toHttpUrlOrNull()
        val validUrl = parsed?.isHttps == true && parsed.host == "x.com" &&
            ALLOWED_SIGNED_GET_PATH_REGEX.matches(parsed.encodedPath)
        if (!validUrl || !TRANSACTION_ID_REGEX.matches(transactionId)) {
            finishAsyncRequest(callbackId, errorPayload("Invalid signed request"))
            return
        }
        val referer = when {
            parsed!!.encodedPath.startsWith("/i/api/2/notifications/") -> "https://x.com/notifications"
            parsed.encodedPath.contains("/SearchTimeline") -> "https://x.com/search"
            else -> "https://x.com/home"
        }
        requestExecutor.execute {
            try {
                val request = buildRequest(url).newBuilder()
                    .header("x-client-transaction-id", transactionId)
                    .header("x-twitter-auth-type", "OAuth2Session")
                    .header("referer", referer)
                    .build()
                runAsyncRequest(request, callbackId)
            } catch (e: Exception) {
                finishAsyncRequest(callbackId, errorPayload(e.message ?: "Request failed"))
            }
        }
    }

    @JavascriptInterface
    fun executeSearchGetAsync(url: String, transactionId: String, callbackId: String) {
        executeSignedGetAsync(url, transactionId, callbackId)
    }

    @JavascriptInterface
    fun executeFormPostWithTransactionAsync(url: String, formBody: String, transactionId: String, callbackId: String) {
        val parsedUrl = url.toHttpUrlOrNull()
        val validUrl = parsedUrl?.isHttps == true && parsedUrl.host == "x.com" &&
            ALLOWED_FORM_POST_PATH_REGEX.matches(parsedUrl.encodedPath)
        if (!validUrl || !TRANSACTION_ID_REGEX.matches(transactionId)) {
            finishAsyncRequest(callbackId, errorPayload("Unsupported form post URL"))
            return
        }
        val authToken = prefs.getString("auth_token", "") ?: ""
        val ct0 = prefs.getString("ct0", "") ?: ""
        val mediaType = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(mediaType))
            .addHeader("authorization", "Bearer $BEARER_TOKEN")
            .addHeader("x-csrf-token", ct0)
            .addHeader("cookie", "auth_token=$authToken; ct0=$ct0")
            .addHeader("user-agent", USER_AGENT)
            .addHeader("content-type", "application/x-www-form-urlencoded")
            .addHeader("x-twitter-active-user", "yes")
            .addHeader("x-twitter-client-language", "en")
            .addHeader("x-twitter-auth-type", "OAuth2Session")
            .addHeader("x-client-transaction-id", transactionId)
            .addHeader("referer", "https://x.com/home")
            .build()
        runAsyncRequest(request, callbackId)
    }

    private fun finishAsyncRequest(callbackId: String, payload: String) {
        pendingTempResults[callbackId] = payload
        (context as? MainActivity)?.dispatchJsCallback(callbackId)
    }

    private fun runAsyncRequest(request: Request, callbackId: String) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finishAsyncRequest(callbackId, errorPayload(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                val payload = try {
                    response.use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            body
                        } else {
                            """{"error": true, "status": ${resp.code}, "message": ${JSONObject.quote(body.take(200).ifBlank { "HTTP ${resp.code} from X" })}}"""
                        }
                    }
                } catch (e: Exception) {
                    errorPayload(e.message ?: "Could not finish reading X response")
                }
                finishAsyncRequest(callbackId, payload)
            }
        })
    }

    private fun buildPostRequest(url: String, jsonPayload: String, transactionId: String? = null): Request? {
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl?.isHttps != true || parsedUrl.host != "x.com" ||
            !ALLOWED_POST_PATH_REGEX.matches(parsedUrl.encodedPath)
        ) {
            return null
        }
        val authToken = prefs.getString("auth_token", "") ?: ""
        val ct0 = prefs.getString("ct0", "") ?: ""

        val cookieHeader = "auth_token=$authToken; ct0=$ct0"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("authorization", "Bearer $BEARER_TOKEN")
            .addHeader("x-csrf-token", ct0)
            .addHeader("cookie", cookieHeader)
            .addHeader("user-agent", USER_AGENT)
            .addHeader("content-type", "application/json")
            .addHeader("x-twitter-active-user", "yes")
            .addHeader("x-twitter-client-language", "en")
            .addHeader("x-twitter-auth-type", "OAuth2Session")
            .addHeader("referer", "https://x.com/home")
        if (!transactionId.isNullOrBlank()) {
            if (!TRANSACTION_ID_REGEX.matches(transactionId)) return null
            builder.addHeader("x-client-transaction-id", transactionId)
        }
        return builder.build()
    }

    @JavascriptInterface
    fun executePostWithTransactionAsync(url: String, jsonPayload: String, transactionId: String, callbackId: String) {
        if (!TRANSACTION_ID_REGEX.matches(transactionId)) {
            finishAsyncRequest(callbackId, errorPayload("Invalid request signature"))
            return
        }
        val request = buildPostRequest(url, jsonPayload, transactionId)
        if (request == null) {
            finishAsyncRequest(callbackId, errorPayload("Unsupported post URL"))
            return
        }
        runAsyncRequest(request, callbackId)
    }

    @JavascriptInterface
    fun executePostAsync(url: String, jsonPayload: String, callbackId: String) {
        val request = buildPostRequest(url, jsonPayload)
        if (request == null) {
            finishAsyncRequest(callbackId, errorPayload("Unsupported post URL"))
            return
        }
        runAsyncRequest(request, callbackId)
    }

    @JavascriptInterface
    fun executePost(url: String, jsonPayload: String): String {
        val request = buildPostRequest(url, jsonPayload) ?: return errorPayload("Unsupported post URL")

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    body
                } else {
                    """{"error": true, "status": ${response.code}, "message": ${JSONObject.quote(body.take(200).ifBlank { "HTTP ${response.code} from X" })}}"""
                }
            }
        } catch (e: Exception) {
            """{"error": true, "message": ${org.json.JSONObject.quote(e.message ?: "Post failed")}}"""
        }
    }

    @JavascriptInterface
    fun saveLiveHtml(htmlContent: String): Boolean {
        return try {
            if (!htmlContent.contains("<html") || !htmlContent.contains("mainContent") ||
                !htmlContent.trimEnd().endsWith("</html>", ignoreCase = true)) {
                return false
            }
            val liveFile = File(context.filesDir, "live_index.html")
            val tempFile = File.createTempFile("live_index_", ".tmp", context.filesDir)
            try {
                tempFile.writeText(htmlContent, Charsets.UTF_8)
                tempFile.renameTo(liveFile)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun resetLiveHtml(): Boolean {
        return try {
            val liveFile = File(context.filesDir, "live_index.html")
            if (liveFile.exists()) {
                liveFile.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun isUsingLiveHtml(): Boolean {
        return (context as? MainActivity)?.isUsingLiveHtml() ?: false
    }

    @JavascriptInterface
    fun reloadApp() {
        (context as? MainActivity)?.reloadAppUrl()
    }

    @JavascriptInterface
    fun openExternalUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        val allowed = (parsed.host == "github.com" && parsed.encodedPath.startsWith("/sovopr/xlite-android")) ||
            parsed.host == "x.com"
        if (!allowed) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun installApkUpdateAsync(url: String, expectedVersion: String): Boolean {
        val parsed = url.toHttpUrlOrNull()
        if (parsed?.isHttps != true || parsed.host != "github.com" ||
            !ALLOWED_APK_PATH_REGEX.matches(parsed.encodedPath)
        ) {
            emitApkEvent(JSONObject().put("state", "error").put("message", "Unsupported APK update URL"))
            return false
        }
        val cleanVersion = expectedVersion.trim().takeIf { SEMVER_REGEX.matches(it) } ?: ""
        if (!apkDownloadInFlight.compareAndSet(false, true)) return false

        apkExecutor.execute {
            var tmpFile: File? = null
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
                val fileVersion = cleanVersion.ifEmpty { "latest" }
                val targetFile = File(updatesDir, "xlite-update-$fileVersion.apk")

                if (verifyDownloadedApk(targetFile, cleanVersion)) {
                    proceedToInstall(targetFile, fileVersion)
                    return@execute
                }

                cleanupOldApks(updatesDir, targetFile.name)
                val downloadTmp = File(updatesDir, "xlite-update-$fileVersion.tmp")
                tmpFile = downloadTmp
                if (downloadTmp.exists()) downloadTmp.delete()

                emitApkEvent(
                    JSONObject()
                        .put("state", "downloading")
                        .put("version", fileVersion)
                        .put("bytes", 0L)
                        .put("totalBytes", -1L)
                        .put("percent", 0)
                )

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "*/*")
                    .addHeader("User-Agent", "XLite-Updater")
                    .build()
                val call = apkClient.newCall(request)
                activeApkCall = call

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} downloading APK")
                    }
                    val body = response.body ?: throw IOException("Empty APK response")
                    val totalBytes = body.contentLength()
                    if (totalBytes > MAX_APK_BYTES) {
                        throw IOException("APK exceeds maximum allowed size")
                    }

                    var totalRead = 0L
                    var lastEmitMs = 0L
                    var lastPercent = -1
                    body.byteStream().use { input ->
                        FileOutputStream(downloadTmp).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalRead += read
                                if (totalRead > MAX_APK_BYTES) {
                                    throw IOException("APK exceeds maximum allowed size")
                                }
                                output.write(buffer, 0, read)

                                val now = System.currentTimeMillis()
                                val percent = if (totalBytes > 0) ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
                                val shouldEmit = (percent >= 0 && percent != lastPercent && (now - lastEmitMs >= 350L || totalRead == totalBytes)) ||
                                    (percent < 0 && now - lastEmitMs >= 500L)
                                if (shouldEmit) {
                                    lastEmitMs = now
                                    lastPercent = percent
                                    emitApkEvent(
                                        JSONObject()
                                            .put("state", "downloading")
                                            .put("version", fileVersion)
                                            .put("bytes", totalRead)
                                            .put("totalBytes", totalBytes)
                                            .put("percent", percent)
                                    )
                                }
                            }
                        }
                    }

                    if (totalBytes > 0 && totalRead != totalBytes) {
                        throw IOException("Incomplete download ($totalRead / $totalBytes bytes)")
                    }
                }

                emitApkEvent(JSONObject().put("state", "verifying").put("version", fileVersion))
                if (targetFile.exists()) targetFile.delete()
                if (!downloadTmp.renameTo(targetFile)) {
                    throw IOException("Could not finalize downloaded APK")
                }
                if (!verifyDownloadedApk(targetFile, cleanVersion)) {
                    targetFile.delete()
                    throw IOException("Downloaded APK failed package verification")
                }

                proceedToInstall(targetFile, fileVersion)
            } catch (e: IOException) {
                if (activeApkCall?.isCanceled() == true) {
                    emitApkEvent(JSONObject().put("state", "cancelled").put("message", "Update download cancelled."))
                } else {
                    emitApkEvent(JSONObject().put("state", "error").put("message", e.message ?: "APK download failed"))
                }
            } catch (e: Exception) {
                emitApkEvent(JSONObject().put("state", "error").put("message", e.message ?: "APK update failed"))
            } finally {
                tmpFile?.takeIf { it.exists() }?.delete()
                activeApkCall = null
                apkDownloadInFlight.set(false)
            }
        }
        return true
    }

    @JavascriptInterface
    fun cancelApkDownload(): Boolean {
        val call = activeApkCall ?: return false
        call.cancel()
        return true
    }

    @JavascriptInterface
    fun getApkUpdateStatus(): String {
        val updatesDir = File(context.cacheDir, "updates")
        val cached = updatesDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("xlite-update-") && it.name.endsWith(".apk") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull { verifyDownloadedApk(it, "") }
        val cachedVersion = cached?.let { readApkPackageInfo(it.absolutePath)?.versionName }
        return JSONObject()
            .put("downloading", apkDownloadInFlight.get())
            .put("canInstall", canInstallPackages())
            .put("cachedVersion", cachedVersion ?: JSONObject.NULL)
            .toString()
    }

    internal fun resumePendingApkInstallIfPermitted() {
        val file = pendingApkFile ?: return
        val version = pendingApkVersion
        if (!canInstallPackages()) return
        pendingApkFile = null
        pendingApkVersion = ""
        if (verifyDownloadedApk(file, version)) {
            launchApkInstaller(file, version)
        } else {
            emitApkEvent(JSONObject().put("state", "error").put("message", "Cached update missing or invalid; please tap Update again."))
        }
    }

    private fun proceedToInstall(apkFile: File, versionLabel: String) {
        if (canInstallPackages()) {
            pendingApkFile = null
            pendingApkVersion = ""
            launchApkInstaller(apkFile, versionLabel)
        } else {
            openUnknownSourcesSettings(apkFile, versionLabel)
        }
    }

    private fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    private fun openUnknownSourcesSettings(apkFile: File, versionLabel: String) {
        pendingApkFile = apkFile
        pendingApkVersion = versionLabel
        emitApkEvent(
            JSONObject()
                .put("state", "permission_required")
                .put("version", versionLabel)
                .put("message", "Enable 'Allow from this source' in Android Settings — return to X Lite to install v$versionLabel.")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { }
        }
    }

    private fun launchApkInstaller(apkFile: File, versionLabel: String): Boolean {
        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            emitApkEvent(JSONObject().put("state", "installing").put("version", versionLabel))
            true
        } catch (e: Exception) {
            emitApkEvent(JSONObject().put("state", "error").put("message", e.message ?: "Could not open Android installer"))
            false
        }
    }

    private fun verifyDownloadedApk(file: File, expectedVersion: String): Boolean {
        if (!file.exists() || file.length() < MIN_APK_BYTES) return false
        val header = ByteArray(4)
        try {
            file.inputStream().use { input ->
                if (input.read(header) != 4) return false
            }
        } catch (_: Exception) {
            return false
        }
        val isZipHeader = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        if (!isZipHeader) return false

        val archiveInfo = readApkPackageInfo(file.absolutePath) ?: return false
        if (archiveInfo.packageName != context.packageName) return false
        if (expectedVersion.isNotBlank() && SEMVER_REGEX.matches(expectedVersion) && archiveInfo.versionName != expectedVersion) {
            return false
        }
        val installedInfo = getInstalledPackageInfo() ?: return true
        return PackageInfoCompat.getLongVersionCode(archiveInfo) >= PackageInfoCompat.getLongVersionCode(installedInfo)
    }

    private fun cleanupOldApks(updatesDir: File, keepName: String) {
        updatesDir.listFiles()?.forEach { file ->
            if (file.name != keepName) {
                file.delete()
            }
        }
    }

    private fun readApkPackageInfo(filePath: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    filePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(filePath, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getInstalledPackageInfo(): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun emitApkEvent(json: JSONObject) {
        (context as? MainActivity)?.dispatchApkUpdateEvent(json.toString())
    }

    @JavascriptInterface
    fun getAppVersionInfo(): String {
        val isLive = isUsingLiveHtml()
        val info = getInstalledPackageInfo()
        return JSONObject()
            .put("version", info?.versionName ?: "unknown")
            .put("build", info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L)
            .put("isLive", isLive)
            .toString()
    }

    private fun errorPayload(message: String) = JSONObject().put("error", true).put("message", message).toString()

    private fun isAllowedGetUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps) return false
        return when (parsed.host) {
            "x.com" -> parsed.encodedPath == "/home" || parsed.encodedPath.startsWith("/i/api/")
            "api.twitter.com" -> parsed.encodedPath.startsWith("/i/api/") || parsed.encodedPath.startsWith("/1.1/search/")
            "abs.twimg.com" -> Regex("^/responsive-web/client-web/ondemand\\.s\\.[0-9a-f]+a\\.js$").matches(parsed.encodedPath)
            "gist.githubusercontent.com" -> parsed.encodedPath.startsWith("/sovopr/c52e8a1490b3a5415525b27063726bbe/")
            else -> false
        }
    }
}
