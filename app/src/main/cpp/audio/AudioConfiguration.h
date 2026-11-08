// Shared, Oboe-independent value types so state logic is unit-testable on the host.
#pragma once

#include <cstdint>
#include <string>

namespace weaudio {

// Values are mirrored in Kotlin (EngineState.fromCode). Keep both in sync.
enum class EngineState : int {
    Uninitialized = 0,
    Stopped = 1,
    Starting = 2,
    Running = 3,
    Paused = 4,
    Disconnected = 5,
    Error = 6,
};

// What the user asked the transport to do (not what the device is doing).
enum class Intent { Idle, Playing, Paused };

// Oboe-independent mirror of oboe::StreamState.
enum class StreamPhase {
    None, Open, Starting, Started, Pausing, Paused, Flushing, Flushed,
    Stopping, Stopped, Disconnected, Closed, Unknown
};

struct StreamRequest {
    int32_t channelCount = 2;
    int32_t sampleRate = 0;      // 0 = let the device pick its native rate
    bool lowLatency = true;
    bool exclusive = true;
};

// -1 / empty means "could not be obtained" and is reported as "Unavailable".
struct StreamInfo {
    bool open = false;
    std::string api, format, performanceMode, sharingMode, state;
    int32_t sampleRate = -1, channelCount = -1, framesPerBurst = -1;
    int32_t bufferSizeFrames = -1, bufferCapacityFrames = -1, deviceId = -1, xRuns = -1;
};

// Engine state is derived from the real stream phase: "Running" is reported only when
// the device stream confirms it has started.
inline EngineState deriveEngineState(bool initialized, bool failed, bool recovering,
                                     bool hasStream, StreamPhase phase, Intent intent) {
    if (failed) return EngineState::Error;
    if (!initialized) return EngineState::Uninitialized;
    if (recovering) return EngineState::Disconnected;
    if (!hasStream) return intent == Intent::Paused ? EngineState::Paused : EngineState::Stopped;
    switch (phase) {
        case StreamPhase::Started:      return EngineState::Running;
        case StreamPhase::Starting:     return EngineState::Starting;
        case StreamPhase::Disconnected: return EngineState::Disconnected;
        case StreamPhase::Pausing:
        case StreamPhase::Paused:       return EngineState::Paused;
        case StreamPhase::Stopping:
        case StreamPhase::Stopped:
        case StreamPhase::Flushing:
        case StreamPhase::Flushed:
        case StreamPhase::Open:
            // A stop used as a pause fallback keeps the logical Paused state.
            return intent == Intent::Paused ? EngineState::Paused : EngineState::Stopped;
        default:                        return EngineState::Stopped;
    }
}

}  // namespace weaudio
