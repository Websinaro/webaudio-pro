// JNI surface for com.weaudio.pro.audio.NativeAudioBridge.
// One process-wide engine; Kotlin never sees a native pointer. Control calls return 0 on
// success and 1 on failure (details via getLastError()).
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "audio/AudioEngine.h"

namespace {

std::mutex gMutex;
std::shared_ptr<weaudio::AudioEngine> gEngine;

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
        if (!gEngine) gEngine = std::make_shared<weaudio::AudioEngine>();
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
