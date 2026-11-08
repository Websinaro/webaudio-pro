#include "audio/AudioCallback.h"

#include <cstring>

namespace weaudio {

oboe::DataCallbackResult AudioCallback::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                     int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);  // stream is always opened as float
    const int32_t channels = stream->getChannelCount();
    if (stream->getFormat() != oboe::AudioFormat::Float || channels <= 0) {
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * stream->getBytesPerFrame());
        return oboe::DataCallbackResult::Continue;
    }
    processor_.render(out, numFrames, channels);   // test tone (overwrites)
    drums_.render(out, numFrames, channels);        // drum voices (adds)
    master_.process(out, numFrames, channels);
    clock_.advance(numFrames);
    return oboe::DataCallbackResult::Continue;
}

void AudioCallback::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    if (detached_.load(std::memory_order_acquire)) return;
    if (auto listener = listener_.lock()) listener->onStreamDisconnected(error);
}

}  // namespace weaudio
