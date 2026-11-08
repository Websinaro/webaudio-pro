package com.weaudio.pro

import com.weaudio.pro.audio.AudioDiagnostics
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.audio.NativeAudioBackend
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real JNI + Oboe engine on a device/emulator. Skipped (not failed) when the
 * device has no usable audio output.
 */
class NativeEngineIntegrationTest {
    private val engine = NativeAudioBackend()

    @Before fun setUp() { engine.release() }
    @After fun tearDown() { engine.release() }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test fun initializeStartStopReleaseReinitialize() {
        assumeTrue("No audio output available: ${engine.lastError()}", engine.initialize())
        assertEquals(EngineState.STOPPED, engine.engineState())

        val diag = AudioDiagnostics.parse(engine.diagnosticsText())
        assertTrue("sample rate must be reported", (diag.sampleRate ?: 0) > 0)
        assertTrue(diag["channels"].toIntOrNull()?.let { it > 0 } == true)

        assertTrue(engine.start())
        assertTrue("engine must confirm RUNNING", waitFor { engine.engineState() == EngineState.RUNNING })
        assertTrue("clock must advance", waitFor { engine.positionFrames() > 0 })
        engine.setToneEnabled(true)
        assertTrue("tone must produce signal", waitFor { engine.peakLevel() > 0.05f })

        engine.setToneEnabled(false)
        assertTrue("disabled tone must fall silent", waitFor { engine.peakLevel() < 0.001f })

        assertTrue(engine.pause())
        assertTrue(waitFor { engine.engineState() == EngineState.PAUSED })
        assertTrue(engine.resume())
        assertTrue(waitFor { engine.engineState() == EngineState.RUNNING })

        assertTrue(engine.stop())
        assertTrue(waitFor { engine.engineState() == EngineState.STOPPED })
        assertEquals(0L, engine.positionFrames())

        engine.release()
        assertEquals(EngineState.UNINITIALIZED, engine.engineState())

        assertTrue("reinitialize after release", engine.initialize())
        assertEquals(EngineState.STOPPED, engine.engineState())
        assertTrue(engine.start())
        assertTrue(waitFor { engine.engineState() == EngineState.RUNNING })
    }

    @Test fun controlsBeforeInitializeFailSafely() {
        assertFalse(engine.start())
        assertFalse(engine.stop())
        assertEquals(EngineState.UNINITIALIZED, engine.engineState())
        engine.release()   // double release is safe
    }
}
