package com.mohamedref.pagemint.cv

import org.junit.Assert.*
import org.junit.Test

class QuadGeometryTest {
    @Test fun unsortedCornersBecomeClockwise() {
        val input = listOf(Corner(.85f, .92f), Corner(.12f, .14f), Corner(.10f, .87f), Corner(.84f, .11f))
        val quad = QuadGeometry.order(input)
        assertEquals(Corner(.12f, .14f), quad[0])
        assertEquals(Corner(.84f, .11f), quad[1])
        assertEquals(Corner(.85f, .92f), quad[2])
        assertEquals(Corner(.10f, .87f), quad[3])
        assertTrue(QuadGeometry.valid(quad))
    }

    @Test fun crossedCornersAreRejected() {
        val crossed = listOf(Corner(.1f, .1f), Corner(.9f, .9f), Corner(.9f, .1f), Corner(.1f, .9f))
        assertFalse(QuadGeometry.valid(crossed))
    }

    @Test fun tinyAndOutsideQuadrilateralsAreRejected() {
        assertFalse(QuadGeometry.valid(listOf(Corner(.1f, .1f), Corner(.12f, .1f), Corner(.12f, .12f), Corner(.1f, .12f))))
        assertFalse(QuadGeometry.valid(listOf(Corner(-.1f, .1f), Corner(.9f, .1f), Corner(.9f, .9f), Corner(.1f, .9f))))
    }

    @Test fun fallbackIsValid() { assertTrue(QuadGeometry.valid(QuadGeometry.fallback())) }
}
