#pragma once

#include <atomic>
#include <cstdint>

namespace weaudio {

// Final stage before the device: smoothed master volume + soft limiter that guarantees
// |sample| <= 1.0 (prevents digital clipping) and scrubs NaN/Inf. Real-time safe.
class MasterOutput {
public:
    static constexpr float kKnee = 0.85f;  // below this level samples pass untouched

    void setVolume(float linear) noexcept;
    float volume() const noexcept { return target_.load(std::memory_order_relaxed); }
    float peak() const noexcept { return peak_.load(std::memory_order_relaxed); }

    void process(float* buffer, int32_t frames, int32_t channels) noexcept;

    // Pure function, exposed for tests.
    static float softLimit(float x) noexcept;

private:
    std::atomic<float> target_{0.8f};
    std::atomic<float> peak_{0.0f};
    float current_ = 0.8f;  // audio thread only
};

}  // namespace weaudio
