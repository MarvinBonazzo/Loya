package com.cartawallet.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Piccolo controller Camera2 condiviso dagli scanner.
 * Usa soltanto API Android di sistema: non inizializza SDK, servizi o telemetria esterni.
 */
internal class Camera2Controller(
    private val activity: Activity,
    private val textureView: TextureView,
    private val mode: Mode,
    private val onAnalysisImage: ((Image) -> Unit)? = null,
    private val onReady: ((hasFlash: Boolean) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
) {
    enum class Mode { BARCODE, DOCUMENT }

    data class JpegCapture(val bytes: ByteArray, val rotationDegrees: Int)

    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var cameraId: String? = null
    private var previewSize = Size(1280, 720)
    private var sensorOrientation = 90
    private var flashAvailable = false
    private var torchEnabled = false
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var pendingJpeg: ((JpegCapture) -> Unit)? = null
    private val opening = AtomicBoolean(false)
    private var started = false

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun start() {
        if (started) return
        started = true
        startBackgroundThread()
        if (textureView.isAvailable) {
            configureTransform(textureView.width, textureView.height)
            openCamera()
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    fun stop() {
        started = false
        opening.set(false)
        pendingJpeg = null
        runCatching { captureSession?.stopRepeating() }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }

    fun toggleTorch(): Boolean {
        if (!flashAvailable) return false
        torchEnabled = !torchEnabled
        updatePreviewRequest()
        return torchEnabled
    }

    fun triggerFocus() {
        val session = captureSession ?: return
        val builder = previewRequest ?: return
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        }
    }

    fun captureJpeg(callback: (JpegCapture) -> Unit): Boolean {
        if (mode != Mode.DOCUMENT || pendingJpeg != null) return false
        val device = cameraDevice ?: return false
        val session = captureSession ?: return false
        val reader = imageReader ?: return false
        val rotation = jpegRotation()
        pendingJpeg = callback
        return runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_ORIENTATION, rotation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }
            session.capture(request.build(), null, backgroundHandler)
            true
        }.getOrElse {
            pendingJpeg = null
            onError?.invoke(activity.getString(R.string.camera_capture_failed))
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!started || cameraDevice != null || !opening.compareAndSet(false, true)) return
        try {
            selectBackCamera()
            val selectedId = cameraId ?: error(activity.getString(R.string.camera_rear_unavailable))
            cameraManager.openCamera(selectedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening.set(false)
                    if (!started) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    opening.set(false)
                    camera.close()
                    cameraDevice = null
                    onError?.invoke(activity.getString(R.string.camera_disconnected))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    opening.set(false)
                    camera.close()
                    cameraDevice = null
                    onError?.invoke(activity.getString(R.string.camera_unavailable))
                }
            }, backgroundHandler)
        } catch (_: Exception) {
            opening.set(false)
            onError?.invoke(activity.getString(R.string.camera_open_failed))
        }
    }

    private fun selectBackCamera() {
        val selected = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
        cameraId = selected
        val id = selected ?: return
        val characteristics = cameraManager.getCameraCharacteristics(id)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val outputSize = if (mode == Mode.BARCODE) {
            chooseAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty())
        } else {
            chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG).orEmpty())
        }
        val desiredRatio = if (mode == Mode.DOCUMENT) {
            outputSize.width.toFloat() / outputSize.height.coerceAtLeast(1)
        } else {
            16f / 9f
        }
        previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture::class.java).orEmpty(), desiredRatio)
        val format = if (mode == Mode.BARCODE) ImageFormat.YUV_420_888 else ImageFormat.JPEG
        imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, format, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (mode == Mode.BARCODE) {
                    try {
                        onAnalysisImage?.invoke(image)
                    } finally {
                        image.close()
                    }
                } else {
                    val callback = pendingJpeg
                    if (callback == null) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        pendingJpeg = null
                        callback(JpegCapture(bytes, jpegRotation()))
                    } finally {
                        image.close()
                    }
                }
            }, backgroundHandler)
        }
    }

    private fun createSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val reader = imageReader ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)
        previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            if (mode == Mode.BARCODE) addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        camera.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!started || cameraDevice == null) {
                    session.close()
                    return
                }
                captureSession = session
                updatePreviewRequest()
                activity.runOnUiThread { onReady?.invoke(flashAvailable) }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError?.invoke(activity.getString(R.string.camera_configuration_failed))
            }
        }, backgroundHandler)
    }

    private fun updatePreviewRequest() {
        val session = captureSession ?: return
        val builder = previewRequest ?: return
        runCatching {
            builder.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        when (rotation) {
            Surface.ROTATION_0 -> Unit
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = max(viewHeight.toFloat() / previewSize.height, viewWidth.toFloat() / previewSize.width)
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            }
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun jpegRotation(): Int {
        @Suppress("DEPRECATION")
        val displayRotation = activity.windowManager.defaultDisplay.rotation
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation + displayDegrees + 360) % 360
    }

    private fun choosePreviewSize(sizes: Array<out Size>, desiredRatio: Float): Size = sizes
        .filter { it.width <= 1920 && it.height <= 1080 }
        .minByOrNull {
            abs(it.width.toFloat() / it.height.coerceAtLeast(1) - desiredRatio) * 1200f +
                abs(it.width - 1440) * 0.02f
        }
        ?: sizes.firstOrNull()
        ?: Size(1280, 720)

    private fun chooseAnalysisSize(sizes: Array<out Size>): Size = sizes
        .filter { it.width in 960..1600 && it.height in 540..1200 }
        .minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
        ?: sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
        ?: Size(1280, 720)

    private fun chooseJpegSize(sizes: Array<out Size>): Size = sizes
        .filter { it.width.toLong() * it.height <= 12_000_000L }
        .maxByOrNull { it.width.toLong() * it.height }
        ?: sizes.maxByOrNull { it.width.toLong() * it.height }
        ?: Size(2560, 1920)

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CartaCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        runCatching { thread.join(700) }
        backgroundThread = null
        backgroundHandler = null
    }
}
