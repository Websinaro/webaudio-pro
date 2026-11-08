// Host tests for the Phase 3 musical clock + sequencer (tick math, sample-accurate scheduling,
// transport, loop, mute/solo, tempo changes, live arrangement edits, real-time safety).
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <thread>
#include <vector>

#include "engine/Sequencer.h"
#include "sampler/DrumSampler.h"

using namespace weaudio;
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s (line %d)\n", msg, __LINE__); ++failures; } else std::printf("ok:   %s\n", msg); } while (0)

// ---- allocation counter: proves the audio path does not allocate -------------------------
static std::atomic<long> gAllocs{0};
static thread_local bool tCount = false;
#pragma GCC diagnostic ignored "-Wmismatched-new-delete"
void* operator new(std::size_t n) { if (tCount) gAllocs.fetch_add(1); void* p = std::malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }

constexpr int kSR = 48000;
struct Hit { int64_t frame; int slot, track; float vel, pl, pr; };

struct Rig {
    Sequencer seq;
    DrumSampler drums;
    std::vector<ScheduledHit> buf = std::vector<ScheduledHit>(Sequencer::kMaxHitsPerBlock);
    int64_t frame = 0;
    Rig() {
        drums.setOutputRate(kSR);
        DecodedSample s; s.sampleRate = kSR; s.frames = 4000;
        for (int i = 0; i < 4000; ++i) { s.data.push_back(0.5f); s.data.push_back(0.5f); }
        s.peak = 0.5f;
        for (int slot = 0; slot < 12; ++slot) drums.installDecoded(slot, s);
        drums.setActive(true);
    }
    // Simulates the audio callback for `frames` frames in blocks of `block`; returns hits with absolute frame positions.
    std::vector<Hit> run(int frames, int block, std::vector<float>* audio = nullptr) {
        std::vector<Hit> hits;
        std::vector<float> out(size_t(block) * 2);
        for (int done = 0; done < frames; done += block) {
            const int n = std::min(block, frames - done);
            std::fill(out.begin(), out.end(), 0.0f);
            const int c = seq.process(n, kSR, buf.data(), int(buf.size()));
            drums.renderWithHits(out.data(), n, 2, buf.data(), c);
            for (int i = 0; i < c; ++i) hits.push_back({frame + buf[size_t(i)].offset, buf[size_t(i)].slot, buf[size_t(i)].track,
                                                         buf[size_t(i)].velocity, buf[size_t(i)].panL, buf[size_t(i)].panR});
            if (audio) audio->insert(audio->end(), out.begin(), out.begin() + n * 2);
            frame += n;
        }
        return hits;
    }
};
static SeqEvent ev(int64_t tick, int track, int slot, float vel = 1.0f) { SeqEvent e; e.tick = tick; e.track = track; e.slot = slot; e.velocity = vel; return e; }
static bool pub(Sequencer& s, std::vector<SeqEvent> e, int64_t len, bool loop = false, int64_t ls = 0, int64_t le = 0) {
    return s.publish(e.data(), int(e.size()), len, loop, ls, le);
}

static void testTimeMath() {
    CHECK(framesPerTick(120.0, 48000) == 25.0, "120 BPM @48k = exactly 25 frames/tick");
    CHECK(std::fabs(framesPerTick(90.0, 44100) - 60.0 * 44100 / (90.0 * 960)) < 1e-9, "tempo-to-frames @90 BPM/44.1k");
    CHECK(ticksPerBeat(4) == 960 && ticksPerBeat(8) == 480 && ticksPerBeat(2) == 1920, "ticks per beat by denominator");
    CHECK(ticksPerBar(4, 4) == 3840 && ticksPerBar(6, 8) == 2880 && ticksPerBar(3, 4) == 2880 && ticksPerBar(7, 8) == 3360, "ticks per bar");
    BarBeat b = ticksToBarBeat(2 * 3840 + 3 * 960 + 10, 4, 4);
    CHECK(b.bar == 2 && b.beat == 3 && b.tickInBeat == 10, "tick -> bar/beat (4/4)");
    b = ticksToBarBeat(2880 + 480 * 2 + 5, 6, 8);
    CHECK(b.bar == 1 && b.beat == 2 && b.tickInBeat == 5, "tick -> bar/beat (6/8)");
    CHECK(kTicksPerSixteenth == 240 && 16 * kTicksPerSixteenth == 3840, "16 sixteenths = one 4/4 bar");
    CHECK(quantizeTick(119, 240) == 0 && quantizeTick(121, 240) == 240 && quantizeTick(360, 240) == 480, "quantize to nearest grid");
}

