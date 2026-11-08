#pragma once

#include <atomic>
#include <cstdint>

namespace weaudio {

// Sample-accurate frame counter advanced by the audio callback, read by any thread.
class AudioClock {
public:
    void advance(int32_t frames) noexcept { frames_.fetch_add(frames, std::memory_order_relaxed); }
    void reset() noexcept { frames_.store(0, std::memory_order_relaxed); }
    int64_t frames() const noexcept { return frames_.load(std::memory_order_relaxed); }

private:
    std::atomic<int64_t> frames_{0};
};

}  // namespace weaudio
