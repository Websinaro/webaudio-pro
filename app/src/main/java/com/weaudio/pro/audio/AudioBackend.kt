package com.weaudio.pro.audio

import java.util.Locale

/** Mirrors weaudio::EngineState in AudioConfiguration.h. */
enum class EngineState(val code: Int) {
    UNINITIALIZED(0), STOPPED(1), STARTING(2), RUNNING(3), PAUSED(4), DISCONNECTED(5), ERROR(6);

    companion object {
        fun fromCode(code: Int): EngineState = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

const val UNAVAILABLE = "Unavailable"

/** Boundary between UI logic and the native engine; faked in unit tests. */
interface AudioBackend {
    fun initialize(): Boolean
    fun start(): Boolean
    fun pause(): Boolean
    fun resume(): Boolean
    fun stop(): Boolean
    fun release()
    fun engineState(): EngineState
    fun diagnosticsText(): String
    fun lastError(): String
    fun positionFrames(): Long
    fun peakLevel(): Float
    fun setToneEnabled(enabled: Boolean)
    fun setToneFrequency(hz: Float)
    fun setMasterVolume(volume: Float)
}

/** Real backend: every call goes to the native Oboe engine through JNI. */
class NativeAudioBackend : AudioBackend {
    private var loadError: String? = null
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("weaudio")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            false
        }
    }

    override fun initialize() = loaded && NativeAudioBridge.initializeEngine() == 0
    override fun start() = loaded && NativeAudioBridge.startAudio() == 0
    override fun pause() = loaded && NativeAudioBridge.pauseAudio() == 0
    override fun resume() = loaded && NativeAudioBridge.resumeAudio() == 0
    override fun stop() = loaded && NativeAudioBridge.stopAudio() == 0
    override fun release() { if (loaded) NativeAudioBridge.releaseEngine() }
    override fun engineState() =
        if (loaded) EngineState.fromCode(NativeAudioBridge.getEngineState()) else EngineState.UNINITIALIZED
    override fun diagnosticsText() = if (loaded) NativeAudioBridge.getDiagnostics() else ""
    override fun lastError(): String =
        if (loaded) NativeAudioBridge.getLastError() else "Native audio library could not be loaded: $loadError"
    override fun positionFrames() = if (loaded) NativeAudioBridge.getPositionFrames() else 0L
    override fun peakLevel() = if (loaded) NativeAudioBridge.getPeakLevel() else 0f
    override fun setToneEnabled(enabled: Boolean) { if (loaded) NativeAudioBridge.setTestToneEnabled(enabled) }
    override fun setToneFrequency(hz: Float) { if (loaded) NativeAudioBridge.setTestToneFrequency(hz) }
    override fun setMasterVolume(volume: Float) { if (loaded) NativeAudioBridge.setMasterVolume(volume) }
}

/** Parsed "key=value" snapshot produced by AudioDiagnostics.cpp. Missing values read "Unavailable". */
data class AudioDiagnostics(private val values: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String = values[key]?.takeIf { it.isNotBlank() } ?: UNAVAILABLE
    val sampleRate: Int? get() = values["sampleRate"]?.toIntOrNull()

    fun withUnit(key: String, unit: String): String =
        get(key).let { if (it == UNAVAILABLE) it else "$it $unit" }

    /** Buffer duration derived from the real buffer size and sample rate. */
    fun bufferMillis(): String {
        val frames = values["bufferSize"]?.toIntOrNull()
        val rate = sampleRate
        return if (frames == null || rate == null || rate <= 0) UNAVAILABLE
        else String.format(Locale.US, "%.2f ms", frames * 1000.0 / rate)
    }

    companion object {
        fun parse(text: String): AudioDiagnostics = AudioDiagnostics(
            text.lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
        )
    }
}

/** mm:ss.mmm from a frame count; "--:--.---" while the sample rate is unknown. */
fun formatTransportTime(frames: Long, sampleRate: Int?): String {
    if (sampleRate == null || sampleRate <= 0) return "--:--.---"
    val totalMs = frames * 1000L / sampleRate
    return String.format(Locale.US, "%02d:%02d.%03d", totalMs / 60000, (totalMs / 1000) % 60, totalMs % 1000)
}
