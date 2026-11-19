// 3D LUTs and the built-in profiles (Stage 11). Formulas: tools/golden/lut_reference.py.
#include "lineover/lut.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>

#include "op_util.h"

namespace lo {
namespace {

constexpr double kRec2020ToSrgb[9] = {1.6603034854214451,   -0.58757014253161666, -0.072890060215055993,
                                      -0.12437559530838646, 1.1328344814319582,   -0.008359737187401451,
                                      -0.018112279959916489, -0.10058360850721247, 1.1187703261570985};
constexpr double kSrgbToRec2020[9] = {0.62744137205797812,  0.32929745952190931, 0.043351458394494675,
                                      0.069027617147078099, 0.91958066688702822, 0.011361422575400862,
                                      0.016364235071680722, 0.088017162471726973, 0.89556497272598279};
constexpr double kLuma709[3] = {0.2126, 0.7152, 0.0722};

double enc(double x) {
    const double a = std::fabs(x);
    const double e = a <= 0.0031308 ? a * 12.92 : 1.055 * std::pow(a, 1.0 / 2.4) - 0.055;
    return x < 0 ? -e : (x > 0 ? e : 0.0);
}

double dec(double e) {
    const double a = std::fabs(e);
    const double x = a <= 0.04045 ? a / 12.92 : std::pow((a + 0.055) / 1.055, 2.4);
    return e < 0 ? -x : (e > 0 ? x : 0.0);
}

void mul3(const double* m, const double* v, double* out) {
    for (int r = 0; r < 3; ++r) out[r] = m[3 * r] * v[0] + m[3 * r + 1] * v[1] + m[3 * r + 2] * v[2];
}

std::mutex& registryMutex() {
    static std::mutex m;
    return m;
}

std::map<std::string, std::shared_ptr<const Lut3D>>& registry() {
    static std::map<std::string, std::shared_ptr<const Lut3D>> r;
    return r;
}

// The LUT and profile op.
class LutOp final : public Op {
public:
    LutOp(std::shared_ptr<const Lut3D> lut, float amount, OpType type)
        : lut_(std::move(lut)), amount_(std::clamp(amount, 0.f, 1.f)), type_(type) {}
    OpType type() const override { return type_; }
    bool isIdentity() const override { return lut_ == nullptr || amount_ == 0.f; }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        if (isIdentity()) {
            if (in != out) std::memcpy(out, in, pixels * 4 * sizeof(float));
            return;
        }
        const Lut3D& l = *lut_;
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            const double rgb[3] = {p[0], p[1], p[2]};
            double s[3], e[3], c[3], cc[3], t[3];
            mul3(kRec2020ToSrgb, rgb, s);
            double residual = 0;
            for (int k = 0; k < 3; ++k) {
                e[k] = enc(s[k]);
                const double span = static_cast<double>(l.domainMax[k]) - l.domainMin[k];
                c[k] = (e[k] - l.domainMin[k]) / span;
                cc[k] = std::clamp(c[k], 0.0, 1.0);
                residual += kLuma709[k] * (c[k] - cc[k]) * span;
            }
            lutTetrahedral(l, cc, t);
            double lin[3], o[3];
            for (int k = 0; k < 3; ++k) lin[k] = dec(e[k] + amount_ * (t[k] + residual - e[k]));
            mul3(kSrgbToRec2020, lin, o);
            float* q = out + 4 * i;
            const float a = p[3];
            q[0] = static_cast<float>(o[0]);
            q[1] = static_cast<float>(o[1]);
            q[2] = static_cast<float>(o[2]);
            q[3] = a;
        }
    }
    void gpu(GpuOpContext& c) const override {
        c.setLut3d(lut_.get());
        c.setShader(LO_GLSL_ENCODE
                    "uniform highp sampler3D u_lut3d;\nuniform float u_lutN;\nuniform vec3 u_domMin;\nuniform vec3 u_domSpan;\n"
                    "uniform float u_amount;\n"
                    "vec3 lo_l3(ivec3 i) { return texelFetch(u_lut3d, i, 0).rgb; }\n"
                    // lut_reference.tetrahedral: the same six cases, the same comparisons.
                    "vec3 lo_tetra(vec3 c) {\n"
                    "    vec3 x = clamp(c, 0.0, 1.0) * (u_lutN - 1.0);\n"
                    "    vec3 i0 = min(floor(x), vec3(u_lutN - 2.0));\n"
                    "    vec3 f = x - i0;\n"
                    "    ivec3 i = ivec3(i0);\n"
                    "    vec3 c000 = lo_l3(i), c111 = lo_l3(i + ivec3(1, 1, 1));\n"
                    "    float fr = f.r, fg = f.g, fb = f.b;\n"
                    "    if (fr >= fg && fg >= fb) {\n"
                    "        vec3 c100 = lo_l3(i + ivec3(1, 0, 0)), c110 = lo_l3(i + ivec3(1, 1, 0));\n"
                    "        return c000 + fr * (c100 - c000) + fg * (c110 - c100) + fb * (c111 - c110);\n"
                    "    }\n"
                    "    if (fr >= fb && fb > fg) {\n"
                    "        vec3 c100 = lo_l3(i + ivec3(1, 0, 0)), c101 = lo_l3(i + ivec3(1, 0, 1));\n"
                    "        return c000 + fr * (c100 - c000) + fb * (c101 - c100) + fg * (c111 - c101);\n"
                    "    }\n"
                    "    if (fb > fr && fr >= fg) {\n"
                    "        vec3 c001 = lo_l3(i + ivec3(0, 0, 1)), c101 = lo_l3(i + ivec3(1, 0, 1));\n"
                    "        return c000 + fb * (c001 - c000) + fr * (c101 - c001) + fg * (c111 - c101);\n"
                    "    }\n"
                    "    if (fg > fr && fr >= fb) {\n"
                    "        vec3 c010 = lo_l3(i + ivec3(0, 1, 0)), c110 = lo_l3(i + ivec3(1, 1, 0));\n"
                    "        return c000 + fg * (c010 - c000) + fr * (c110 - c010) + fb * (c111 - c110);\n"
                    "    }\n"
                    "    if (fg >= fb && fb > fr) {\n"
                    "        vec3 c010 = lo_l3(i + ivec3(0, 1, 0)), c011 = lo_l3(i + ivec3(0, 1, 1));\n"
                    "        return c000 + fg * (c010 - c000) + fb * (c011 - c010) + fr * (c111 - c011);\n"
                    "    }\n"
                    "    vec3 c001 = lo_l3(i + ivec3(0, 0, 1)), c011 = lo_l3(i + ivec3(0, 1, 1));\n"
                    "    return c000 + fb * (c001 - c000) + fg * (c011 - c001) + fr * (c111 - c011);\n"
                    "}\n",
                    "vec3 s = vec3(dot(vec3(1.6603034854214451, -0.58757014253161666, -0.072890060215055993), c.rgb),\n"
                    "              dot(vec3(-0.12437559530838646, 1.1328344814319582, -0.008359737187401451), c.rgb),\n"
                    "              dot(vec3(-0.018112279959916489, -0.10058360850721247, 1.1187703261570985), c.rgb));\n"
                    "vec3 e = lo_enc(s);\n"
                    "vec3 cn = (e - u_domMin) / u_domSpan;\n"
                    "vec3 cc = clamp(cn, 0.0, 1.0);\n"
                    "float residual = dot((cn - cc) * u_domSpan, vec3(0.2126, 0.7152, 0.0722));\n"
                    "vec3 lin = lo_dec(e + u_amount * (lo_tetra(cc) + residual - e));\n"
                    "return vec4(dot(vec3(0.62744137205797812, 0.32929745952190931, 0.043351458394494675), lin),\n"
                    "            dot(vec3(0.069027617147078099, 0.91958066688702822, 0.011361422575400862), lin),\n"
                    "            dot(vec3(0.016364235071680722, 0.088017162471726973, 0.89556497272598279), lin), c.a);");
        const Lut3D& l = *lut_;
        c.setUniform1f("u_lutN", static_cast<float>(l.size));
        c.setUniform1f("u_amount", amount_);
        const float dmin[3] = {l.domainMin[0], l.domainMin[1], l.domainMin[2]};
        const float span[3] = {l.domainMax[0] - l.domainMin[0], l.domainMax[1] - l.domainMin[1], l.domainMax[2] - l.domainMin[2]};
        c.setUniform3f("u_domMin", dmin);
        c.setUniform3f("u_domSpan", span);
    }

private:
    std::shared_ptr<const Lut3D> lut_;
    float amount_;
    OpType type_;
};

