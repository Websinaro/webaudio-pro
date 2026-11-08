package com.weaudio.pro.arrangement

import com.weaudio.pro.audio.NativeAudioBridge

enum class SeqState { STOPPED, PLAYING, PAUSED }

/** Boundary to the native Sequencer (faked in unit tests). All calls are lock-free on the native side. */
interface SequencerBackend {
    /** Publishes a new arrangement snapshot to the audio thread. False if rejected (too many events). */
    fun publish(data: SeqData): Boolean
    fun play()
    fun pause()
    /** Stops and returns the playhead to tick 0. */
    fun stop()
    fun seek(tick: Long)
    fun setTempo(bpm: Double)
    fun setTimeSignature(numerator: Int, denominator: Int)
    fun setTrack(index: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean)
    /** The REAL native transport position (ticks), advanced by the audio callback. */
    fun positionTicks(): Long
    fun state(): SeqState
    fun droppedHits(): Long
}

class NativeSequencerBackend : SequencerBackend {
    private val loaded: Boolean by lazy { try { System.loadLibrary("weaudio"); true } catch (e: UnsatisfiedLinkError) { false } }
    override fun publish(data: SeqData) = loaded && NativeAudioBridge.seqPublish(data.ticks, data.tracks, data.slots, data.velocities,
        data.pitches, data.lengthTicks, data.loopEnabled, data.loopStart, data.loopEnd)
    override fun play() { if (loaded) NativeAudioBridge.seqPlay() }
    override fun pause() { if (loaded) NativeAudioBridge.seqPause() }
    override fun stop() { if (loaded) NativeAudioBridge.seqStop() }
    override fun seek(tick: Long) { if (loaded) NativeAudioBridge.seqSeek(tick) }
    override fun setTempo(bpm: Double) { if (loaded) NativeAudioBridge.seqSetTempo(bpm) }
    override fun setTimeSignature(numerator: Int, denominator: Int) { if (loaded) NativeAudioBridge.seqSetTimeSignature(numerator, denominator) }
    override fun setTrack(index: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean) { if (loaded) NativeAudioBridge.seqSetTrack(index, volume, pan, mute, solo) }
    override fun positionTicks() = if (loaded) NativeAudioBridge.seqPositionTicks() else 0L
    override fun state() = if (!loaded) SeqState.STOPPED else SeqState.entries.getOrElse(NativeAudioBridge.seqState()) { SeqState.STOPPED }
    override fun droppedHits() = if (loaded) NativeAudioBridge.seqDroppedHits() else 0L
}
