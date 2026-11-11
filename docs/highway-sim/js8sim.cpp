// js8sim: JS8Call-improved decoder frame-success harness (same decoder code as the app).
//   js8sim gen MODE N SEED OUT.f32         one period per trial at 12 kHz, clean CPFSK
//   js8sim dec MODE N SEED FADED.f32 REF.f32 NOISESEED SNR...   prints "snr,ok,n,false"
// SNR is average signal power (REF, over the frame) over noise power in 2500 Hz.
#include <QCoreApplication>
#include <QLoggingCategory>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <numbers>
#include <random>
#include <string>
#include <vector>

#include "JS8_Include/commons.h"
#include "JS8_Mode/JS8.h"

struct dec_data dec_data;
struct specData specData;
std::mutex fftw_mutex;
Q_LOGGING_CATEGORY(decoder_js8, "decoder.js8", QtWarningMsg)

#include "../JS8_Mode/JS8.cpp"

namespace {
constexpr double rate = 12000.0;

std::string message(unsigned seed, unsigned trial) {
    static char const alpha[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    std::mt19937 r(seed * 1000003u + trial);
    std::string m;
    for (int i = 0; i < 12; ++i) m += alpha[r() % 36];
    return m;
}

std::vector<float> readf(char const *p) {
    std::ifstream f(p, std::ios::binary);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), {});
    std::vector<float> v(b.size() / 4);
    std::memcpy(v.data(), b.data(), v.size() * 4);
    return v;
}

template <typename Mode> int frameStart() { return (int)std::lround(Mode::ASTART * rate); }
template <typename Mode> int frameLen() { return NN * Mode::NSPS; }

template <typename Mode> void gen(int n, unsigned seed, char const *out) {
    std::vector<float> all;
    std::mt19937 rng(seed);
    std::uniform_real_distribution<double> ph(-std::numbers::pi, std::numbers::pi);
    double const baud = rate / Mode::NSPS;
    for (int t = 0; t < n; ++t) {
        std::vector<float> s(Mode::NMAX, 0.0f);
        std::array<int, NN> tones{};
        auto m = message(seed, t);
        JS8::encode(0, JS8::Costas::array(Mode::NCOSTAS), m.c_str(), tones.data());
        std::complex<double> phase = std::polar(1.0, ph(rng));
        int const start = frameStart<Mode>();
        for (int sym = 0; sym < NN; ++sym) {
            auto step = std::polar(1.0, 2.0 * std::numbers::pi * (1500.0 + tones[sym] * baud) / rate);
            for (int k = 0; k < Mode::NSPS; ++k) {
                phase *= step;
                int i = start + sym * Mode::NSPS + k;
                if (i < Mode::NMAX) s[i] = (float)(0.12 * std::sqrt(2.0) * phase.real());
            }
        }
        all.insert(all.end(), s.begin(), s.end());
    }
    std::ofstream f(out, std::ios::binary);
    f.write((char const *)all.data(), all.size() * 4);
}

template <typename Mode> void dec(int n, unsigned seed, char const *fadedp, char const *refp, unsigned nseed,
                                  std::vector<double> const &snrs) {
    auto faded = readf(fadedp), ref = readf(refp);
    double p = 0;
    long cnt = 0;
    for (int t = 0; t < n; ++t)
        for (int i = frameStart<Mode>(); i < std::min(frameStart<Mode>() + frameLen<Mode>(), Mode::NMAX); ++i) {
            double v = ref[(size_t)t * Mode::NMAX + i];
            p += v * v;
            ++cnt;
        }
    double const pref = p / cnt;
    for (double snr : snrs) {
        double const sigma = std::sqrt(pref * (rate / 2) / (2500.0 * std::pow(10.0, snr / 10.0)));
        double const gain = 2000.0 / std::max(sigma, 0.17); // int16 headroom, like the upstream benchmark
        int ok = 0, falses = 0;
        for (int t = 0; t < n; ++t) {
            std::mt19937 rng(nseed * 7919u + t * 104729u + (unsigned)std::lround(snr * 100 + 100000));
            std::normal_distribution<double> nd(0.0, sigma);
            for (int i = 0; i < Mode::NMAX; ++i) {
                long q = std::lround((faded[(size_t)t * Mode::NMAX + i] + nd(rng)) * gain);
                dec_data.d2[i] = (std::int16_t)std::clamp(q, -32768l, 32767l);
            }
            dec_data.params.nfa = 100;
            dec_data.params.nfb = 5000;
            dec_data.params.nfqso = 1500;
            dec_data.params.newdat = true;
            dec_data.params.syncStats = false;
            dec_data.params.nutc = code_time(0, 0, 0);
            auto decoder = std::make_unique<DecodeMode<Mode>>();
            auto const want = message(seed, t);
            bool got = false;
            (*decoder)(dec_data, 0, Mode::NMAX, [&](JS8::Event::Variant const &e) {
                if (auto d = std::get_if<JS8::Event::Decoded>(&e)) {
                    if (d->data == want) got = true;
                    else ++falses;
                }
            });
            ok += got;
        }
        std::printf("%.1f,%d,%d,%d\n", snr, ok, n, falses);
        std::fflush(stdout);
    }
}

template <typename Mode> int run(int argc, char **argv) {
    int n = std::atoi(argv[3]);
    unsigned seed = std::strtoul(argv[4], nullptr, 10);
    if (std::string(argv[1]) == "gen") {
        gen<Mode>(n, seed, argv[5]);
    } else if (std::string(argv[1]) == "info") {
        std::printf("%d %d %d\n", Mode::NMAX, frameStart<Mode>(), frameLen<Mode>());
    } else {
        std::vector<double> snrs;
        for (int i = 8; i < argc; ++i) snrs.push_back(std::atof(argv[i]));
        dec<Mode>(n, seed, argv[5], argv[6], std::strtoul(argv[7], nullptr, 10), snrs);
    }
    return 0;
}
} // namespace

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    std::string m = argv[2];
    if (m == "A") return run<ModeA>(argc, argv);
    if (m == "B") return run<ModeB>(argc, argv);
    if (m == "C") return run<ModeC>(argc, argv);
    if (m == "E") return run<ModeE>(argc, argv);
    if (m == "I") return run<ModeI>(argc, argv);
    return 2;
}
