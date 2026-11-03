package com.mohamedref.pagemint.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RectificationGeometryTest {
    private fun quad(width: Int, height: Int, vararg pixels: Pair<Double, Double>): List<Corner> =
        pixels.map { (x, y) -> Corner((x / (width - 1)).toFloat(), (y / (height - 1)).toFloat()) }

    private fun pose(points: Array<Pair<Double, Double>>): List<Corner> =
        quad(1200, 900, *points)

    @Test fun headOnDocumentKeepsProportions() {
        val corners = quad(1000, 1000, 200.0 to 100.0, 800.0 to 100.0,
            800.0 to 940.0, 200.0 to 940.0)
        val plan = RectificationGeometry.plan(1000, 1000, corners)
        assertEquals(600.0 / 840, plan.aspectRatio, 0.001)
        assertTrue(kotlin.math.abs(600 - plan.outputWidth) <= 1)
        assertTrue(kotlin.math.abs(840 - plan.outputHeight) <= 1)
    }

    @Test fun tiltedA4DoesNotUseForeshortenedEdgeLengthRatio() {
        val examples = listOf(
            arrayOf(390.8907 to 115.2438, 812.5406 to 67.1034,
                832.6206 to 822.3946, 449.1712 to 721.7214),
            arrayOf(315.2869 to 148.0149, 618.5157 to 135.3115,
                892.5301 to 760.2763, 589.6832 to 625.3415),
        )
        for (points in examples) {
            val plan = RectificationGeometry.plan(1200, 900, pose(points))
            assertEquals(210.0 / 297.0, plan.aspectRatio, 0.004)
            assertEquals(plan.aspectRatio, plan.outputWidth.toDouble() / plan.outputHeight, 0.003)
        }
    }

    @Test fun explicitPaperRatioDoesNotDependOnCameraAngle() {
        val corners = pose(arrayOf(390.8907 to 115.2438, 812.5406 to 67.1034,
            832.6206 to 822.3946, 449.1712 to 721.7214))
        for (format in PageFormat.entries.filter { it.aspectRatio != null }) {
            val plan = RectificationGeometry.plan(1200, 900, corners, format)
            assertEquals(format.aspectRatio!!, plan.outputWidth.toDouble() / plan.outputHeight, 0.003)
        }
    }

    @Test fun maxEdgeAndPixelBudgetAreStrictlyEnforced() {
        val plan = RectificationGeometry.plan(8000, 8000, QuadGeometry.fallback())
        assertTrue(plan.outputWidth <= 4800 && plan.outputHeight <= 4800)
        assertTrue(plan.outputWidth.toLong() * plan.outputHeight <= 12_000_000L)
    }

    @Test fun borderPreservesOrderAndAvoidsOutOfBounds() {
        val corners = listOf(Corner(.18f, .17f), Corner(.85f, .12f),
            Corner(.91f, .83f), Corner(.12f, .88f))
        val expanded = QuadGeometry.withSafetyBorder(corners)
        assertTrue(QuadGeometry.valid(expanded))
        assertTrue(QuadGeometry.area(expanded) > QuadGeometry.area(corners))
        assertTrue(expanded.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(QuadGeometry.fallback(), QuadGeometry.withSafetyBorder(QuadGeometry.fallback()))
    }

    @Test fun invalidOrDegeneratePagesAreRejected() {
        val folded = listOf(Corner(.1f, .1f), Corner(.85f, .2f),
            Corner(.2f, .85f), Corner(.8f, .8f))
        assertFalse(QuadGeometry.valid(folded))
        val valid = QuadGeometry.fallback()
        for (w in listOf(0, 1)) {
            val result = runCatching { RectificationGeometry.plan(w, 900, valid) }
            assertTrue(result.isFailure)
        }
    }
}