static void testScheduling() {
    for (int block : {64, 192, 300, 480, 1024}) {
        Rig r; pub(r.seq, {ev(0, 0, 0), ev(960, 1, 1), ev(961, 0, 2), ev(1920, 0, 0)}, 3840);
        r.seq.play();
        auto h = r.run(60000, block);
        bool ok = h.size() == 4 && h[0].frame == 0 && h[1].frame == 24000 && h[2].frame == 24025 && h[3].frame == 48000;
        char m[96]; std::snprintf(m, sizeof m, "sample-accurate hit frames, block=%d", block);
        CHECK(ok, m);
    }
    // Audible onset is exactly at the scheduled sample (not rounded to a block boundary).
    Rig r; pub(r.seq, {ev(960, 0, 0)}, 3840); r.seq.play();
    std::vector<float> a; r.run(30000, 300, &a);
    int64_t first = -1; for (size_t i = 0; i < a.size() / 2; ++i) if (std::fabs(a[i * 2]) > 0.0f) { first = int64_t(i); break; }
    CHECK(first == 24000, "audio onset is at sample 24000 (inside a 300-frame block)");
}

static void testSync() {
    Rig r; std::vector<SeqEvent> e;
    for (int t = 0; t < 6; ++t) e.push_back(ev(1920, t, t));       // six tracks, same tick
    e.push_back(ev(1920, 0, 5)); e.push_back(ev(1920, 0, 5));       // + overlapping duplicates on one track
    pub(r.seq, e, 3840); r.seq.play();
    auto h = r.run(60000, 256);
    bool same = h.size() == 8; for (auto& x : h) same = same && x.frame == 48000;
    CHECK(same, "6 tracks + duplicate events trigger on the same sample");
    std::vector<int> tracks; for (auto& x : h) tracks.push_back(x.track);
    CHECK(std::count(tracks.begin(), tracks.end(), 0) == 3, "same-track simultaneous events all trigger");
}

static void testBoundariesAndLoop() {
    { Rig r; pub(r.seq, {ev(0, 0, 0), ev(3839, 0, 0), ev(3840, 0, 0), ev(9000, 0, 0)}, 3840); r.seq.play();
      auto h = r.run(200000, 512);
      CHECK(h.size() == 2, "events at/after arrangement end never fire");
      CHECK(r.seq.state() == TransportState::Stopped && r.seq.positionTicks() == 0, "auto-stop at arrangement end, position reset"); }
    { Rig r; pub(r.seq, {ev(0, 0, 0), ev(1920, 0, 1)}, 7680, true, 0, 3840); r.seq.play();
      auto h = r.run(96000 * 3, 700);
      std::vector<int64_t> f; for (auto& x : h) f.push_back(x.frame);
      CHECK(h.size() == 6 && f[0] == 0 && f[2] == 96000 && f[4] == 192000 && f[3] == 96000 + 48000, "loop region repeats with exact bar-length period");
      CHECK(r.seq.state() == TransportState::Playing, "looping transport keeps playing");
      for (int i = 0; i < 20; ++i) r.run(96000, 333);   // drift check over 20 more loops
      CHECK(std::llabs(r.seq.positionTicks() - (int64_t(r.frame) % 96000) / 25) <= 1, "no drift after many loops"); }
    { Rig r; pub(r.seq, {ev(3000, 0, 0), ev(500, 0, 1)}, 7680, true, 1000, 3840); r.seq.play();   // loop starts at 1000: event@500 never plays
      auto h = r.run(100000, 512); bool onlyLoop = true; for (auto& x : h) onlyLoop = (onlyLoop && x.slot == 0) || x.frame == 12500;
      CHECK(onlyLoop, "loop region wraps to loop start (not to 0)"); }
}

