package com.sovopr.xlite

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
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
import java.util.concurrent.TimeUnit

class TwitterBridge(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("xlite_prefs", Context.MODE_PRIVATE)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val BEARER_TOKEN = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    }

    private fun buildRequest(url: String): Request {
        val isTwitter = url.contains("twitter.com") || url.contains("x.com")
        val builder = Request.Builder().url(url)
        if (isTwitter) {
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
        return """{"auth_token":"$auth","ct0":"$ct0","image_mode":"$imgMode"}"""
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
            if (k.startsWith("cache_")) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    @JavascriptInterface
    fun executeGet(url: String): String {
        val request = buildRequest(url)
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    body
                } else {
                    """{"error": true, "status": ${response.code}, "message": ${org.json.JSONObject.quote(body.take(200))}}"""
                }
            }
        } catch (e: Exception) {
            """{"error": true, "message": ${org.json.JSONObject.quote(e.message ?: "Network error")}}"""
        }
    }

    @JavascriptInterface
    fun executeGetAsync(url: String, callbackId: String) {
        val request = buildRequest(url)
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
                        """{"error": true, "status": ${resp.code}, "message": ${org.json.JSONObject.quote(body.take(200))}}"""
                    }
                    saveCache("temp_res_$callbackId", payload)
                    (context as? MainActivity)?.dispatchJsCallback(callbackId)
                }
            }
        })
    }

    @JavascriptInterface
    fun executePost(url: String, jsonPayload: String): String {
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
                    """{"error": true, "status": ${response.code}, "message": ${org.json.JSONObject.quote(body.take(200))}}"""
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
        val liveFile = File(context.filesDir, "live_index.html")
        return liveFile.exists() && liveFile.length() > 500
    }

    @JavascriptInterface
    fun reloadApp() {
        (context as? MainActivity)?.reloadAppUrl()
    }

    @JavascriptInterface
    fun getAppVersionInfo(): String {
        val isLive = isUsingLiveHtml()
        return """{"version": "1.0.2", "isLive": $isLive}"""
    }
}
