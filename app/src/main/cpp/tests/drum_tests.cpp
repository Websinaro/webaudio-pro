// Host tests for the drum sampler (decoder, resampler, voices, stealing, gain, pitch, limiter).
#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "engine/MasterOutput.h"
#include "sampler/DrumSampler.h"
#include "sampler/SampleDecoder.h"
#include "sampler/SampleRateConverter.h"

using namespace weaudio;
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s (line %d)\n", msg, __LINE__); ++failures; } else std::printf("ok:   %s\n", msg); } while (0)
constexpr double kPi = 3.14159265358979323846;

// ---- WAV builders -------------------------------------------------------------------
static void w32(std::vector<uint8_t>& v, uint32_t x) { for (int i = 0; i < 4; ++i) v.push_back(uint8_t(x >> (8 * i))); }
static void w16(std::vector<uint8_t>& v, uint16_t x) { v.push_back(uint8_t(x)); v.push_back(uint8_t(x >> 8)); }
static std::vector<uint8_t> makeWav(const std::vector<float>& s, int rate, int ch, int bits, bool isFloat, bool extensible = false) {
    std::vector<uint8_t> body;
    for (float f : s) {
        if (isFloat) { if (bits == 32) { uint32_t u; std::memcpy(&u, &f, 4); w32(body, u); } else { double d = f; uint64_t u; std::memcpy(&u, &d, 8); w32(body, uint32_t(u)); w32(body, uint32_t(u >> 32)); } }
        else if (bits == 8) body.push_back(uint8_t(std::lround(f * 127.0f) + 128));
        else if (bits == 16) w16(body, uint16_t(int16_t(std::lround(f * 32767.0f))));
        else if (bits == 24) { int32_t t = int32_t(std::lround(f * 8388607.0f)); body.push_back(uint8_t(t)); body.push_back(uint8_t(t >> 8)); body.push_back(uint8_t(t >> 16)); }
        else w32(body, uint32_t(int32_t(std::lround(double(f) * 2147483647.0))));
    }
    std::vector<uint8_t> v = {'R','I','F','F'};
    const uint32_t fmtLen = extensible ? 40 : 16;
    w32(v, uint32_t(4 + 8 + fmtLen + 8 + body.size()));
    for (char c : std::string("WAVEfmt ")) v.push_back(uint8_t(c));
    w32(v, fmtLen);
    w16(v, extensible ? 0xFFFE : (isFloat ? 3 : 1)); w16(v, uint16_t(ch)); w32(v, uint32_t(rate));
    w32(v, uint32_t(rate * ch * bits / 8)); w16(v, uint16_t(ch * bits / 8)); w16(v, uint16_t(bits));
    if (extensible) { w16(v, 22); w16(v, uint16_t(bits)); w32(v, 0); w16(v, isFloat ? 3 : 1); for (int i = 0; i < 14; ++i) v.push_back(i == 0 ? 0 : (i == 2 ? 0x10 : 0)); }
    for (char c : std::string("data")) v.push_back(uint8_t(c));
    w32(v, uint32_t(body.size()));
    v.insert(v.end(), body.begin(), body.end());
    return v;
}
static std::vector<float> sine(int rate, double hz, double secs, float amp = 0.5f, int ch = 1) {
    std::vector<float> s; const int n = int(rate * secs);
    for (int i = 0; i < n; ++i) { float x = amp * float(std::sin(2 * kPi * hz * i / rate)); for (int c = 0; c < ch; ++c) s.push_back(x); }
    return s;
}
static DecodedSample decoded(const std::vector<float>& mono, int rate) {
    DecodedSample d; d.sampleRate = rate; d.frames = int(mono.size());
    for (float f : mono) { d.data.push_back(f); d.data.push_back(f); }
    d.peak = *std::max_element(mono.begin(), mono.end()); return d;
}
static float peakOf(const std::vector<float>& v) { float p = 0; for (float f : v) p = std::max(p, std::fabs(f)); return p; }
static std::vector<float> renderBlocks(DrumSampler& d, int frames, int block = 192) {
    std::vector<float> out(size_t(frames) * 2, 0.0f);
    for (int done = 0; done < frames; done += block) d.render(out.data() + size_t(done) * 2, std::min(block, frames - done), 2);
    return out;
}
static double rms(const std::vector<float>& v, size_t from, size_t to) { double a = 0; for (size_t i = from; i < to; ++i) a += double(v[i]) * v[i]; return std::sqrt(a / double(to - from)); }
// Zero-crossing frequency estimate on the left channel of an interleaved stereo buffer.
static double estHz(const std::vector<float>& v, int rate, size_t fromFrame, size_t toFrame) {
    int zc = 0; for (size_t i = fromFrame + 1; i < toFrame; ++i) if ((v[(i - 1) * 2] < 0) != (v[i * 2] < 0)) ++zc;
    return zc / 2.0 / (double(toFrame - fromFrame) / rate);
}