static void testTransport() {
    Rig a, b;   // b is the uninterrupted reference
    auto ev6 = std::vector<SeqEvent>{ev(0, 0, 0), ev(960, 0, 1), ev(1920, 0, 2), ev(2880, 0, 3)};
    pub(a.seq, ev6, 3840); pub(b.seq, ev6, 3840); a.seq.play(); b.seq.play();
    auto ref = b.run(100000, 256);
    auto h1 = a.run(30000, 256);
    a.seq.pause();
    auto h2 = a.run(50000, 256);                    // paused: stream running, transport frozen
    int64_t pausedAt = a.seq.positionTicks();
    CHECK(h2.empty() && a.seq.state() == TransportState::Paused, "pause produces no hits");
    CHECK(pausedAt == int64_t(30000 / 25.0) || pausedAt == 1200, "pause preserves position (1200 ticks)");
    a.seq.play();
    auto h3 = a.run(70000, 256);
    std::vector<int64_t> got; for (auto& x : h1) got.push_back(x.frame); for (auto& x : h3) got.push_back(x.frame - 50000);
    std::vector<int64_t> want; for (auto& x : ref) want.push_back(x.frame);
    CHECK(got == want, "resume continues exactly where pause stopped");
    // stop + restart
    Rig c; pub(c.seq, ev6, 3840); c.seq.play(); c.run(30000, 256);
    c.seq.stop();
    CHECK(c.seq.state() == TransportState::Stopped && c.seq.positionTicks() == 0, "stop resets position to 0 immediately");
    c.seq.play(); auto hh = c.run(100, 64);
    CHECK(hh.size() == 1 && hh[0].frame - 30000 == 0, "restart plays from bar 1");
    // seek while paused, then play
    Rig d; pub(d.seq, ev6, 3840); d.seq.play(); d.run(10, 10); d.seq.pause(); d.seq.seek(1920); d.seq.play();
    auto hs = d.run(100, 64);
    CHECK(hs.size() == 1 && hs[0].slot == 2 && d.seq.positionTicks() == 1920 + 100 / 25, "seek sets position; next event is the one at the seek point");
    // pause from stopped is ignored
    Rig e; e.seq.pause(); CHECK(e.seq.state() == TransportState::Stopped, "pause while stopped is ignored");
}

static void testTempoAndEdits() {
    Rig r; pub(r.seq, {ev(0, 0, 0), ev(960, 0, 0), ev(1920, 0, 0), ev(2880, 0, 0)}, 3840); r.seq.play();
    auto h = r.run(24001, 128);                       // beats 0 and 1 at 120 BPM
    r.seq.setTempo(240.0);
    auto h2 = r.run(40000, 128);
    CHECK(h.size() == 2 && h2.size() == 2, "tempo change: remaining events still fire once");
    CHECK(h2[0].frame == 36001, "tempo change: next beat arrives at the new tempo (sub-frame exact)");
    CHECK(h2[1].frame - h2[0].frame == 12000, "tempo change: beat spacing halves at 240 BPM");
    // Edit during playback: new events appear, old ones vanish, nothing double fires.
    Rig q; pub(q.seq, {ev(0, 0, 0), ev(1920, 0, 0)}, 3840); q.seq.play();
    auto a = q.run(30000, 256);
    pub(q.seq, {ev(0, 0, 0), ev(1920, 0, 0), ev(2400, 0, 5)}, 3840);
    pub(q.seq, {ev(0, 0, 0), ev(2400, 0, 7)}, 3840);
    auto b = q.run(30001, 256);
    CHECK(a.size() == 1 && b.size() == 1 && b[0].slot == 7 && b[0].frame == 60000, "live arrangement edit: latest snapshot used, no duplicates");
    CHECK(!pub(q.seq, std::vector<SeqEvent>(Sequencer::kMaxEvents + 1), 10), "oversized arrangement rejected, old one kept");
}

