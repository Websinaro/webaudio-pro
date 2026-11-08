#include "audio/AudioStreamManager.h"

#include <algorithm>
#include <chrono>
#include <thread>

#include "audio/AudioLog.h"

namespace weaudio {

oboe::Result AudioStreamManager::openWith(const StreamRequest& r, bool exclusive, bool lowLatency,
                                          bool fixedFormat) {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setPerformanceMode(lowLatency ? oboe::PerformanceMode::LowLatency
                                        : oboe::PerformanceMode::None)
        ->setSharingMode(exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
        ->setDataCallback(callback_)
        ->setErrorCallback(callback_);
    if (fixedFormat) {
        // Float, requested channel count, device-native sample rate (rate left Unspecified).
        b.setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(r.channelCount)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true);
        if (r.sampleRate > 0) b.setSampleRate(r.sampleRate);
    }
    std::shared_ptr<oboe::AudioStream> s;
    const oboe::Result res = b.openStream(s);
    if (res == oboe::Result::OK) {
        stream_ = std::move(s);
        // Double-buffer the burst size: low latency with headroom against underruns.
        const int32_t burst = stream_->getFramesPerBurst();
        if (burst > 0) stream_->setBufferSizeInFrames(burst * 2);
    }
    return res;
}

oboe::Result AudioStreamManager::open(const StreamRequest& request) {
    close();
    fallbackNote_.clear();
    oboe::Result res = oboe::Result::ErrorInternal;

    if (request.exclusive && request.lowLatency) {
        res = openWith(request, true, true, true);
        if (res == oboe::Result::OK) return res;
        WA_LOGW("Exclusive low-latency open failed: %s", oboe::convertToText(res));
        fallbackNote_ = "Exclusive unavailable; using shared mode";
    }
    res = openWith(request, false, request.lowLatency, true);
    if (res == oboe::Result::OK) return res;
    WA_LOGW("Shared open failed: %s", oboe::convertToText(res));

    res = openWith(request, false, false, false);
    if (res == oboe::Result::OK) {
        fallbackNote_ = "Requested configuration unsupported; using device defaults";
        return res;
    }
    WA_LOGE("All stream configurations failed: %s", oboe::convertToText(res));
    return res;
}

void AudioStreamManager::waitForPhase(oboe::StreamState target, int timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (stream_ && stream_->getState() != target &&
           std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
}

oboe::Result AudioStreamManager::start() {
    if (!stream_) return oboe::Result::ErrorClosed;
    return stream_->requestStart();
}

oboe::Result AudioStreamManager::pause() {
    if (!stream_) return oboe::Result::ErrorClosed;
    oboe::Result r = stream_->requestPause();
    if (r == oboe::Result::OK) waitForPhase(oboe::StreamState::Paused, 250);
    return r;
}

oboe::Result AudioStreamManager::stop() {
    if (!stream_) return oboe::Result::ErrorClosed;
    oboe::Result r = stream_->requestStop();
    if (r == oboe::Result::OK) waitForPhase(oboe::StreamState::Stopped, 250);
    return r;
}

void AudioStreamManager::close() {
    if (stream_) {
        stream_->close();
        stream_.reset();
    }
}

StreamPhase AudioStreamManager::phase() const {
    if (!stream_) return StreamPhase::None;
    switch (stream_->getState()) {
        case oboe::StreamState::Open:         return StreamPhase::Open;
        case oboe::StreamState::Starting:     return StreamPhase::Starting;
        case oboe::StreamState::Started:      return StreamPhase::Started;
        case oboe::StreamState::Pausing:      return StreamPhase::Pausing;
        case oboe::StreamState::Paused:       return StreamPhase::Paused;
        case oboe::StreamState::Flushing:     return StreamPhase::Flushing;
        case oboe::StreamState::Flushed:      return StreamPhase::Flushed;
        case oboe::StreamState::Stopping:     return StreamPhase::Stopping;
        case oboe::StreamState::Stopped:      return StreamPhase::Stopped;
        case oboe::StreamState::Disconnected: return StreamPhase::Disconnected;
        case oboe::StreamState::Closed:       return StreamPhase::Closed;
        default:                              return StreamPhase::Unknown;
    }
}

StreamInfo AudioStreamManager::info() const {
    StreamInfo i;
    if (!stream_) return i;
    i.open = true;
    i.api = oboe::convertToText(stream_->getAudioApi());
    i.format = oboe::convertToText(stream_->getFormat());
    i.performanceMode = oboe::convertToText(stream_->getPerformanceMode());
    i.sharingMode = oboe::convertToText(stream_->getSharingMode());
    i.state = oboe::convertToText(stream_->getState());
    i.sampleRate = stream_->getSampleRate();
    i.channelCount = stream_->getChannelCount();
    i.framesPerBurst = stream_->getFramesPerBurst();
    i.bufferSizeFrames = stream_->getBufferSizeInFrames();
    i.bufferCapacityFrames = stream_->getBufferCapacityInFrames();
    i.deviceId = stream_->getDeviceId();
    auto xr = stream_->getXRunCount();  // AAudio only
    if (xr) i.xRuns = xr.value();
    return i;
}

}  // namespace weaudio
