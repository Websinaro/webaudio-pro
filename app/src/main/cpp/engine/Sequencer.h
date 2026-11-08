#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "sampler/ScheduledHit.h"

namespace weaudio {

// ---- Musical time -----------------------------------------------------------------------
// Internal unit: the TICK. 960 ticks per quarter note (PPQ), integers everywhere.
//   ticksPerBeat(den) = 960 * 4 / den          (4/4: 960, 6/8: 480)
//   ticksPerBar       = numerator * ticksPerBeat
//   sixteenth note    = 240 ticks, 16-step pattern in 4/4 = 1 bar = 3840 ticks
//   frames per tick   = 60 * sampleRate / (bpm * 960)
// Seconds are never stored; they only appear when converting to frames inside the audio callback.
constexpr int32_t kPPQ = 960;
constexpr int32_t kTicksPerSixteenth = kPPQ / 4;

constexpr int32_t ticksPerBeat(int32_t denominator) { return denominator > 0 ? kPPQ * 4 / denominator : kPPQ; }
constexpr int64_t ticksPerBar(int32_t numerator, int32_t denominator) { return int64_t(numerator) * ticksPerBeat(denominator); }

struct BarBeat { int64_t bar; int32_t beat; int32_t tickInBeat; };  // all zero-based
inline BarBeat ticksToBarBeat(int64_t tick, int32_t num, int32_t den) {
    const int64_t tpBar = ticksPerBar(num, den);
    const int32_t tpBeat = ticksPerBeat(den);
    if (tick < 0 || tpBar <= 0) return {0, 0, 0};
    const int64_t inBar = tick % tpBar;
    return {tick / tpBar, int32_t(inBar / tpBeat), int32_t(inBar % tpBeat)};
}
// Frames per tick for a tempo. (double: 60*sr/(bpm*PPQ), e.g. 120 BPM @ 48 kHz = 25 exactly.)
inline double framesPerTick(double bpm, int32_t sampleRate) { return 60.0 * double(sampleRate) / (bpm * double(kPPQ)); }
inline int64_t quantizeTick(int64_t tick, int32_t grid) {
    if (grid <= 0) return tick;
    return ((tick + grid / 2) / grid) * grid;
}

struct SeqEvent {
    int64_t tick = 0;
    int32_t track = 0;   // native track index 0..kMaxTracks-1
    int32_t slot = 0;    // DrumSampler slot
    float velocity = 1.0f;
    float pitch = 0.0f;
};

enum class TransportState : int32_t { Stopped = 0, Playing = 1, Paused = 2 };

// Shared musical transport + arrangement scheduler. ONE instance drives every track.
//
// Threading
//  * Control threads (JNI): publish(), play/pause/stop/seek, setTempo, setTimeSignature, setTrack*.
//    They only write atomics or a private snapshot; they never touch audio-thread state directly.
//  * Audio thread: process() only. No allocation, no locks, no I/O. A snapshot is published through
//    a 3-buffer handshake (see publish()/acquireSnapshot()): the audio thread never waits and never
//    sees a half-written arrangement, so edits during playback are safe.
//  * While no stream runs, applyPendingOffline() lets control calls (stop/seek) take effect at once.
class Sequencer {
public:
    static constexpr int kMaxTracks = 32;
    static constexpr int kMaxEvents = 32768;
    static constexpr int kMaxHitsPerBlock = 1024;

    Sequencer();

    // ---- control plane ----------------------------------------------------------------
    // Events need not be sorted. Returns false (nothing published) if count > kMaxEvents.
    bool publish(const SeqEvent* events, int32_t count, int64_t lengthTicks,
                 bool loopEnabled, int64_t loopStart, int64_t loopEnd) noexcept;
    void play() noexcept;
    void pause() noexcept;
    void stop() noexcept;                    // Stopped, position -> 0
    void seek(int64_t tick) noexcept;
    void setTempo(double bpm) noexcept;      // clamped 20..400
    void setTimeSignature(int32_t num, int32_t den) noexcept;
    void setTrackVolume(int track, float v) noexcept;   // 0..2
    void setTrackPan(int track, float p) noexcept;      // -1..1
    void setTrackMute(int track, bool m) noexcept;
    void setTrackSolo(int track, bool s) noexcept;
    void applyPendingOffline() noexcept;     // only effective while the audio thread is not running process()

    TransportState state() const noexcept { return TransportState(state_.load(std::memory_order_acquire)); }
    int64_t positionTicks() const noexcept { return displayTicks_.load(std::memory_order_acquire); }
    double tempo() const noexcept { return tempo_.load(std::memory_order_relaxed); }
    int32_t timeSigNumerator() const noexcept { return sigNum_.load(std::memory_order_relaxed); }
    int32_t timeSigDenominator() const noexcept { return sigDen_.load(std::memory_order_relaxed); }
    uint64_t droppedHits() const noexcept { return droppedHits_.load(std::memory_order_relaxed); }
    uint64_t publishCount() const noexcept { return publishes_.load(std::memory_order_relaxed); }

    // ---- audio thread -----------------------------------------------------------------
    // Advances the transport by `frames` and writes the hits due in this block (sorted by offset)
    // to `out`. Returns the number of hits. Does nothing (returns 0) unless Playing.
    int32_t process(int32_t frames, int32_t sampleRate, ScheduledHit* out, int32_t maxHits) noexcept;

private:
    struct Snapshot {
        std::vector<SeqEvent> events;   // reserved to kMaxEvents at construction, never reallocated
        int32_t count = 0;
        int64_t length = 0;
        bool loop = false;
        int64_t loopStart = 0, loopEnd = 0;
    };
    struct Track {
        std::atomic<float> volume{1.0f}, pan{0.0f};
        std::atomic<bool> mute{false}, solo{false};
    };

    const Snapshot* acquireSnapshot() noexcept;
    void applyCommands() noexcept;
    void reanchor(double tick, int64_t minTick) noexcept;

    std::array<std::unique_ptr<Snapshot>, 3> snaps_;
    std::atomic<int> published_{0};
    std::atomic<int> reading_{-1};
    std::atomic_flag publishing_ = ATOMIC_FLAG_INIT;   // serialises control-thread publishers only

    std::array<Track, kMaxTracks> tracks_;
    std::atomic<int32_t> state_{0};          // actual state (audio thread owns transitions while running)
    std::atomic<int32_t> requested_{0};
    std::atomic<int64_t> seekRequest_{-1};
    std::atomic<double> tempo_{120.0};
    std::atomic<int32_t> sigNum_{4}, sigDen_{4};
    std::atomic<int64_t> displayTicks_{0};
    std::atomic<uint64_t> droppedHits_{0}, publishes_{0};
    std::atomic_flag audio_ = ATOMIC_FLAG_INIT;   // process()/applyPendingOffline() mutual exclusion (try-lock)

    // audio-thread state (guarded by audio_): position = anchorTick_ + af_ / framesPerTick
    double anchorTick_ = 0.0;
    int64_t af_ = 0;        // whole frames elapsed since the anchor
    int64_t minTick_ = 0;   // events before this tick were already handled (seek/anchor guard)
    double lastFpt_ = 0.0;
};

}  // namespace weaudio
