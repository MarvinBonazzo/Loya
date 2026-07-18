package com.cartawallet.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Scanner documenti locale multipagina basato su Camera2 e OpenCV. */
class DocumentScannerActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var camera: Camera2Controller
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var captureButton: TextView
    private lateinit var finishButton: TextView
    private lateinit var torchButton: TextView
    private lateinit var removeButton: TextView
    private lateinit var lastPreview: ImageView
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val pageFiles = mutableListOf<File>()
    private val sessionDirectory by lazy {
        File(cacheDir, "document_scan_${UUID.randomUUID()}").apply { mkdirs() }
    }
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, R.string.document_engine_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        cacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("document_scan_") }
            ?.forEach(File::deleteRecursively)

        setContentView(buildContentView())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelScan()
        })
        camera = Camera2Controller(
            activity = this,
            textureView = textureView,
            mode = Camera2Controller.Mode.DOCUMENT,
            onReady = { hasFlash ->
                torchButton.visibility = if (hasFlash) View.VISIBLE else View.INVISIBLE
                statusText.text = getString(R.string.document_status_ready)
                textureView.postDelayed({ camera.triggerFocus() }, 300)
            },
            onError = { message -> runOnUiThread { statusText.text = message } },
        )
        updatePageUi()
    }

    override fun onResume() {
        super.onResume()
        if (::camera.isInitialized) camera.start()
    }

    override fun onPause() {
        if (::camera.isInitialized) camera.stop()
        super.onPause()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF071A17.toInt()) }
        textureView = TextureView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        root.addView(DocumentOverlayView(this), FrameLayout.LayoutParams(-1, -1))

        val closeButton = pillButton(getString(R.string.scanner_close)).apply { setOnClickListener { cancelScan() } }
        root.addView(closeButton, FrameLayout.LayoutParams(dp(92), dp(48), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16)
            topMargin = dp(18)
        })
        val localBadge = pillButton(getString(R.string.document_offline_badge)).apply {
            setTextColor(0xFFD7FF72.toInt())
            textSize = 9.5f
        }
        root.addView(localBadge, FrameLayout.LayoutParams(dp(166), dp(40), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(16)
            topMargin = dp(22)
        })

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(17))
            background = roundedDrawable(0xF0122521.toInt(), dp(25).toFloat())
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DocumentScannerActivity).apply {
                text = getString(R.string.document_scanner_title)
                setTextColor(Color.WHITE)
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@DocumentScannerActivity).apply {
                text = getString(R.string.document_scanner_subtitle)
                setTextColor(0xAFFFFFFF.toInt())
                textSize = 10.5f
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        countText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF102B25.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedDrawable(0xFFD7FF72.toInt(), dp(18).toFloat())
        }
        titleRow.addView(countText, LinearLayout.LayoutParams(dp(74), dp(36)))
        bottomPanel.addView(titleRow, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            text = getString(R.string.document_starting_scanner)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
        }
        bottomPanel.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val importButton = actionButton(getString(R.string.document_gallery)).apply { setOnClickListener { openGallery() } }
        torchButton = actionButton(getString(R.string.document_torch)).apply {
            visibility = View.INVISIBLE
            setOnClickListener {
                val enabled = camera.toggleTorch()
                text = getString(if (enabled) R.string.scanner_torch_off else R.string.document_torch)
            }
        }
        removeButton = actionButton(getString(R.string.document_remove_last)).apply {
            isEnabled = false
            setOnClickListener { removeLastPage() }
        }
        toolsRow.addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(7) })
        toolsRow.addView(torchButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(7) })
        toolsRow.addView(removeButton, LinearLayout.LayoutParams(0, dp(44), 1.35f))
        bottomPanel.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        lastPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedDrawable(0x22FFFFFF, dp(13).toFloat())
        }
        mainRow.addView(lastPreview, LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(12) })
        captureButton = TextView(this).apply {
            text = getString(R.string.document_capture)
            gravity = Gravity.CENTER
            setTextColor(0xFF102B25.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFD7FF72.toInt())
                setStroke(dp(4), Color.WHITE)
            }
            setOnClickListener { capturePage() }
        }
        mainRow.addView(captureButton, LinearLayout.LayoutParams(dp(70), dp(70)))
        finishButton = TextView(this).apply {
            text = getString(R.string.document_save)
            gravity = Gravity.CENTER
            setTextColor(0xFF102B25.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedDrawable(0xFFFFFFFF.toInt(), dp(15).toFloat())
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { finishSuccess() }
        }
        mainRow.addView(finishButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(12) })
        bottomPanel.addView(mainRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(12)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (closeButton.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(12)
                closeButton.layoutParams = this
            }
            (localBadge.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(16)
                localBadge.layoutParams = this
            }
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + dp(10)
                bottomPanel.layoutParams = this
            }
            insets
        }
        textureView.setOnClickListener {
            camera.triggerFocus()
            statusText.text = getString(R.string.scanner_status_focusing)
            statusText.postDelayed({
                if (!processing.get()) statusText.text = getString(R.string.document_status_ready)
            }, 600)
        }
        return root
    }

    private fun capturePage() {
        if (pageFiles.size >= MAX_PAGES) {
            statusText.text = resources.getQuantityString(
                R.plurals.document_page_limit,
                MAX_PAGES,
                MAX_PAGES,
            )
            return
        }
        if (!processing.compareAndSet(false, true)) return
        setBusy(true, getString(R.string.document_capturing))
        val started = camera.captureJpeg { capture ->
            processingExecutor.execute {
                val target = nextPageFile()
                val success = DocumentImageProcessor.processJpeg(capture.bytes, capture.rotationDegrees, target)
                runOnUiThread { completePage(target, success) }
            }
        }
        if (!started) {
            processing.set(false)
            setBusy(false, getString(R.string.document_camera_not_ready))
        }
    }

    private fun openGallery() {
        if (processing.get()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(intent, getString(R.string.document_import_pages)), GALLERY_REQUEST)
    }

    @Deprecated("Compatibilita' con Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != GALLERY_REQUEST || resultCode != Activity.RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            } ?: data.data?.let(::add)
        }.take((MAX_PAGES - pageFiles.size).coerceAtLeast(0))
        if (uris.isEmpty() || !processing.compareAndSet(false, true)) return
        val firstIndex = pageFiles.size
        setBusy(true, getString(R.string.document_correcting_pages))
        processingExecutor.execute {
            val importedFiles = mutableListOf<File>()
            uris.forEachIndexed { index, uri ->
                val target = nextPageFile(firstIndex + index)
                if (DocumentImageProcessor.processUri(contentResolver, uri, target)) {
                    importedFiles.add(target)
                } else {
                    target.delete()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    importedFiles.forEach(File::delete)
                    return@runOnUiThread
                }
                pageFiles.addAll(importedFiles)
                processing.set(false)
                val imported = importedFiles.size
                setBusy(
                    false,
                    if (imported > 0) {
                        resources.getQuantityString(R.plurals.document_pages_corrected, imported, imported)
                    } else {
                        getString(R.string.document_images_unreadable)
                    },
                )
                updatePageUi()
            }
        }
    }

    private fun completePage(target: File, success: Boolean) {
        if (isFinishing || isDestroyed) {
            target.delete()
            return
        }
        processing.set(false)
        if (success) {
            pageFiles.add(target)
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE))
            setBusy(false, getString(R.string.document_page_corrected))
        } else {
            target.delete()
            setBusy(false, getString(R.string.document_processing_failed))
        }
        updatePageUi()
    }

    private fun setBusy(busy: Boolean, message: String) {
        captureButton.isEnabled = !busy
        captureButton.alpha = if (busy) 0.45f else 1f
        finishButton.isEnabled = !busy && pageFiles.isNotEmpty()
        statusText.text = message
    }

    private fun updatePageUi() {
        countText.text = getString(R.string.document_page_count, pageFiles.size, MAX_PAGES)
        val hasPages = pageFiles.isNotEmpty()
        finishButton.isEnabled = hasPages && !processing.get()
        finishButton.alpha = if (finishButton.isEnabled) 1f else 0.45f
        removeButton.isEnabled = hasPages && !processing.get()
        removeButton.alpha = if (removeButton.isEnabled) 1f else 0.42f
        if (hasPages) {
            val options = BitmapFactory.Options().apply { inSampleSize = 6 }
            lastPreview.setImageBitmap(BitmapFactory.decodeFile(pageFiles.last().absolutePath, options))
        } else {
            lastPreview.setImageDrawable(null)
        }
    }

    private fun removeLastPage() {
        if (processing.get()) return
        pageFiles.removeLastOrNull()?.delete()
        statusText.text = getString(
            if (pageFiles.isEmpty()) R.string.document_status_ready else R.string.document_last_page_removed,
        )
        updatePageUi()
    }

    private fun finishSuccess() {
        if (processing.get() || pageFiles.isEmpty()) return
        delivered = true
        setResult(
            Activity.RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pageFiles.map(File::getAbsolutePath))),
        )
        finish()
    }

    private fun cancelScan() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        processingExecutor.shutdownNow()
        if (!delivered) sessionDirectory.deleteRecursively()
        super.onDestroy()
    }

    private fun nextPageFile(index: Int = pageFiles.size): File =
        File(sessionDirectory, "page_${index.toString().padStart(2, '0')}_${UUID.randomUUID()}.jpg")

    private fun pillButton(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = roundedDrawable(0x99122521.toInt(), dp(24).toFloat())
        isClickable = true
        isFocusable = true
    }

    private fun actionButton(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 10.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = roundedDrawable(0x22FFFFFF, dp(13).toFloat())
        isClickable = true
        isFocusable = true
    }

    private fun roundedDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAGE_PATHS = "document_page_paths"
        private const val GALLERY_REQUEST = 201
        private const val MAX_PAGES = 10
    }
}

private class DocumentOverlayView(context: android.content.Context) : View(context) {
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x64000000 }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD7FF72.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = dp(11).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val cutout = Path()
    private val frame = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameWidth = width * 0.86f
        val frameHeight = minOf(frameWidth * 1.37f, height * 0.56f)
        val left = (width - frameWidth) / 2f
        val top = height * 0.14f
        frame.set(left, top, left + frameWidth, top + frameHeight)
        cutout.reset()
        cutout.fillType = Path.FillType.EVEN_ODD
        cutout.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        cutout.addRoundRect(frame, dp(14).toFloat(), dp(14).toFloat(), Path.Direction.CW)
        canvas.drawPath(cutout, dimPaint)
        canvas.drawRoundRect(frame, dp(14).toFloat(), dp(14).toFloat(), framePaint)
        canvas.drawText(context.getString(R.string.document_guide), width / 2f, frame.top - dp(14), guidePaint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
