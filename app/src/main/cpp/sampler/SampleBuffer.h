#pragma once
#include <cstdint>
#include <vector>

namespace weaudio {

// Immutable-after-construction stereo float32 sample, already at the OUTPUT sample rate.
// Created on a control thread, handed to the audio thread by raw pointer, retired back.
struct SampleBuffer {
    std::vector<float> data;   // interleaved L,R
    int32_t frames = 0;
    int32_t sampleRate = 0;
};

// Decoded sample at its ORIGINAL rate (control thread only; kept so we can re-resample if
// the output device rate changes).
struct DecodedSample {
    std::vector<float> data;   // interleaved stereo
    int32_t frames = 0;
    int32_t sampleRate = 0;
    float peak = 0.0f;
};

}  // namespace weaudio
