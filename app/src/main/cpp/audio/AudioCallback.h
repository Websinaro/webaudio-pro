#pragma once

#include <memory>

#include <oboe/Oboe.h>

#include "engine/AudioClock.h"
#include "engine/AudioProcessor.h"
#include "engine/MasterOutput.h"
#include "sampler/DrumSampler.h"

namespace weaudio {

// Implemented by AudioEngine; notified (on a non-realtime thread) after a stream error.
class StreamErrorListener {
public:
    virtual ~StreamErrorListener() = default;
    virtual void onStreamDisconnected(oboe::Result error) = 0;
};

// Oboe data + error callbacks. onAudioReady runs on the real-time audio thread:
// no allocation, no locks, no I/O, no logging.
class AudioCallback final : public oboe::AudioStreamDataCallback,
                            public oboe::AudioStreamErrorCallback {
public:
    AudioCallback(AudioProcessor& processor, DrumSampler& drums, MasterOutput& master, AudioClock& clock,
                  std::weak_ptr<StreamErrorListener> listener)
        : processor_(processor), drums_(drums), master_(master), clock_(clock), listener_(std::move(listener)) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

    // After detach() no further engine notifications are delivered.
    void detach() noexcept { detached_.store(true, std::memory_order_release); }

private:
    AudioProcessor& processor_;
    DrumSampler& drums_;
    MasterOutput& master_;
    AudioClock& clock_;
    std::weak_ptr<StreamErrorListener> listener_;
    std::atomic<bool> detached_{false};
};

}  // namespace weaudio