static void testMixer() {
    Rig r; pub(r.seq, {ev(0, 0, 0), ev(0, 1, 1), ev(0, 2, 2)}, 3840);
    r.seq.setTrackMute(1, true); r.seq.play();
    auto h = r.run(100, 100);
    CHECK(h.size() == 2 && h[0].track == 0 && h[1].track == 2, "mute silences only that track");
    Rig s; pub(s.seq, {ev(0, 0, 0), ev(0, 1, 1), ev(0, 2, 2)}, 3840);
    s.seq.setTrackSolo(2, true); s.seq.play();
    auto hs = s.run(100, 100); CHECK(hs.size() == 1 && hs[0].track == 2, "solo plays only soloed track");
    Rig t; pub(t.seq, {ev(0, 0, 0), ev(0, 1, 1)}, 3840); t.seq.setTrackSolo(1, true); t.seq.setTrackMute(1, true); t.seq.play();
    CHECK(t.run(100, 100).empty(), "mute wins over solo on the same track");
    Rig v; pub(v.seq, {ev(0, 0, 0, 0.8f), ev(0, 1, 0, 0.8f), ev(0, 2, 0, 0.8f)}, 3840);
    v.seq.setTrackVolume(0, 0.5f); v.seq.setTrackPan(1, -1.0f); v.seq.setTrackPan(2, 1.0f); v.seq.play();
    auto hv = v.run(100, 100);
    CHECK(std::fabs(hv[0].vel - 0.4f) < 1e-6f, "track volume scales hit velocity");
    CHECK(std::fabs(hv[0].pl - 1.0f) < 1e-5f && std::fabs(hv[0].pr - 1.0f) < 1e-5f, "centre pan is unity on both channels");
    CHECK(hv[1].pl > 1.4f && hv[1].pr < 1e-5f && hv[2].pr > 1.4f && hv[2].pl < 1e-5f, "hard pan routes to one channel");
    // Pan reaches the audio: hard-left track has silent right channel.
    Rig w; pub(w.seq, {ev(0, 0, 0)}, 3840); w.seq.setTrackPan(0, -1.0f); w.seq.play();
    std::vector<float> a; w.run(2000, 256, &a); float l = 0, rr = 0;
    for (size_t i = 0; i < a.size(); i += 2) { l = std::max(l, std::fabs(a[i])); rr = std::max(rr, std::fabs(a[i + 1])); }
    CHECK(l > 0.05f && rr < 1e-6f, "hard-left pan: right output silent");
}

static void testRealtimeSafety() {
    Rig r; std::vector<SeqEvent> e; for (int i = 0; i < 2000; ++i) e.push_back(ev(i * 120, i % 6, i % 12));
    pub(r.seq, e, 3840 * 64, true, 0, 3840 * 4); r.seq.play();
    std::vector<float> out(512 * 2); ScheduledHit hits[Sequencer::kMaxHitsPerBlock];
    r.run(2000, 100);   // warm up
    gAllocs = 0; tCount = true;
    for (int i = 0; i < 2000; ++i) {
        const int c = r.seq.process(256, kSR, hits, Sequencer::kMaxHitsPerBlock);
        r.drums.renderWithHits(out.data(), 256, 2, hits, c);
    }
    tCount = false;
    CHECK(gAllocs.load() == 0, "process()+renderWithHits() perform zero heap allocations");
}

static void testConcurrentPublish() {
    Rig r; std::atomic<bool> stop{false}; std::atomic<long> published{0};
    std::thread editor([&] {
        int k = 0;
        while (!stop.load()) {
            std::vector<SeqEvent> e; const int n = 200 + (k % 50);
            for (int i = 0; i < n; ++i) e.push_back(ev(i * 15, i % 6, 3));   // every event: slot 3 (consistency marker)
            pub(r.seq, e, 3840, true, 0, 3840); ++k; published++;
        }
    });
    pub(r.seq, {ev(0, 0, 3)}, 3840, true, 0, 3840); r.seq.play();
    bool consistent = true;
    for (long i = 0; i < 2000000 && (i < 3000 || published.load() < 50); ++i) { auto h = r.run(256, 256); for (auto& x : h) consistent = consistent && x.slot == 3 && x.track >= 0 && x.track < 6; }
    stop = true; editor.join();
    CHECK(consistent && published.load() > 10, "arrangement edits racing the audio thread never expose torn snapshots");
}

int main() {
    testTimeMath(); testScheduling(); testSync(); testBoundariesAndLoop(); testTransport();
    testTempoAndEdits(); testMixer(); testRealtimeSafety(); testConcurrentPublish();
    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nALL SEQUENCER TESTS PASSED\n", failures);
    return failures ? 1 : 0;
}
