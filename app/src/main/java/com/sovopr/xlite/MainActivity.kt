package com.sovopr.xlite

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.setBackgroundColor(0xFF000000.toInt())
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        val bridge = TwitterBridge(this)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        val imgCacheDir = File(cacheDir, "images")
        if (!imgCacheDir.exists()) imgCacheDir.mkdirs()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.scheme == "https" && url.host == "local.xlite" && url.path == "/img") {
                    val targetUrl = url.getQueryParameter("url") ?: return null
                    val mode = url.getQueryParameter("mode") ?: "thumb"
                    return handleImageRequest(targetUrl, mode, imgCacheDir)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun handleImageRequest(targetUrl: String, mode: String, cacheDir: File): WebResourceResponse? {
        val hash = sha256("$targetUrl-$mode")
        val cacheFile = File(cacheDir, "$hash.webp")

        if (cacheFile.exists()) {
            return try {
                WebResourceResponse("image/webp", "UTF-8", FileInputStream(cacheFile))
            } catch (e: Exception) {
                null
            }
        }

        return try {
            val req = Request.Builder()
                .url(targetUrl)
                .addHeader("User-Agent", TwitterBridge.USER_AGENT)
                .build()

            val rawBytes = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes() ?: return null
            }

            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

            val maxDim = if (mode == "hd") 900 else 380
            val width = original.width
            val height = original.height

            val scaled = if (width > maxDim || height > maxDim) {
                val ratio = width.toFloat() / height.toFloat()
                val targetW = if (width > height) maxDim else (maxDim * ratio).toInt()
                val targetH = if (width > height) (maxDim / ratio).toInt() else maxDim
                Bitmap.createScaledBitmap(original, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), true)
            } else {
                original
            }

            val quality = if (mode == "hd") 65 else 32
            val bos = ByteArrayOutputStream()
            
            // Android 11+ supports WEBP_LOSSY; Android 7+ supports WEBP
            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            scaled.compress(format, quality, bos)
            val webpBytes = bos.toByteArray()

            try {
                cacheFile.writeBytes(webpBytes)
            } catch (_: Exception) {}

            WebResourceResponse("image/webp", "UTF-8", ByteArrayInputStream(webpBytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
