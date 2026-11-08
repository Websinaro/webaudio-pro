package com.weaudio.pro

import com.weaudio.pro.audio.*
import com.weaudio.pro.project.Project
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModelsTest {
    @Test fun engineStateCodesMatchNative() {
        assertEquals(EngineState.RUNNING, EngineState.fromCode(3))
        assertEquals(EngineState.UNINITIALIZED, EngineState.fromCode(0))
        assertEquals(EngineState.ERROR, EngineState.fromCode(99))
    }

    @Test fun diagnosticsParseAndUnavailable() {
        val d = AudioDiagnostics.parse("api=AAudio\nsampleRate=48000\nformat=Unavailable\nlastError=a=b\nbad line\n")
        assertEquals("AAudio", d["api"])
        assertEquals("48000 Hz", d.withUnit("sampleRate", "Hz"))
        assertEquals(UNAVAILABLE, d["format"])
        assertEquals(UNAVAILABLE, d["missing"])
        assertEquals("a=b", d["lastError"])
        assertEquals(UNAVAILABLE, d.withUnit("missing", "Hz"))
    }

    @Test fun bufferDurationIsDerivedFromRealValues() {
        assertEquals("4.00 ms", AudioDiagnostics.parse("bufferSize=192\nsampleRate=48000").bufferMillis())
        assertEquals(UNAVAILABLE, AudioDiagnostics.parse("sampleRate=48000").bufferMillis())
        assertEquals(UNAVAILABLE, AudioDiagnostics().bufferMillis())
    }

    @Test fun transportTimeFormatting() {
        assertEquals("00:00.000", formatTransportTime(0, 48000))
        assertEquals("01:05.500", formatTransportTime(48000L * 65 + 24000, 48000))
        assertEquals("--:--.---", formatTransportTime(100, null))
    }

    @Test fun defaultProject() {
        val p = Project()
        assertEquals("Untitled Project", p.name)
        assertEquals("4/4", p.timeSignature.toString())
        assertEquals(0, p.trackCount)
    }
}
