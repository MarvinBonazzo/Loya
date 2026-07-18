package com.cartawallet.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX feeds the bundled ML Kit model; decoded values never leave this activity. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class BarcodeScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var torchButton: TextView
    private lateinit var statusText: TextView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processingFrame = AtomicBoolean(false)
    private var camera: Camera? = null
    private var torchEnabled = false
    private var resultDelivered = false

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
            )
            .enableAllPotentialBarcodes()
            .build(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContentView(buildContentView())
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyseFrame)

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                torchButton.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
                statusText.text = getString(R.string.google_scanner_status_ready)
                previewView.postDelayed({ focusAtCenter() }, 300)
            } catch (_: Exception) {
                statusText.text = getString(R.string.camera_unavailable)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyseFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || resultDelivered || !processingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val decoded = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (decoded != null && !resultDelivered) deliverResult(decoded)
            }
            .addOnCompleteListener {
                processingFrame.set(false)
                imageProxy.close()
            }
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 26, 23)) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(GoogleScannerOverlayView(this), FrameLayout.LayoutParams(-1, -1))

        val close = pillButton(
            getString(R.string.scanner_close),
            getString(R.string.scanner_close_description),
        ).apply { setOnClickListener { finish() } }
        root.addView(close, FrameLayout.LayoutParams(dp(92), dp(48), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(18)
            topMargin = dp(18)
        })

        val badge = pillButton(
            getString(R.string.google_scanner_badge),
            getString(R.string.google_scanner_badge_description),
        ).apply {
            setTextColor(0xFFD7FF72.toInt())
            textSize = 9f
        }
        root.addView(badge, FrameLayout.LayoutParams(dp(214), dp(40), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(18)
            topMargin = dp(22)
        })

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = roundedDrawable(0xE6122521.toInt(), dp(24).toFloat())
        }
        bottomPanel.addView(TextView(this).apply {
            text = getString(R.string.scanner_title)
            setTextColor(Color.WHITE)
            textSize = 21f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, -2))
        bottomPanel.addView(TextView(this).apply {
            text = getString(R.string.scanner_subtitle)
            setTextColor(0xBFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(5))
        }, LinearLayout.LayoutParams(-1, -2))
        statusText = TextView(this).apply {
            text = getString(R.string.scanner_starting_camera)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(13))
        }
        bottomPanel.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        torchButton = pillButton(
            getString(R.string.scanner_torch_on),
            getString(R.string.scanner_torch_description),
        ).apply {
            setTextColor(0xFF08251E.toInt())
            background = roundedDrawable(0xFFD7FF72.toInt(), dp(24).toFloat())
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        bottomPanel.addView(torchButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (close.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(12)
                close.layoutParams = this
            }
            (badge.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(16)
                badge.layoutParams = this
            }
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + dp(12)
                bottomPanel.layoutParams = this
            }
            insets
        }

        previewView.setOnClickListener {
            focusAtCenter()
            statusText.text = getString(R.string.scanner_status_focusing)
            statusText.postDelayed({
                if (!resultDelivered) statusText.text = getString(R.string.google_scanner_status_ready)
            }, 650)
        }
        return root
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        torchButton.text = getString(if (torchEnabled) R.string.scanner_torch_off else R.string.scanner_torch_on)
    }

    private fun focusAtCenter() {
        val activeCamera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
        activeCamera.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun deliverResult(barcode: Barcode) {
        val value = barcode.rawValue ?: return
        if (resultDelivered || value.isBlank()) return
        resultDelivered = true
        runOnUiThread {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_VALUE, value)
                    .putExtra(EXTRA_FORMAT, formatName(barcode.format)),
            )
            finish()
        }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_EAN_13 -> "ean13"
        Barcode.FORMAT_EAN_8 -> "ean8"
        Barcode.FORMAT_UPC_A -> "upc"
        Barcode.FORMAT_UPC_E -> "upce"
        Barcode.FORMAT_CODE_39 -> "code39"
        Barcode.FORMAT_CODE_93 -> "code93"
        Barcode.FORMAT_CODABAR -> "codabar"
        Barcode.FORMAT_ITF -> "itf"
        Barcode.FORMAT_QR_CODE -> "qr"
        Barcode.FORMAT_PDF417 -> "pdf417"
        Barcode.FORMAT_AZTEC -> "aztec"
        Barcode.FORMAT_DATA_MATRIX -> "datamatrix"
        else -> "code128"
    }

    private fun pillButton(label: String, description: String) = TextView(this).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = roundedDrawable(0x99122521.toInt(), dp(24).toFloat())
        isClickable = true
        isFocusable = true
    }

    private fun roundedDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        barcodeScanner.close()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VALUE = "barcode_value"
        const val EXTRA_FORMAT = "barcode_format"
    }
}

private class GoogleScannerOverlayView(context: android.content.Context) : View(context) {
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x72000000 }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD7FF72.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88D7FF72.toInt()
        strokeWidth = dp(2).toFloat()
    }
    private val frame = RectF()
    private val cutoutPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameWidth = width * 0.84f
        val frameHeight = minOf(frameWidth * 0.64f, height * 0.42f)
        val left = (width - frameWidth) / 2f
        val top = height * 0.24f
        frame.set(left, top, left + frameWidth, top + frameHeight)
        cutoutPath.reset()
        cutoutPath.fillType = Path.FillType.EVEN_ODD
        cutoutPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        cutoutPath.addRoundRect(frame, dp(24).toFloat(), dp(24).toFloat(), Path.Direction.CW)
        canvas.drawPath(cutoutPath, dimPaint)
        canvas.drawRoundRect(frame, dp(24).toFloat(), dp(24).toFloat(), framePaint)
        val progress = (System.currentTimeMillis() % 1500L) / 1500f
        val scanY = frame.top + dp(28) + progress * (frame.height() - dp(56))
        canvas.drawLine(frame.left + dp(26), scanY, frame.right - dp(26), scanY, linePaint)
        postInvalidateDelayed(16)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