// ------------------------------------------------------------------ profiles (OKLab)

constexpr double kVividChroma = 1.25, kVividContrast = 0.6;
constexpr double kFlatChroma = 0.85, kFlatContrast = 0.5, kFlatLift = 0.05;
constexpr int kGamutSteps = 12;

void labOfSrgb(const double lin[3], double lab[3]) {
    double r2020[3];
    mul3(kSrgbToRec2020, lin, r2020);
    opsutil::toLab(r2020, lab);
}

void srgbOfLab(const double lab[3], double lin[3]) {
    double r2020[3];
    opsutil::fromLab(lab, r2020);
    mul3(kRec2020ToSrgb, r2020, lin);
}

bool inGamut(const double rgb[3]) {
    for (int k = 0; k < 3; ++k)
        if (rgb[k] < -1e-9 || rgb[k] > 1.0 + 1e-9) return false;
    return true;
}

void profileAt(const std::string& name, const double e[3], double out[3]) {
    double lin[3], lab[3], rgb[3];
    for (int k = 0; k < 3; ++k) lin[k] = dec(e[k]);
    labOfSrgb(lin, lab);
    const double L = lab[0];
    if (name == "vivid") {
        const double L2 = L + kVividContrast * (L - 0.5) * L * (1.0 - L);
        const double base[3] = {L2, lab[1], lab[2]};
        const double target[3] = {L2, lab[1] * kVividChroma, lab[2] * kVividChroma};
        srgbOfLab(target, rgb);
        double t = 1.0;
        if (!inGamut(rgb)) {
            double lo = 0.0, hi = 1.0;
            for (int s = 0; s < kGamutSteps; ++s) {
                const double mid = 0.5 * (lo + hi);
                const double probe[3] = {L2, base[1] + mid * (target[1] - base[1]), base[2] + mid * (target[2] - base[2])};
                double pr[3];
                srgbOfLab(probe, pr);
                if (inGamut(pr)) lo = mid; else hi = mid;
            }
            t = lo;
        }
        const double fin[3] = {L2, base[1] + t * (target[1] - base[1]), base[2] + t * (target[2] - base[2])};
        srgbOfLab(fin, rgb);
    } else if (name == "mono") {
        const double y = std::pow(std::clamp(L, 0.0, 1.0), 3.0);
        rgb[0] = rgb[1] = rgb[2] = y;
    } else {  // flat
        double L2 = L - kFlatContrast * (L - 0.5) * L * (1.0 - L);
        L2 = kFlatLift + (1.0 - kFlatLift) * L2;
        const double fin[3] = {L2, lab[1] * kFlatChroma, lab[2] * kFlatChroma};
        srgbOfLab(fin, rgb);
    }
    for (int k = 0; k < 3; ++k) out[k] = std::clamp(enc(std::clamp(rgb[k], 0.0, 1.0)), 0.0, 1.0);
}

