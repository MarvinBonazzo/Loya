package com.cartawallet.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Ritaglio prospettico e miglioramento del documento, interamente in memoria locale. */
internal object DocumentImageProcessor {
    fun processJpeg(bytes: ByteArray, rotationDegrees: Int, target: File, flipHorizontal: Boolean = false): Boolean {
        val bitmap = decodeSampled(bytes) ?: return false
        val oriented = transform(bitmap, rotationDegrees, flipHorizontal)
        if (oriented !== bitmap) bitmap.recycle()
        return processBitmap(oriented, target)
    }

    fun processUri(resolver: ContentResolver, uri: Uri, target: File): Boolean {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: 0
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val (rotation, flipHorizontal) = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
            ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
            ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
            ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
            ExifInterface.ORIENTATION_TRANSVERSE -> -90 to true
            ExifInterface.ORIENTATION_ROTATE_270 -> -90 to false
            else -> 0 to false
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        return processJpeg(bytes, rotation, target, flipHorizontal)
    }

    private fun processBitmap(bitmap: Bitmap, target: File): Boolean {
        val mats = mutableListOf<Mat>()
        val contours = mutableListOf<MatOfPoint>()
        return try {
            val source = Mat().also(mats::add)
            Utils.bitmapToMat(bitmap, source)

            val detectionScale = minOf(1.0, 1400.0 / max(source.cols(), source.rows()).coerceAtLeast(1))
            val detection = Mat().also(mats::add)
            Imgproc.resize(source, detection, Size(), detectionScale, detectionScale, Imgproc.INTER_AREA)
            val gray = Mat().also(mats::add)
            Imgproc.cvtColor(detection, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val edges = Mat().also(mats::add)
            Imgproc.Canny(gray, edges, 55.0, 165.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also(mats::add)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            val hierarchy = Mat().also(mats::add)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val corners = findDocument(contours, detection.size(), detectionScale)
                ?: defaultCorners(source.cols(), source.rows())
            val ordered = orderCorners(corners)
            val width = max(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2]))
                .roundToInt().coerceIn(320, 3600)
            val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2]))
                .roundToInt().coerceIn(320, 3600)

            val sourcePoints = MatOfPoint2f(*ordered).also(mats::add)
            val destinationPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0),
                Point(0.0, height - 1.0),
            ).also(mats::add)
            val transform = Imgproc.getPerspectiveTransform(sourcePoints, destinationPoints).also(mats::add)
            val warped = Mat(height, width, CvType.CV_8UC4).also(mats::add)
            Imgproc.warpPerspective(source, warped, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC)

            val rgb = Mat().also(mats::add)
            Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
            val lab = Mat().also(mats::add)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            val channels = mutableListOf<Mat>()
            Core.split(lab, channels)
            mats.addAll(channels)
            val clahe = Imgproc.createCLAHE(1.45, Size(8.0, 8.0))
            try {
                clahe.apply(channels[0], channels[0])
            } finally {
                clahe.collectGarbage()
                clahe.clear()
            }
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
            val softened = Mat().also(mats::add)
            Imgproc.GaussianBlur(rgb, softened, Size(0.0, 0.0), 1.0)
            Core.addWeighted(rgb, 1.18, softened, -0.18, 2.0, rgb)
            val outputRgba = Mat().also(mats::add)
            Imgproc.cvtColor(rgb, outputRgba, Imgproc.COLOR_RGB2RGBA)

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outputRgba, output)
            target.parentFile?.mkdirs()
            val saved = FileOutputStream(target).use { output.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            output.recycle()
            saved
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
            contours.forEach { it.release() }
            mats.distinctBy { it.nativeObjAddr }.forEach { it.release() }
        }
    }

    private fun findDocument(contours: List<MatOfPoint>, size: Size, scale: Double): Array<Point>? {
        val minimumArea = size.area() * 0.16
        for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }) {
            if (Imgproc.contourArea(contour) < minimumArea) break
            val curve = MatOfPoint2f(*contour.toArray())
            val approximate = MatOfPoint2f()
            try {
                val perimeter = Imgproc.arcLength(curve, true)
                Imgproc.approxPolyDP(curve, approximate, perimeter * 0.022, true)
                val points = approximate.toArray()
                val polygon = MatOfPoint(*points)
                val isConvex = try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
                if (points.size == 4 && isConvex) {
                    return Array(4) { index -> Point(points[index].x / scale, points[index].y / scale) }
                }
            } finally {
                curve.release()
                approximate.release()
            }
        }
        return null
    }

    private fun defaultCorners(width: Int, height: Int): Array<Point> {
        val insetX = width * 0.018
        val insetY = height * 0.018
        return arrayOf(
            Point(insetX, insetY),
            Point(width - insetX, insetY),
            Point(width - insetX, height - insetY),
            Point(insetX, height - insetY),
        )
    }

    /** Risultato: alto-sinistra, alto-destra, basso-destra, basso-sinistra. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3400) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun transform(bitmap: Bitmap, degrees: Int, flipHorizontal: Boolean): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0 && !flipHorizontal) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply {
                postRotate(normalized.toFloat())
                if (flipHorizontal) postScale(-1f, 1f)
            },
            true,
        )
    }
}
