package com.weaudio.pro.audio

/**
 * JNI surface of libweaudio.so (cpp/jni/NativeAudioBridge.cpp). No native pointers cross
 * this boundary. Control calls return 0 on success, 1 on failure. Use [NativeAudioBackend],
 * which guards against the library failing to load.
 */
internal object NativeAudioBridge {
    external fun initializeEngine(): Int
    external fun startAudio(): Int
    external fun stopAudio(): Int
    external fun pauseAudio(): Int
    external fun resumeAudio(): Int
    external fun releaseEngine()
    external fun getEngineState(): Int
    external fun getSampleRate(): Int
    external fun getChannelCount(): Int
    external fun setTestToneEnabled(enabled: Boolean)
    external fun setTestToneFrequency(hz: Float)
    external fun setMasterVolume(volume: Float)
    external fun getPositionFrames(): Long
    external fun getPeakLevel(): Float
    external fun getDiagnostics(): String
    external fun getLastError(): String

    // Drum sampler (cpp/jni/NativeAudioBridge.cpp)
    external fun drumLoadSample(slot: Int, wav: ByteArray): String
    external fun drumUnloadSample(slot: Int)
    external fun drumTrigger(slot: Int, velocity: Float): Boolean
    external fun drumStopAll()
    external fun drumSetVolume(slot: Int, volume: Float)
    external fun drumSetPitch(slot: Int, semitones: Float)
    external fun drumSetMuted(slot: Int, muted: Boolean)
    external fun drumIsLoaded(slot: Int): Boolean
    external fun drumActiveVoices(): Int
}
