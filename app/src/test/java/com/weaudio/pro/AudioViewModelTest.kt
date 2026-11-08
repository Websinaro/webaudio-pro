package com.weaudio.pro

import com.weaudio.pro.audio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Scriptable stand-in for the native engine (state machine only; no audio). */
class FakeBackend(var initOk: Boolean = true, var startOk: Boolean = true) : AudioBackend {
    var state = EngineState.UNINITIALIZED
    var error = ""
    var released = 0
    var position = 0L
    var toneEnabled: Boolean? = null
    var toneHz: Float? = null

    override fun initialize(): Boolean {
        if (!initOk) { state = EngineState.ERROR; error = "Audio device unavailable"; return false }
        state = EngineState.STOPPED; error = ""; return true
    }
    override fun start(): Boolean {
        if (state == EngineState.UNINITIALIZED || !startOk) { error = "Unable to start audio stream"; return false }
        state = EngineState.RUNNING; return true
    }
    override fun pause(): Boolean { if (state != EngineState.RUNNING) return false; state = EngineState.PAUSED; return true }
    override fun resume() = start()
    override fun stop(): Boolean { state = EngineState.STOPPED; position = 0; return true }
    override fun release() { state = EngineState.UNINITIALIZED; released++ }
    override fun engineState() = state
    override fun diagnosticsText() = "sampleRate=48000\nchannels=2\n"
    override fun lastError() = error
    override fun positionFrames() = position
    override fun peakLevel() = 0.2f
    override fun setToneEnabled(enabled: Boolean) { toneEnabled = enabled }
    override fun setToneFrequency(hz: Float) { toneHz = hz }
    override fun setMasterVolume(volume: Float) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class AudioViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun newVm(b: FakeBackend) = AudioViewModel(b, dispatcher)

    @Test fun initializeMovesEngineToStopped() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize()
        assertEquals(EngineState.STOPPED, vm.state.value.engine)
        assertNull(vm.state.value.error)
        assertEquals(48000, vm.meters.value.sampleRate)
    }

    @Test fun playThenStopFollowsEngine() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.play()
        assertEquals(EngineState.RUNNING, vm.state.value.engine)
        b.position = 96000; vm.stop()
        assertEquals(EngineState.STOPPED, vm.state.value.engine)
        assertEquals(0L, vm.meters.value.positionFrames)
    }

    @Test fun initializationFailureShowsErrorAndNeverRunning() {
        val b = FakeBackend(initOk = false); val vm = newVm(b)
        vm.initialize()
        assertEquals(EngineState.ERROR, vm.state.value.engine)
        assertEquals("Audio device unavailable", vm.state.value.error)
        vm.play()   // must not crash and must not claim running
        assertNotEquals(EngineState.RUNNING, vm.state.value.engine)
        vm.dismissError()
        // engine still ERROR, so the error is re-reported from the engine rather than hidden
        vm.initialize()
        assertNotNull(vm.state.value.error)
    }

    @Test fun startFailureIsReported() {
        val b = FakeBackend(startOk = false); val vm = newVm(b)
        vm.initialize(); vm.play()
        assertEquals("Unable to start audio stream", vm.state.value.error)
        assertEquals(EngineState.STOPPED, vm.state.value.engine)
    }

    @Test fun playRecoversFromErrorByReinitializing() {
        val b = FakeBackend(initOk = false); val vm = newVm(b)
        vm.initialize()
        b.initOk = true
        vm.play()
        assertEquals(EngineState.RUNNING, vm.state.value.engine)
        assertNull(vm.state.value.error)
        assertEquals(1, b.released)
    }

    @Test fun pauseAndResume() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.play(); vm.pause()
        assertEquals(EngineState.PAUSED, vm.state.value.engine)
        vm.play()
        assertEquals(EngineState.RUNNING, vm.state.value.engine)
    }

    @Test fun restartStopsAndStartsAgain() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.play(); b.position = 5000
        vm.restart()
        assertEquals(EngineState.RUNNING, vm.state.value.engine)
        assertEquals(0L, b.position)
    }

    @Test fun interruptionsPauseAndResumeOnlyWhenAllCleared() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.play()
        vm.beginInterruption("background"); vm.beginInterruption("focus")
        assertEquals(EngineState.PAUSED, vm.state.value.engine)
        vm.endInterruption("background")
        assertEquals(EngineState.PAUSED, vm.state.value.engine)
        vm.endInterruption("focus")
        assertEquals(EngineState.RUNNING, vm.state.value.engine)
    }

    @Test fun interruptionDoesNotResumeAfterUserStop() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.play(); vm.beginInterruption("background"); vm.stop(); vm.endInterruption("background")
        assertEquals(EngineState.STOPPED, vm.state.value.engine)
    }

    @Test fun interruptionDoesNotStartAudioThatWasNotPlaying() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.beginInterruption("background"); vm.endInterruption("background")
        assertEquals(EngineState.STOPPED, vm.state.value.engine)
    }

    @Test fun toneSettingsAreReappliedAfterReinitialize() {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.setToneEnabled(false); vm.setToneFrequency(880f)
        b.toneEnabled = null; b.toneHz = null
        vm.reinitialize()
        assertEquals(false, b.toneEnabled); assertEquals(880f, b.toneHz)
        assertEquals(1, b.released)
    }

    @Test fun shutdownReleasesEngine() = runTest {
        val b = FakeBackend(); val vm = newVm(b)
        vm.initialize(); vm.play(); vm.shutdown()
        assertEquals(EngineState.UNINITIALIZED, vm.state.value.engine)
        assertEquals(1, b.released)
    }
}
