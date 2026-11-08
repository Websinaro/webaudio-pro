#pragma once
#include <cstdint>
#include <vector>

namespace weaudio {

// High-quality offline resampler for interleaved stereo float: Blackman-Harris windowed sinc
// (16 zero-crossings per side), cutoff lowered when downsampling to prevent aliasing,
// per-output weight normalisation (exact unity DC gain). Non-real-time only.
std::vector<float> resampleStereo(const std::vector<float>& in, int32_t srcRate, int32_t dstRate);

}  // namespace weaudio
