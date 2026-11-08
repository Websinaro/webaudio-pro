package com.weaudio.pro

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import com.weaudio.pro.audio.keyboard.KeyInput
import com.weaudio.pro.audio.keyboard.KeyMap
import com.weaudio.pro.audio.sampler.DrumBackend
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.samples.KitInfo
import com.weaudio.pro.samples.KitSource
import com.weaudio.pro.settings.DrumSettingsStore
import com.weaudio.pro.studio.drum.DrumViewModel
import com.weaudio.pro.studio.drum.SampleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeDrumBackend : DrumBackend {
    val loaded = HashMap<Int, ByteArray>()
    val hits = ArrayList<Pair<Int, Float>>()
    val volumes = HashMap<Int, Float>(); val muted = HashMap<Int, Boolean>()
    var running = true
    var rejectLoads = false
    override fun loadSample(slot: Int, wav: ByteArray): String =
        if (rejectLoads || wav.isEmpty()) "Not a RIFF/WAVE file" else { loaded[slot] = wav; "" }
    override fun unloadSample(slot: Int) { loaded.remove(slot) }
    override fun trigger(slot: Int, velocity: Float): Boolean {
        if (!running || slot !in loaded || muted[slot] == true) return false
        hits += slot to velocity; return true
    }
    override fun stopAll() {}
    override fun setVolume(slot: Int, volume: Float) { volumes[slot] = volume }
    override fun setPitch(slot: Int, semitones: Float) {}
    override fun setMuted(slot: Int, muted: Boolean) { this.muted[slot] = muted }
    override fun isLoaded(slot: Int) = slot in loaded
}

class FakeKits(private val missing: Set<String> = emptySet()) : KitSource {
    override fun kits() = listOf(KitInfo("a", "A"), KitInfo("b", "B"))
    override fun read(kitId: String, instrument: DrumInstrument): ByteArray? = if (instrument.fileName in missing) null else "$kitId/${instrument.fileName}".toByteArray()
}

class MemSettings : DrumSettingsStore {
    var map = KeyMap.defaults(); var vel = 0.8f
    override fun loadKeyMap() = map
    override fun saveKeyMap(map: KeyMap) { this.map = map }
    override fun loadVelocity() = vel
    override fun saveVelocity(v: Float) { vel = v }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DrumViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(b: FakeDrumBackend = FakeDrumBackend(), kits: KitSource = FakeKits(), s: MemSettings = MemSettings(), saved: SavedStateHandle = SavedStateHandle()) =
        DrumViewModel(b, kits, s, saved, io = dispatcher, pressVisibleMs = 0)
    private fun key(code: Int, down: Boolean = true, repeat: Int = 0) = KeyInput(code, down, repeat, 3, true)

    @Test fun defaultKitLoadsAllTwelveInstruments() {
        val b = FakeDrumBackend(); val v = vm(b)
        assertEquals("a", v.state.value.kitId); assertEquals(12, v.state.value.loadedCount); assertFalse(v.state.value.kitLoading)
        assertEquals(12, b.loaded.size)
        assertEquals("a/kick.wav", String(b.loaded[0]!!))
    }

    @Test fun kitSelectionReloadsSamples() {
        val b = FakeDrumBackend(); val v = vm(b); v.loadKit("b")
        assertEquals("b", v.state.value.kitId); assertEquals("b/snare.wav", String(b.loaded[1]!!))
    }

    @Test fun missingKitFileShowsErrorForThatInstrumentOnly() {
        val v = vm(kits = FakeKits(missing = setOf("ride.wav")))
        assertEquals(SampleStatus.ERROR, v.state.value[DrumInstrument.RIDE].status)
        assertNotNull(v.state.value[DrumInstrument.RIDE].error)
        assertEquals(11, v.state.value.loadedCount)
    }

