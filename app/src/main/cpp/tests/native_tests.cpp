// Host-side tests for the Oboe-independent engine code. No framework needed.
#include <cmath>
#include <cstdio>
#include <vector>

#include "audio/AudioConfiguration.h"
#include "engine/AudioClock.h"
#include "engine/AudioProcessor.h"
#include "engine/MasterOutput.h"

using namespace weaudio;

static int failures = 0;
#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) { std::printf("FAIL: %s (line %d)\n", msg, __LINE__); ++failures; } \
        else std::printf("ok:   %s\n", msg);                                  \
    } while (0)

// Simulates what AudioCallback::onAudioReady does, in device-sized blocks.
static std::vector<float> renderChain(AudioProcessor& p, MasterOutput& m, AudioClock& c,
                                      int sr, int ch, int totalFrames, int block) {
    p.setSampleRate(sr);
    std::vector<float> all(static_cast<size_t>(totalFrames) * ch);
    for (int done = 0; done < totalFrames; done += block) {
        int n = std::min(block, totalFrames - done);
        float* out = all.data() + static_cast<size_t>(done) * ch;
        p.render(out, n, ch);
        m.process(out, n, ch);
        c.advance(n);
    }
    return all;
}

static void testFrequencyAccuracy() {
    for (float hz : {220.0f, 440.0f, 1000.0f, 5000.0f}) {
        AudioProcessor p; MasterOutput m; AudioClock c;
        m.setVolume(0.8f); p.setToneFrequency(hz);
        const int sr = 48000, frames = sr * 2;
        auto buf = renderChain(p, m, c, sr, 2, frames, 192);
        int crossings = 0; int first = -1, last = -1;
        for (int i = sr / 2 + 1; i < frames; ++i) {  // skip fade-in / smoothing
            float a = buf[(i - 1) * 2], b = buf[i * 2];
            if (a < 0 && b >= 0) { ++crossings; if (first < 0) first = i; last = i; }
        }
        double measured = (crossings - 1) * static_cast<double>(sr) / (last - first);
        char msg[96]; std::snprintf(msg, sizeof msg, "frequency %.0f Hz measured %.3f Hz", hz, measured);
        CHECK(std::fabs(measured - hz) < hz * 0.001, msg);
    }
}

static void testAmplitudeStereoAndClock() {
    AudioProcessor p; MasterOutput m; AudioClock c;
    m.setVolume(1.0f);
    auto buf = renderChain(p, m, c, 48000, 2, 48000, 256);
    float peak = 0; bool same = true;
    for (int i = 24000; i < 48000; ++i) {
        peak = std::max(peak, std::fabs(buf[i * 2]));
        same &= buf[i * 2] == buf[i * 2 + 1];
    }
    CHECK(std::fabs(peak - AudioProcessor::kToneAmplitude) < 0.005f, "tone peak is -12 dBFS");
    CHECK(same, "stereo channels identical");
    CHECK(c.frames() == 48000, "clock advanced by rendered frames");
    c.reset();
    CHECK(c.frames() == 0, "clock reset");
    CHECK(m.peak() > 0.2f && m.peak() <= 1.0f, "master peak meter reports real level");
}

static void testBlockSizeIndependence() {
    AudioProcessor p1, p2; MasterOutput m1, m2; AudioClock c1, c2;
    auto a = renderChain(p1, m1, c1, 44100, 2, 8820, 8820);
    auto b = renderChain(p2, m2, c2, 44100, 2, 8820, 97);
    double maxDiff = 0;
    for (size_t i = 0; i < a.size(); ++i) maxDiff = std::max(maxDiff, (double)std::fabs(a[i] - b[i]));
    CHECK(maxDiff < 1e-5, "phase continuous across callback block sizes");
}

