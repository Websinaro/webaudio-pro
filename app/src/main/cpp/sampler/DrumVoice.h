#pragma once
#include <cstdint>
#include "sampler/SampleBuffer.h"

namespace weaudio {

// One playing hit. Plain data, owned and mutated only by the audio thread.
struct DrumVoice {
    const SampleBuffer* buffer = nullptr;  // borrowed; the sampler never frees it while active
    double pos = 0.0;          // fractional read position, in frames
    float ratio = 1.0f;        // pitch ratio (frames advanced per output frame)
    float velocity = 1.0f;
    float panL = 1.0f;         // per-hit channel gains (sequencer track pan); 1/1 for pad hits
    float panR = 1.0f;
    float gain = 0.0f;         // smoothed linear gain (per-block ramp)
    uint64_t order = 0;        // allocation order, for stealing
    int32_t instrument = -1;
    int32_t fadeInPos = 0;     // declick on start
    float fadeGain = 1.0f;     // declick on steal/choke
    float fadeStep = 0.0f;     // > 0 once the voice is being faded out
    bool active = false;
    bool fading() const { return fadeStep > 0.0f; }
};

}  // namespace weaudio