std::shared_ptr<const Lut3D> makeProfile(const std::string& name) {
    auto lut = std::make_shared<Lut3D>();
    const int n = kProfileSize;
    lut->size = n;
    lut->rgb.resize(static_cast<std::size_t>(n) * n * n * 3);
    for (int b = 0; b < n; ++b)
        for (int g = 0; g < n; ++g)
            for (int r = 0; r < n; ++r) {
                const double e[3] = {r / double(n - 1), g / double(n - 1), b / double(n - 1)};
                double o[3];
                profileAt(name, e, o);
                float* q = &lut->rgb[3 * ((static_cast<std::size_t>(b) * n + g) * n + r)];
                for (int k = 0; k < 3; ++k) q[k] = static_cast<float>(o[k]);
            }
    lut->finish();
    return lut;
}

}  // namespace

bool Lut3D::finish() {
    if (size < kLutMinSize || size > kLutMaxSize) return false;
    if (rgb.size() != static_cast<std::size_t>(size) * size * size * 3) return false;
    for (int k = 0; k < 3; ++k)
        if (!(domainMax[k] > domainMin[k])) return false;
    std::uint64_t h = 1469598103934665603ull ^ static_cast<std::uint64_t>(size);
    auto mix = [&](const void* p, std::size_t bytes) {
        const auto* b = static_cast<const unsigned char*>(p);
        for (std::size_t i = 0; i < bytes; ++i) h = (h ^ b[i]) * 1099511628211ull;
    };
    mix(domainMin, sizeof(domainMin));
    mix(domainMax, sizeof(domainMax));
    mix(rgb.data(), rgb.size() * sizeof(float));
    hash = h;
    return true;
}

