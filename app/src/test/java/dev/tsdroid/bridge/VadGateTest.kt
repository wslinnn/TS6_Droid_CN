package dev.tsdroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadGateTest {

    private fun frame(amplitude: Int, size: Int = 960): ShortArray {
        // Constant amplitude keeps RMS == amplitude, so dBFS is predictable
        return ShortArray(size) { amplitude.toShort() }
    }

    @Test
    fun `silence converts to floor dB`() {
        assertEquals(-120f, VadGate.rmsDb(ShortArray(960)), 0.01f)
    }

    @Test
    fun `constant amplitude converts to expected dB`() {
        // 3277 / 32768 ≈ 0.1 full scale → ≈ -20 dBFS
        assertEquals(-20f, VadGate.rmsDb(frame(3277)), 0.1f)
    }

    @Test
    fun `dB to gain conversion`() {
        assertEquals(1f, VadGate.dbToGain(0f), 1e-6f)
        assertEquals(0.0316f, VadGate.dbToGain(-30f), 0.001f)
        assertEquals(5.623f, VadGate.dbToGain(15f), 0.001f)
    }

    @Test
    fun `quiet frames do not transmit`() {
        val gate = VadGate(preRollFrames = 3)
        assertNull(gate.process(frame(100), nowMs = 0))
        assertNull(gate.process(frame(100), nowMs = 20))
        assertFalse(gate.isActive)
    }

    @Test
    fun `activation flushes pre-roll then current frame`() {
        val gate = VadGate(preRollFrames = 3)
        assertNull(gate.process(frame(100), 0))
        assertNull(gate.process(frame(100), 20))
        val out = gate.process(frame(3277), 40)
        assertTrue(gate.isActive)
        // Two buffered quiet frames + the loud frame, in order
        assertEquals(3, out!!.size)
        assertEquals(100, out[0][0].toInt())
        assertEquals(3277, out[2][0].toInt())
    }

    @Test
    fun `pre-roll buffer is bounded`() {
        val gate = VadGate(preRollFrames = 2)
        gate.process(frame(100), 0)
        gate.process(frame(100), 20)
        gate.process(frame(100), 40)
        val out = gate.process(frame(3277), 60)!!
        assertEquals(2, out.size)
    }

    @Test
    fun `hangover keeps sending after level drops`() {
        val gate = VadGate(preRollFrames = 1)
        gate.process(frame(100), 0)
        gate.process(frame(3277), 20) // activate
        // Drop below close threshold (-48 dB): frames keep going within 400 ms
        for (t in 1..19) {
            val out = gate.process(frame(100), 20 + t * 20L)
            assertTrue("frame at t=${20 + t * 20} should transmit within hangover", out != null)
        }
        // Past the hangover (belowSince=40ms, so ≥440ms): deactivate, go silent
        assertNull(gate.process(frame(100), 600))
        assertFalse(gate.isActive)
    }

    @Test
    fun `level within hysteresis band keeps active past hangover`() {
        val gate = VadGate(preRollFrames = 1)
        gate.process(frame(100), 0)
        gate.process(frame(3277), 20) // -20 dB, activates
        // -46 dB: below open (-45) but above close (-48) — must stay active forever
        val band = (32768.0 * Math.pow(10.0, -46.0 / 20.0)).toInt()
        for (t in 1..30) {
            val out = gate.process(frame(band), 20 + t * 20L)
            assertTrue("frame at t=${20 + t * 20} should still transmit", out != null)
        }
        assertTrue(gate.isActive)
    }

    @Test
    fun `reset clears active state and pre-roll`() {
        val gate = VadGate(preRollFrames = 3)
        gate.process(frame(100), 0)
        gate.reset()
        // Pre-roll was cleared by reset, so activation returns only this frame
        val out = gate.process(frame(3277), 20)
        assertEquals(1, out!!.size)
        assertTrue(gate.isActive)
    }
}
