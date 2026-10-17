package com.kriztech.scan.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.File

import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object BitmapUtil {

    fun fitMax(src: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= maxDim) return src
        val scale = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun rotate90(src: Bitmap): Bitmap = rotate(src, 90)

    fun crop(src: Bitmap, r: Rect): Bitmap {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val w = r.width().coerceAtMost(src.width - left)
        val h = r.height().coerceAtMost(src.height - top)
        if (w <= 0 || h <= 0) return src
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    /** Perspective warp: map the four corners (TL,TR,BR,BL in bitmap coords) onto a target rectangle with OpenCV homography. */
    fun perspectiveWarp(src: Bitmap, quad: List<PointF>, outW: Int = src.width, outH: Int = src.height): Bitmap {
        val w = outW.coerceAtLeast(1)
        val h = outH.coerceAtLeast(1)
        if (quad.size != 4) return src

        // Ensure corners are in clockwise TL, TR, BR, BL order
        val orderedQuad = orderCorners(quad)

        // 1. Try high-precision OpenCV perspective warp (true 3x3 homography with cubic interpolation)
        if (Cleanup.ensureLoaded()) {
            try {
                val rgba = Mat()
                val warped = Mat()
                Utils.bitmapToMat(src, rgba)

                val srcPts = MatOfPoint2f(
                    Point(orderedQuad[0].x.toDouble(), orderedQuad[0].y.toDouble()),
                    Point(orderedQuad[1].x.toDouble(), orderedQuad[1].y.toDouble()),
                    Point(orderedQuad[2].x.toDouble(), orderedQuad[2].y.toDouble()),
                    Point(orderedQuad[3].x.toDouble(), orderedQuad[3].y.toDouble())
                )
                val dstPts = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(w.toDouble(), 0.0),
                    Point(w.toDouble(), h.toDouble()),
                    Point(0.0, h.toDouble())
                )

                val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
                Imgproc.warpPerspective(
                    rgba,
                    warped,
                    transform,
                    Size(w.toDouble(), h.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_CONSTANT,
                    Scalar(255.0, 255.0, 255.0, 255.0)
                )

                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, out)

                rgba.release()
                warped.release()
                srcPts.release()
                dstPts.release()
                transform.release()
                return out
            } catch (_: Throwable) {
                // fallback to Matrix
            }
        }

        // 2. Fallback to Android Matrix poly-to-poly
        val srcPts = floatArrayOf(
            orderedQuad[0].x, orderedQuad[0].y,
            orderedQuad[1].x, orderedQuad[1].y,
            orderedQuad[2].x, orderedQuad[2].y,
            orderedQuad[3].x, orderedQuad[3].y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            w.toFloat(), 0f,
            w.toFloat(), h.toFloat(),
            0f, h.toFloat()
        )
        val m = Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
            return crop(src, boundingRect(orderedQuad, src.width, src.height))
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun orderCorners(pts: List<PointF>): List<PointF> {
        if (pts.size != 4) return pts
        val tl = pts.minByOrNull { it.x + it.y } ?: pts[0]
        val br = pts.maxByOrNull { it.x + it.y } ?: pts[2]
        val tr = pts.maxByOrNull { it.x - it.y } ?: pts[1]
        val bl = pts.minByOrNull { it.x - it.y } ?: pts[3]

        val v1x = tr.x - tl.x
        val v1y = tr.y - tl.y
        val v2x = bl.x - tl.x
        val v2y = bl.y - tl.y
        val cross = v1x * v2y - v1y * v2x
        return if (cross > 0) listOf(tl, tr, br, bl) else listOf(tl, bl, br, tr)
    }

    /** Downscale to fit within maxW x maxH (preserving aspect); no-op when already smaller. */
    fun downscaleToFit(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (src.width <= maxW && src.height <= maxH) return src
        val s = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Luminance-only copy. */
    fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        ) }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun boundingRect(quad: List<android.graphics.PointF>, w: Int, h: Int): Rect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = 0f
        var b = 0f
        for (p in quad) {
            l = minOf(l, p.x); t = minOf(t, p.y); r = maxOf(r, p.x); b = maxOf(b, p.y)
        }
        val left = l.toInt().coerceIn(0, w - 1)
        val top = t.toInt().coerceIn(0, h - 1)
        return Rect(left, top, r.toInt().coerceIn(left + 1, w), b.toInt().coerceIn(top + 1, h))
    }

    /** Load a bitmap from any uri (content or file), honoring EXIF rotation, capped at maxDim. */
    fun loadFromUri(context: Context, uri: Uri, maxDim: Int = 2000): Bitmap? {
        val tmp = File(context.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
        try {
            val ins = context.contentResolver.openInputStream(uri) ?: return null
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val bmp = decodeFile(tmp.absolutePath, maxDim) ?: return null
            val orientation = try {
                val ei = ExifInterface(tmp.absolutePath)
                when (ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) {
                0
            }
            tmp.delete()
            return if (orientation != 0) rotate(bmp, orientation) else bmp
        } catch (e: Exception) {
            tmp.delete()
            return null
        }
    }

    fun decodeFile(path: String, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, o2)
    }

    /**
     * Decode JPEG/PNG bytes, downsampling only as much as needed to fit [maxDim] on the
     * long edge. Two passes so a huge camera capture is never fully materialised just to
     * be scaled down afterwards - saves a lot of memory and stays at full quality when
     * the capture already fits.
     */
    fun decodeBytes(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }
}
