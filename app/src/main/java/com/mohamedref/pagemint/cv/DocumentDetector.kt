package com.mohamedref.pagemint.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Multiple edge strategies followed by geometric ranking. Detection never silently discards the original. */
object DocumentDetector {
    private data class Candidate(val quad: List<Corner>, val score: Double)

    fun detect(bitmap: Bitmap): PageDetection {
        val longSide = max(bitmap.width, bitmap.height)
        val factor = min(1.0, 1100.0 / longSide)
        val w = max(32, (bitmap.width * factor).toInt())
        val h = max(32, (bitmap.height * factor).toInt())
        val small = if (factor < 1.0) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val rgba = Mat()
        val gray = Mat()
        val softened = Mat()
        val claheImage = Mat()
        val equalized = Mat()
        val gradient = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        var best: Candidate? = null
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, softened, Size(5.0, 5.0), 0.0)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            try { clahe.apply(gray, claheImage) } finally { clahe.collectGarbage() }
            Imgproc.GaussianBlur(claheImage, equalized, Size(5.0, 5.0), 0.0)
            Imgproc.morphologyEx(gray, gradient, Imgproc.MORPH_GRADIENT, kernel)

            for ((source, low, high) in listOf(
                Triple(softened, 35.0, 105.0),
                Triple(softened, 65.0, 180.0),
                Triple(equalized, 35.0, 105.0),
                Triple(gradient, 45.0, 130.0),
            )) {
                val edges = Mat()
                val closed = Mat()
                val hierarchy = Mat()
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.Canny(source, edges, low, high)
                    Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
                    Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours) {
                        val area = Imgproc.contourArea(contour)
                        val portion = area / (w.toDouble() * h)
                        if (portion !in 0.09..0.94) continue
                        val curve = MatOfPoint2f(*contour.toArray())
                        try {
                            val perimeter = Imgproc.arcLength(curve, true)
                            for (epsilon in listOf(0.015, 0.025, 0.04)) {
                                val approximation = MatOfPoint2f()
                                try {
                                    Imgproc.approxPolyDP(curve, approximation, perimeter * epsilon, true)
                                    if (approximation.total() != 4L) continue
                                    val convex = MatOfPoint(*approximation.toArray())
                                    try {
                                        if (!Imgproc.isContourConvex(convex)) continue
                                    } finally { convex.release() }
                                    val ordered = QuadGeometry.order(approximation.toArray().map {
                                        Corner((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f))
                                    })
                                    if (!QuadGeometry.valid(ordered)) continue
                                    val score = score(ordered, portion)
                                    if (score > (best?.score ?: Double.NEGATIVE_INFINITY)) best = Candidate(ordered, score)
                                } finally { approximation.release() }
                            }
                        } finally { curve.release() }
                    }
                } finally {
                    contours.forEach(MatOfPoint::release)
                    edges.release(); closed.release(); hierarchy.release()
                }
            }
        } finally {
            rgba.release(); gray.release(); softened.release(); claheImage.release()
            equalized.release(); gradient.release(); kernel.release()
            if (small !== bitmap) small.recycle()
        }
        // Confidence isn't calibrated yet; low confidence always goes through manual review.
        val found = best
        return if (found != null && found.score >= 0.57) {
            PageDetection(found.quad, found.score.toFloat().coerceIn(0f, 1f), automatic = true)
        } else {
            PageDetection(QuadGeometry.fallback(), 0f, automatic = false)
        }
    }

    private fun score(quad: List<Corner>, contourPortion: Double): Double {
        var angles = 0.0
        for (i in 0..3) {
            val a = quad[(i + 3) % 4]
            val b = quad[i]
            val c = quad[(i + 1) % 4]
            val ux = (a.x - b.x).toDouble(); val uy = (a.y - b.y).toDouble()
            val vx = (c.x - b.x).toDouble(); val vy = (c.y - b.y).toDouble()
            angles += abs((ux * vx + uy * vy) / max(1e-5, hypot(ux, uy) * hypot(vx, vy)))
        }
        val perpendicular = (1.0 - angles / 4.0).coerceIn(0.0, 1.0)
        // Prefer a substantial document, but don't select the whole photo frame.
        val coverage = min(1.0, contourPortion / 0.64)
        val margin = quad.minOf { min(min(it.x, 1f - it.x), min(it.y, 1f - it.y)).toDouble() }
        val inside = (margin / 0.025).coerceIn(0.0, 1.0)
        return 0.58 * coverage + 0.34 * perpendicular + 0.08 * inside
    }
}
