package com.weaudio.pro.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Slow-changing state: only changes on user action or engine state change. */
data class AudioUiState(
    val engine: EngineState = EngineState.UNINITIALIZED,
    val diagnostics: AudioDiagnostics = AudioDiagnostics(),
    val error: String? = null,
    val toneEnabled: Boolean = false,   // Phase 2: the Studio plays drums; the diagnostics tone is opt-in
    val toneFrequency: Float = 440f,
    val masterVolume: Float = 0.8f,
    val deviceInfo: Map<String, String> = emptyMap(),
)

/** Fast-changing values, kept apart so only the widgets that show them recompose. */
data class TransportMeters(val positionFrames: Long = 0, val peak: Float = 0f, val sampleRate: Int? = null)

/**
 * Drives the native engine off the main thread. All state shown in the UI is read back from
 * the engine; nothing is assumed. Commands are serialized by [lock].
 */
class AudioViewModel(
    private val backend: AudioBackend,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val readDeviceInfo: () -> Map<String, String> = { emptyMap() },
) : ViewModel() {

    private val _state = MutableStateFlow(AudioUiState())
    val state: StateFlow<AudioUiState> = _state.asStateFlow()
    private val _meters = MutableStateFlow(TransportMeters())
    val meters: StateFlow<TransportMeters> = _meters.asStateFlow()

    private val lock = Mutex()
    private val interruptions = linkedSetOf<String>()   // guarded by lock
    private var resumeAfterInterruption = false          // guarded by lock

    private fun launchOp(block: () -> Unit) {
        viewModelScope.launch(io) { lock.withLock { block() } }
    }

    // ---- commands -------------------------------------------------------------------------

    fun initialize() = launchOp {
        _state.update { it.copy(deviceInfo = readDeviceInfo()) }
        ensureInitialized()
        refresh()
    }

    fun play() = launchOp { doPlay(); refresh() }
    fun pause() = launchOp { doPause(); refresh() }
    fun stop() = launchOp { doStop(); refresh() }
    fun restart() = launchOp { doStop(); doPlay(); refresh() }

    /** Tears the engine down and brings it up again (diagnostics screen / error recovery). */
    fun reinitialize() = launchOp {
        backend.release()
        ensureInitialized()
        refresh()
    }

    /** Pauses for [reason] (background, audio focus loss); resumes once all reasons clear. */
    fun beginInterruption(reason: String) = launchOp {
        val first = interruptions.isEmpty()
        interruptions += reason
        if (first) {
            val s = backend.engineState()
            if (s == EngineState.RUNNING || s == EngineState.STARTING) {
                backend.pause()
                resumeAfterInterruption = true
            }
        }
        refresh()
    }

    fun endInterruption(reason: String) = launchOp {
        interruptions -= reason
        if (interruptions.isEmpty() && resumeAfterInterruption) {
            resumeAfterInterruption = false
            doPlay()
        }
        refresh()
    }

    fun setToneEnabled(enabled: Boolean) {
        _state.update { it.copy(toneEnabled = enabled) }
        backend.setToneEnabled(enabled)
    }

    fun setToneFrequency(hz: Float) {
        _state.update { it.copy(toneFrequency = hz) }
        backend.setToneFrequency(hz)
    }

    fun setMasterVolume(volume: Float) {
        _state.update { it.copy(masterVolume = volume) }
        backend.setMasterVolume(volume)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Safe, idempotent release used on app exit. */
    fun shutdown() {
        backend.release()
        _meters.value = TransportMeters()
        _state.update { it.copy(engine = EngineState.UNINITIALIZED) }
    }

    /** Called periodically by the UI while visible; reads real engine state. */
    suspend fun poll() = withContext(io) { refresh() }

    override fun onCleared() {
        backend.release()
    }

    // ---- internals (run under [lock] or from poll) -----------------------------------------

    private fun ensureInitialized(): Boolean {
        when (backend.engineState()) {
            EngineState.UNINITIALIZED -> Unit
            EngineState.ERROR -> backend.release()   // recover from a failed engine
            else -> return true
        }
        val ok = backend.initialize()
        if (ok) {
            backend.setToneEnabled(_state.value.toneEnabled)
            backend.setToneFrequency(_state.value.toneFrequency)
            backend.setMasterVolume(_state.value.masterVolume)
            _state.update { it.copy(error = null) }
        } else {
            reportFailure("Audio initialization failed")
        }
        return ok
    }

    private fun reportFailure(fallback: String) {
        val message = backend.lastError().ifBlank { fallback }
        _state.update { it.copy(error = message) }
    }

    private fun doPlay() {
        if (!ensureInitialized()) return
        resumeAfterInterruption = false
        val ok = if (backend.engineState() == EngineState.PAUSED) backend.resume() else backend.start()
        if (ok) _state.update { it.copy(error = null) } else reportFailure("Unable to start audio")
    }

    private fun doPause() {
        resumeAfterInterruption = false
        if (!backend.pause()) reportFailure("Unable to pause audio")
    }

    private fun doStop() {
        resumeAfterInterruption = false
        if (backend.engineState() != EngineState.UNINITIALIZED && !backend.stop()) reportFailure("Unable to stop audio")
    }

    private fun refresh() {
        val engine = backend.engineState()
        val diagnostics = AudioDiagnostics.parse(backend.diagnosticsText())
        _meters.value = TransportMeters(
            positionFrames = backend.positionFrames(),
            peak = if (engine == EngineState.RUNNING) backend.peakLevel() else 0f,
            sampleRate = diagnostics.sampleRate,
        )
        _state.update {
            it.copy(
                engine = engine,
                diagnostics = diagnostics,
                error = if (engine == EngineState.ERROR) backend.lastError().ifBlank { "Audio engine error" } else it.error,
            )
        }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            val appContext = context.applicationContext
            initializer { AudioViewModel(NativeAudioBackend(), Dispatchers.Default) { queryDeviceInfo(appContext) } }
        }

        /** Real values from the OS; anything the OS does not report is "Unavailable". */
        fun queryDeviceInfo(context: Context): Map<String, String> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            return mapOf(
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Native output rate" to (am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.let { "$it Hz" } ?: UNAVAILABLE),
                "Native frames/buffer" to (am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: UNAVAILABLE),
                "Pro audio" to (if (context.packageManager.hasSystemFeature("android.hardware.audio.pro")) "Supported" else "Not reported"),
                "Low-latency" to (if (context.packageManager.hasSystemFeature("android.hardware.audio.low_latency")) "Supported" else "Not reported"),
            )
        }
    }
}
