package com.mohamedref.pagemint.cv

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Normalized to the unrotated, EXIF-corrected bitmap: x and y are in [0, 1]. */
data class Corner(val x: Float, val y: Float)

data class PageDetection(val corners: List<Corner>, val confidence: Float, val automatic: Boolean)

object QuadGeometry {
    /** Clockwise in image coordinates, starting at the upper-left corner. */
    fun order(points: List<Corner>): List<Corner> {
        require(points.size == 4) { "Four points required" }
        val cx = points.map { it.x }.average()
        val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }

    fun area(corners: List<Corner>): Float {
        if (corners.size != 4) return 0f
        var twice = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            twice += a.x * b.y - b.x * a.y
        }
        return abs(twice) / 2f
    }

    fun valid(corners: List<Corner>): Boolean {
        if (corners.size != 4 || area(corners) < 0.025f) return false
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        var sign = 0
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            if (hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < 0.04) return false
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 0.0001f) return false
            val current = if (cross > 0) 1 else -1
            if (sign != 0 && sign != current) return false
            sign = current
        }
        return true
    }

    /** Adds a small, bounded outside border so a near-edge character is less likely to be cut. */
    fun withSafetyBorder(corners: List<Corner>, fraction: Float = 0.012f): List<Corner> {
        require(valid(corners)) { "Invalid document corners" }
        require(fraction in 0f..0.05f) { "Border must be between 0 and 5 percent" }
        val cx = corners.map { it.x }.average().toFloat()
        val cy = corners.map { it.y }.average().toFloat()
        val expanded = corners.map {
            Corner((cx + (it.x - cx) * (1f + fraction)).coerceIn(0f, 1f),
                (cy + (it.y - cy) * (1f + fraction)).coerceIn(0f, 1f))
        }
        return if (valid(expanded)) expanded else corners
    }

    fun fallback(): List<Corner> = listOf(
        Corner(0f, 0f), Corner(1f, 0f),
        Corner(1f, 1f), Corner(0f, 1f),
    )
}
