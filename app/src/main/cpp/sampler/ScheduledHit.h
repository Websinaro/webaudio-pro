#pragma once
#include <cstdint>

namespace weaudio {

// One drum hit scheduled at a sample offset inside the current audio block.
// Produced by Sequencer::process() on the audio thread, consumed by DrumSampler::renderWithHits().
struct ScheduledHit {
    int32_t offset = 0;     // frame offset within the block, 0..frames-1
    int32_t slot = 0;       // DrumSampler instrument slot
    int32_t track = 0;      // originating track index (diagnostics/tests)
    float velocity = 1.0f;  // already multiplied by the track volume
    float panL = 1.0f;      // per-hit channel gains (track pan)
    float panR = 1.0f;
    float pitch = 0.0f;     // extra semitones, added to the instrument pitch
};

}  // namespace weaudio
