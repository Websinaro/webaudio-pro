#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

#include "sampler/DrumVoice.h"
#include "sampler/SampleBuffer.h"
#include "sampler/SampleDecoder.h"
#include "sampler/SpscRing.h"

namespace weaudio {

// Native drum sampler: 12 instrument slots, 32-voice polyphony, real-time safe render().
//
// Threading
//  * Control threads (Kotlin/JNI): loadSample/unloadSample/setOutputRate. Decode + resample run
//    on the CALLING thread; the finished immutable SampleBuffer is handed over through a
//    command ring. The audio thread installs it and returns the old one through a retire ring,
//    which control threads free. The audio thread never allocates, locks, or frees.
//  * Any thread: trigger(), stopAll() and the atomic per-instrument parameters. Multiple
//    producers are serialised by a tiny spin flag (never touched by the audio thread).
//  * Audio thread: render() only. A try-lock guard (never blocks) keeps it mutually exclusive
//    with "offline" draining, used while the stream is not running so loads still complete.
//
// Future timeline/sequencer events must call trigger() as well (same path).
class DrumSampler {
public:
    static constexpr int kInstruments = 12;
    static constexpr int kMaxPolyphony = 32;   // non-fading voices
    static constexpr int kVoicePool = 40;      // + room for voices fading out after steal/choke
    static constexpr int kFadeInFrames = 32;   // ~0.7 ms @ 48 kHz
    static constexpr int kFadeOutFrames = 144; // ~3 ms
    static constexpr float kTailFrames = 48.0f;// fade applied to the last frames of a sample
    static constexpr float kHeadroom = 0.6f;   // per-voice scale; MasterOutput soft-limits the sum

    DrumSampler();
    ~DrumSampler();
    DrumSampler(const DrumSampler&) = delete;
    DrumSampler& operator=(const DrumSampler&) = delete;

    // ---- control plane ----------------------------------------------------------------
    // Returns "" on success, otherwise a user-presentable error. Previous sample is kept on failure.
    std::string loadSample(int slot, const uint8_t* bytes, size_t size);
    std::string installDecoded(int slot, DecodedSample sample);
    void unloadSample(int slot);
    void setOutputRate(int32_t hz);          // re-resamples every loaded sample
    int32_t outputRate() const { return outputRate_.load(); }
    void setActive(bool running);            // engine: callback is (not) being driven
    bool isLoaded(int slot) const;
    int loadedCount() const;

    // ---- any thread -------------------------------------------------------------------
    bool trigger(int slot, float velocity) noexcept;  // false if rejected (inactive/queue full/muted)
    void stopAll() noexcept;
    void setVolume(int slot, float linear) noexcept;    // 0..2
    void setPitchSemitones(int slot, float st) noexcept;// -24..24, applied to new hits
    void setMuted(int slot, bool muted) noexcept;
    float volume(int slot) const noexcept;
    float pitchSemitones(int slot) const noexcept;
    bool muted(int slot) const noexcept;

    // ---- audio thread -----------------------------------------------------------------
    // Adds the drum mix into `out` (does not clear it).
    void render(float* out, int32_t frames, int32_t channels) noexcept;

    // ---- stats (atomics, any thread) --------------------------------------------------
    int activeVoices() const { return activeVoices_.load(std::memory_order_relaxed); }
    uint64_t triggerCount() const { return triggers_.load(std::memory_order_relaxed); }
    uint64_t stealCount() const { return steals_.load(std::memory_order_relaxed); }
    uint64_t droppedCount() const { return dropped_.load(std::memory_order_relaxed); }

private:
    enum class Cmd : uint8_t { Trigger, Swap, StopAll };
    struct Command { Cmd type = Cmd::StopAll; int32_t slot = 0; float velocity = 0; const SampleBuffer* buffer = nullptr; };
    struct Params {
        std::atomic<float> volume{1.0f}, pitch{0.0f};
        std::atomic<bool> muted{false}, loaded{false};
    };

    bool push(const Command& c) noexcept;
    bool pushControl(const Command& c);
    void drainOffline();
    void drainCommands() noexcept;
    void startVoice(int slot, float velocity) noexcept;
    DrumVoice* allocateVoice() noexcept;
    void fadeOut(DrumVoice& v) noexcept;
    void mix(float* out, int32_t frames, int32_t channels) noexcept;
    void collectRetired();
    std::string install(int slot, std::vector<float> data, int32_t frames, int32_t rate);
    static bool validSlot(int s) { return s >= 0 && s < kInstruments; }

    std::array<Params, kInstruments> params_;
    std::array<int, kInstruments> chokeGroup_;
    // audio-thread state (guarded by consumer_)
    std::array<const SampleBuffer*, kInstruments> slots_{};
    std::array<DrumVoice, kVoicePool> voices_{};
    uint64_t orderCounter_ = 0;

    SpscRing<Command, 256> commands_;
    SpscRing<const SampleBuffer*, 64> retired_;
    std::atomic_flag producer_ = ATOMIC_FLAG_INIT;
    std::atomic_flag consumer_ = ATOMIC_FLAG_INIT;

    std::mutex control_;  // control threads only
    std::array<DecodedSample, kInstruments> sources_;
    std::atomic<int32_t> outputRate_{48000};
    std::atomic<bool> active_{false};

    std::atomic<int> activeVoices_{0};
    std::atomic<uint64_t> triggers_{0}, steals_{0}, dropped_{0};
};

}  // namespace weaudio