// ---- tests ----------------------------------------------------------------------------
static void testDecoder() {
    auto s = sine(44100, 440, 0.1, 0.5f);
    for (int bits : {8, 16, 24, 32}) {
        auto r = decodeWav(makeWav(s, 44100, 1, bits, false).data(), makeWav(s, 44100, 1, bits, false).size());
        const double tol = bits == 8 ? 0.02 : 0.001;
        bool match = r.ok && r.sample.frames == int(s.size());
        for (size_t i = 0; match && i < s.size(); ++i) match = std::fabs(r.sample.data[i * 2] - s[i]) < tol && r.sample.data[i * 2] == r.sample.data[i * 2 + 1];
        char m[64]; std::snprintf(m, sizeof m, "PCM %d-bit mono decodes accurately, duplicated to stereo", bits); CHECK(match, m);
    }
    for (int bits : {32, 64}) {
        auto w = makeWav(s, 48000, 1, bits, true); auto r = decodeWav(w.data(), w.size());
        bool match = r.ok && r.sample.sampleRate == 48000;
        for (size_t i = 0; match && i < s.size(); ++i) match = std::fabs(r.sample.data[i * 2] - s[i]) < 1e-6;
        char m[64]; std::snprintf(m, sizeof m, "IEEE float %d-bit decodes exactly", bits); CHECK(match, m);
    }
    auto st = sine(48000, 300, 0.05, 0.4f, 2); auto w = makeWav(st, 48000, 2, 16, false); auto r = decodeWav(w.data(), w.size());
    CHECK(r.ok && r.sample.frames == int(st.size() / 2), "stereo 16-bit frame count");
    auto we = makeWav(s, 48000, 1, 16, false, true); r = decodeWav(we.data(), we.size());
    CHECK(r.ok && r.sample.frames == int(s.size()), "WAVE_FORMAT_EXTENSIBLE PCM accepted");
}
static void testDecoderRejects() {
    auto good = makeWav(sine(44100, 440, 0.1), 44100, 1, 16, false);
    CHECK(!decodeWav(nullptr, 0).ok, "empty input rejected");
    CHECK(!decodeWav(good.data(), 20).ok, "tiny file rejected");
    auto bad = good; bad[0] = 'X'; CHECK(!decodeWav(bad.data(), bad.size()).ok, "bad RIFF magic rejected");
    auto trunc = good; trunc.resize(trunc.size() - 100); CHECK(!decodeWav(trunc.data(), trunc.size()).ok, "truncated data rejected");
    auto sil = makeWav(std::vector<float>(4410, 0.0f), 44100, 1, 16, false); CHECK(!decodeWav(sil.data(), sil.size()).ok, "silent sample rejected");
    auto six = makeWav(sine(44100, 440, 0.05, 0.5f, 6), 44100, 6, 16, false); CHECK(!decodeWav(six.data(), six.size()).ok, "6-channel rejected");
    auto lowRate = makeWav(sine(4000, 100, 0.1), 4000, 1, 16, false); CHECK(!decodeWav(lowRate.data(), lowRate.size()).ok, "unsupported rate rejected");
    auto long_ = makeWav(std::vector<float>(44100 * 13, 0.3f), 44100, 1, 16, false); CHECK(!decodeWav(long_.data(), long_.size()).ok, "over-long sample rejected");
    DecodeLimits tiny; tiny.maxFileBytes = 100; CHECK(!decodeWav(good.data(), good.size(), tiny).ok, "file-size limit enforced");
    auto nan = makeWav(sine(44100, 440, 0.05), 44100, 1, 32, true); float f = NAN; std::memcpy(&nan[60], &f, 4);
    CHECK(!decodeWav(nan.data(), nan.size()).ok, "NaN float data rejected");
    auto badChunk = good; badChunk[16] = 0xFF; badChunk[17] = 0xFF; CHECK(!decodeWav(badChunk.data(), badChunk.size()).ok, "corrupt fmt chunk size rejected");
    std::vector<uint8_t> junk(2000); for (size_t i = 0; i < junk.size(); ++i) junk[i] = uint8_t(i * 31); CHECK(!decodeWav(junk.data(), junk.size()).ok, "random bytes rejected");
    // fuzz: every truncation of a valid file must be handled without crashing
    int ok = 0; for (size_t n = 0; n < good.size(); n += 7) ok += decodeWav(good.data(), n).ok ? 1 : 0; CHECK(ok == 0, "all truncations rejected safely");
}
static void testResampler() {
    for (auto pr : {std::pair{44100, 48000}, std::pair{48000, 44100}, std::pair{22050, 48000}, std::pair{96000, 48000}}) {
        auto mono = sine(pr.first, 1000, 0.5, 0.5f); DecodedSample d = decoded(mono, pr.first);
        auto out = resampleStereo(d.data, pr.first, pr.second);
        const size_t frames = out.size() / 2;
        const size_t expect = size_t(std::llround(double(mono.size()) * pr.second / pr.first));
        char m[96]; std::snprintf(m, sizeof m, "SRC %d->%d: length", pr.first, pr.second); CHECK(frames == expect, m);
        double maxErr = 0; for (size_t i = frames / 10; i < frames * 9 / 10; ++i) maxErr = std::max(maxErr, double(std::fabs(out[i * 2] - 0.5f * float(std::sin(2 * kPi * 1000.0 * double(i) / pr.second)))));
        std::snprintf(m, sizeof m, "SRC %d->%d: sine error %.5f < 0.002", pr.first, pr.second, maxErr); CHECK(maxErr < 0.002, m);
    }
    auto dc = std::vector<float>(44100 * 2, 0.5f); auto o = resampleStereo(dc, 44100, 48000);
    CHECK(std::fabs(o[o.size() / 2] - 0.5f) < 1e-4, "SRC preserves DC (unity gain)");
    // anti-aliasing: 20 kHz tone downsampled to 24 kHz rate (Nyquist 12k) must be strongly attenuated
    auto hi = decoded(sine(48000, 20000, 0.2, 0.5f), 48000); auto lo = resampleStereo(hi.data, 48000, 24000);
    std::vector<float> mid(lo.begin() + 2000, lo.end() - 2000); CHECK(rms(mid, 0, mid.size()) < 0.01, "SRC downsample rejects aliasing component");
    CHECK(resampleStereo({}, 44100, 48000).empty(), "SRC empty input");
}
struct Rig { DrumSampler d; Rig() { d.setOutputRate(48000); d.setActive(true); } };
static std::string load(DrumSampler& d, int slot, const std::vector<float>& mono, int rate = 48000) {
    auto w = makeWav(mono, rate, 1, 16, false); return d.loadSample(slot, w.data(), w.size());
}
static void testTriggerAndGain() {
    Rig r; CHECK(load(r.d, 0, sine(48000, 200, 0.5, 0.8f)).empty(), "load sample");
    CHECK(r.d.isLoaded(0) && !r.d.isLoaded(1), "loaded flags");
    CHECK(peakOf(renderBlocks(r.d, 4800)) == 0.0f, "no sound before trigger");
    CHECK(r.d.trigger(0, 1.0f), "trigger accepted");
    auto a = renderBlocks(r.d, 30000); const float pa = peakOf(a);
    CHECK(pa > 0.3f && pa < 0.8f, "triggered sample is audible at headroom-scaled level");
    r.d.setVolume(0, 0.5f); r.d.trigger(0, 1.0f); auto b = renderBlocks(r.d, 30000);
    CHECK(std::fabs(peakOf(b) / pa - 0.5f) < 0.03f, "volume 0.5 halves the output");
    r.d.setVolume(0, 1.0f); r.d.trigger(0, 0.25f); auto c = renderBlocks(r.d, 30000);
    CHECK(std::fabs(peakOf(c) / pa - 0.25f) < 0.03f, "velocity scales the output");
    r.d.setMuted(0, true); CHECK(!r.d.trigger(0, 1.0f), "muted instrument does not trigger");
    r.d.setMuted(0, false);
    CHECK(!r.d.trigger(1, 1.0f), "unloaded instrument rejected");
    CHECK(!r.d.trigger(99, 1.0f) && !r.d.trigger(-1, 1.0f), "invalid slot rejected");
    CHECK(!r.d.trigger(0, NAN) && !r.d.trigger(0, 0.0f), "invalid velocity rejected");
    r.d.setActive(false); CHECK(!r.d.trigger(0, 1.0f), "inactive engine rejects triggers");
}
static void testMuteWhilePlaying() {
    Rig r; load(r.d, 0, sine(48000, 200, 1.0, 0.8f)); r.d.trigger(0, 1.0f); renderBlocks(r.d, 960);
    r.d.setMuted(0, true); renderBlocks(r.d, 960 * 2);  // ramp out over one block each
    auto tail = renderBlocks(r.d, 4800); CHECK(peakOf(tail) < 1e-4f, "muting silences a ringing voice");
}
static void testPitch() {
    Rig r; load(r.d, 0, sine(48000, 400, 1.0, 0.7f));
    r.d.trigger(0, 1.0f); auto base = renderBlocks(r.d, 24000);
    r.d.setPitchSemitones(0, 12.0f); r.d.trigger(0, 1.0f); auto up = renderBlocks(r.d, 24000);
    const double f0 = estHz(base, 48000, 1000, 20000), f1 = estHz(up, 48000, 1000, 20000);
    CHECK(std::fabs(f0 - 400) < 8, "unshifted playback keeps frequency"); CHECK(std::fabs(f1 / f0 - 2.0) < 0.03, "+12 semitones doubles frequency");
    r.d.setPitchSemitones(0, -12.0f); r.d.trigger(0, 1.0f); auto dn = renderBlocks(r.d, 24000);
    CHECK(std::fabs(estHz(dn, 48000, 1000, 20000) / f0 - 0.5) < 0.03, "-12 semitones halves frequency");
    // pitched-up sample ends after half the time
    Rig r2; load(r2.d, 0, sine(48000, 400, 1.0, 0.7f)); r2.d.setPitchSemitones(0, 12.0f); r2.d.trigger(0, 1.0f); auto s = renderBlocks(r2.d, 40000);
    CHECK(rms(s, 2 * 30000, 2 * 40000) < 1e-5 && r2.d.activeVoices() == 0, "voice ends when pitched sample is exhausted");
}
static void testSampleRateConversionInEngine() {
    Rig r; load(r.d, 0, sine(44100, 1000, 1.0, 0.7f), 44100);   // 44.1k sample on a 48k engine
    r.d.trigger(0, 1.0f); auto o = renderBlocks(r.d, 24000);
    CHECK(std::fabs(estHz(o, 48000, 1000, 20000) - 1000) < 15, "44.1 kHz sample plays at the right pitch on a 48 kHz engine");
    r.d.setOutputRate(44100); r.d.trigger(0, 1.0f); auto o2 = renderBlocks(r.d, 22050);
    CHECK(std::fabs(estHz(o2, 44100, 1000, 20000) - 1000) < 15, "rate change re-resamples loaded samples");
}
static void testPolyphonyAndStealing() {
    Rig r; for (int s = 0; s < 12; ++s) load(r.d, s, sine(48000, 100.0 + 40 * s, 2.0, 0.5f));
    for (int s = 0; s < 12; ++s) if (s != 3) r.d.trigger(s, 1.0f);   // slot 3 (open hat) would choke slot 2
    renderBlocks(r.d, 192);
    CHECK(r.d.activeVoices() == 11, "11 non-choking instruments play together");
    CHECK(r.d.triggerCount() == 11, "trigger count matches");
    // layered same-instrument hits
    Rig q; load(q.d, 0, sine(48000, 100, 3.0, 0.5f));
    for (int i = 0; i < 20; ++i) { q.d.trigger(0, 1.0f); renderBlocks(q.d, 192); }
    CHECK(q.d.activeVoices() == 20 && q.d.stealCount() == 0, "overlapping hits layer without stealing below the limit");
    for (int i = 0; i < 40; ++i) { q.d.trigger(0, 1.0f); renderBlocks(q.d, 192); }
    renderBlocks(q.d, 1920);
    CHECK(q.d.activeVoices() <= DrumSampler::kMaxPolyphony, "polyphony limit respected");
    CHECK(q.d.stealCount() >= 28, "oldest voices are stolen when the limit is exceeded");
    q.d.stopAll(); renderBlocks(q.d, 192); CHECK(q.d.activeVoices() == 0, "stopAll silences everything");
    // steal produces a fade, not a discontinuity: check max sample-to-sample step during a steal burst
    Rig z; load(z.d, 0, sine(48000, 50, 3.0, 0.5f)); float maxStep = 0, prev = 0; bool first = true;
    for (int i = 0; i < 60; ++i) { z.d.trigger(0, 0.3f); auto b = renderBlocks(z.d, 192); for (size_t k = 0; k < b.size(); k += 2) { if (!first) maxStep = std::max(maxStep, std::fabs(b[k] - prev)); prev = b[k]; first = false; } }
    CHECK(maxStep < 0.1f, "voice stealing is click-free (bounded sample steps)");
}
static void testHatChoke() {
    Rig r; load(r.d, 3, sine(48000, 300, 2.0, 0.6f)); load(r.d, 2, sine(48000, 5000, 0.05, 0.6f));
    r.d.trigger(3, 1.0f); renderBlocks(r.d, 4800);
    r.d.trigger(2, 1.0f); renderBlocks(r.d, 4800 * 2);
    CHECK(r.d.activeVoices() == 0, "closed hi-hat chokes the open hi-hat");
}
static void testClippingPrevention() {
    Rig r; MasterOutput m; m.setVolume(1.0f);
    for (int s = 0; s < 12; ++s) load(r.d, s, std::vector<float>(48000, 0.95f));   // worst case: DC-loud samples
    for (int i = 0; i < 40; ++i) r.d.trigger(i % 12, 1.0f);
    std::vector<float> out(2 * 192);
    float p = 0, raw = 0;
    for (int b = 0; b < 100; ++b) {
        std::fill(out.begin(), out.end(), 0.0f); r.d.render(out.data(), 192, 2);
        raw = std::max(raw, peakOf(out)); m.process(out.data(), 192, 2); p = std::max(p, peakOf(out));
    }
    CHECK(raw > 1.0f, "stress mix exceeds full scale before limiting (test is meaningful)");
    CHECK(p <= 1.0f && std::isfinite(p), "master limiter keeps the drum mix within +/-1.0");
}
static void testReplaceAndUnload() {
    Rig r; load(r.d, 0, sine(48000, 200, 1.0, 0.6f)); r.d.trigger(0, 1.0f); renderBlocks(r.d, 960);
    CHECK(load(r.d, 0, sine(48000, 800, 1.0, 0.6f)).empty(), "replace sample");
    renderBlocks(r.d, 192); CHECK(r.d.activeVoices() == 0, "replacing cuts voices that read the old buffer");
    r.d.trigger(0, 1.0f); auto o = renderBlocks(r.d, 24000);
    CHECK(std::fabs(estHz(o, 48000, 1000, 20000) - 800) < 15, "new sample plays after replacement");
    auto bad = std::vector<uint8_t>(100, 7); CHECK(!r.d.loadSample(0, bad.data(), bad.size()).empty(), "invalid replacement reports an error");
    r.d.trigger(0, 1.0f); auto o2 = renderBlocks(r.d, 24000);
    CHECK(std::fabs(estHz(o2, 48000, 1000, 20000) - 800) < 15, "failed replacement keeps the previous sample");
    r.d.unloadSample(0); CHECK(!r.d.isLoaded(0) && !r.d.trigger(0, 1.0f), "unloaded sample cannot trigger");
    for (int i = 0; i < 300; ++i) { load(r.d, 0, sine(48000, 200, 0.02, 0.5f)); renderBlocks(r.d, 192); }   // retire ring must not overflow/leak
    CHECK(r.d.isLoaded(0), "300 consecutive replacements are handled");
}
static void testOfflineLoadWhileStopped() {
    DrumSampler d; d.setOutputRate(48000);   // engine not running
    CHECK(load(d, 4, sine(48000, 300, 0.5, 0.6f)).empty(), "load while stream is stopped");
    d.setActive(true); d.trigger(4, 1.0f); CHECK(peakOf(renderBlocks(d, 4800)) > 0.1f, "sample loaded offline plays once the stream starts");
}
static void testBlockSizeIndependence() {
    Rig a, b; load(a.d, 0, sine(48000, 330, 0.5, 0.6f)); load(b.d, 0, sine(48000, 330, 0.5, 0.6f));
    a.d.trigger(0, 0.8f); b.d.trigger(0, 0.8f);
    auto x = renderBlocks(a.d, 9600, 64), y = renderBlocks(b.d, 9600, 480); float md = 0;
    for (size_t i = 0; i < x.size(); ++i) md = std::max(md, std::fabs(x[i] - y[i]));
    CHECK(md < 1e-4f, "output is independent of callback block size (consistent timing)");
}

