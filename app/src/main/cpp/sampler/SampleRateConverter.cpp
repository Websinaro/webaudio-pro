#include "sampler/SampleRateConverter.h"

#include <algorithm>
#include <cmath>

namespace weaudio {
namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr int kZeroCrossings = 16;
constexpr int kTableRes = 512;  // kernel samples per input sample (linear interpolation)

double window(double t) {  // t in [-1,1]
    if (std::fabs(t) >= 1.0) return 0.0;
    const double x = (t + 1.0) * kPi;  // 0..2pi
    return 0.35875 - 0.48829 * std::cos(x) + 0.14128 * std::cos(2 * x) - 0.01168 * std::cos(3 * x);
}
}  // namespace

std::vector<float> resampleStereo(const std::vector<float>& in, int32_t srcRate, int32_t dstRate) {
    if (srcRate <= 0 || dstRate <= 0 || in.empty()) return {};
    if (srcRate == dstRate) return in;

    const int64_t inFrames = int64_t(in.size() / 2);
    const int64_t outFrames = std::max<int64_t>(1, std::llround(double(inFrames) * dstRate / srcRate));
    const double step = double(srcRate) / dstRate;                 // input frames per output frame
    const double fc = std::min(1.0, double(dstRate) / srcRate) * 0.97;  // fraction of input Nyquist
    const double support = kZeroCrossings / fc;                    // kernel half-width, input frames

    const int tableHalf = int(std::ceil(support * kTableRes));
    std::vector<double> table(size_t(tableHalf) + 2, 0.0);
    for (int i = 0; i < int(table.size()); ++i) {
        const double x = double(i) / kTableRes;
        const double a = kPi * fc * x;
        const double sinc = x == 0.0 ? 1.0 : std::sin(a) / a;
        table[size_t(i)] = fc * sinc * window(x / support);
    }

    std::vector<float> out(size_t(outFrames) * 2);
    for (int64_t o = 0; o < outFrames; ++o) {
        const double center = double(o) * step;
        const int64_t first = int64_t(std::ceil(center - support));
        const int64_t last = int64_t(std::floor(center + support));
        double l = 0, r = 0, wsum = 0;
        for (int64_t k = first; k <= last; ++k) {
            const double dist = std::fabs(double(k) - center) * kTableRes;
            const size_t idx = size_t(dist);
            if (idx + 1 >= table.size()) continue;
            const double f = dist - double(idx);
            const double w = table[idx] + (table[idx + 1] - table[idx]) * f;
            wsum += w;  // normalise over ALL taps, including those beyond the signal edges
            if (k < 0 || k >= inFrames) continue;
            l += w * in[size_t(k) * 2];
            r += w * in[size_t(k) * 2 + 1];
        }
        const double norm = wsum != 0.0 ? 1.0 / wsum : 1.0;
        out[size_t(o) * 2] = float(l * norm);
        out[size_t(o) * 2 + 1] = float(r * norm);
    }
    return out;
}

}  // namespace weaudio
