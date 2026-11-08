package com.weaudio.pro.audio.sampler

import com.weaudio.pro.audio.NativeAudioBridge

/** Boundary to the native drum sampler (faked in unit tests). Every call is non-blocking except load/unload. */
interface DrumBackend {
    /** Decodes + resamples on the calling thread. Returns "" on success or an error message. */
    fun loadSample(slot: Int, wav: ByteArray): String
    fun unloadSample(slot: Int)
    /** Real-time-safe: enqueues a hit for the audio thread. False if rejected (stream not running, muted, unloaded). */
    fun trigger(slot: Int, velocity: Float): Boolean
    fun stopAll()
    fun setVolume(slot: Int, volume: Float)
    fun setPitch(slot: Int, semitones: Float)
    fun setMuted(slot: Int, muted: Boolean)
    fun isLoaded(slot: Int): Boolean
}

class NativeDrumBackend : DrumBackend {
    private var loadError: String? = null
    private val loaded: Boolean by lazy {
        try { System.loadLibrary("weaudio"); true } catch (e: UnsatisfiedLinkError) { loadError = e.message; false }
    }
    override fun loadSample(slot: Int, wav: ByteArray) =
        if (loaded) NativeAudioBridge.drumLoadSample(slot, wav) else "Native audio library could not be loaded: $loadError"
    override fun unloadSample(slot: Int) { if (loaded) NativeAudioBridge.drumUnloadSample(slot) }
    override fun trigger(slot: Int, velocity: Float) = loaded && NativeAudioBridge.drumTrigger(slot, velocity)
    override fun stopAll() { if (loaded) NativeAudioBridge.drumStopAll() }
    override fun setVolume(slot: Int, volume: Float) { if (loaded) NativeAudioBridge.drumSetVolume(slot, volume) }
    override fun setPitch(slot: Int, semitones: Float) { if (loaded) NativeAudioBridge.drumSetPitch(slot, semitones) }
    override fun setMuted(slot: Int, muted: Boolean) { if (loaded) NativeAudioBridge.drumSetMuted(slot, muted) }
    override fun isLoaded(slot: Int) = loaded && NativeAudioBridge.drumIsLoaded(slot)
}