    @Test fun touchAndKeyboardReachTheSameBackendTriggerWithSameVelocity() {
        val b = FakeDrumBackend(); val v = vm(b); v.setStudioVisible(true)
        assertTrue(v.trigger(DrumInstrument.SNARE))                        // touch path
        v.onKeyEvent(key(KeyEvent.KEYCODE_S))                              // keyboard path
        assertEquals(listOf(1 to 0.8f, 1 to 0.8f), b.hits)
    }

    @Test fun keyboardRequiresStudioVisibleAndWindowFocus() {
        val b = FakeDrumBackend(); val v = vm(b)
        assertFalse("not visible", v.onKeyEvent(key(KeyEvent.KEYCODE_S))); assertTrue(b.hits.isEmpty())
        v.setStudioVisible(true)
        assertTrue(v.onKeyEvent(key(KeyEvent.KEYCODE_S))); assertEquals(listOf(1 to 0.8f), b.hits)
        v.onKeyEvent(key(KeyEvent.KEYCODE_S, down = false))
        v.setWindowFocused(false); assertFalse(v.onKeyEvent(key(KeyEvent.KEYCODE_S))); assertEquals(1, b.hits.size)
        v.setWindowFocused(true); v.setTextInputActive(true); assertFalse("typing in a text field", v.onKeyEvent(key(KeyEvent.KEYCODE_S)))
        assertEquals(1, b.hits.size)
    }

    @Test fun autoRepeatDoesNotRetrigger() {
        val b = FakeDrumBackend(); val v = vm(b); v.setStudioVisible(true)
        v.onKeyEvent(key(KeyEvent.KEYCODE_A)); repeat(10) { assertTrue(v.onKeyEvent(key(KeyEvent.KEYCODE_A, repeat = it + 1))) }
        assertEquals(1, b.hits.size)
    }

    @Test fun focusLossClearsPressedPadsAndAllowsRetrigger() {
        val b = FakeDrumBackend(); val v = vm(b); v.setStudioVisible(true)
        v.onKeyEvent(key(KeyEvent.KEYCODE_A)); assertTrue(DrumInstrument.KICK in v.pressed.value)
        v.setWindowFocused(false); assertTrue(DrumInstrument.KICK !in v.pressed.value)
        v.setWindowFocused(true); v.onKeyEvent(key(KeyEvent.KEYCODE_A)); assertEquals(2, b.hits.size)
    }

    @Test fun keyboardDisconnectionReleasesHeldKey() {
        val b = FakeDrumBackend(); val v = vm(b); v.setStudioVisible(true)
        v.onKeyEvent(key(KeyEvent.KEYCODE_A)); v.onKeyboardDeviceRemoved(3)
        assertTrue(v.pressed.value.isEmpty()); v.onKeyEvent(key(KeyEvent.KEYCODE_A)); assertEquals(2, b.hits.size)
    }

    @Test fun captureRemapsKeyPersistsAndEscapeCancels() {
        val s = MemSettings(); val b = FakeDrumBackend(); val v = vm(b, s = s); v.setStudioVisible(true)
        v.beginKeyCapture(DrumInstrument.CLAP)
        assertTrue(v.onKeyEvent(key(KeyEvent.KEYCODE_Z))); assertTrue(v.onKeyEvent(key(KeyEvent.KEYCODE_Z, down = false)))
        assertTrue("capture must not play a drum", b.hits.isEmpty())
        assertEquals(DrumInstrument.CLAP, v.state.value.keyMap.instrumentFor(KeyEvent.KEYCODE_Z)); assertNull(v.state.value.captureTarget)
        assertEquals(KeyEvent.KEYCODE_Z, s.map.keyFor(DrumInstrument.CLAP))
        v.onKeyEvent(key(KeyEvent.KEYCODE_Z)); assertEquals(listOf(DrumInstrument.CLAP.slot to 0.8f), b.hits)
        v.beginKeyCapture(DrumInstrument.KICK); v.onKeyEvent(key(DrumViewModel.KEYCODE_ESCAPE)); assertNull(v.state.value.captureTarget)
        assertEquals(KeyEvent.KEYCODE_A, v.state.value.keyMap.keyFor(DrumInstrument.KICK))
        v.resetKeyMap(); assertEquals(KeyMap.defaults(), v.state.value.keyMap)
    }

