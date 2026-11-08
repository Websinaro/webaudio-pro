#include "audio/AudioEngine.h"

#include "audio/AudioLog.h"

namespace weaudio {

AudioEngine::~AudioEngine() { release(); }

bool AudioEngine::openStreamLocked() {
    StreamRequest req;  // stereo, low latency, exclusive, device-native sample rate
    const oboe::Result r = stream_->open(req);
    if (r != oboe::Result::OK) {
        fail(std::string("Unable to open audio stream: ") + oboe::convertToText(r));
        return false;
    }
    const StreamInfo info = stream_->info();
    if (info.sampleRate > 0) {
        processor_.setSampleRate(info.sampleRate);
        drums_->setOutputRate(info.sampleRate);   // re-resamples loaded drum samples if the rate changed
    }
    WA_LOGI("Stream open: %s, %d Hz, %d ch, %s, %s", info.api.c_str(), info.sampleRate,
            info.channelCount, info.performanceMode.c_str(), info.sharingMode.c_str());
    return true;
}

void AudioEngine::fail(const std::string& message) {
    failed_ = true;
    lastError_ = message;
    WA_LOGE("%s", message.c_str());
}

bool AudioEngine::initialize() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (initialized_) return true;
    failed_ = false;
    lastError_.clear();
    callback_ = std::make_shared<AudioCallback>(processor_, *drums_, master_, clock_,
                                                std::weak_ptr<StreamErrorListener>(weak_from_this()));
    stream_ = std::make_unique<AudioStreamManager>(callback_);
    if (!openStreamLocked()) {
        stream_.reset();
        callback_.reset();
        return false;
    }
    clock_.reset();
    intent_ = Intent::Idle;
    initialized_ = true;
    return true;
}

bool AudioEngine::startLocked() {
    if (!initialized_) {
        lastError_ = "Engine is not initialized";
        return false;
    }
    if (!stream_->isOpen() && !openStreamLocked()) return false;
    oboe::Result r = stream_->start();
    if (r != oboe::Result::OK) {
        // Stream may be stale (device change): reopen once and retry.
        WA_LOGW("Start failed (%s); reopening stream", oboe::convertToText(r));
        if (!openStreamLocked()) return false;
        r = stream_->start();
        if (r != oboe::Result::OK) {
            fail(std::string("Unable to start audio stream: ") + oboe::convertToText(r));
            return false;
        }
    }
    failed_ = false;
    intent_ = Intent::Playing;
    drums_->setActive(true);
    return true;
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    return startLocked();
}

bool AudioEngine::resume() { return start(); }

bool AudioEngine::pause() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) {
        lastError_ = "Engine is not initialized";
        return false;
    }
    intent_ = Intent::Paused;
    drums_->setActive(false);
    if (!stream_->isOpen()) return true;
    if (stream_->pause() != oboe::Result::OK) {
        // Pause is not supported on every back-end: fall back to stop, keep logical Paused.
        WA_LOGW("Pause unsupported; falling back to stop");
        if (stream_->stop() != oboe::Result::OK) {
            fail("Unable to pause or stop the audio stream");
            return false;
        }
    }
    return true;
}

bool AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) {
        lastError_ = "Engine is not initialized";
        return false;
    }
    intent_ = Intent::Idle;
    drums_->setActive(false);
    if (stream_->isOpen() && stream_->stop() != oboe::Result::OK) {
        fail("Unable to stop the audio stream");
        return false;
    }
    clock_.reset();  // transport returns to the start
    return true;
}

void AudioEngine::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    drums_->setActive(false);
    if (callback_) callback_->detach();
    if (stream_) stream_->close();
    stream_.reset();
    callback_.reset();
    initialized_ = false;
    failed_ = false;
    recovering_ = false;
    intent_ = Intent::Idle;
}

// Device unplugged / audio server restarted: reopen on the new default device and resume
// if the user was playing.
void AudioEngine::onStreamDisconnected(oboe::Result error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_ || !stream_) return;
    WA_LOGW("Stream disconnected: %s", oboe::convertToText(error));
    recovering_ = true;
    drums_->setActive(false);
    stream_->close();
    ++reconnectCount_;
    const bool reopened = openStreamLocked();
    recovering_ = false;
    if (!reopened) return;  // fail() already flagged the error
    if (intent_ == Intent::Playing && !startLocked()) {
        WA_LOGE("Could not resume after disconnect");
    }
}

EngineState AudioEngine::stateLocked() const {
    const bool has = stream_ && stream_->isOpen();
    return deriveEngineState(initialized_, failed_, recovering_, has,
                             has ? stream_->phase() : StreamPhase::None, intent_);
}

EngineState AudioEngine::state() {
    std::lock_guard<std::mutex> lock(mutex_);
    return stateLocked();
}

StreamInfo AudioEngine::streamInfo() {
    std::lock_guard<std::mutex> lock(mutex_);
    return stream_ ? stream_->info() : StreamInfo{};
}

std::string AudioEngine::lastError() {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

std::string AudioEngine::diagnostics() { return buildDiagnostics(*this); }

}  // namespace weaudio
