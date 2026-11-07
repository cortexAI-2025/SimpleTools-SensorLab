package com.simpletools.sensorlab

import com.simpletools.sensorlab.core.charts.ZoomWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomWindowTest {
    private val full = ZoomWindow.full(0.0, 10.0)

    @Test fun startsOnTheWholeSession() {
        assertEquals(10.0, full.span, 1e-9)
        assertTrue(full.isFullRange)
    }

    @Test fun zoomInKeepsTheCentreFixedByDefault() {
        val z = full.zoomed(2.0)
        assertEquals(5.0, z.span, 1e-9)
        assertEquals(2.5, z.start, 1e-9)
        assertEquals(7.5, z.end, 1e-9)
        assertTrue(!z.isFullRange)
    }

    @Test fun anchoredZoomKeepsThatPointUnderTheFinger() {
        val z = full.zoomed(2.0, anchor = 0.0)
        assertEquals(0.0, z.start, 1e-9)
        assertEquals(5.0, z.end, 1e-9)
        val z2 = z.zoomed(2.0, anchor = 1.0)
        assertEquals(z.end, z2.end, 1e-9)
    }

    @Test fun zoomOutIsClampedToTheSession() {
        val z = full.zoomed(2.0, anchor = 0.0).zoomed(0.01)
        assertEquals(0.0, z.start, 1e-9)
        assertEquals(10.0, z.end, 1e-9)
        assertTrue(z.isFullRange)
    }

    @Test fun zoomInStopsAtTheMinimumSpan() {
        var z = full
        repeat(60) { z = z.zoomed(2.0) }
        assertEquals(ZoomWindow.MIN_SPAN_S, z.span, 1e-9)
        assertTrue(z.start >= 0.0 && z.end <= 10.0)
    }

    @Test fun panStaysInsideTheSession() {
        val z = full.zoomed(4.0, anchor = 0.0)
        val right = z.panned(100.0)
        assertEquals(10.0 - z.span, right.start, 1e-9)
        val left = z.panned(-100.0)
        assertEquals(0.0, left.start, 1e-9)
        assertEquals(z.span, right.span, 1e-9)
    }

    @Test fun resetReturnsToTheFullRange() {
        assertTrue(full.zoomed(3.0).reset().isFullRange)
    }

    @Test fun invalidInputsDoNothing() {
        assertEquals(full, full.zoomed(0.0))
        assertEquals(full, full.zoomed(Double.NaN))
        assertEquals(full, full.panned(Double.NaN))
    }

    @Test fun emptySessionGetsAUsableRange() {
        val z = ZoomWindow.full(5.0, 5.0)
        assertTrue(z.fullEnd > z.fullStart)
    }
}
