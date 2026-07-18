package com.cartawallet.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingPermissionCallback: (() -> Unit)? = null
    @Volatile private var pendingBackupBytes: ByteArray? = null

    private val mediaDirectory: File by lazy {
        File(filesDir, LOCAL_MEDIA_DIR).apply { mkdirs() }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
        private const val FILE_CHOOSER_REQUEST = 101
        private const val BARCODE_SCANNER_REQUEST = 102
        private const val DOCUMENT_SCANNER_REQUEST = 103
        private const val BACKUP_SAVE_REQUEST = 104
        private const val PREFERENCES_NAME = "carta_wallet"
        private const val DB_KEY = "carta-wallet-cards"
        private const val LANGUAGE_KEY = "app-language"
        private const val DEFAULT_LANGUAGE = "it"
        private const val LOCAL_MEDIA_DIR = "wallet_media"
        private const val LOCAL_MEDIA_HOST = "media.cartawallet.local"
        private const val BACKUP_SCHEMA = "loya-wallet-backup"
        private const val BACKUP_VERSION = 1
        private const val MAX_BACKUP_BYTES = 64L * 1024L * 1024L
        private const val MAX_BACKUP_CARDS = 1000
        private const val MAX_BACKUP_PHOTOS = 2000
        private const val MAX_CARD_PHOTOS = 50
        private const val MAX_BACKUP_IMAGE_BYTES = 12L * 1024L * 1024L
        private const val MAX_BACKUP_IMAGE_TOTAL_BYTES = 56L * 1024L * 1024L
        private const val MAX_BACKUP_IMAGE_SIDE = 6000
        private const val MAX_BACKUP_IMAGE_PIXELS = 16L * 1024L * 1024L
        private const val MAX_SOURCE_IMAGE_BYTES = 32L * 1024L * 1024L
        private const val MAX_SOURCE_IMAGE_SIDE = 16000
        private const val MAX_SOURCE_IMAGE_PIXELS = 80L * 1024L * 1024L
        private const val BACKUP_IMAGE_SIDE = 1800
    }

    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredLanguage()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(webView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "window._handleAndroidBack ? window._handleAndroidBack() : 'exit';",
                ) { result ->
                    if (result.contains("exit")) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (uri.scheme == "https" && uri.host == LOCAL_MEDIA_HOST) {
                    return localMediaResponse(uri)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val data = preferences.getString(DB_KEY, null)
                pruneUnusedMedia(data)
                if (data != null) {
                    view?.evaluateJavascript(
                        "if(window._injectCards) window._injectCards(${JSONObject.quote(data)});",
                        null,
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                if (!request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) return
                if (hasCameraPermission()) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingPermissionCallback = {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    }
                    requestCameraPermission()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                val isImage = acceptTypes.any { it.startsWith("image/") }
                val isCaptureEnabled = fileChooserParams?.isCaptureEnabled == true
                when {
                    isImage && isCaptureEnabled -> launchCamera()
                    isImage -> showImagePicker()
                    else -> showFilePicker(fileChooserParams)
                }
                return true
            }
        }

        webView.setBackgroundColor(0xFFF5F4EF.toInt())
    }

    private fun localMediaResponse(uri: Uri): WebResourceResponse? {
        val file = privateMediaFile(uri.toString()) ?: return null
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
        return WebResourceResponse(mime, null, FileInputStream(file)).apply {
            responseHeaders = mapOf("Cache-Control" to "private, max-age=31536000, immutable")
        }
    }

    /** Rimuove solo acquisizioni mai confermate, senza toccare file citati nel wallet. */
    private fun pruneUnusedMedia(savedJson: String?) {
        val metadata = savedJson.orEmpty()
        mediaDirectory.listFiles()?.forEach { file ->
            if (file.isFile && !metadata.contains(file.name)) file.delete()
        }
    }

    private fun launchNativeBarcodeScanner() {
        if (!hasCameraPermission()) {
            pendingPermissionCallback = { launchNativeBarcodeScanner() }
            requestCameraPermission()
            return
        }
        startActivityForResult(Intent(this, BarcodeScannerActivity::class.java), BARCODE_SCANNER_REQUEST)
    }

    private fun launchDocumentScanner() {
        if (resources.getBoolean(R.bool.document_scanner_requires_camera) && !hasCameraPermission()) {
            pendingPermissionCallback = { launchDocumentScanner() }
            requestCameraPermission()
            return
        }
        startActivityForResult(Intent(this, DocumentScannerActivity::class.java), DOCUMENT_SCANNER_REQUEST)
    }

    private fun launchCamera() {
        if (!hasCameraPermission()) {
            pendingPermissionCallback = { launchCamera() }
            requestCameraPermission()
            return
        }
        val photoFile = File.createTempFile("photo_", ".jpg", cacheDir)
        cameraPhotoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
        }
        startActivityForResult(cameraIntent, FILE_CHOOSER_REQUEST)
    }

    private fun showImagePicker() {
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(galleryIntent, getString(R.string.choose_image)), FILE_CHOOSER_REQUEST)
    }

    private fun showFilePicker(params: WebChromeClient.FileChooserParams?) {
        val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, FILE_CHOOSER_REQUEST)
    }

    @Deprecated("Kept for WebView and document-scanner compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            BACKUP_SAVE_REQUEST -> finishBackupSave(resultCode, data)

            FILE_CHOOSER_REQUEST -> {
                val result = if (resultCode == Activity.RESULT_OK) data?.data ?: cameraPhotoUri else null
                fileUploadCallback?.onReceiveValue(result?.let { arrayOf(it) })
                fileUploadCallback = null
                cameraPhotoUri = null
            }

            BARCODE_SCANNER_REQUEST -> {
                if (resultCode != Activity.RESULT_OK) {
                    webView.evaluateJavascript(
                        "if(window._onNativeBarcodeScanCancelled) window._onNativeBarcodeScanCancelled();",
                        null,
                    )
                    return
                }
                val value = data?.getStringExtra(BarcodeScannerActivity.EXTRA_VALUE) ?: return
                val format = data.getStringExtra(BarcodeScannerActivity.EXTRA_FORMAT) ?: "code128"
                webView.evaluateJavascript(
                    "if(window._onNativeBarcodeScan) window._onNativeBarcodeScan(" +
                        "${JSONObject.quote(value)},${JSONObject.quote(format)});",
                    null,
                )
            }

            DOCUMENT_SCANNER_REQUEST -> {
                if (resultCode != Activity.RESULT_OK) {
                    webView.evaluateJavascript(
                        "if(window._onNativeDocumentScanCancelled) window._onNativeDocumentScanCancelled();",
                        null,
                    )
                    return
                }
                val urls = data?.getStringArrayListExtra(DocumentScannerActivity.EXTRA_PAGE_PATHS)
                    .orEmpty()
                    .mapNotNull(::copyPathToPrivateMedia)
                if (urls.isEmpty()) {
                    notifyDocumentScanError(getString(R.string.no_pages_captured))
                } else {
                    webView.evaluateJavascript(
                        "if(window._onNativeDocumentScan) window._onNativeDocumentScan(${JSONArray(urls)});",
                        null,
                    )
                }
            }
        }
    }

    private fun finishBackupSave(resultCode: Int, data: Intent?) {
        val bytes = pendingBackupBytes
        pendingBackupBytes = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            notifyWeb("_onNativeBackupExportCancelled")
            return
        }
        if (bytes == null) {
            notifyWeb("_onNativeBackupExportError")
            return
        }
        val success = runCatching {
            contentResolver.openOutputStream(data.data!!, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Unable to open backup destination")
        }.isSuccess
        notifyWeb(if (success) "_onNativeBackupExported" else "_onNativeBackupExportError")
    }

    private fun notifyWeb(callback: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(window.$callback) window.$callback();", null)
        }
    }

    private fun copyPathToPrivateMedia(path: String): String? {
        return try {
            val source = File(path)
            if (!source.isFile || !source.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)) return null
            val name = "scan_${UUID.randomUUID()}.jpg"
            val target = File(mediaDirectory, name)
            source.inputStream().use { input -> FileOutputStream(target).use(input::copyTo) }
            source.delete()
            source.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
            localMediaUrl(name)
        } catch (_: Exception) {
            null
        }
    }

    private data class PortableImage(val mime: String, val bytes: ByteArray)

    private fun decodePortableImage(dataUrl: String): PortableImage? {
        val match = Regex("^data:image/(jpeg|png|webp);base64,([A-Za-z0-9+/]+={0,2})$").matchEntire(dataUrl)
            ?: return null
        val encoded = match.groupValues[2]
        if (encoded.length % 4 != 0) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BACKUP_IMAGE_BYTES) return null
        val mime = match.groupValues[1]
        return validatedPortableImage(mime, bytes)
    }

    private fun imageBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun dimensionsAllowed(width: Int, height: Int, maxSide: Int, maxPixels: Long): Boolean =
        width in 1..maxSide && height in 1..maxSide && width.toLong() * height.toLong() <= maxPixels

    private fun validatedPortableImage(mime: String, bytes: ByteArray): PortableImage? {
        if (bytes.isEmpty() || bytes.size > MAX_BACKUP_IMAGE_BYTES || !imageSignatureMatches(mime, bytes)) return null
        val (width, height) = imageBounds(bytes) ?: return null
        if (!dimensionsAllowed(width, height, MAX_BACKUP_IMAGE_SIDE, MAX_BACKUP_IMAGE_PIXELS)) return null
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
            )
        }.getOrNull() ?: return null
        val decodedCorrectly = bitmap.width > 0 && bitmap.height > 0 &&
            dimensionsAllowed(bitmap.width, bitmap.height, MAX_BACKUP_IMAGE_SIDE, MAX_BACKUP_IMAGE_PIXELS)
        bitmap.recycle()
        return if (decodedCorrectly) PortableImage(mime, bytes) else null
    }

    private fun normalizedBackupImage(bytes: ByteArray): PortableImage? {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_IMAGE_BYTES) return null
        val (sourceWidth, sourceHeight) = imageBounds(bytes) ?: return null
        if (!dimensionsAllowed(sourceWidth, sourceHeight, MAX_SOURCE_IMAGE_SIDE, MAX_SOURCE_IMAGE_PIXELS)) return null

        var sampleSize = 1
        while (sourceWidth / sampleSize > BACKUP_IMAGE_SIDE * 2 ||
            sourceHeight / sampleSize > BACKUP_IMAGE_SIDE * 2 ||
            sourceWidth.toLong() * sourceHeight.toLong() / sampleSize / sampleSize > MAX_BACKUP_IMAGE_PIXELS
        ) {
            sampleSize *= 2
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }.getOrNull() ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) {
            decoded.recycle()
            return null
        }

        val scale = minOf(1.0, BACKUP_IMAGE_SIDE.toDouble() / maxOf(decoded.width, decoded.height).toDouble())
        val targetWidth = maxOf(1, (decoded.width * scale).toInt())
        val targetHeight = maxOf(1, (decoded.height * scale).toInt())
        val target = runCatching {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(
                        decoded,
                        null,
                        Rect(0, 0, targetWidth, targetHeight),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                }
            }
        }.getOrNull()
        decoded.recycle()
        target ?: return null

        val output = ByteArrayOutputStream()
        val compressed = runCatching { target.compress(Bitmap.CompressFormat.JPEG, 86, output) }.getOrDefault(false)
        target.recycle()
        if (!compressed) return null
        return validatedPortableImage("jpeg", output.toByteArray())
    }

    private fun imageSignatureMatches(mime: String, bytes: ByteArray): Boolean = when (mime) {
        "jpeg" -> bytes.size >= 3 &&
            bytes[0].toInt() and 0xff == 0xff &&
            bytes[1].toInt() and 0xff == 0xd8 &&
            bytes[2].toInt() and 0xff == 0xff
        "png" -> bytes.size >= 8 &&
            bytes[0].toInt() and 0xff == 0x89 &&
            bytes.copyOfRange(1, 4).contentEquals("PNG".toByteArray()) &&
            bytes[4].toInt() and 0xff == 0x0d && bytes[5].toInt() and 0xff == 0x0a &&
            bytes[6].toInt() and 0xff == 0x1a && bytes[7].toInt() and 0xff == 0x0a
        "webp" -> bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        else -> false
    }

    private fun privateMediaFile(url: String): File? {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != LOCAL_MEDIA_HOST || uri.port != -1 ||
            uri.userInfo != null || uri.query != null || uri.fragment != null || uri.pathSegments.size != 1
        ) return null
        val fileName = uri.pathSegments.single()
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$").matches(fileName)) return null
        val file = File(mediaDirectory, fileName)
        if (!file.isFile || file.canonicalFile.parentFile != mediaDirectory.canonicalFile) return null
        return file
    }

    private fun portableImageForUrl(url: String): PortableImage? {
        decodePortableImage(url)?.let { return it }
        val dataUrl = Regex("^data:image/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/]+={0,2})$").matchEntire(url)
        if (dataUrl != null) {
            val encoded = dataUrl.groupValues[1]
            if (encoded.length % 4 != 0) return null
            val decoded = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
            return normalizedBackupImage(decoded)
        }
        val file = privateMediaFile(url) ?: return null
        if (file.length() <= 0 || file.length() > MAX_SOURCE_IMAGE_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!file.name.startsWith("scan_")) {
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "jpeg"
                "png" -> "png"
                "webp" -> "webp"
                else -> null
            }
            if (mime != null) validatedPortableImage(mime, bytes)?.let { return it }
        }
        return normalizedBackupImage(bytes)
    }

    private fun requiredString(json: JSONObject, key: String, maxLength: Int, allowEmpty: Boolean = true): String {
        val value = json.opt(key) as? String ?: error("Invalid $key")
        if (value.length > maxLength || (!allowEmpty && value.isBlank())) error("Invalid $key")
        return value
    }

    private fun eanChecksumValid(code: String): Boolean {
        if (code.isEmpty() || code.any { it !in '0'..'9' }) return false
        val check = code.last().digitToInt()
        var sum = 0
        var multiplier = 3
        for (index in code.length - 2 downTo 0) {
            sum += code[index].digitToInt() * multiplier
            multiplier = if (multiplier == 3) 1 else 3
        }
        return (10 - sum % 10) % 10 == check
    }

    private fun upceValid(code: String): Boolean {
        if (!Regex("^\\d{7,8}$").matches(code) || code.first() !in "01") return false
        if (code.length == 7) return true
        val numberSystem = code[0]
        val value = code.substring(1, 7)
        val check = code[7]
        val expanded = when (value[5]) {
            '0', '1', '2' -> "$numberSystem${value.substring(0, 2)}${value[5]}0000${value.substring(2, 5)}$check"
            '3' -> "$numberSystem${value.substring(0, 3)}00000${value.substring(3, 5)}$check"
            '4' -> "$numberSystem${value.substring(0, 4)}00000${value[4]}$check"
            else -> "$numberSystem${value.substring(0, 5)}0000${value[5]}$check"
        }
        return eanChecksumValid(expanded)
    }

    private fun codeMatchesFormat(code: String, format: String): Boolean {
        if (code.isEmpty()) return false
        return when (format) {
            "ean13" -> code.length == 13 && eanChecksumValid(code)
            "ean8" -> code.length == 8 && eanChecksumValid(code)
            "upc" -> code.length == 12 && eanChecksumValid(code)
            "upce" -> upceValid(code)
            "itf" -> code.all { it in '0'..'9' } && code.length % 2 == 0
            "code39" -> Regex("^[0-9A-Z\\-. $/+%]+$").matches(code)
            "code93" -> code.all { it.code in 0..127 }
            "codabar" -> Regex("^[A-Da-d][0-9\\-\\$:/\\.+]+[A-Da-d]$").matches(code)
            "code128", "qr", "pdf417", "aztec", "datamatrix" -> true
            else -> false
        }
    }

    private fun buildBackupBytes(cardsJson: String, exportedAt: String): ByteArray {
        if (cardsJson.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) error("Wallet metadata is too large")
        val sourceCards = JSONArray(cardsJson)
        if (sourceCards.length() > MAX_BACKUP_CARDS) error("Too many cards")
        val outputCards = JSONArray()
        val ids = mutableSetOf<String>()
        var totalPhotos = 0
        var totalImageBytes = 0L

        repeat(sourceCards.length()) { index ->
            val source = sourceCards.optJSONObject(index) ?: error("Invalid card")
            val id = requiredString(source, "id", 128, false)
            if (!Regex("^[A-Za-z0-9_-]+$").matches(id) || !ids.add(id)) error("Invalid card id")
            val name = requiredString(source, "name", 200, false)
            val category = requiredString(source, "category", 20, false)
            if (category !in setOf("fedelta", "trasporti", "documenti", "altro")) error("Invalid category")
            val code = requiredString(source, "code", 16384)
            val format = requiredString(source, "format", 20)
            val allowedFormats = setOf("ean13", "ean8", "upc", "upce", "code128", "code39", "code93", "codabar", "itf", "qr", "pdf417", "aztec", "datamatrix")
            if (category == "documenti") {
                if (code.isNotEmpty() || format.isNotEmpty()) error("Invalid document")
            } else if (code.isEmpty() || format !in allowedFormats || !codeMatchesFormat(code, format)) {
                error("Invalid code")
            }
            val color = requiredString(source, "color", 7, false)
            if (!Regex("^#[0-9A-Fa-f]{6}$").matches(color)) error("Invalid color")
            val notes = requiredString(source, "notes", 20000)
            val createdAtValue = source.opt("createdAt") as? Number ?: error("Invalid timestamp")
            val createdAt = createdAtValue.toLong()
            if (createdAt < 0 || createdAt > 9_007_199_254_740_991L || createdAtValue.toDouble() != createdAt.toDouble()) {
                error("Invalid timestamp")
            }

            val sourcePhotos = source.optJSONArray("photos") ?: error("Invalid photos")
            if (sourcePhotos.length() > MAX_CARD_PHOTOS) error("Too many photos")
            val outputPhotos = JSONArray()
            repeat(sourcePhotos.length()) { photoIndex ->
                val photoUrl = sourcePhotos.opt(photoIndex) as? String ?: error("Invalid photo")
                val image = portableImageForUrl(photoUrl) ?: error("Invalid photo")
                totalPhotos += 1
                totalImageBytes += image.bytes.size
                if (totalPhotos > MAX_BACKUP_PHOTOS || totalImageBytes > MAX_BACKUP_IMAGE_TOTAL_BYTES) {
                    error("Backup images are too large")
                }
                outputPhotos.put(
                    "data:image/${image.mime};base64," + Base64.encodeToString(image.bytes, Base64.NO_WRAP),
                )
            }

            outputCards.put(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("category", category)
                    .put("code", code)
                    .put("format", format)
                    .put("color", color)
                    .put("notes", notes)
                    .put("photos", outputPhotos)
                    .put("createdAt", createdAt),
            )
        }

        val safeExportedAt = exportedAt.takeIf { it.length in 1..64 } ?: error("Invalid export date")
        val backup = JSONObject()
            .put("schema", BACKUP_SCHEMA)
            .put("version", BACKUP_VERSION)
            .put("exportedAt", safeExportedAt)
            .put("source", "android")
            .put("cards", outputCards)
        val bytes = backup.toString(2).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BACKUP_BYTES) error("Backup is too large")
        return bytes
    }

    private fun storeDataUrl(dataUrl: String): String {
        return try {
            val image = decodePortableImage(dataUrl) ?: return ""
            val extension = when (image.mime) {
                "png" -> "png"
                "webp" -> "webp"
                else -> "jpg"
            }
            val name = "image_${UUID.randomUUID()}.$extension"
            FileOutputStream(File(mediaDirectory, name)).use { it.write(image.bytes) }
            localMediaUrl(name)
        } catch (_: Exception) {
            ""
        }
    }

    private fun localMediaUrl(fileName: String) = "https://$LOCAL_MEDIA_HOST/$fileName"

    private fun deleteLocalMedia(url: String) {
        privateMediaFile(url)?.delete()
    }

    private fun notifyDocumentScanError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(
            "if(window._onNativeDocumentScanError) window._onNativeDocumentScanError(${JSONObject.quote(message)});",
            null,
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingPermissionCallback?.invoke()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
        }
        pendingPermissionCallback = null
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun saveData(key: String, value: String): Boolean =
            preferences.edit().putString(key, value).commit()

        @JavascriptInterface
        fun loadData(key: String): String = preferences.getString(key, "") ?: ""

        @JavascriptInterface
        fun getLanguage(): String = storedLanguage()

        @JavascriptInterface
        fun setLanguage(code: String) {
            val language = normalizeLanguage(code) ?: return
            if (preferences.getString(LANGUAGE_KEY, null) != language) {
                preferences.edit { putString(LANGUAGE_KEY, language) }
            }
            runOnUiThread {
                val locales = LocaleListCompat.forLanguageTags(language)
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }
        }

        @JavascriptInterface
        fun getDistribution(): String = getString(R.string.distribution_id)

        @JavascriptInterface
        fun scanBarcode() {
            runOnUiThread { launchNativeBarcodeScanner() }
        }

        @JavascriptInterface
        fun scanDocument() {
            runOnUiThread { launchDocumentScanner() }
        }

        @JavascriptInterface
        fun storeImage(dataUrl: String): String = storeDataUrl(dataUrl)

        @JavascriptInterface
        fun exportBackup(cardsJson: String, exportedAt: String, suggestedName: String): Boolean {
            if (pendingBackupBytes != null) return false
            val bytes = runCatching { buildBackupBytes(cardsJson, exportedAt) }.getOrNull() ?: return false
            val fileName = suggestedName.takeIf {
                Regex("^loya-backup-\\d{4}-\\d{2}-\\d{2}\\.json$").matches(it)
            } ?: "loya-backup.json"
            pendingBackupBytes = bytes
            runOnUiThread {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, fileName)
                }
                runCatching { startActivityForResult(intent, BACKUP_SAVE_REQUEST) }
                    .onFailure {
                        pendingBackupBytes = null
                        notifyWeb("_onNativeBackupExportError")
                    }
            }
            return true
        }

        @JavascriptInterface
        fun deleteImage(url: String) {
            deleteLocalMedia(url)
        }

        @JavascriptInterface
        fun setMaxBrightness() {
            runOnUiThread {
                window.attributes = window.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                }
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun restoreBrightness() {
            runOnUiThread {
                window.attributes = window.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(ms.coerceIn(10, 500).toLong())
        }
    }

    private fun applyStoredLanguage() {
        val language = storedLanguage()
        if (!preferences.contains(LANGUAGE_KEY)) {
            preferences.edit { putString(LANGUAGE_KEY, language) }
        }
        val locales = LocaleListCompat.forLanguageTags(language)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun storedLanguage(): String =
        normalizeLanguage(preferences.getString(LANGUAGE_KEY, null)) ?: DEFAULT_LANGUAGE

    private fun normalizeLanguage(code: String?): String? = when (code?.trim()?.lowercase()) {
        "it", "it-it" -> "it"
        "en", "en-us", "en-gb" -> "en"
        else -> null
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
