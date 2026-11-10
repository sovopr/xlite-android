package com.sovopr.xlite

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TwitterBridge(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("xlite_prefs", Context.MODE_PRIVATE)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val requestExecutor = Executors.newSingleThreadExecutor()

    companion object {
        const val BEARER_TOKEN = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
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

    @JavascriptInterface
    fun saveCache(key: String, data: String) {
        prefs.edit().putString("cache_$key", data).apply()
    }

    @JavascriptInterface
    fun getCache(key: String): String {
        return prefs.getString("cache_$key", "") ?: ""
    }

    @JavascriptInterface
    fun clearCache() {
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
        runAsyncGet(buildRequest(url), callbackId)
    }

    @JavascriptInterface
    fun executeSearchGetAsync(url: String, transactionId: String, callbackId: String) {
        val parsed = url.toHttpUrlOrNull()
        val validSearchUrl = parsed?.isHttps == true && parsed.host == "x.com" &&
            Regex("^/i/api/graphql/[A-Za-z0-9_-]+/SearchTimeline$").matches(parsed.encodedPath)
        val validId = Regex("^[A-Za-z0-9+/]{90,110}$").matches(transactionId)
        if (!validSearchUrl || !validId) {
            saveCache("temp_res_$callbackId", errorPayload("Invalid search request"))
            (context as? MainActivity)?.dispatchJsCallback(callbackId)
            return
        }
        requestExecutor.execute {
            try {
                val request = buildRequest(url).newBuilder()
                    .header("x-client-transaction-id", transactionId)
                    .header("x-twitter-auth-type", "OAuth2Session")
                    .header("referer", "https://x.com/search")
                    .build()
                runAsyncGet(request, callbackId)
            } catch (e: Exception) {
                saveCache("temp_res_$callbackId", errorPayload(e.message ?: "Search request failed"))
                (context as? MainActivity)?.dispatchJsCallback(callbackId)
            }
        }
    }

    private fun runAsyncGet(request: Request, callbackId: String) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val errPayload = """{"error": true, "message": ${org.json.JSONObject.quote(e.message ?: "Network error")}}"""
                saveCache("temp_res_$callbackId", errPayload)
                (context as? MainActivity)?.dispatchJsCallback(callbackId)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string() ?: ""
                    val payload = if (resp.isSuccessful) {
                        body
                    } else {
                        """{"error": true, "status": ${resp.code}, "message": ${JSONObject.quote(body.take(200).ifBlank { "HTTP ${resp.code} from X" })}}"""
                    }
                    saveCache("temp_res_$callbackId", payload)
                    (context as? MainActivity)?.dispatchJsCallback(callbackId)
                }
            }
        })
    }

    @JavascriptInterface
    fun executePost(url: String, jsonPayload: String): String {
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl?.isHttps != true || parsedUrl.host != "x.com" || !parsedUrl.encodedPath.endsWith("/CreateTweet")) {
            return errorPayload("Unsupported post URL")
        }
        val authToken = prefs.getString("auth_token", "") ?: ""
        val ct0 = prefs.getString("ct0", "") ?: ""

        val cookieHeader = "auth_token=$authToken; ct0=$ct0"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("authorization", "Bearer $BEARER_TOKEN")
            .addHeader("x-csrf-token", ct0)
            .addHeader("cookie", cookieHeader)
            .addHeader("user-agent", USER_AGENT)
            .addHeader("content-type", "application/json")
            .addHeader("x-twitter-active-user", "yes")
            .addHeader("x-twitter-client-language", "en")
            .build()

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
            if (!htmlContent.contains("<html") || !htmlContent.contains("mainContent")) {
                return false
            }
            val liveFile = File(context.filesDir, "live_index.html")
            liveFile.writeText(htmlContent, Charsets.UTF_8)
            true
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
    fun getAppVersionInfo(): String {
        val isLive = isUsingLiveHtml()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("version", info.versionName ?: "unknown")
            .put("build", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
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
