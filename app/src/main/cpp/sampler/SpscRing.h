#pragma once
#include <array>
#include <atomic>
#include <cstddef>

namespace weaudio {

// Wait-free single-producer / single-consumer ring (capacity N-1). No allocation after construction.
template <typename T, size_t N>
class SpscRing {
public:
    bool push(const T& v) noexcept {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t nx = (h + 1) % N;
        if (nx == tail_.load(std::memory_order_acquire)) return false;
        buf_[h] = v;
        head_.store(nx, std::memory_order_release);
        return true;
    }
    bool pop(T& out) noexcept {
        const size_t t = tail_.load(std::memory_order_relaxed);
        if (t == head_.load(std::memory_order_acquire)) return false;
        out = buf_[t];
        tail_.store((t + 1) % N, std::memory_order_release);
        return true;
    }
private:
    std::array<T, N> buf_{};
    std::atomic<size_t> head_{0}, tail_{0};
};

}  // namespace weaudio
