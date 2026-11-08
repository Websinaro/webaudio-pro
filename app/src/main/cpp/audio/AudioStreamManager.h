#pragma once

#include <memory>
#include <string>

#include <oboe/Oboe.h>

#include "audio/AudioCallback.h"
#include "audio/AudioConfiguration.h"

namespace weaudio {

// Owns the single Oboe output stream. Not internally synchronized: AudioEngine serializes
// every call with its control mutex.
class AudioStreamManager {
public:
    explicit AudioStreamManager(std::shared_ptr<AudioCallback> callback)
        : callback_(std::move(callback)) {}
    ~AudioStreamManager() { close(); }

    // Opens with progressive fallback: exclusive+low-latency -> shared+low-latency ->
    // shared, default performance, device-chosen channels/rate.
    oboe::Result open(const StreamRequest& request);
    oboe::Result start();
    oboe::Result pause();
    oboe::Result stop();
    void close();

    bool isOpen() const { return stream_ != nullptr; }
    StreamPhase phase() const;
    StreamInfo info() const;
    const std::string& fallbackNote() const { return fallbackNote_; }

private:
    oboe::Result openWith(const StreamRequest& r, bool exclusive, bool lowLatency, bool fixedFormat);
    void waitForPhase(oboe::StreamState target, int timeoutMs);

    std::shared_ptr<AudioCallback> callback_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::string fallbackNote_;
};

}  // namespace weaudio
