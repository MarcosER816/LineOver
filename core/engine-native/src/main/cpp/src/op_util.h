// Internal helpers shared by the op implementations (not public API): GLSL snippets and
// the scalar functions they mirror.
#pragma once

#include <cmath>

namespace lo {
namespace opsutil {

// GLSL shared by the OKLCh ops: conversions and the shapes of the C++ above.
#define LO_GLSL_OKLAB                                                                            \
    "const mat3 LO_TO_LMS = mat3(0.616688441751, 0.265140196183, 0.100150645103,\n"             \
    "    0.36015907047, 0.635856484699, 0.204004323432, 0.023043293521, 0.099030233566, 0.696324677437);\n" \
    "const mat3 LO_LMS_TO_LAB = mat3(0.2104542553, 1.9779984951, 0.0259040371,\n"                \
    "    0.793617785, -2.428592205, 0.7827717662, -0.0040720468, 0.4505937099, -0.808675766);\n"  \
    "const mat3 LO_LAB_TO_LMS = mat3(0.999999998451, 1.000000008882, 1.000000054672,\n"          \
    "    0.396337792174, -0.105561342324, -0.089484182095, 0.215803758061, -0.063854174772, -1.291485537864);\n" \
    "const mat3 LO_LMS_TO_RGB = mat3(2.140140411041, -0.884832452797, -0.048579058003,\n"        \
    "    -1.246355950476, 2.163172720721, -0.454490907968, 0.106431725912, -0.27836159213, 1.502356294642);\n" \
    "vec3 lo_cbrt(vec3 x) { return sign(x) * pow(abs(x), vec3(1.0 / 3.0)); }\n"                 \
    "vec3 lo_toLab(vec3 c) { return LO_LMS_TO_LAB * lo_cbrt(LO_TO_LMS * c); }\n"                 \
    "vec3 lo_fromLab(vec3 lab) { vec3 l = LO_LAB_TO_LMS * lab; return LO_LMS_TO_RGB * (l * l * l); }\n" \
    "float lo_hue(vec2 ab) { float h = degrees(atan(ab.y, ab.x)); return h < 0.0 ? h + 360.0 : h; }\n"

// Extended sRGB transfer, as reference.encode / decode.
#define LO_GLSL_ENCODE                                                                               \
    "float lo_enc1(float x) { float a = abs(x);\n"                                                   \
    "    return sign(x) * (a <= 0.0031308 ? a * 12.92 : 1.055 * pow(a, 1.0 / 2.4) - 0.055); }\n"     \
    "float lo_dec1(float e) { float a = abs(e);\n"                                                   \
    "    return sign(e) * (a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4)); }\n"           \
    "vec3 lo_enc(vec3 x) { return vec3(lo_enc1(x.r), lo_enc1(x.g), lo_enc1(x.b)); }\n"              \
    "vec3 lo_dec(vec3 e) { return vec3(lo_dec1(e.r), lo_dec1(e.g), lo_dec1(e.b)); }\n"

inline float encode1(float x) {
    const float a = std::fabs(x);
    const float e = a <= 0.0031308f ? a * 12.92f : 1.055f * std::pow(a, 1.f / 2.4f) - 0.055f;
    return std::copysign(e, x) * (x == 0.f ? 0.f : 1.f);
}

inline float decode1(float e) {
    const float a = std::fabs(e);
    const float x = a <= 0.04045f ? a / 12.92f : std::pow((a + 0.055f) / 1.055f, 2.4f);
    return std::copysign(x, e) * (e == 0.f ? 0.f : 1.f);
}


inline float smoothstepf(float e0, float e1, float x) {
    float t = (x - e0) / (e1 - e0);
    t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
    return t * t * (3.f - 2.f * t);
}

inline float spow(float v, float p) { return std::copysign(std::pow(std::fabs(v), p), v) * (v == 0.f ? 0.f : 1.f); }

}  // namespace opsutil
}  // namespace lo
