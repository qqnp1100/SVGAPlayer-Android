package com.opensource.svgaplayer.drawer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeGateTest {
    @Test
    fun `same frame is processed once while looped frame is processed again`() {
        val gate = FrameChangeGate()

        assertTrue(gate.shouldProcess(0))
        assertFalse(gate.shouldProcess(0))
        assertTrue(gate.shouldProcess(1))
        assertTrue(gate.shouldProcess(0))
    }
}
