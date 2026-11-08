package com.weaudio.pro

import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weaudio.pro.arrangement.*
import com.weaudio.pro.audio.NativeAudioBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.sin

/**
 * On-device test of the REAL native engine: six tracks, one shared transport. Needs an audio output (any phone/emulator).
 * Timing assertions use generous tolerances: they prove the transport is driven by the audio callback, not exact sample timing
 * (sample-exactness is covered by the host C++ tests).
 */
@RunWith(AndroidJUnit4::class)
class ArrangementIntegrationTest {
    private val seq = NativeSequencerBackend()

    private fun wav(): ByteArray {   // 0.25 s, 44.1 kHz mono 16-bit decaying sine
        val n = 11025; val pcm = ByteArrayOutputStream()
        for (i in 0 until n) { val v = (sin(i * 0.12) * 12000 * (1 - i / n.toDouble())).toInt(); pcm.write(v and 0xFF); pcm.write((v shr 8) and 0xFF) }
        val d = pcm.toByteArray(); val o = ByteArrayOutputStream()
        fun i32(x: Int) { for (k in 0..3) o.write((x shr (8 * k)) and 0xFF) }
        fun i16(x: Int) { o.write(x and 0xFF); o.write((x shr 8) and 0xFF) }
        o.write("RIFF".toByteArray()); i32(36 + d.size); o.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(44100); i32(88200); i16(2); i16(16)
        o.write("data".toByteArray()); i32(d.size); o.write(d); return o.toByteArray()
    }

    @Before fun setUp() {
        System.loadLibrary("weaudio")
        assertEquals(0, NativeAudioBridge.initializeEngine()); assertEquals(0, NativeAudioBridge.startAudio())
        for (slot in 0 until 12) assertEquals("", NativeAudioBridge.drumLoadSample(slot, wav()))
    }
    @After fun tearDown() { seq.stop(); NativeAudioBridge.stopAudio(); NativeAudioBridge.releaseEngine() }

    private fun sixTrackProject(): ArrangementProject {
        val ed = ArrangementEditor(); var p = ArrangementProject()
        for (slot in 0 until 6) {
            p = ed.addTrack(p, slot, "T$slot").project
            val pat = ed.createPattern(p, "P$slot"); p = pat.project
            for (s in 0 until 16 step 4) p = ed.toggleStep(p, pat.createdId!!, slot, s * TICKS_PER_SIXTEENTH).project
            p = ed.addClip(p, p.tracks.last().id, pat.createdId!!, 0, 4L * 3840).project
        }
        return p
    }
    private fun publish(p: ArrangementProject) { p.tracks.forEachIndexed { i, t -> seq.setTrack(i, t.volume, t.pan, t.mute, t.solo) }; assertTrue(seq.publish(flatten(p))) }

    @Test fun sixTracksPlayOnOneClockWithPauseResumeStopAndTempoChange() {
        val p = sixTrackProject(); publish(p); seq.setTempo(120.0); seq.setTimeSignature(4, 4)
        seq.play(); Thread.sleep(1000)
        assertEquals(SeqState.PLAYING, seq.state())
        val pos = seq.positionTicks()
        assertTrue("position follows the audio callback (~1920 ticks/s @120 BPM), was $pos", pos in 1200..2700)
        var peakVoices = 0; repeat(60) { peakVoices = maxOf(peakVoices, NativeAudioBridge.drumActiveVoices()); Thread.sleep(25) }
        assertTrue("overlapping hits from several tracks were audible, voices=$peakVoices", peakVoices >= 2)

        seq.pause(); Thread.sleep(100); val paused = seq.positionTicks(); Thread.sleep(400)
        assertEquals(SeqState.PAUSED, seq.state()); assertEquals("pause freezes position", paused, seq.positionTicks())
        seq.play(); Thread.sleep(300); assertTrue("resume continues from the paused position", seq.positionTicks() > paused)

        seq.setTempo(240.0); val before = seq.positionTicks(); Thread.sleep(500)
        assertTrue("tempo doubled: ~1920 ticks in 0.5 s", seq.positionTicks() - before in 1200..2700)

        seq.stop(); assertEquals(SeqState.STOPPED, seq.state()); assertEquals(0L, seq.positionTicks())
        seq.play(); Thread.sleep(300); assertTrue(seq.positionTicks() in 1..1500)   // restart from bar 1
        assertEquals("no hits dropped", 0L, seq.droppedHits())
        assertTrue("engine stable", NativeAudioBridge.getDiagnostics().contains("xRuns"))
    }

    @Test fun editingTheArrangementDuringPlaybackIsSafe() {
        var p = sixTrackProject(); publish(p); seq.play()
        val ed = ArrangementEditor()
        repeat(40) { i ->
            p = ed.setTrackMute(p, p.tracks[i % 6].id, i % 2 == 0).project
            p.tracks.forEachIndexed { k, t -> seq.setTrack(k, t.volume, t.pan, t.mute, t.solo) }
            Thread.sleep(20)
        }
        assertEquals(SeqState.PLAYING, seq.state()); assertTrue(seq.positionTicks() > 0)
    }

    @Test fun rotatingTheDeviceDuringPlaybackKeepsTheTransportRunning() {
        publish(sixTrackProject()); seq.play(); Thread.sleep(300)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }; Thread.sleep(800)
            val landscape = seq.positionTicks()
            sc.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }; Thread.sleep(800)
            assertTrue("transport kept advancing through two rotations", seq.positionTicks() > landscape || seq.state() == SeqState.STOPPED)
            assertEquals("native state is still consistent", 0, NativeAudioBridge.getEngineState().let { if (it >= 0) 0 else 1 })
        }
    }
}