// "Audio thread" renders continuously while other threads trigger, replace samples and change parameters.
// Run under ThreadSanitizer/ASan to validate the lock-free hand-over.
static void testConcurrentStress() {
    Rig r; for (int s = 0; s < 12; ++s) load(r.d, s, sine(48000, 100.0 + 30 * s, 0.4, 0.5f));
    std::atomic<bool> stop{false}; std::atomic<float> maxAbs{0};
    std::thread audio([&] { std::vector<float> o(192 * 2); while (!stop) { std::fill(o.begin(), o.end(), 0.0f); r.d.render(o.data(), 192, 2); for (float f : o) if (!std::isfinite(f)) maxAbs = 1e9f; std::this_thread::sleep_for(std::chrono::microseconds(300)); } });
    std::vector<std::thread> producers;
    for (int t = 0; t < 3; ++t) producers.emplace_back([&, t] { for (int i = 0; i < 4000; ++i) { r.d.trigger((i + t) % 12, 0.7f); r.d.setVolume(i % 12, 0.5f + 0.1f * float(i % 5)); r.d.setMuted(i % 12, i % 97 == 0); if (i % 500 == 0) r.d.stopAll(); } });
    std::thread loader([&] { for (int i = 0; i < 25; ++i) load(r.d, i % 12, sine(44100, 200.0 + i, 0.2, 0.5f), 44100); });
    for (auto& p : producers) p.join();
    loader.join(); stop = true; audio.join();
    CHECK(maxAbs.load() < 1e8f, "concurrent trigger/replace/param stress: no NaN/Inf, no crash or race");
    CHECK(r.d.activeVoices() <= DrumSampler::kMaxPolyphony + 8, "voice pool stays bounded under stress");
}

