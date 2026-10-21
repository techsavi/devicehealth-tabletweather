package com.example.tabletpanel.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarknessDetectorTest {
    private val threshold = 5f

    @Test
    fun dimsOnlyAfterStayingDark() {
        val d = DarknessDetector(dimDelayMs = 60_000)
        assertFalse(d.update(2f, threshold, 0))
        assertFalse(d.update(1f, threshold, 59_000))
        assertTrue(d.update(1f, threshold, 60_000))
    }

    @Test
    fun aBriefShadowDoesNotDim() {
        val d = DarknessDetector(dimDelayMs = 60_000)
        d.update(2f, threshold, 0) // someone walks past the sensor
        d.update(80f, threshold, 5_000) // light again: the dark timer resets
        assertFalse(d.update(2f, threshold, 61_000))
        assertTrue(d.update(2f, threshold, 121_000))
    }

    @Test
    fun brightensImmediatelyButOnlyWellAboveThreshold() {
        val d = DarknessDetector(dimDelayMs = 0)
        assertTrue(d.update(1f, threshold, 0))
        // Between the threshold and the bright-again level (15 lux) stays dimmed: no flicker.
        assertTrue(d.update(10f, threshold, 1_000))
        assertFalse(d.update(40f, threshold, 2_000))
    }
}
