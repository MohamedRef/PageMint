package com.mohamedref.pagemint.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object DocumentProcessor {
    fun rectify(
        original: Bitmap,
        corners: List<Corner>,
        format: PageFormat = PageFormat.AUTO,
        safetyBorder: Boolean = true,
    ): Bitmap {
        val plan = RectificationGeometry.plan(original.width, original.height, corners, format)
        val cropCorners = if (safetyBorder) QuadGeometry.withSafetyBorder(corners) else corners
        val points = cropCorners.map { Point(it.x * (original.width - 1.0), it.y * (original.height - 1.0)) }
        val outWidth = plan.outputWidth
        val outHeight = plan.outputHeight
        val src = Mat(); val dst = Mat()
        val from = MatOfPoint2f(*points.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outWidth - 1.0, 0.0),
            Point(outWidth - 1.0, outHeight - 1.0), Point(0.0, outHeight - 1.0),
        )
        try {
            Utils.bitmapToMat(original, src)
            val homography = Imgproc.getPerspectiveTransform(from, to)
            try {
                Imgproc.warpPerspective(src, dst, homography, Size(outWidth.toDouble(), outHeight.toDouble()), Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE)
            } finally { homography.release() }
            return Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(dst, it) }
        } finally {
            src.release(); dst.release(); from.release(); to.release()
        }
    }

    /** Local luminance normalization; chroma channels are retained to protect stamps and handwriting. */
    fun proEnhance(cropped: Bitmap): Bitmap {
        val rgba = Mat(); val rgb = Mat(); val lab = Mat(); val resultRgb = Mat()
        val lightSmall = Mat(); val backgroundSmall = Mat(); val background = Mat()
        val normalized = Mat(); val balanced = Mat(); val contrasted = Mat(); val enhancedL = Mat()
        val blur = Mat(); val final = Mat()
        val channels = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(cropped, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)
            val lightness = channels[0]
            val miniSize = Size(max(24.0, lightness.cols() / 10.0), max(24.0, lightness.rows() / 10.0))
            Imgproc.resize(lightness, lightSmall, miniSize, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(lightSmall, backgroundSmall, Size(0.0, 0.0), 13.0)
            Imgproc.resize(backgroundSmall, background, lightness.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.max(background, org.opencv.core.Scalar(24.0), background)
            Core.divide(lightness, background, normalized, 243.0)
            Core.addWeighted(lightness, 0.18, normalized, 0.82, 0.0, balanced)
            val clahe = Imgproc.createCLAHE(1.7, Size(8.0, 8.0))
            try { clahe.apply(balanced, contrasted) } finally { clahe.collectGarbage() }
            Core.addWeighted(balanced, 0.76, contrasted, 0.24, 0.0, enhancedL)
            channels[0].release()
            channels[0] = enhancedL.clone()
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, resultRgb, Imgproc.COLOR_Lab2RGB)
            Imgproc.GaussianBlur(resultRgb, blur, Size(0.0, 0.0), 0.8)
            Core.addWeighted(resultRgb, 1.16, blur, -0.16, 0.0, final)
            val outputRgba = Mat()
            try {
                Imgproc.cvtColor(final, outputRgba, Imgproc.COLOR_RGB2RGBA)
                return Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(outputRgba, it) }
            } finally { outputRgba.release() }
        } finally {
            channels.forEach(Mat::release)
            listOf(rgba, rgb, lab, resultRgb, lightSmall, backgroundSmall, background,
                normalized, balanced, contrasted, enhancedL, blur, final).forEach(Mat::release)
        }
    }
}
