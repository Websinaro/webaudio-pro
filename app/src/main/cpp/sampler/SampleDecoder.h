#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include "sampler/SampleBuffer.h"

namespace weaudio {

struct DecodeLimits {
    size_t maxFileBytes = 16u * 1024u * 1024u;
    int32_t minRate = 8000, maxRate = 192000;
    double maxSeconds = 12.0;
};

struct DecodeResult {
    bool ok = false;
    std::string error;          // human-readable, empty on success
    DecodedSample sample;       // stereo float32 at original rate
};

// WAV (RIFF) PCM 8/16/24/32-bit, IEEE float 32/64-bit, WAVE_FORMAT_EXTENSIBLE; mono or stereo.
// Mono is duplicated to stereo. Rejects malformed, truncated, silent and out-of-limit input.
// Never reads outside [data, data+size). Non-real-time.
DecodeResult decodeWav(const uint8_t* data, size_t size, const DecodeLimits& limits = {});

}  // namespace weaudio
