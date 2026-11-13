package com.sovopr.xlite

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: TwitterBridge
    private lateinit var videoStreamProxy: VideoStreamProxy
    private var fullscreenView: View? = null
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val client = TwitterBridge.sharedBaseClient.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val richClient = TwitterBridge.sharedBaseClient.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val hdPrimaryClient = TwitterBridge.sharedBaseClient.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    private val hdFallbackClient = TwitterBridge.sharedBaseClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val cacheCleanupExecutor = Executors.newSingleThreadExecutor()
    private val cacheWritesCount = AtomicInteger(0)

    companion object {
        private const val MAX_DISK_IMAGE_CACHE_BYTES = 80L * 1024L * 1024L
        private val IMAGE_RESPONSE_HEADERS = mapOf(
            "Cache-Control" to "public, max-age=604800",
            "Access-Control-Allow-Origin" to "*"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.setBackgroundColor(0xFF000000.toInt())
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        bridge = TwitterBridge(this)
        videoStreamProxy = VideoStreamProxy.get(this)
        cacheCleanupExecutor.execute { videoStreamProxy.getProxyPort() }
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val root = findViewById<ViewGroup>(android.R.id.content) ?: return
                val container = FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(0xFF000000.toInt())
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                root.addView(
                    container,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenView = view
                fullscreenContainer = container
                fullscreenCallback = callback
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, container).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() {
                exitFullscreenVideo()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) {
                    exitFullscreenVideo()
                } else if (webView.canGoBack()) {
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
            live != null && installed != null && XLitePolicy.compareSemver(live, installed) > 0
        } catch (_: Exception) { false }
    }

    private fun readPageVersion(html: String): String? =
        Regex("const APP_VERSION = '([0-9]+\\.[0-9]+\\.[0-9]+)'")
            .find(html)?.groupValues?.getOrNull(1)

    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized) {
            bridge.resumePendingApkInstallIfPermitted()
        }
    }

    override fun onDestroy() {
        exitFullscreenVideo()
        super.onDestroy()
    }

    private fun exitFullscreenVideo() {
        val view = fullscreenView ?: return
        val container = fullscreenContainer
        val callback = fullscreenCallback
        fullscreenView = null
        fullscreenContainer = null
        fullscreenCallback = null
        try {
            if (container != null) {
                (container.parent as? ViewGroup)?.removeView(container)
                container.removeView(view)
            }
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            callback?.onCustomViewHidden()
        } catch (_: Exception) {}
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

    fun dispatchApkUpdateEvent(payloadJson: String) {
        runOnUiThread {
            webView.evaluateJavascript("window.__onApkUpdateEvent && window.__onApkUpdateEvent(${org.json.JSONObject.quote(payloadJson)});", null)
        }
    }

    private fun fetchImageBytes(httpClient: OkHttpClient, upstreamUrl: String, maxBytes: Int): ByteArray? {
        return try {
            val req = Request.Builder()
                .url(upstreamUrl)
                .addHeader("User-Agent", TwitterBridge.USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
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
                val bytes = output.toByteArray()
                if (bytes.isEmpty()) null else bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildImageCandidateUrls(imageUrl: Uri, mode: String, imageFormat: String): List<String> =
        XLitePolicy.buildImageCandidateUrls(imageUrl.toString(), mode, imageFormat)

    private fun detectImageMime(bytes: ByteArray, fallbackFormat: String = "webp"): String =
        XLitePolicy.detectImageMime(bytes, fallbackFormat)

    private fun detectImageMimeFromFile(file: File): String {
        val header = ByteArray(12)
        return try {
            FileInputStream(file).use { input ->
                val read = input.read(header)
                if (read > 0) detectImageMime(header.copyOf(read)) else "image/webp"
            }
        } catch (_: Exception) { "image/webp" }
    }

    private fun writeImageCacheFile(cacheDir: File, cacheFile: File, bytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("image-", ".tmp", cacheDir)
            try {
                tempFile.writeBytes(bytes)
                tempFile.renameTo(cacheFile)
            } finally {
                tempFile.delete()
            }
            if (cacheWritesCount.incrementAndGet() % 30 == 0) {
                cacheCleanupExecutor.execute { trimDiskCacheIfNeeded(cacheDir, MAX_DISK_IMAGE_CACHE_BYTES) }
            }
        } catch (_: Exception) {}
    }

    private fun trimDiskCacheIfNeeded(cacheDir: File, maxBytes: Long) {
        try {
            val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            val target = (maxBytes * 7) / 10
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= target) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {}
    }

    private fun handleImageRequest(targetUrl: String, mode: String, cacheDir: File): WebResourceResponse? {
        val imageUrl = try { Uri.parse(targetUrl) } catch (_: Exception) { return null }
        if (imageUrl.scheme != "https" || imageUrl.host != "pbs.twimg.com") return null
        if (mode !in setOf("thumb", "rich", "hd")) return null
        val hash = sha256("$targetUrl-$mode")
        val cacheFile = File(cacheDir, "$hash.img")
        val legacyCacheFile = File(cacheDir, "$hash.webp")

        val existingCache = when {
            cacheFile.exists() && cacheFile.length() > 16 -> cacheFile
            legacyCacheFile.exists() && legacyCacheFile.length() > 16 -> legacyCacheFile
            else -> null
        }
        if (existingCache != null) {
            val mime = detectImageMimeFromFile(existingCache)
            return WebResourceResponse(mime, null, 200, "OK", IMAGE_RESPONSE_HEADERS, FileInputStream(existingCache))
        }

        return try {
            val cleanLastSegment = (imageUrl.lastPathSegment ?: "")
                .replace(Regex(":(thumb|small|medium|large|orig)$"), "")
            val isBannerOrCard = cleanLastSegment.isNotEmpty() &&
                ((imageUrl.path ?: "").startsWith("/profile_banners/") || (imageUrl.path ?: "").startsWith("/card_img/"))
            val imageFormat = (imageUrl.getQueryParameter("format")
                ?: cleanLastSegment.substringAfterLast('.', if (isBannerOrCard) "jpg" else "jpg"))
                .lowercase()
            if (!isBannerOrCard && imageFormat !in setOf("jpg", "jpeg", "png", "webp")) return null

            val maxBytes = when (mode) {
                "hd", "rich" -> 2560 * 1024
                else -> 256 * 1024
            }
            val candidates = buildImageCandidateUrls(imageUrl, mode, imageFormat)
            var rawBytes: ByteArray? = null
            for ((index, candidate) in candidates.withIndex()) {
                val activeClient = when {
                    mode == "hd" && index < 2 -> hdPrimaryClient
                    mode == "hd" -> hdFallbackClient
                    mode == "rich" -> richClient
                    else -> client
                }
                rawBytes = fetchImageBytes(activeClient, candidate, maxBytes)
                if (rawBytes != null) break
            }
            if (rawBytes == null) return null

            // Rich mode and well-sized images pass through without CPU recompression for fast scrolling & sharp pixels.
            val rawMime = detectImageMime(rawBytes, imageFormat)
            val passThrough = mode == "rich" || (mode == "hd" && rawBytes.size <= 380 * 1024 && rawMime in setOf("image/webp", "image/jpeg"))
            if (passThrough) {
                writeImageCacheFile(cacheDir, cacheFile, rawBytes)
                return WebResourceResponse(rawMime, null, 200, "OK", IMAGE_RESPONSE_HEADERS, ByteArrayInputStream(rawBytes))
            }

            val maxDim = if (mode == "hd") 1440 else 380
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

            val quality = if (mode == "hd") 82 else 32
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

            writeImageCacheFile(cacheDir, cacheFile, webpBytes)
            WebResourceResponse("image/webp", null, 200, "OK", IMAGE_RESPONSE_HEADERS, ByteArrayInputStream(webpBytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(input: String): String = XLitePolicy.sha256Hex(input.toByteArray())
}
