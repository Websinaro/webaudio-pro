#include "engine/MasterOutput.h"

#include <algorithm>
#include <cmath>

namespace weaudio {

void MasterOutput::setVolume(float linear) noexcept {
    if (!std::isfinite(linear)) return;
    target_.store(std::clamp(linear, 0.0f, 1.0f), std::memory_order_relaxed);
}

// Identity below the knee, then a tanh curve that approaches (never exceeds) 1.0.
// Value and slope are continuous at the knee, so there is no audible discontinuity.
float MasterOutput::softLimit(float x) noexcept {
    if (!std::isfinite(x)) return 0.0f;
    const float a = std::fabs(x);
    if (a <= kKnee) return x;
    const float room = 1.0f - kKnee;
    const float y = kKnee + room * std::tanh((a - kKnee) / room);
    return std::copysign(std::min(y, 1.0f), x);
}

void MasterOutput::process(float* buffer, int32_t frames, int32_t channels) noexcept {
    if (buffer == nullptr || frames <= 0 || channels <= 0) return;
    const float target = target_.load(std::memory_order_relaxed);
    constexpr float kSmooth = 0.002f;  // one-pole per-frame smoothing, avoids zipper noise
    float cur = current_;
    float blockPeak = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        cur += (target - cur) * kSmooth;
        float* frame = buffer + static_cast<size_t>(i) * static_cast<size_t>(channels);
        for (int32_t c = 0; c < channels; ++c) {
            const float y = softLimit(frame[c] * cur);
            frame[c] = y;
            blockPeak = std::max(blockPeak, std::fabs(y));
        }
    }
    current_ = cur;
    peak_.store(blockPeak, std::memory_order_relaxed);
}

}  // namespace weaudio
