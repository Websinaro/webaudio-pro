#pragma once

#include <atomic>
#include <cstdint>

namespace weaudio {

// Generates the Phase 1 test tone. Future phases replace/extend render() with a mix of
// sources. Real-time safe: no allocation, no locks; parameters are lock-free atomics.
class AudioProcessor {
public:
    static constexpr float kToneAmplitude = 0.25f;  // -12 dBFS
    static constexpr float kMinFrequency = 20.0f;
    static constexpr float kRampSeconds = 0.010f;   // anti-click fade on enable/disable

    void setSampleRate(int32_t hz) noexcept { sampleRate_.store(hz, std::memory_order_relaxed); }
    int32_t sampleRate() const noexcept { return sampleRate_.load(std::memory_order_relaxed); }
    void setToneEnabled(bool on) noexcept { enabled_.store(on, std::memory_order_relaxed); }
    bool toneEnabled() const noexcept { return enabled_.load(std::memory_order_relaxed); }
    void setToneFrequency(float hz) noexcept { frequency_.store(hz, std::memory_order_relaxed); }
    float toneFrequency() const noexcept { return frequency_.load(std::memory_order_relaxed); }

    // Writes `frames` interleaved frames of `channels` channels (overwrites the buffer).
    void render(float* out, int32_t frames, int32_t channels) noexcept;

private:
    std::atomic<int32_t> sampleRate_{48000};
    std::atomic<bool> enabled_{true};
    std::atomic<float> frequency_{440.0f};
    double phase_ = 0.0;  // radians, touched only by the audio thread
    float gain_ = 0.0f;   // smoothed amplitude, audio thread only
};

}  // namespace weaudio
