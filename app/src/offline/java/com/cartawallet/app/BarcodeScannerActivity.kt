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
import android.media.Image
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

/** Scanner Camera2 + ZXing: riconoscimento offline, senza Play Services o telemetria. */
class BarcodeScannerActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var torchButton: TextView
    private lateinit var statusText: TextView
    private lateinit var camera: Camera2Controller
    private val decoding = AtomicBoolean(false)
    private val reader = MultiFormatReader()
    private var resultDelivered = false
    private var lastDecodeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        reader.setHints(
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(
                    DecodeHintType.POSSIBLE_FORMATS,
                    listOf(
                        BarcodeFormat.CODE_128,
                        BarcodeFormat.CODE_39,
                        BarcodeFormat.CODE_93,
                        BarcodeFormat.CODABAR,
                        BarcodeFormat.EAN_13,
                        BarcodeFormat.EAN_8,
                        BarcodeFormat.ITF,
                        BarcodeFormat.UPC_A,
                        BarcodeFormat.UPC_E,
                        BarcodeFormat.QR_CODE,
                        BarcodeFormat.PDF_417,
                        BarcodeFormat.AZTEC,
                        BarcodeFormat.DATA_MATRIX,
                    ),
                )
                put(DecodeHintType.RETURN_CODABAR_START_END, true)
                put(DecodeHintType.ALSO_INVERTED, true)
                put(DecodeHintType.CHARACTER_SET, "UTF-8")
            },
        )

        setContentView(buildContentView())
        camera = Camera2Controller(
            activity = this,
            textureView = textureView,
            mode = Camera2Controller.Mode.BARCODE,
            onAnalysisImage = ::analyseImage,
            onReady = { hasFlash ->
                torchButton.visibility = if (hasFlash) View.VISIBLE else View.GONE
                statusText.text = getString(R.string.scanner_status_ready)
                textureView.postDelayed({ camera.triggerFocus() }, 300)
            },
            onError = { message -> runOnUiThread { statusText.text = message } },
        )
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 26, 23)) }
        textureView = TextureView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        root.addView(ScannerOverlayView(this), FrameLayout.LayoutParams(-1, -1))

        val close = pillButton(
            getString(R.string.scanner_close),
            getString(R.string.scanner_close_description),
        ).apply { setOnClickListener { finish() } }
        root.addView(close, FrameLayout.LayoutParams(dp(92), dp(48), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(18)
            topMargin = dp(18)
        })

        val localBadge = pillButton(
            getString(R.string.offline_scanner_badge),
            getString(R.string.offline_scanner_badge_description),
        ).apply {
            setTextColor(0xFFD7FF72.toInt())
            textSize = 10f
        }
        root.addView(localBadge, FrameLayout.LayoutParams(dp(158), dp(40), Gravity.TOP or Gravity.END).apply {
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
            setOnClickListener {
                val enabled = camera.toggleTorch()
                text = getString(if (enabled) R.string.scanner_torch_off else R.string.scanner_torch_on)
            }
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
            (localBadge.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(16)
                localBadge.layoutParams = this
            }
            (bottomPanel.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bars.bottom + dp(12)
                bottomPanel.layoutParams = this
            }
            insets
        }

        textureView.setOnClickListener {
            camera.triggerFocus()
            statusText.text = getString(R.string.scanner_status_focusing)
            statusText.postDelayed({
                if (!resultDelivered) statusText.text = getString(R.string.scanner_status_ready)
            }, 650)
        }
        return root
    }

    private fun analyseImage(image: Image) {
        if (resultDelivered) return
        val now = System.currentTimeMillis()
        if (now - lastDecodeAt < 80 || !decoding.compareAndSet(false, true)) return
        lastDecodeAt = now
        try {
            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val luminance = compactLuminance(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
            val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            val result = decode(source) ?: decode(source.rotateCounterClockwise())
            if (result != null && result.text.isNotBlank()) deliverResult(result)
        } catch (_: Exception) {
            // Un frame non leggibile e' normale: il successivo e' gia' in arrivo.
        } finally {
            reader.reset()
            decoding.set(false)
        }
    }

    private fun compactLuminance(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        val data = ByteArray(width * height)
        val source = buffer.duplicate()
        if (pixelStride == 1 && rowStride == width) {
            source.rewind()
            source.get(data, 0, minOf(data.size, source.remaining()))
            return data
        }
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            source.position(y * rowStride)
            val length = minOf(rowStride, source.remaining())
            source.get(row, 0, length)
            for (x in 0 until width) data[y * width + x] = row[x * pixelStride]
        }
        return data
    }

    private fun decode(source: com.google.zxing.LuminanceSource): Result? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
    } catch (_: com.google.zxing.ReaderException) {
        null
    }

    private fun deliverResult(result: Result) {
        if (resultDelivered) return
        resultDelivered = true
        runOnUiThread {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_VALUE, result.text)
                    .putExtra(EXTRA_FORMAT, formatName(result.barcodeFormat)),
            )
            finish()
        }
    }

    private fun formatName(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.EAN_13 -> "ean13"
        BarcodeFormat.EAN_8 -> "ean8"
        BarcodeFormat.UPC_A -> "upc"
        BarcodeFormat.UPC_E -> "upce"
        BarcodeFormat.CODE_39 -> "code39"
        BarcodeFormat.CODE_93 -> "code93"
        BarcodeFormat.CODABAR -> "codabar"
        BarcodeFormat.ITF -> "itf"
        BarcodeFormat.QR_CODE -> "qr"
        BarcodeFormat.PDF_417 -> "pdf417"
        BarcodeFormat.AZTEC -> "aztec"
        BarcodeFormat.DATA_MATRIX -> "datamatrix"
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

    companion object {
        const val EXTRA_VALUE = "barcode_value"
        const val EXTRA_FORMAT = "barcode_format"
    }
}

private class ScannerOverlayView(context: android.content.Context) : View(context) {
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