static void testEnableDisableRamp() {
    AudioProcessor p; MasterOutput m; AudioClock c;
    p.setSampleRate(48000); p.setToneEnabled(false);
    std::vector<float> buf(2 * 4800);
    p.render(buf.data(), 4800, 2);
    bool silent = true; for (float s : buf) silent &= (s == 0.0f);
    CHECK(silent, "disabled tone is silence");
    p.setToneEnabled(true); p.render(buf.data(), 4800, 2);
    CHECK(std::fabs(buf[0]) < 0.01f, "enable fades in (no click)");
    float mx = 0; for (float s : buf) mx = std::max(mx, std::fabs(s));
    CHECK(mx > 0.2f, "enabled tone reaches full level");
    p.setToneEnabled(false); p.render(buf.data(), 4800, 2);
    CHECK(buf[2 * 4799] == 0.0f, "disable fades to silence");
}

static void testClippingProtection() {
    CHECK(MasterOutput::softLimit(0.5f) == 0.5f, "below knee passes unchanged");
    bool bounded = true;
    for (float x = -100; x <= 100; x += 0.37f) bounded &= std::fabs(MasterOutput::softLimit(x)) <= 1.0f;
    CHECK(bounded, "soft limiter output never exceeds 1.0");
    CHECK(MasterOutput::softLimit(NAN) == 0.0f && MasterOutput::softLimit(INFINITY) == 0.0f, "NaN/Inf scrubbed");
    CHECK(std::fabs(MasterOutput::softLimit(0.85f + 1e-4f) - 0.85f) < 2e-4f, "continuous at knee");
    CHECK(MasterOutput::softLimit(-3.0f) < 0 && MasterOutput::softLimit(-3.0f) >= -1.0f, "negative side limited");
    MasterOutput m; m.setVolume(1.0f);
    std::vector<float> hot(2 * 4800, 4.0f);
    m.process(hot.data(), 4800, 2);
    bool ok = true; for (float s : hot) ok &= std::fabs(s) <= 1.0f;
    CHECK(ok, "hot input is limited through process()");
    m.setVolume(7.0f); CHECK(m.volume() == 1.0f, "volume clamped to 1.0");
}

static void testFrequencyClamp() {
    AudioProcessor p; p.setSampleRate(48000); p.setToneFrequency(1e9f);
    std::vector<float> buf(2 * 4800);
    p.render(buf.data(), 4800, 2);
    bool fin = true; for (float s : buf) fin &= std::isfinite(s) && std::fabs(s) <= 0.26f;
    CHECK(fin, "absurd frequency is clamped, output stays bounded");
    p.render(nullptr, 10, 2); p.render(buf.data(), 0, 2);
    CHECK(true, "null/zero-length render is safe");
}

static void testEngineStateDerivation() {
    using S = EngineState; using P = StreamPhase; using I = Intent;
    CHECK(deriveEngineState(false, false, false, false, P::None, I::Idle) == S::Uninitialized, "uninitialized");
    CHECK(deriveEngineState(true, false, false, true, P::Open, I::Idle) == S::Stopped, "open stream = stopped");
    CHECK(deriveEngineState(true, false, false, true, P::Starting, I::Playing) == S::Starting, "starting is not running");
    CHECK(deriveEngineState(true, false, false, true, P::Started, I::Playing) == S::Running, "started = running");
    CHECK(deriveEngineState(true, false, false, true, P::Stopped, I::Playing) == S::Stopped,
          "intent Playing but stream stopped is NOT reported running");
    CHECK(deriveEngineState(true, false, false, true, P::Paused, I::Paused) == S::Paused, "paused");
    CHECK(deriveEngineState(true, false, false, true, P::Stopped, I::Paused) == S::Paused, "pause fallback via stop");
    CHECK(deriveEngineState(true, false, true, false, P::None, I::Playing) == S::Disconnected, "recovering = disconnected");
    CHECK(deriveEngineState(true, false, false, true, P::Disconnected, I::Playing) == S::Disconnected, "disconnected stream");
    CHECK(deriveEngineState(true, true, false, true, P::Started, I::Playing) == S::Error, "failure wins");
}

int main() {
    testFrequencyAccuracy();
    testAmplitudeStereoAndClock();
    testBlockSizeIndependence();
    testEnableDisableRamp();
    testClippingProtection();
    testFrequencyClamp();
    testEngineStateDerivation();
    std::printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
