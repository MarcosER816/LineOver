// 3D LUTs (Stage 11): the LUT op (imported .cube files, Presets → 3D LUTs) and the built-in
// profiles (Neutral, Vivid, Mono, Flat), which are LUTs made here. Formulas:
// tools/golden/lut_reference.py; the C++ here, the GLSL below and the Python change together.
//
// The op works on sRGB-encoded Rec.709 values (what .cube files are made for): working
// linear Rec.2020 -> linear sRGB -> sRGB transfer; c = (e - domainMin) / span, clamped for
// a tetrahedral lookup; outside the domain the LUT continues with slope 1 in Rec.709 luma
// (HDR highlights are not clipped); e' = e + amount (lut - e); then back.
//
// A LUT's data never travels in the graph JSON: Kotlin parses the .cube and registers the
// table under an id (its SHA-256); the graph's `lut` op names that id.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "lineover/ops.h"

namespace lo {

struct Lut3D {
    int size = 0;                       // N, 2..65
    float domainMin[3] = {0.f, 0.f, 0.f};
    float domainMax[3] = {1.f, 1.f, 1.f};
    std::vector<float> rgb;             // N^3 x RGB, red changing fastest, then green, then blue
    std::uint64_t hash = 0;             // of the content (GPU texture cache key)

    // Fills `hash`; false when the size or the table does not fit.
    bool finish();
};

inline constexpr int kLutMinSize = 2;
inline constexpr int kLutMaxSize = 65;

// Process-wide registry (thread-safe): Kotlin registers imported LUTs before using them.
void registerLut(const std::string& id, std::shared_ptr<const Lut3D> lut);
void unregisterLut(const std::string& id);
std::shared_ptr<const Lut3D> findLut(const std::string& id);

// The built-in profiles: "vivid", "mono", "flat" (33^3, made once); null for "neutral" (no
// change) and unknown names.
std::shared_ptr<const Lut3D> profileLut(const std::string& name);
inline constexpr int kProfileSize = 33;

// The LUT op (OpType::Lut or OpType::Profile), amount 0..1.
std::unique_ptr<Op> makeLut(std::shared_ptr<const Lut3D> lut, float amount, OpType type = OpType::Lut);

// Tetrahedral lookup of `lut` at c (each 0..1), as lut_reference.tetrahedral.
void lutTetrahedral(const Lut3D& lut, const double c[3], double out[3]);

}  // namespace lo
