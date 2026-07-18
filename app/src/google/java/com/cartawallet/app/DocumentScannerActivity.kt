package com.cartawallet.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Adapts the Play Services scanner to the same private-cache contract as the Offline flavor. */
class DocumentScannerActivity : AppCompatActivity() {
    private val sessionDirectory: File by lazy {
        File(cacheDir, "document_scan_${UUID.randomUUID()}").apply { mkdirs() }
    }
    private var delivered = false
    private var scannerStarted = false

    private val documentScanner by lazy {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(MAX_PAGES)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launchScanner()
    }

    private fun launchScanner() {
        if (scannerStarted) return
        scannerStarted = true
        documentScanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                try {
                    startIntentSenderForResult(
                        intentSender,
                        GOOGLE_SCANNER_REQUEST,
                        null,
                        0,
                        0,
                        0,
                    )
                } catch (_: Exception) {
                    finishWithError(R.string.google_document_open_failed)
                }
            }
            .addOnFailureListener {
                if (!isFinishing && !isDestroyed) finishWithError(R.string.google_document_unavailable)
            }
    }

    @Deprecated("Required by the Google Play Services document scanner contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GOOGLE_SCANNER_REQUEST) return
        if (resultCode != Activity.RESULT_OK) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
        val paths = result?.pages.orEmpty()
            .take(MAX_PAGES)
            .mapIndexedNotNull { index, page -> copyPageToCache(page.imageUri, index) }
        if (paths.isEmpty()) {
            finishWithError(R.string.google_document_copy_failed)
            return
        }

        delivered = true
        setResult(
            Activity.RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(paths)),
        )
        finish()
    }

    private fun copyPageToCache(uri: Uri, index: Int): String? = try {
        val target = File(sessionDirectory, "page_${index.toString().padStart(2, '0')}_${UUID.randomUUID()}.jpg")
        val input = contentResolver.openInputStream(uri) ?: return null
        input.use { source -> FileOutputStream(target).use(source::copyTo) }
        target.absolutePath
    } catch (_: Exception) {
        null
    }

    private fun finishWithError(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        if (!delivered) sessionDirectory.deleteRecursively()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAGE_PATHS = "document_page_paths"
        private const val GOOGLE_SCANNER_REQUEST = 301
        private const val MAX_PAGES = 10
    }
}
