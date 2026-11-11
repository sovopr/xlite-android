package com.sovopr.xlite

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
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
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

        val bridge = TwitterBridge(this)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        val imgCacheDir = File(cacheDir, "images")
        if (!imgCacheDir.exists()) imgCacheDir.mkdirs()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                if (!request.isForMainFrame) return false
                if (url.scheme == "file" && (url.path == "/android_asset/index.html" || url.path == File(filesDir, "live_index.html").absolutePath)) return false
                if (url.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) { }
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.scheme == "https" && url.host == "local.xlite" && url.path == "/img") {
                    val targetUrl = url.getQueryParameter("url") ?: return null
                    val mode = url.getQueryParameter("mode") ?: "thumb"
                    return handleImageRequest(targetUrl, mode, imgCacheDir)
                        ?: WebResourceResponse(
                            "image/webp", "UTF-8", 503, "Image unavailable",
                            mapOf("Cache-Control" to "no-store"),
                            ByteArrayInputStream(ByteArray(0))
                        )
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

        loadInitialHtml()
    }

    fun loadInitialHtml() {
        val liveIndex = File(filesDir, "live_index.html")
        if (isUsingLiveHtml()) {
            webView.loadUrl("file://${liveIndex.absolutePath}")
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    fun isUsingLiveHtml(): Boolean {
        val liveIndex = File(filesDir, "live_index.html")
        if (!liveIndex.exists() || liveIndex.length() <= 500) return false
        return try {
            val bundled = assets.open("index.html").bufferedReader().use { it.readText() }
            val installed = readPageVersion(bundled)
            val live = readPageVersion(liveIndex.readText())
            live != null && installed != null && compareVersions(live, installed) > 0
        } catch (_: Exception) { false }
    }

    private fun readPageVersion(html: String): List<Int>? =
        Regex("const APP_VERSION = '([0-9]+)\\.([0-9]+)\\.([0-9]+)'")
            .find(html)?.groupValues?.drop(1)?.map { it.toInt() }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (index in 0..2) {
            val comparison = a[index].compareTo(b[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun reloadAppUrl() {
        runOnUiThread {
            loadInitialHtml()
        }
    }

    fun dispatchJsCallback(callbackId: String) {
        runOnUiThread {
            webView.evaluateJavascript("window.__onAsyncFinish(${org.json.JSONObject.quote(callbackId)});", null)
        }
    }

    private fun handleImageRequest(targetUrl: String, mode: String, cacheDir: File): WebResourceResponse? {
        val imageUrl = try { Uri.parse(targetUrl) } catch (_: Exception) { return null }
        if (imageUrl.scheme != "https" || imageUrl.host != "pbs.twimg.com") return null
        if (mode != "thumb" && mode != "hd") return null
        val hash = sha256("$targetUrl-$mode")
        val cacheFile = File(cacheDir, "$hash.webp")

        if (cacheFile.exists()) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cacheFile.absolutePath, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    return WebResourceResponse("image/webp", "UTF-8", FileInputStream(cacheFile))
                }
            } catch (_: Exception) { }
            cacheFile.delete()
        }

        return try {
            // Fetch X's small variant over cellular. Recompressing an original after
            // downloading it does not save any mobile data.
            val imageFormat = imageUrl.getQueryParameter("format")
                ?: imageUrl.lastPathSegment?.substringAfterLast('.', "jpg")
                ?: "jpg"
            if (imageFormat !in setOf("jpg", "jpeg", "png", "webp")) return null
            val upstreamUrl = if (imageUrl.path?.startsWith("/media/") == true) {
                imageUrl.buildUpon().clearQuery()
                    .appendQueryParameter("format", if (imageFormat == "jpeg") "jpg" else imageFormat)
                    .appendQueryParameter("name", if (mode == "hd") "medium" else "thumb")
                    .build().toString()
            } else {
                targetUrl
            }
            val maxBytes = if (mode == "hd") 512 * 1024 else 96 * 1024
            val req = Request.Builder()
                .url(upstreamUrl)
                .addHeader("User-Agent", TwitterBridge.USER_AGENT)
                .build()

            val rawBytes = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                if (body.contentLength() > maxBytes) return null
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > maxBytes) return null
                        output.write(buffer, 0, size)
                    }
                }
                output.toByteArray()
            }

            val maxDim = if (mode == "hd") 900 else 380
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDim * 2 || bounds.outHeight / sampleSize > maxDim * 2) sampleSize *= 2
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return null
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

            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            if (!scaled.compress(format, quality, bos)) return null
            val webpBytes = bos.toByteArray()
            if (webpBytes.isEmpty()) return null

            try {
                val tempFile = File.createTempFile("image-", ".tmp", cacheDir)
                try {
                    tempFile.writeBytes(webpBytes)
                    tempFile.renameTo(cacheFile)
                } finally {
                    tempFile.delete()
                }
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
