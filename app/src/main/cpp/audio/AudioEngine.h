#pragma once

#include <memory>
#include <mutex>
#include <string>

#include "audio/AudioCallback.h"
#include "audio/AudioConfiguration.h"
#include "audio/AudioStreamManager.h"
#include "engine/AudioClock.h"
#include "engine/AudioProcessor.h"
#include "engine/MasterOutput.h"
#include "sampler/DrumSampler.h"

namespace weaudio {

// The shared native engine. Control methods (any thread) are serialized by one mutex;
// the real-time callback never takes it. Must be owned by a std::shared_ptr.
class AudioEngine final : public StreamErrorListener,
                          public std::enable_shared_from_this<AudioEngine> {
public:
    // The sampler is owned by the JNI layer so loaded kits survive engine re-initialisation.
    explicit AudioEngine(std::shared_ptr<DrumSampler> drums = std::make_shared<DrumSampler>())
        : drums_(std::move(drums)) {}
    ~AudioEngine() override;

    // Return true on success; on failure lastError() explains why.
    bool initialize();   // opens the stream (stopped)
    bool start();
    bool pause();
    bool resume();
    bool stop();
    void release();

    EngineState state();
    StreamInfo streamInfo();
    std::string lastError();
    std::string diagnostics();   // "key=value" lines, see AudioDiagnostics.cpp

    void setToneEnabled(bool on) { processor_.setToneEnabled(on); }
    void setToneFrequency(float hz) { processor_.setToneFrequency(hz); }
    void setMasterVolume(float v) { master_.setVolume(v); }
    int64_t positionFrames() const { return clock_.frames(); }
    float peakLevel() const { return master_.peak(); }
    DrumSampler& drums() { return *drums_; }

    // StreamErrorListener (called on an Oboe worker thread)
    void onStreamDisconnected(oboe::Result error) override;

private:
    bool openStreamLocked();
    bool startLocked();
    EngineState stateLocked() const;
    void fail(const std::string& message);

    std::mutex mutex_;
    AudioProcessor processor_;
    std::shared_ptr<DrumSampler> drums_;
    MasterOutput master_;
    AudioClock clock_;
    std::shared_ptr<AudioCallback> callback_;
    std::unique_ptr<AudioStreamManager> stream_;

    bool initialized_ = false;
    bool failed_ = false;
    bool recovering_ = false;
    Intent intent_ = Intent::Idle;
    int reconnectCount_ = 0;
    std::string lastError_;

    friend std::string buildDiagnostics(AudioEngine&);
};

std::string buildDiagnostics(AudioEngine& engine);

}  // namespace weaudio
