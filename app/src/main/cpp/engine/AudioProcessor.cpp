#include "engine/AudioProcessor.h"

#include <algorithm>
#include <cmath>

namespace weaudio {

void AudioProcessor::render(float* out, int32_t frames, int32_t channels) noexcept {
    if (out == nullptr || frames <= 0 || channels <= 0) return;

    constexpr double kTwoPi = 6.283185307179586476925286766559;
    const double sr = static_cast<double>(std::max(sampleRate(), 8000));
    // Keep the tone below Nyquist so a bad UI value can never alias or blow up.
    const double freq = std::clamp(static_cast<double>(toneFrequency()),
                                   static_cast<double>(kMinFrequency), sr * 0.45);
    const double phaseInc = kTwoPi * freq / sr;
    const float target = toneEnabled() ? kToneAmplitude : 0.0f;
    const float step = kToneAmplitude / static_cast<float>(kRampSeconds * sr);

    double phase = phase_;
    float gain = gain_;
    for (int32_t i = 0; i < frames; ++i) {
        if (gain < target) gain = std::min(target, gain + step);
        else if (gain > target) gain = std::max(target, gain - step);

        const float sample = gain * static_cast<float>(std::sin(phase));
        phase += phaseInc;
        if (phase >= kTwoPi) phase -= kTwoPi;

        float* frame = out + static_cast<size_t>(i) * static_cast<size_t>(channels);
        for (int32_t c = 0; c < channels; ++c) frame[c] = sample;
    }
    phase_ = phase;
    gain_ = gain;
}

}  // namespace weaudio
