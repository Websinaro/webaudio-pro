#include "engine/Sequencer.h"

#include <algorithm>
#include <cmath>

namespace weaudio {

namespace {
constexpr double kEps = 1e-6;   // tolerance (in frames) when mapping ticks to frame offsets
}

Sequencer::Sequencer() {
    for (auto& s : snaps_) { s = std::make_unique<Snapshot>(); s->events.resize(kMaxEvents); }
}

// ------------------------------------------------------------------ control plane
bool Sequencer::publish(const SeqEvent* events, int32_t count, int64_t lengthTicks,
                        bool loopEnabled, int64_t loopStart, int64_t loopEnd) noexcept {
    if (count < 0 || count > kMaxEvents || (count > 0 && events == nullptr)) return false;
    while (publishing_.test_and_set(std::memory_order_acquire)) {}   // control threads only
    // With 3 buffers one is always neither published nor being read.
    const int pub = published_.load();
    const int rd = reading_.load();
    int target = 0;
    while (target == pub || target == rd) ++target;
    Snapshot& s = *snaps_[size_t(target)];
    std::copy(events, events + count, s.events.begin());
    std::stable_sort(s.events.begin(), s.events.begin() + count,
                     [](const SeqEvent& a, const SeqEvent& b) { return a.tick < b.tick; });
    s.count = count;
    s.length = std::max<int64_t>(lengthTicks, 0);
    s.loop = loopEnabled && loopEnd > loopStart && loopStart >= 0;
    s.loopStart = loopStart;
    s.loopEnd = loopEnd;
    published_.store(target);   // seq_cst: pairs with the re-check in acquireSnapshot()
    publishes_.fetch_add(1, std::memory_order_relaxed);
    publishing_.clear(std::memory_order_release);
    return true;
}

void Sequencer::play() noexcept { requested_.store(int32_t(TransportState::Playing)); applyPendingOffline(); }
void Sequencer::pause() noexcept { requested_.store(int32_t(TransportState::Paused)); applyPendingOffline(); }
void Sequencer::stop() noexcept {
    seekRequest_.store(0);
    requested_.store(int32_t(TransportState::Stopped));
    applyPendingOffline();
}
void Sequencer::seek(int64_t tick) noexcept { seekRequest_.store(std::max<int64_t>(tick, 0)); applyPendingOffline(); }
void Sequencer::setTempo(double bpm) noexcept { if (std::isfinite(bpm)) tempo_.store(std::clamp(bpm, 20.0, 400.0)); }
void Sequencer::setTimeSignature(int32_t n, int32_t d) noexcept {
    if (n < 1 || n > 32) return;
    if (d != 1 && d != 2 && d != 4 && d != 8 && d != 16) return;
    sigNum_.store(n); sigDen_.store(d);
}
void Sequencer::setTrackVolume(int t, float v) noexcept {
    if (t >= 0 && t < kMaxTracks && std::isfinite(v)) tracks_[size_t(t)].volume.store(std::clamp(v, 0.0f, 2.0f));
}
void Sequencer::setTrackPan(int t, float p) noexcept {
    if (t >= 0 && t < kMaxTracks && std::isfinite(p)) tracks_[size_t(t)].pan.store(std::clamp(p, -1.0f, 1.0f));
}
void Sequencer::setTrackMute(int t, bool m) noexcept { if (t >= 0 && t < kMaxTracks) tracks_[size_t(t)].mute.store(m); }
void Sequencer::setTrackSolo(int t, bool s) noexcept { if (t >= 0 && t < kMaxTracks) tracks_[size_t(t)].solo.store(s); }

void Sequencer::applyPendingOffline() noexcept {
    if (audio_.test_and_set(std::memory_order_acquire)) return;   // audio thread is running: it applies commands itself
    applyCommands();
    audio_.clear(std::memory_order_release);
}

// ------------------------------------------------------------------ audio thread
void Sequencer::reanchor(double tick, int64_t minTick) noexcept {
    anchorTick_ = tick; af_ = 0; minTick_ = minTick;
}

void Sequencer::applyCommands() noexcept {
    const int64_t seekTo = seekRequest_.exchange(-1);
    if (seekTo >= 0) {
        reanchor(double(seekTo), seekTo);
        displayTicks_.store(seekTo, std::memory_order_release);
    }
    int32_t want = requested_.load();
    if (want == int32_t(TransportState::Paused) && state_.load() == int32_t(TransportState::Stopped)) {
        requested_.store(want = int32_t(TransportState::Stopped));   // cannot pause from stopped
    }
    if (want != state_.load()) {
        if (want == int32_t(TransportState::Stopped)) {
            reanchor(0.0, 0);
            displayTicks_.store(0, std::memory_order_release);
        }
        state_.store(want, std::memory_order_release);
    }
}

const Sequencer::Snapshot* Sequencer::acquireSnapshot() noexcept {
    for (int attempt = 0; attempt < 4; ++attempt) {
        const int idx = published_.load();
        reading_.store(idx);
        if (published_.load() == idx) return snaps_[size_t(idx)].get();
    }
    return snaps_[size_t(published_.load())].get();   // 4 racing publishes in one block: practically unreachable
}

// Position model: pos = anchorTick_ + af_ / framesPerTick. `af_` counts whole frames since the last
// anchor (seek, stop, loop wrap, tempo/sample-rate change), so position never accumulates floating
// error. An event's frame is ceil((tick - anchor) * fpt): computed identically whatever the block
// size, so hit positions are independent of the audio buffer size.
int32_t Sequencer::process(int32_t frames, int32_t sampleRate, ScheduledHit* out, int32_t maxHits) noexcept {
    if (audio_.test_and_set(std::memory_order_acquire)) return 0;   // offline control call mid-flight: skip, never wait
    applyCommands();
    int32_t hits = 0;
    if (state_.load(std::memory_order_relaxed) == int32_t(TransportState::Playing) && frames > 0 && sampleRate > 0) {
        const Snapshot* snap = acquireSnapshot();
        const double fpt = framesPerTick(tempo_.load(std::memory_order_relaxed), sampleRate);
        if (fpt != lastFpt_) {   // tempo or sample rate changed: continue from the exact current musical position
            if (lastFpt_ > 0.0) reanchor(anchorTick_ + double(af_) / lastFpt_, int64_t(std::ceil(anchorTick_ + double(af_) / lastFpt_ - 1e-6)));
            lastFpt_ = fpt;
        }
        bool anySolo = false;
        for (const Track& t : tracks_) if (t.solo.load(std::memory_order_relaxed)) { anySolo = true; break; }

        int32_t cursor = 0;
        bool ended = false;
        while (cursor < frames) {
            const bool looping = snap->loop;
            const double regionEnd = looping ? double(snap->loopEnd) : double(snap->length);
            const double endExact = (regionEnd - anchorTick_) * fpt;                  // frames from anchor to region end
            if (endExact <= double(af_) + kEps) {                                    // already at/after the end
                if (looping) { reanchor(double(snap->loopStart), snap->loopStart); continue; }
                ended = true; break;
            }
            const int64_t endFrame = int64_t(std::ceil(endExact - kEps));
            const int64_t room = endFrame - af_;
            const bool atEnd = room <= int64_t(frames - cursor);
            const int32_t n = atEnd ? int32_t(std::max<int64_t>(room, 1)) : frames - cursor;
            const int64_t f0 = af_, f1 = af_ + n;                                    // segment = frames [f0, f1)

            const SeqEvent* b = snap->events.data();
            const SeqEvent* e = b + snap->count;
            const int64_t lowTick = std::max<int64_t>(minTick_, int64_t(std::floor(anchorTick_ + double(f0) / fpt)) - 2);
            const SeqEvent* it = std::lower_bound(b, e, lowTick, [](const SeqEvent& ev, int64_t t) { return ev.tick < t; });
            for (; it != e; ++it) {
                if (double(it->tick) >= regionEnd) break;                             // never fire at/after the region end
                const int64_t fe = int64_t(std::ceil((double(it->tick) - anchorTick_) * fpt - kEps));
                if (fe >= f1) { if (double(it->tick) > anchorTick_ + double(f1) / fpt + 2.0) break; continue; }
                if (fe < f0 || it->tick < minTick_) continue;
                const int tr = it->track;
                if (tr < 0 || tr >= kMaxTracks) continue;
                const Track& t = tracks_[size_t(tr)];
                if (t.mute.load(std::memory_order_relaxed)) continue;
                if (anySolo && !t.solo.load(std::memory_order_relaxed)) continue;
                if (hits >= maxHits) { droppedHits_.fetch_add(1, std::memory_order_relaxed); continue; }
                const float pan = t.pan.load(std::memory_order_relaxed);
                const float ang = (pan + 1.0f) * 0.78539816339f;            // 0..pi/2, equal power, unity at centre
                ScheduledHit& h = out[hits++];
                h.offset = cursor + int32_t(fe - f0); h.slot = it->slot; h.track = tr;
                h.velocity = it->velocity * t.volume.load(std::memory_order_relaxed);
                h.panL = std::cos(ang) * 1.41421356f; h.panR = std::sin(ang) * 1.41421356f;
                h.pitch = it->pitch;
            }
            cursor += n;
            if (atEnd) {
                const double overshoot = double(endFrame) - endExact;                 // [0,1) frames, carried into the next pass
                if (looping) { reanchor(double(snap->loopStart) + overshoot / fpt, snap->loopStart); }
                else { ended = true; break; }
            } else {
                af_ = f1;
            }
        }
        if (ended) {
            state_.store(int32_t(TransportState::Stopped), std::memory_order_release);
            requested_.store(int32_t(TransportState::Stopped));
            reanchor(0.0, 0);
        }
        displayTicks_.store(int64_t(std::floor(anchorTick_ + double(af_) / fpt)), std::memory_order_release);
        reading_.store(-1);
    }
    audio_.clear(std::memory_order_release);
    return hits;
}

}  // namespace weaudio