void registerLut(const std::string& id, std::shared_ptr<const Lut3D> lut) {
    std::lock_guard<std::mutex> lock(registryMutex());
    registry()[id] = std::move(lut);
}

void unregisterLut(const std::string& id) {
    std::lock_guard<std::mutex> lock(registryMutex());
    registry().erase(id);
}

std::shared_ptr<const Lut3D> findLut(const std::string& id) {
    std::lock_guard<std::mutex> lock(registryMutex());
    auto it = registry().find(id);
    return it == registry().end() ? nullptr : it->second;
}

std::shared_ptr<const Lut3D> profileLut(const std::string& name) {
    if (name != "vivid" && name != "mono" && name != "flat") return nullptr;
    static std::mutex m;
    static std::map<std::string, std::shared_ptr<const Lut3D>> made;
    std::lock_guard<std::mutex> lock(m);
    auto& slot = made[name];
    if (!slot) slot = makeProfile(name);
    return slot;
}

std::unique_ptr<Op> makeLut(std::shared_ptr<const Lut3D> lut, float amount, OpType type) {
    return std::make_unique<LutOp>(std::move(lut), amount, type);
}

void lutTetrahedral(const Lut3D& lut, const double c[3], double out[3]) {
    const int n = lut.size;
    int i[3];
    double f[3];
    for (int k = 0; k < 3; ++k) {
        const double x = std::clamp(c[k], 0.0, 1.0) * (n - 1);
        const double i0 = std::min(std::floor(x), static_cast<double>(n - 2));
        i[k] = static_cast<int>(i0);
        f[k] = x - i0;
    }
    auto at = [&](int dr, int dg, int db, int k) {
        return static_cast<double>(
            lut.rgb[3 * ((static_cast<std::size_t>(i[2] + db) * n + (i[1] + dg)) * n + (i[0] + dr)) + k]);
    };
    const double fr = f[0], fg = f[1], fb = f[2];
    for (int k = 0; k < 3; ++k) {
        const double c000 = at(0, 0, 0, k), c111 = at(1, 1, 1, k);
        double v;
        if (fr >= fg && fg >= fb) {
            v = c000 + fr * (at(1, 0, 0, k) - c000) + fg * (at(1, 1, 0, k) - at(1, 0, 0, k)) + fb * (c111 - at(1, 1, 0, k));
        } else if (fr >= fb && fb > fg) {
            v = c000 + fr * (at(1, 0, 0, k) - c000) + fb * (at(1, 0, 1, k) - at(1, 0, 0, k)) + fg * (c111 - at(1, 0, 1, k));
        } else if (fb > fr && fr >= fg) {
            v = c000 + fb * (at(0, 0, 1, k) - c000) + fr * (at(1, 0, 1, k) - at(0, 0, 1, k)) + fg * (c111 - at(1, 0, 1, k));
        } else if (fg > fr && fr >= fb) {
            v = c000 + fg * (at(0, 1, 0, k) - c000) + fr * (at(1, 1, 0, k) - at(0, 1, 0, k)) + fb * (c111 - at(1, 1, 0, k));
        } else if (fg >= fb && fb > fr) {
            v = c000 + fg * (at(0, 1, 0, k) - c000) + fb * (at(0, 1, 1, k) - at(0, 1, 0, k)) + fr * (c111 - at(0, 1, 1, k));
        } else {
            v = c000 + fb * (at(0, 0, 1, k) - c000) + fg * (at(0, 1, 1, k) - at(0, 0, 1, k)) + fr * (c111 - at(0, 1, 1, k));
        }
        out[k] = v;
    }
}

}  // namespace lo
