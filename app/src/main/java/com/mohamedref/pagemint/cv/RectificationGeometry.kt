package com.mohamedref.pagemint.cv

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Explicit paper ratios avoid stretching known-size documents when camera calibration is unknown. */
enum class PageFormat(val label: String, val aspectRatio: Double?) {
    AUTO("Auto", null),
    A4_PORTRAIT("A4 portrait", 210.0 / 297.0),
    A4_LANDSCAPE("A4 landscape", 297.0 / 210.0),
    LETTER_PORTRAIT("Letter portrait", 8.5 / 11.0),
    LETTER_LANDSCAPE("Letter landscape", 11.0 / 8.5),
    ID_LANDSCAPE("ID landscape", 85.60 / 53.98),
}

data class RectificationPlan(
    val outputWidth: Int,
    val outputHeight: Int,
    /** Physical width / height of the flat page, estimated or selected. */
    val aspectRatio: Double,
)

/** Pure Kotlin calculations so the geometry can be tested without Android or OpenCV. */
object RectificationGeometry {
    private const val DEFAULT_MAX_EDGE = 4800
    private const val DEFAULT_PIXEL_BUDGET = 12_000_000L

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        corners: List<Corner>,
        format: PageFormat = PageFormat.AUTO,
        maxEdge: Int = DEFAULT_MAX_EDGE,
        pixelBudget: Long = DEFAULT_PIXEL_BUDGET,
    ): RectificationPlan {
        require(imageWidth > 1 && imageHeight > 1) { "Invalid image dimensions" }
        require(maxEdge >= 64 && pixelBudget >= 4096) { "Invalid output limits" }
        require(QuadGeometry.valid(corners)) { "Please adjust the four document corners" }

        val points = corners.map { P(it.x.toDouble() * (imageWidth - 1), it.y.toDouble() * (imageHeight - 1)) }
        val top = distance(points[0], points[1])
        val right = distance(points[1], points[2])
        val bottom = distance(points[2], points[3])
        val left = distance(points[3], points[0])
        val widest = max(top, bottom)
        val tallest = max(left, right)
        val edgeRatio = (top + bottom) / (left + right)

        val calibratedRatio = if (format == PageFormat.AUTO) {
            cameraAwareRatio(points, imageWidth, imageHeight)?.takeIf { it in 0.12..8.0 &&
                it >= edgeRatio / 2.7 && it <= edgeRatio * 2.7 }
        } else null
        val chosenRatio = format.aspectRatio ?: calibratedRatio ?: edgeRatio
        require(chosenRatio.isFinite() && chosenRatio > 0.0) { "Unusable perspective geometry" }

        // A long edge represents the best-sampled part of its corresponding direction.
        // Match both at the chosen physical aspect without discarding that sampled detail.
        val wantedWidth = max(widest, tallest * chosenRatio)
        val wantedHeight = wantedWidth / chosenRatio
        val scale = min(1.0, min(maxEdge / max(wantedWidth, wantedHeight),
            sqrt(pixelBudget.toDouble() / (wantedWidth * wantedHeight))))
        // Truncating instead of rounding guarantees the final bitmap respects pixelBudget.
        val width = (wantedWidth * scale).toInt().coerceIn(64, maxEdge)
        val height = (wantedHeight * scale).toInt().coerceIn(64, maxEdge)
        return RectificationPlan(width, height, chosenRatio)
    }

    private data class P(val x: Double, val y: Double)
    private fun distance(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    /**
     * The homography from a unit square to the photograph is H = [a b c; d e f; g h 1].
     * Its first two columns back-projected through the camera matrix describe the
     * physical page axes. Their norm ratio is the page's true width / height.
     *
     * For a standard pinhole camera with principal point at image centre, perpendicular
     * page axes also constrain the focal length: (a-cx*g)*(b-cx*h) +
     * (d-cy*g)*(e-cy*h) + focal^2*g*h = 0.
     * Near-affine pictures are ill-conditioned, so we use a plausible focal estimate
     * and fall back to observed edges when the result is not trustworthy.
     */
    private fun cameraAwareRatio(q: List<P>, imageWidth: Int, imageHeight: Int): Double? {
        val (p0, p1, p2, p3) = q
        val dx1 = p1.x - p2.x
        val dx2 = p3.x - p2.x
        val dx3 = p0.x - p1.x + p2.x - p3.x
        val dy1 = p1.y - p2.y
        val dy2 = p3.y - p2.y
        val dy3 = p0.y - p1.y + p2.y - p3.y
        val determinant = dx1 * dy2 - dx2 * dy1
        if (abs(determinant) < 1e-10 * imageWidth.toDouble() * imageHeight) return null

        val g = (dx3 * dy2 - dx2 * dy3) / determinant
        val h = (dx1 * dy3 - dx3 * dy1) / determinant
        val a = p1.x - p0.x + g * p1.x
        val b = p3.x - p0.x + h * p3.x
        val d = p1.y - p0.y + g * p1.y
        val e = p3.y - p0.y + h * p3.y
        val cx = (imageWidth - 1) / 2.0
        val cy = (imageHeight - 1) / 2.0
        val ux = a - cx * g
        val uy = d - cy * g
        val vx = b - cx * h
        val vy = e - cy * h

        val longest = max(imageWidth, imageHeight).toDouble()
        val focalSquared = if (abs(g * h) > 1e-10) -(ux * vx + uy * vy) / (g * h) else Double.NaN
        val focal = if (focalSquared.isFinite() && focalSquared >= (0.4 * longest) * (0.4 * longest) &&
            focalSquared <= (5.0 * longest) * (5.0 * longest)) sqrt(focalSquared) else 1.2 * longest
        val widthAxis = hypot(hypot(ux, uy), focal * g)
        val heightAxis = hypot(hypot(vx, vy), focal * h)
        return (widthAxis / heightAxis).takeIf { it.isFinite() && it > 0.0 }
    }
}
