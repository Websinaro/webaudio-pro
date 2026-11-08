#include "sampler/SampleDecoder.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace weaudio {
namespace {

uint32_t rd32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (uint32_t(p[3]) << 24); }
uint16_t rd16(const uint8_t* p) { return uint16_t(p[0] | (p[1] << 8)); }

DecodeResult fail(const std::string& m) { DecodeResult r; r.error = m; return r; }

constexpr uint16_t kPcm = 1, kFloat = 3, kExtensible = 0xFFFE;

}  // namespace

DecodeResult decodeWav(const uint8_t* d, size_t n, const DecodeLimits& lim) {
    if (d == nullptr || n == 0) return fail("Empty file");
    if (n > lim.maxFileBytes) return fail("File is too large");
    if (n < 44) return fail("File is too small to be a WAV file");
    if (std::memcmp(d, "RIFF", 4) != 0 || std::memcmp(d + 8, "WAVE", 4) != 0)
        return fail("Not a RIFF/WAVE file");

    uint16_t format = 0, channels = 0, bits = 0;
    uint32_t rate = 0;
    bool haveFmt = false;
    const uint8_t* pcm = nullptr;
    size_t pcmBytes = 0;

    size_t pos = 12;
    while (pos + 8 <= n) {
        const uint8_t* id = d + pos;
        const size_t len = rd32(d + pos + 4);
        const size_t body = pos + 8;
        if (std::memcmp(id, "fmt ", 4) == 0) {
            if (len < 16 || body + len > n) return fail("Corrupt fmt chunk");
            format = rd16(d + body);
            channels = rd16(d + body + 2);
            rate = rd32(d + body + 4);
            bits = rd16(d + body + 14);
            if (format == kExtensible) {
                if (len < 40) return fail("Corrupt WAVE_FORMAT_EXTENSIBLE header");
                format = rd16(d + body + 24);  // first two bytes of the sub-format GUID
            }
            haveFmt = true;
        } else if (std::memcmp(id, "data", 4) == 0) {
            if (body + len > n) return fail("Truncated data chunk");
            pcm = d + body;
            pcmBytes = len;
            break;
        }
        if (body + len > n) return fail("Corrupt chunk size");
        pos = body + len + (len & 1);  // chunks are word-aligned
    }
    if (!haveFmt) return fail("Missing fmt chunk");
    if (pcm == nullptr) return fail("Missing data chunk");

    if (channels < 1 || channels > 2) return fail("Only mono and stereo WAV files are supported");
    if (int32_t(rate) < lim.minRate || int32_t(rate) > lim.maxRate) return fail("Unsupported sample rate");
    const bool isFloat = format == kFloat;
    if (format != kPcm && !isFloat) return fail("Unsupported WAV encoding (need PCM or IEEE float)");
    if (isFloat ? (bits != 32 && bits != 64) : (bits != 8 && bits != 16 && bits != 24 && bits != 32))
        return fail("Unsupported bit depth");

    const size_t bytesPer = bits / 8;
    const size_t frameBytes = bytesPer * channels;
    const size_t frames = pcmBytes / frameBytes;
    if (frames == 0) return fail("Sample contains no audio data");
    if (double(frames) / rate > lim.maxSeconds) return fail("Sample is too long");

    DecodeResult r;
    DecodedSample& s = r.sample;
    s.frames = int32_t(frames);
    s.sampleRate = int32_t(rate);
    s.data.resize(frames * 2);

    float peak = 0.0f;
    for (size_t i = 0; i < frames; ++i) {
        float v[2] = {0, 0};
        for (uint16_t c = 0; c < channels; ++c) {
            const uint8_t* p = pcm + i * frameBytes + c * bytesPer;
            float x;
            if (isFloat) {
                if (bits == 32) { uint32_t u = rd32(p); float f; std::memcpy(&f, &u, 4); x = f; }
                else { uint64_t u = uint64_t(rd32(p)) | (uint64_t(rd32(p + 4)) << 32); double f; std::memcpy(&f, &u, 8); x = float(f); }
            } else if (bits == 8) x = (int(p[0]) - 128) / 128.0f;
            else if (bits == 16) x = int16_t(rd16(p)) / 32768.0f;
            else if (bits == 24) { int32_t t = (p[0] << 8) | (p[1] << 16) | (int32_t(p[2]) << 24); x = float(t >> 8) / 8388608.0f; }
            else x = float(int32_t(rd32(p)) / 2147483648.0);
            if (!std::isfinite(x)) return fail("Sample contains invalid (NaN/Inf) values");
            v[c] = x;
        }
        if (channels == 1) v[1] = v[0];
        s.data[i * 2] = v[0];
        s.data[i * 2 + 1] = v[1];
        peak = std::max({peak, std::fabs(v[0]), std::fabs(v[1])});
    }
    if (peak < 1e-5f) return fail("Sample is silent");
    s.peak = peak;
    r.ok = true;
    return r;
}

}  // namespace weaudio
