// JNI surface for com.weaudio.pro.audio.NativeAudioBridge.
// One process-wide engine; Kotlin never sees a native pointer. Control calls return 0 on
// success and 1 on failure (details via getLastError()).
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "audio/AudioEngine.h"

namespace {

std::mutex gMutex;
std::shared_ptr<weaudio::AudioEngine> gEngine;
// Process-wide sampler: loaded kits survive engine release/re-initialisation.
std::shared_ptr<weaudio::DrumSampler> gDrums = std::make_shared<weaudio::DrumSampler>();
std::shared_ptr<weaudio::Sequencer> gSeq = std::make_shared<weaudio::Sequencer>();

std::shared_ptr<weaudio::AudioEngine> engine() {
    std::lock_guard<std::mutex> lock(gMutex);
    return gEngine;
}

jstring toJString(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_weaudio_pro_audio_NativeAudioBridge_##name

JNI_FN(jint, initializeEngine)(JNIEnv*, jobject) {
    std::shared_ptr<weaudio::AudioEngine> e;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEngine) gEngine = std::make_shared<weaudio::AudioEngine>(gDrums, gSeq);
        e = gEngine;
    }
    return e->initialize() ? 0 : 1;
}

JNI_FN(jint, startAudio)(JNIEnv*, jobject) { auto e = engine(); return e && e->start() ? 0 : 1; }
JNI_FN(jint, stopAudio)(JNIEnv*, jobject) { auto e = engine(); return e && e->stop() ? 0 : 1; }
JNI_FN(jint, pauseAudio)(JNIEnv*, jobject) { auto e = engine(); return e && e->pause() ? 0 : 1; }
JNI_FN(jint, resumeAudio)(JNIEnv*, jobject) { auto e = engine(); return e && e->resume() ? 0 : 1; }

JNI_FN(void, releaseEngine)(JNIEnv*, jobject) {
    std::shared_ptr<weaudio::AudioEngine> e;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        e = std::move(gEngine);
    }
    if (e) e->release();
}

JNI_FN(jint, getEngineState)(JNIEnv*, jobject) {
    auto e = engine();
    return e ? static_cast<jint>(e->state()) : static_cast<jint>(weaudio::EngineState::Uninitialized);
}

JNI_FN(jint, getSampleRate)(JNIEnv*, jobject) { auto e = engine(); return e ? e->streamInfo().sampleRate : -1; }
JNI_FN(jint, getChannelCount)(JNIEnv*, jobject) { auto e = engine(); return e ? e->streamInfo().channelCount : -1; }

JNI_FN(void, setTestToneEnabled)(JNIEnv*, jobject, jboolean on) {
    if (auto e = engine()) e->setToneEnabled(on == JNI_TRUE);
}
JNI_FN(void, setTestToneFrequency)(JNIEnv*, jobject, jfloat hz) {
    if (auto e = engine()) e->setToneFrequency(hz);
}
JNI_FN(void, setMasterVolume)(JNIEnv*, jobject, jfloat v) {
    if (auto e = engine()) e->setMasterVolume(v);
}

JNI_FN(jlong, getPositionFrames)(JNIEnv*, jobject) { auto e = engine(); return e ? e->positionFrames() : 0; }
JNI_FN(jfloat, getPeakLevel)(JNIEnv*, jobject) { auto e = engine(); return e ? e->peakLevel() : 0.0f; }

JNI_FN(jstring, getDiagnostics)(JNIEnv* env, jobject) {
    auto e = engine();
    return toJString(env, e ? e->diagnostics() : std::string());
}
JNI_FN(jstring, getLastError)(JNIEnv* env, jobject) {
    auto e = engine();
    return toJString(env, e ? e->lastError() : std::string());
}

