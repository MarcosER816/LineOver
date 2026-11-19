// Export (Stage 12): the pixel steps between the full-resolution render (linear Rec. 2020
// float RGBA) and the encoders, the ICC profiles LineOver embeds, and the PNG / TIFF writers.
// tools/golden/export_reference.py is the reference; ctest output_test checks against it.
//
// Order: resize (Lanczos-3, linear light, never enlarges) -> sharpen for screen -> watermark
// -> convert (gamut map to the target space, transfer, 8 or 16-bit codes). Everything stays
// float until convert: no precision is lost before the encoder.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace lo::output {

enum class Space : int {
    Srgb = 0,
    DisplayP3 = 1,
    Rec2020 = 2,  // 16-bit files only (PNG, TIFF)
};

// A float RGBA image. With a directory it lives in a temporary file mapped into memory
// (a 50 MP render is 800 MB of floats: the kernel pages it, it is not native heap); with
// an empty directory it is on the heap (tests, small images). The file is deleted at once.
class Image {
public:
    static std::unique_ptr<Image> create(const std::string& dir, int width, int height, std::string* error);
    ~Image();
    Image(const Image&) = delete;
    Image& operator=(const Image&) = delete;

    int width() const { return width_; }
    int height() const { return height_; }
    float* data() { return data_; }
    const float* data() const { return data_; }
    float* row(int y) { return data_ + static_cast<std::size_t>(y) * width_ * 4; }
    const float* row(int y) const { return data_ + static_cast<std::size_t>(y) * width_ * 4; }

private:
    Image() = default;
    int width_ = 0;
    int height_ = 0;
    float* data_ = nullptr;
    std::size_t bytes_ = 0;
    bool mapped_ = false;
    std::vector<float> heap_;
};

// Lanczos-3, horizontal into `tmp` (outWidth x height RGBA), then vertical into `out`.
void resize(const float* in, int width, int height, float* tmp, float* out, int outWidth, int outHeight);

// Sharpen for screen: unsharp mask (sigma 0.7 px) on cube-root luminance, applied as a
// luminance ratio (colours keep their chroma). Works in bands: small memory at any size.
inline constexpr float kSharpenLow = 0.35f;
inline constexpr float kSharpenStandard = 0.7f;
inline constexpr float kSharpenHigh = 1.2f;
void sharpen(float* rgba, int width, int height, float amount);

// Composites a premultiplied sRGB RGBA8 mark (Android's ARGB_8888 memory) at (x, y) with
// `opacity`, in linear light. Parts outside the image are skipped.
void watermark(float* rgba, int width, int height, const std::uint8_t* mark, int markWidth, int markHeight, int x, int y,
               float opacity);

// One row of working RGBA -> codes. 8 bit: `channels` 3 (RGB) or 4 (RGBA, alpha 255).
// 16 bit: RGB, host-endian. Rec. 2020 is clipped to 0..1, sRGB / Display P3 gamut-mapped.
void convertRow8(const float* rgba, int pixels, Space space, int channels, std::uint8_t* out);
void convertRow16(const float* rgba, int pixels, Space space, std::uint16_t* out);

// The same linear values the conversion encodes (gamut-mapped / clipped, RGBA): for the
// gain map, which compares them with the HDR render.
void baseLinearRow(const float* rgba, int pixels, Space space, float* out);

// An ICC v4.3 display profile (matrix / TRC) for `space`; byte-identical to
// export_reference.icc_profile.
std::vector<std::uint8_t> iccProfile(Space space);

// Ultra HDR gain map rebuilt from the render: per `factor` x `factor` block, the mean log2
// of (HDR luminance + eps) / (SDR luminance + eps), mapped to 0..255 over 0..log2(ratioMax).
// Returns ratioMax (> 1), or 0 when the image has no highlights above the SDR base.
inline constexpr float kGainmapEpsilon = 1.f / 64.f;
float rebuildGainmap(const float* rgba, int width, int height, Space space, int factor, std::vector<std::uint8_t>* out,
                     int* outWidth, int* outHeight);

// Rows for a writer: fills `out` with row `y` of the encoded image.
using RowSource = std::function<void(int y, void* out)>;

// PNG, 8 or 16-bit RGB (libspng), with the ICC profile (iCCP) and an optional EXIF block
// (eXIf: a TIFF-structured payload, "II*\0" / "MM\0*").
bool writePng(std::FILE* file, int width, int height, int bits, const std::vector<std::uint8_t>& icc,
              const std::vector<std::uint8_t>& exif, const RowSource& rows, std::string* error);

// Baseline TIFF (little-endian, uncompressed RGB strips of 64 rows), 8 or 16-bit, with the
// ICC profile (tag 34675) and these text tags when not empty. Written front to back: the
// file does not need to be seekable.
struct TiffText {
    std::string make, model, software, dateTime, artist, copyright;
};
bool writeTiff(std::FILE* file, int width, int height, int bits, const std::vector<std::uint8_t>& icc, const TiffText& text,
               const RowSource& rows, std::string* error);

}  // namespace lo::output