// Decodes and plays every bundled asset through the real decoder + 48 kHz conversion.
// Directory comes from WA_KIT_DIR (set by CTest); skipped when unset.
static void testBundledKits() {
    const char* dir = std::getenv("WA_KIT_DIR");
    if (!dir) { std::printf("skip: WA_KIT_DIR not set\n"); return; }
    const char* files[12] = {"kick","snare","closed_hat","open_hat","crash","ride","tom_high","tom_mid","tom_floor","clap","rimshot","percussion"};
    for (const char* kit : {"studio_synth", "dry_punch"}) {
        Rig r; int good = 0;
        for (int i = 0; i < 12; ++i) {
            std::string path = std::string(dir) + "/" + kit + "/" + files[i] + ".wav";
            FILE* f = std::fopen(path.c_str(), "rb"); if (!f) continue;
            std::vector<uint8_t> b(1 << 20); size_t n = std::fread(b.data(), 1, b.size(), f); std::fclose(f); b.resize(n);
            if (r.d.loadSample(i, b.data(), b.size()).empty()) ++good;
        }
        char m[96]; std::snprintf(m, sizeof m, "kit %s: all 12 bundled samples decode and load", kit); CHECK(good == 12, m);
        int audible = 0;
        for (int i = 0; i < 12; ++i) { if (i == 3) continue; r.d.trigger(i, 1.0f); auto o = renderBlocks(r.d, 2400); audible += peakOf(o) > 0.05f ? 1 : 0; r.d.stopAll(); renderBlocks(r.d, 192); }
        std::snprintf(m, sizeof m, "kit %s: 11 instruments each produce audible output", kit); CHECK(audible == 11, m);
        r.d.trigger(3, 1.0f); CHECK(peakOf(renderBlocks(r.d, 2400)) > 0.05f, "open hi-hat audible");
    }
}

int main() {
    testDecoder(); testDecoderRejects(); testResampler(); testTriggerAndGain(); testMuteWhilePlaying(); testPitch();
    testSampleRateConversionInEngine(); testPolyphonyAndStealing(); testHatChoke(); testClippingPrevention();
    testReplaceAndUnload(); testOfflineLoadWhileStopped(); testBlockSizeIndependence(); testBundledKits(); testConcurrentStress();
    std::printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