    @Test fun fixedVelocityIsConfigurableAndClamped() {
        val b = FakeDrumBackend(); val s = MemSettings(); val v = vm(b, s = s)
        v.setVelocity(0.5f); v.trigger(DrumInstrument.KICK); assertEquals(0.5f, b.hits.last().second)
        v.setVelocity(9f); assertEquals(1f, v.state.value.velocity); assertEquals(1f, s.vel)
    }

    @Test fun mutedPadReportsStateAndNativeIsInformed() {
        val b = FakeDrumBackend(); val v = vm(b)
        v.setMuted(DrumInstrument.KICK, true); assertTrue(v.state.value[DrumInstrument.KICK].muted); assertEquals(true, b.muted[0])
        assertFalse(v.trigger(DrumInstrument.KICK))
    }

    @Test fun rejectedTriggerWhenEngineStoppedIsReported() {
        val b = FakeDrumBackend(); b.running = false; assertFalse(vm(b).trigger(DrumInstrument.KICK))
    }

    @Test fun selectionAndKitSurviveRecreationViaSavedState() {
        val saved = SavedStateHandle(); val b = FakeDrumBackend()
        val v1 = vm(b, saved = saved); v1.select(DrumInstrument.RIDE); v1.loadKit("b")
        val v2 = vm(FakeDrumBackend(), saved = saved)   // simulates process recreation
        assertEquals(DrumInstrument.RIDE, v2.state.value.selected); assertEquals("b", v2.state.value.kitId)
    }

    @Test fun userSampleReplaceFailureKeepsPreviousSampleAndRestoreWorks() {
        val b = FakeDrumBackend(); val v = vm(b)
        v.replaceSample(DrumInstrument.SNARE, "mine.wav", "WAVDATA".toByteArray())
        assertTrue(v.state.value[DrumInstrument.SNARE].isUserSample); assertEquals("mine.wav", v.state.value[DrumInstrument.SNARE].sampleName)
        v.replaceSample(DrumInstrument.SNARE, "bad.wav", ByteArray(0))   // native rejects
        val s = v.state.value[DrumInstrument.SNARE]
        assertEquals(SampleStatus.LOADED, s.status); assertEquals("Not a RIFF/WAVE file", s.error)
        v.restoreOriginal(DrumInstrument.SNARE)
        val r = v.state.value[DrumInstrument.SNARE]
        assertFalse(r.isUserSample); assertNull(r.error); assertEquals("a/snare.wav", String(b.loaded[1]!!))
    }

    @Test fun volumeAndMuteSurviveSampleReplacement() {
        val b = FakeDrumBackend(); val v = vm(b)
        v.setVolume(DrumInstrument.KICK, 0.4f); v.replaceSample(DrumInstrument.KICK, "k.wav", byteArrayOf(1))
        assertEquals(0.4f, b.volumes[0]); assertEquals(0.4f, v.state.value[DrumInstrument.KICK].volume)
    }

    @Test fun padVisualDoesNotDependOnAudioAcceptance() = runTest {
        val sd = StandardTestDispatcher(testScheduler); Dispatchers.setMain(sd)
        val b = FakeDrumBackend(); val v = DrumViewModel(b, FakeKits(), MemSettings(), SavedStateHandle(), io = sd, pressVisibleMs = 50)
        advanceUntilIdle()
        v.trigger(DrumInstrument.KICK); assertEquals(1, b.hits.size)       // audio already queued...
        assertTrue(DrumInstrument.KICK in v.pressed.value)                 // ...before any visual timer ran
        v.release(DrumInstrument.KICK); advanceUntilIdle(); assertTrue(v.pressed.value.isEmpty())
    }
}
