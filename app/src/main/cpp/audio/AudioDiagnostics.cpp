#include <string>

#include "audio/AudioEngine.h"

namespace weaudio {
namespace {

std::string text(const std::string& v) { return v.empty() ? "Unavailable" : v; }
std::string num(int64_t v) { return v < 0 ? "Unavailable" : std::to_string(v); }

}  // namespace

// Snapshot of real engine/stream values as "key=value" lines. Anything the platform did not
// report is "Unavailable"; nothing here is hardcoded.
std::string buildDiagnostics(AudioEngine& e) {
    std::lock_guard<std::mutex> lock(e.mutex_);
    const StreamInfo i = e.stream_ ? e.stream_->info() : StreamInfo{};
    std::string out;
    auto add = [&out](const char* k, const std::string& v) { out += k; out += '='; out += v; out += '\n'; };

    add("engineState", std::to_string(static_cast<int>(e.stateLocked())));
    add("api", text(i.api));
    add("sampleRate", num(i.sampleRate));
    add("channels", num(i.channelCount));
    add("format", text(i.format));
    add("performanceMode", text(i.performanceMode));
    add("sharingMode", text(i.sharingMode));
    add("streamState", text(i.state));
    add("framesPerBurst", num(i.framesPerBurst));
    add("bufferSize", num(i.bufferSizeFrames));
    add("bufferCapacity", num(i.bufferCapacityFrames));
    add("deviceId", i.deviceId > 0 ? std::to_string(i.deviceId) : "Unavailable");
    add("xRuns", num(i.xRuns));
    add("reconnects", std::to_string(e.reconnectCount_));
    add("toneEnabled", e.processor_.toneEnabled() ? "1" : "0");
    add("toneFrequency", std::to_string(e.processor_.toneFrequency()));
    add("fallback", e.stream_ ? (e.stream_->fallbackNote().empty() ? "None" : e.stream_->fallbackNote()) : "Unavailable");
    add("lastError", e.lastError_.empty() ? "None" : e.lastError_);
    return out;
}

}  // namespace weaudio
