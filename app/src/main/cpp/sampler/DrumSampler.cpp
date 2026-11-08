#include "sampler/DrumSampler.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>

#include "sampler/SampleRateConverter.h"

namespace weaudio {

DrumSampler::DrumSampler() {
    chokeGroup_.fill(-1);
    chokeGroup_[2] = 1;  // closed hi-hat
    chokeGroup_[3] = 1;  // open hi-hat: a closed hit cuts an open one and vice versa
}

DrumSampler::~DrumSampler() {
    Command c;
    while (commands_.pop(c)) if (c.type == Cmd::Swap) delete c.buffer;   // hand-overs never consumed
    for (auto*& b : slots_) { delete b; b = nullptr; }
    collectRetired();
}

// ---------------------------------------------------------------- control plane
bool DrumSampler::push(const Command& c) noexcept {
    while (producer_.test_and_set(std::memory_order_acquire)) { /* other producer: sub-microsecond */ }
    const bool ok = commands_.push(c);
    producer_.clear(std::memory_order_release);
    return ok;
}

void DrumSampler::drainOffline() {
    while (consumer_.test_and_set(std::memory_order_acquire)) std::this_thread::yield();
    drainCommands();
    consumer_.clear(std::memory_order_release);
}

bool DrumSampler::pushControl(const Command& c) {
    bool pushed = false;
    for (int tries = 0; tries < 400 && !pushed; ++tries) {
        pushed = push(c);
        if (pushed) break;
        if (!active_.load()) drainOffline(); else std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (!active_.load()) drainOffline();  // no callback running: complete the hand-over ourselves
    return pushed;
}

void DrumSampler::collectRetired() {
    const SampleBuffer* b = nullptr;
    while (retired_.pop(b)) delete b;
}

std::string DrumSampler::install(int slot, std::vector<float> data, int32_t frames, int32_t rate) {
    auto* buf = new SampleBuffer{std::move(data), frames, rate};
    if (!pushControl({Cmd::Swap, slot, 0.0f, buf})) {
        delete buf;
        return "Audio engine is busy, try again";
    }
    collectRetired();
    return {};
}

std::string DrumSampler::installDecoded(int slot, DecodedSample s) {
    if (!validSlot(slot)) return "Invalid instrument";
    std::lock_guard<std::mutex> lock(control_);
    const int32_t rate = outputRate_.load();
    auto converted = resampleStereo(s.data, s.sampleRate, rate);
    if (converted.empty()) return "Resampling failed";
    const int32_t frames = int32_t(converted.size() / 2);
    std::string err = install(slot, std::move(converted), frames, rate);
    if (!err.empty()) return err;     // previous sample stays in place
    sources_[size_t(slot)] = std::move(s);
    params_[size_t(slot)].loaded.store(true);
    return {};
}

std::string DrumSampler::loadSample(int slot, const uint8_t* bytes, size_t size) {
    if (!validSlot(slot)) return "Invalid instrument";
    DecodeResult r = decodeWav(bytes, size);   // heavy work on the caller's thread
    if (!r.ok) return r.error;
    return installDecoded(slot, std::move(r.sample));
}

void DrumSampler::unloadSample(int slot) {
    if (!validSlot(slot)) return;
    std::lock_guard<std::mutex> lock(control_);
    sources_[size_t(slot)] = DecodedSample{};
    params_[size_t(slot)].loaded.store(false);
    pushControl({Cmd::Swap, slot, 0.0f, nullptr});
    collectRetired();
}

void DrumSampler::setOutputRate(int32_t hz) {
    if (hz < 8000 || hz > 384000) return;
    std::lock_guard<std::mutex> lock(control_);
    if (outputRate_.exchange(hz) == hz) return;
    for (int s = 0; s < kInstruments; ++s) {
        const DecodedSample& src = sources_[size_t(s)];
        if (src.frames <= 0) continue;
        auto converted = resampleStereo(src.data, src.sampleRate, hz);
        if (converted.empty()) continue;
        const int32_t frames = int32_t(converted.size() / 2);
        install(s, std::move(converted), frames, hz);
    }
}

void DrumSampler::setActive(bool running) {
    active_.store(running);
    if (!running) { push({Cmd::StopAll, 0, 0.0f, nullptr}); drainOffline(); }
}

bool DrumSampler::isLoaded(int slot) const { return validSlot(slot) && params_[size_t(slot)].loaded.load(); }
int DrumSampler::loadedCount() const {
    int n = 0;
    for (int i = 0; i < kInstruments; ++i) n += isLoaded(i) ? 1 : 0;
    return n;
}

// ---------------------------------------------------------------- any thread
bool DrumSampler::trigger(int slot, float velocity) noexcept {
    if (!validSlot(slot) || !active_.load(std::memory_order_relaxed)) return false;
    if (!std::isfinite(velocity) || velocity <= 0.0f) return false;
    const Params& p = params_[size_t(slot)];
    if (p.muted.load(std::memory_order_relaxed) || !p.loaded.load(std::memory_order_relaxed)) return false;
    if (!push({Cmd::Trigger, slot, std::min(velocity, 1.0f), nullptr})) {
        dropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    return true;
}

void DrumSampler::stopAll() noexcept { push({Cmd::StopAll, 0, 0.0f, nullptr}); }

void DrumSampler::setVolume(int s, float v) noexcept {
    if (validSlot(s) && std::isfinite(v)) params_[size_t(s)].volume.store(std::clamp(v, 0.0f, 2.0f), std::memory_order_relaxed);
}
void DrumSampler::setPitchSemitones(int s, float st) noexcept {
    if (validSlot(s) && std::isfinite(st)) params_[size_t(s)].pitch.store(std::clamp(st, -24.0f, 24.0f), std::memory_order_relaxed);
}
void DrumSampler::setMuted(int s, bool m) noexcept { if (validSlot(s)) params_[size_t(s)].muted.store(m, std::memory_order_relaxed); }
float DrumSampler::volume(int s) const noexcept { return validSlot(s) ? params_[size_t(s)].volume.load() : 0.0f; }
float DrumSampler::pitchSemitones(int s) const noexcept { return validSlot(s) ? params_[size_t(s)].pitch.load() : 0.0f; }
bool DrumSampler::muted(int s) const noexcept { return validSlot(s) && params_[size_t(s)].muted.load(); }

// ---------------------------------------------------------------- audio thread
void DrumSampler::drainCommands() noexcept {
    Command c;
    while (commands_.pop(c)) {
        switch (c.type) {
            case Cmd::Trigger: startVoice(c.slot, c.velocity); break;
            case Cmd::StopAll:
                for (auto& v : voices_) v.active = false;
                break;
            case Cmd::Swap: {
                const SampleBuffer* old = slots_[size_t(c.slot)];
                if (old != nullptr) {
                    // Voices still reading the old buffer are cut (rare, user-initiated edit).
                    for (auto& v : voices_) if (v.active && v.buffer == old) v.active = false;
                    if (!retired_.push(old)) { /* ring full: leak rather than free on this thread */ }
                }
                slots_[size_t(c.slot)] = c.buffer;
                break;
            }
        }
    }
}

void DrumSampler::fadeOut(DrumVoice& v) noexcept {
    if (!v.fading()) v.fadeStep = 1.0f / float(kFadeOutFrames);
}

DrumVoice* DrumSampler::allocateVoice() noexcept {
    int live = 0;
    DrumVoice* oldestLive = nullptr;
    for (auto& v : voices_) {
        if (!v.active || v.fading()) continue;
        ++live;
        if (oldestLive == nullptr || v.order < oldestLive->order) oldestLive = &v;
    }
    if (live >= kMaxPolyphony && oldestLive != nullptr) {   // voice stealing (oldest first)
        fadeOut(*oldestLive);
        steals_.fetch_add(1, std::memory_order_relaxed);
    }
    DrumVoice* oldest = nullptr;
    for (auto& v : voices_) {
        if (!v.active) return &v;
        if (oldest == nullptr || v.order < oldest->order) oldest = &v;
    }
    steals_.fetch_add(1, std::memory_order_relaxed);        // pool exhausted: hard-cut the oldest
    return oldest;
}

void DrumSampler::startVoice(int slot, float velocity, float panL, float panR, float pitchOffset) noexcept {
    const SampleBuffer* buf = slots_[size_t(slot)];
    if (buf == nullptr || buf->frames <= 0) return;
    const int group = chokeGroup_[size_t(slot)];
    if (group >= 0) for (auto& v : voices_) if (v.active && chokeGroup_[size_t(v.instrument)] == group) fadeOut(v);

    DrumVoice* v = allocateVoice();
    const Params& p = params_[size_t(slot)];
    *v = DrumVoice{};
    v->buffer = buf;
    v->instrument = slot;
    v->velocity = velocity;
    v->ratio = std::exp2((p.pitch.load(std::memory_order_relaxed) + pitchOffset) / 12.0f);
    v->panL = panL;
    v->panR = panR;
    v->gain = velocity * p.volume.load(std::memory_order_relaxed) * kHeadroom;
    v->order = ++orderCounter_;
    v->active = true;
    triggers_.fetch_add(1, std::memory_order_relaxed);
}

void DrumSampler::mix(float* out, int32_t frames, int32_t channels) noexcept {
    int active = 0;
    for (auto& v : voices_) {
        if (!v.active) continue;
        const Params& p = params_[size_t(v.instrument)];
        const float vol = p.muted.load(std::memory_order_relaxed) ? 0.0f : p.volume.load(std::memory_order_relaxed);
        const float target = v.velocity * vol * kHeadroom;
        float g = v.gain;
        const float gStep = (target - g) / float(frames);   // per-block ramp: no zipper/click on mute or volume
        const float* d = v.buffer->data.data();
        const int32_t n = v.buffer->frames;
        double pos = v.pos;
        bool alive = true;
        for (int32_t i = 0; i < frames; ++i) {
            const int32_t idx = int32_t(pos);
            if (idx >= n) { alive = false; break; }
            const float fr = float(pos - double(idx));
            const float* a = d + size_t(idx) * 2;
            const float nl = idx + 1 < n ? a[2] : 0.0f, nr = idx + 1 < n ? a[3] : 0.0f;
            float l = a[0] + (nl - a[0]) * fr, r = a[1] + (nr - a[1]) * fr;

            float env = g;
            if (v.fadeInPos < kFadeInFrames) env *= float(++v.fadeInPos) / float(kFadeInFrames);
            const float remaining = float(double(n) - pos);
            if (remaining < kTailFrames) env *= remaining / kTailFrames;
            if (v.fading()) {
                v.fadeGain -= v.fadeStep;
                if (v.fadeGain <= 0.0f) { alive = false; break; }
                env *= v.fadeGain;
            }
            l *= env * v.panL; r *= env * v.panR;
            float* o = out + size_t(i) * size_t(channels);
            if (channels == 1) o[0] += 0.5f * (l + r);
            else { o[0] += l; o[1] += r; }
            pos += v.ratio;
            g += gStep;
        }
        v.pos = pos;
        v.gain = g;
        if (!alive) v.active = false; else ++active;
    }
    activeVoices_.store(active, std::memory_order_relaxed);
}

void DrumSampler::render(float* out, int32_t frames, int32_t channels) noexcept {
    if (out == nullptr || frames <= 0 || channels <= 0) return;
    if (consumer_.test_and_set(std::memory_order_acquire)) return;  // control thread mid-handover: skip, never wait
    drainCommands();
    mix(out, frames, channels);
    consumer_.clear(std::memory_order_release);
}

void DrumSampler::renderWithHits(float* out, int32_t frames, int32_t channels, const ScheduledHit* hits, int32_t hitCount) noexcept {
    if (out == nullptr || frames <= 0 || channels <= 0) return;
    if (consumer_.test_and_set(std::memory_order_acquire)) return;
    drainCommands();
    int32_t cursor = 0;
    for (int32_t i = 0; i < hitCount; ++i) {
        const ScheduledHit& h = hits[i];
        const int32_t at = std::clamp(h.offset, cursor, frames - 1);
        if (at > cursor) {   // render up to the hit, so the new voice starts exactly at `at`
            mix(out + size_t(cursor) * size_t(channels), at - cursor, channels);
            cursor = at;
        }
        if (validSlot(h.slot) && !params_[size_t(h.slot)].muted.load(std::memory_order_relaxed)) {
            startVoice(h.slot, std::min(h.velocity, 2.0f), h.panL, h.panR, h.pitch);
        }
    }
    if (cursor < frames) mix(out + size_t(cursor) * size_t(channels), frames - cursor, channels);
    consumer_.clear(std::memory_order_release);
}

}  // namespace weaudio