// ---- drum sampler (slot = instrument index 0..11) --------------------------------------
// Returns "" on success, otherwise the error text. Decoding/resampling run on the calling
// (non-realtime) thread.
JNI_FN(jstring, drumLoadSample)(JNIEnv* env, jobject, jint slot, jbyteArray data) {
    if (data == nullptr) return toJString(env, "No data");
    const jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return toJString(env, "Out of memory");
    const std::string err = gDrums->loadSample(slot, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return toJString(env, err);
}
JNI_FN(void, drumUnloadSample)(JNIEnv*, jobject, jint slot) { gDrums->unloadSample(slot); }
JNI_FN(jboolean, drumTrigger)(JNIEnv*, jobject, jint slot, jfloat velocity) {
    return gDrums->trigger(slot, velocity) ? JNI_TRUE : JNI_FALSE;
}
JNI_FN(void, drumStopAll)(JNIEnv*, jobject) { gDrums->stopAll(); }
JNI_FN(void, drumSetVolume)(JNIEnv*, jobject, jint slot, jfloat v) { gDrums->setVolume(slot, v); }
JNI_FN(void, drumSetPitch)(JNIEnv*, jobject, jint slot, jfloat st) { gDrums->setPitchSemitones(slot, st); }
JNI_FN(void, drumSetMuted)(JNIEnv*, jobject, jint slot, jboolean m) { gDrums->setMuted(slot, m == JNI_TRUE); }
JNI_FN(jboolean, drumIsLoaded)(JNIEnv*, jobject, jint slot) { return gDrums->isLoaded(slot) ? JNI_TRUE : JNI_FALSE; }
JNI_FN(jint, drumActiveVoices)(JNIEnv*, jobject) { return gDrums->activeVoices(); }

// ---- sequencer / musical transport -------------------------------------------------------
// Arrangement arrives as parallel arrays (flattened, tick-based events); copied into a
// preallocated native snapshot and published to the audio thread without locks.
JNI_FN(jboolean, seqPublish)(JNIEnv* env, jobject, jlongArray ticks, jintArray tracks, jintArray slots,
                             jfloatArray vels, jfloatArray pitches, jlong length, jboolean loop,
                             jlong loopStart, jlong loopEnd) {
    const jsize n = env->GetArrayLength(ticks);
    if (n < 0 || n > weaudio::Sequencer::kMaxEvents || env->GetArrayLength(tracks) != n || env->GetArrayLength(slots) != n ||
        env->GetArrayLength(vels) != n || env->GetArrayLength(pitches) != n) return JNI_FALSE;
    std::vector<weaudio::SeqEvent> ev(static_cast<size_t>(n));
    jlong* t = env->GetLongArrayElements(ticks, nullptr);
    jint* tr = env->GetIntArrayElements(tracks, nullptr);
    jint* sl = env->GetIntArrayElements(slots, nullptr);
    jfloat* ve = env->GetFloatArrayElements(vels, nullptr);
    jfloat* pi = env->GetFloatArrayElements(pitches, nullptr);
    for (jsize i = 0; i < n; ++i) ev[size_t(i)] = {t[i], tr[i], sl[i], ve[i], pi[i]};
    env->ReleaseLongArrayElements(ticks, t, JNI_ABORT);
    env->ReleaseIntArrayElements(tracks, tr, JNI_ABORT);
    env->ReleaseIntArrayElements(slots, sl, JNI_ABORT);
    env->ReleaseFloatArrayElements(vels, ve, JNI_ABORT);
    env->ReleaseFloatArrayElements(pitches, pi, JNI_ABORT);
    return gSeq->publish(ev.data(), n, length, loop == JNI_TRUE, loopStart, loopEnd) ? JNI_TRUE : JNI_FALSE;
}
JNI_FN(void, seqPlay)(JNIEnv*, jobject) { gSeq->play(); }
JNI_FN(void, seqPause)(JNIEnv*, jobject) { gSeq->pause(); }
JNI_FN(void, seqStop)(JNIEnv*, jobject) { gSeq->stop(); gDrums->stopAll(); }
JNI_FN(void, seqSeek)(JNIEnv*, jobject, jlong tick) { gSeq->seek(tick); }
JNI_FN(void, seqSetTempo)(JNIEnv*, jobject, jdouble bpm) { gSeq->setTempo(bpm); }
JNI_FN(void, seqSetTimeSignature)(JNIEnv*, jobject, jint n, jint d) { gSeq->setTimeSignature(n, d); }
JNI_FN(void, seqSetTrack)(JNIEnv*, jobject, jint idx, jfloat vol, jfloat pan, jboolean mute, jboolean solo) {
    gSeq->setTrackVolume(idx, vol); gSeq->setTrackPan(idx, pan);
    gSeq->setTrackMute(idx, mute == JNI_TRUE); gSeq->setTrackSolo(idx, solo == JNI_TRUE);
}
JNI_FN(jlong, seqPositionTicks)(JNIEnv*, jobject) { return gSeq->positionTicks(); }
JNI_FN(jint, seqState)(JNIEnv*, jobject) { return static_cast<jint>(gSeq->state()); }
JNI_FN(jlong, seqDroppedHits)(JNIEnv*, jobject) { return static_cast<jlong>(gSeq->droppedHits()); }
