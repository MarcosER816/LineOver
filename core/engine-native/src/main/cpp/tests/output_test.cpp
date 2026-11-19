// Export (Stage 12) against tools/golden/export_reference.py: resize, sharpen, watermark and
// conversion within the float / one-code tolerance, the ICC profiles byte for byte, the gain
// map, and round trips of the PNG and TIFF writers (written -> decoded -> equal codes, ICC
// and EXIF present). The files are also left in export_out/ for check_export_files.py
// (OpenCV and LittleCMS read them independently).
#include <sys/stat.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <iostream>
#include <tuple>
#include <vector>

#include "golden_assert.h"
#include "lineover/output.h"
#include "lo_test.h"
#include "npy_io.h"
#include "png_io.h"
#include "spng.h"

using namespace lo::testing;
using lo::output::Space;

// LO_CHECK with a message printed on failure.
#define LO_CHECK_MSG(condition, message)                                                     \
    do {                                                                                     \
        if (!(condition)) {                                                                  \
            ++::lo::testing::failureCount();                                                 \
            std::cerr << "  " << __FILE__ << ":" << __LINE__ << ": " << (message) << "\n";   \
        }                                                                                    \
    } while (false)

namespace {

ImageF32 scene() { return readNpyF32(goldenPath("input/scene_rec2020_f32.npy")); }

std::vector<std::uint8_t> readBytes(const std::string& path) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    LO_CHECK(f != nullptr);
    std::vector<std::uint8_t> b;
    std::uint8_t buf[65536];
    std::size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) b.insert(b.end(), buf, buf + n);
    std::fclose(f);
    return b;
}

const char* spaceName(Space s) { return s == Space::Srgb ? "srgb" : s == Space::DisplayP3 ? "p3" : "rec2020"; }

// The codes of the conversion, as the golden stores them (RGBA, alpha = max code).
ImageF32 convertImage(const ImageF32& in, Space space, int bits) {
    ImageF32 out = in;
    std::vector<std::uint8_t> row8(static_cast<std::size_t>(in.width) * 3);
    std::vector<std::uint16_t> row16(static_cast<std::size_t>(in.width) * 3);
    const float top = bits == 8 ? 255.f : 65535.f;
    for (int y = 0; y < in.height; ++y) {
        const float* src = in.data.data() + static_cast<std::size_t>(y) * in.width * 4;
        if (bits == 8)
            lo::output::convertRow8(src, in.width, space, 3, row8.data());
        else
            lo::output::convertRow16(src, in.width, space, row16.data());
        for (int x = 0; x < in.width; ++x) {
            float* d = out.data.data() + (static_cast<std::size_t>(y) * in.width + x) * 4;
            for (int c = 0; c < 3; ++c) d[c] = bits == 8 ? row8[3 * x + c] : row16[3 * x + c];
            d[3] = top;
        }
    }
    return out;
}

double maxCodeDiff(const ImageF32& a, const ImageF32& b) {
    double worst = 0.0;
    for (std::size_t i = 0; i < a.data.size(); ++i) worst = std::max(worst, std::fabs(static_cast<double>(a.data[i]) - b.data[i]));
    return worst;
}

std::string outDir() {
    mkdir("export_out", 0755);
    return "export_out";
}

// A small EXIF payload (TIFF structure): IFD0 with Software = "LineOver".
std::vector<std::uint8_t> testExif() {
    const char sw[] = "LineOver";
    std::vector<std::uint8_t> b = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x31, 0x01, 2, 0, sizeof(sw), 0, 0, 0, 26, 0, 0, 0, 0, 0, 0, 0};
    b.insert(b.end(), sw, sw + sizeof(sw));
    return b;
}

lo::output::RowSource rowsOf(const ImageF32& codes, int bits) {
    return [&codes, bits](int y, void* out) {
        for (int x = 0; x < codes.width; ++x) {
            const float* p = codes.data.data() + (static_cast<std::size_t>(y) * codes.width + x) * 4;
            for (int c = 0; c < 3; ++c) {
                if (bits == 8)
                    static_cast<std::uint8_t*>(out)[3 * x + c] = static_cast<std::uint8_t>(p[c]);
                else
                    static_cast<std::uint16_t*>(out)[3 * x + c] = static_cast<std::uint16_t>(p[c]);
            }
        }
    };
}

// Reads back a PNG with libspng: codes (host-endian samples), its ICC and EXIF.
ImageF32 decodePng(const std::string& path, int bits, std::vector<std::uint8_t>* icc, std::vector<std::uint8_t>* exif) {
    const std::vector<std::uint8_t> file = readBytes(path);
    spng_ctx* ctx = spng_ctx_new(0);
    LO_CHECK(spng_set_png_buffer(ctx, file.data(), file.size()) == 0);
    spng_ihdr ihdr{};
    LO_CHECK(spng_get_ihdr(ctx, &ihdr) == 0);
    LO_CHECK(ihdr.bit_depth == bits && ihdr.color_type == SPNG_COLOR_TYPE_TRUECOLOR);
    std::size_t size = 0;
    const int fmt = bits == 8 ? SPNG_FMT_RGB8 : SPNG_FMT_PNG;
    LO_CHECK(spng_decoded_image_size(ctx, fmt, &size) == 0);
    std::vector<std::uint8_t> raw(size);
    LO_CHECK(spng_decode_image(ctx, raw.data(), size, fmt, 0) == 0);
    spng_iccp iccp{};
    if (spng_get_iccp(ctx, &iccp) == 0) icc->assign(iccp.profile, iccp.profile + iccp.profile_len);
    spng_exif e{};
    if (spng_get_exif(ctx, &e) == 0) exif->assign(e.data, e.data + e.length);
    ImageF32 out;
    out.width = static_cast<int>(ihdr.width);
    out.height = static_cast<int>(ihdr.height);
    out.data.assign(static_cast<std::size_t>(out.width) * out.height * 4, bits == 8 ? 255.f : 65535.f);
    for (std::size_t i = 0; i < static_cast<std::size_t>(out.width) * out.height; ++i) {
        for (int c = 0; c < 3; ++c) {
            if (bits == 8) {
                out.data[4 * i + c] = raw[3 * i + c];
            } else {
                // SPNG_FMT_PNG decodes 16-bit samples in host order (SPNG_FMT_RAW would be big-endian).
                std::uint16_t v;
                std::memcpy(&v, raw.data() + 6 * i + 2 * c, 2);
                out.data[4 * i + c] = static_cast<float>(v);
            }
        }
    }
    spng_ctx_free(ctx);
    return out;
}

std::uint32_t le(const std::vector<std::uint8_t>& b, std::size_t at, int bytes) {
    std::uint32_t v = 0;
    for (int i = bytes - 1; i >= 0; --i) v = (v << 8) | b[at + static_cast<std::size_t>(i)];
    return v;
}

// A minimal reader for the baseline TIFF the writer makes: tags, then the strips.
ImageF32 decodeTiff(const std::string& path, std::vector<std::uint8_t>* icc, std::string* copyright) {
    const std::vector<std::uint8_t> b = readBytes(path);
    LO_CHECK(b[0] == 'I' && b[1] == 'I' && le(b, 2, 2) == 42);
    const std::uint32_t ifd = le(b, 4, 4);
    const std::uint32_t n = le(b, ifd, 2);
    std::uint32_t width = 0, height = 0, bits = 0, rowsPerStrip = 0, stripCount = 0, stripOffsets = 0;
    for (std::uint32_t i = 0; i < n; ++i) {
        const std::size_t e = ifd + 2 + 12 * i;
        const std::uint32_t tag = le(b, e, 2), type = le(b, e + 2, 2), count = le(b, e + 4, 4);
        const std::size_t valueSize = count * (type == 3 ? 2 : type == 4 ? 4 : type == 5 ? 8 : 1);
        const std::size_t at = valueSize > 4 ? le(b, e + 8, 4) : e + 8;
        const std::uint32_t first = type == 3 ? le(b, at, 2) : type == 4 ? le(b, at, 4) : 0;
        if (tag == 256) width = first;
        if (tag == 257) height = first;
        if (tag == 258) bits = first;
        if (tag == 278) rowsPerStrip = first;
        if (tag == 273) {
            stripCount = count;
            stripOffsets = static_cast<std::uint32_t>(at);
        }
        if (tag == 33432) copyright->assign(b.begin() + static_cast<std::ptrdiff_t>(at), b.begin() + static_cast<std::ptrdiff_t>(at + count - 1));
        if (tag == 34675) icc->assign(b.begin() + static_cast<std::ptrdiff_t>(at), b.begin() + static_cast<std::ptrdiff_t>(at + count));
    }
    ImageF32 out;
    out.width = static_cast<int>(width);
    out.height = static_cast<int>(height);
    out.data.assign(static_cast<std::size_t>(width) * height * 4, bits == 8 ? 255.f : 65535.f);
    const std::size_t bytes = bits / 8;
    for (std::uint32_t s = 0; s < stripCount; ++s) {
        std::size_t at = le(b, stripOffsets + 4 * s, 4);
        for (std::uint32_t y = s * rowsPerStrip; y < std::min(height, (s + 1) * rowsPerStrip); ++y)
            for (std::uint32_t x = 0; x < width; ++x)
                for (int c = 0; c < 3; ++c, at += bytes)
                    out.data[(static_cast<std::size_t>(y) * width + x) * 4 + c] = static_cast<float>(le(b, at, static_cast<int>(bytes)));
    }
    return out;
}

}  // namespace

LO_TEST(resize_matches_the_reference) {
    const ImageF32 in = scene();
    for (auto [w, h] : {std::pair{50, 37}, std::pair{64, 48}}) {
        ImageF32 out;
        out.width = w;
        out.height = h;
        out.channels = 4;
        out.data.assign(static_cast<std::size_t>(w) * h * 4, 0.f);
        std::vector<float> tmp(static_cast<std::size_t>(w) * in.height * 4);
        lo::output::resize(in.data.data(), in.width, in.height, tmp.data(), out.data.data(), w, h);
        const std::string name = "export/resize_" + std::to_string(w) + "x" + std::to_string(h) + "_f32.npy";
        const auto result = compareGolden(out, readNpyF32(goldenPath(name)), "export_resize_" + std::to_string(w));
        LO_CHECK_MSG(result.passed, result.message);
    }
}

LO_TEST(sharpen_matches_the_reference) {
    ImageF32 img = scene();
    lo::output::sharpen(img.data.data(), img.width, img.height, lo::output::kSharpenStandard);
    const auto result = compareGolden(img, readNpyF32(goldenPath("export/sharpen_standard_f32.npy")), "export_sharpen");
    LO_CHECK_MSG(result.passed, result.message);
}

LO_TEST(sharpen_in_bands_equals_one_pass) {
    // 300 rows: three bands; the seams must not show (compare with a 128-row-tall run each).
    ImageF32 tall;
    tall.width = 40;
    tall.height = 300;
    tall.data.resize(static_cast<std::size_t>(40) * 300 * 4);
    for (int y = 0; y < 300; ++y)
        for (int x = 0; x < 40; ++x)
            for (int c = 0; c < 4; ++c)
                tall.data[(static_cast<std::size_t>(y) * 40 + x) * 4 + c] = c == 3 ? 1.f : 0.2f + 0.15f * std::sin(0.3f * x + 0.21f * y + c);
    ImageF32 whole = tall;
    lo::output::sharpen(whole.data.data(), 40, 300, 1.f);
    // Rows 120..140 straddle the first seam: recompute them from a window that has no seam.
    ImageF32 window;
    window.width = 40;
    window.height = 60;
    window.data.assign(tall.data.begin() + 100 * 40 * 4, tall.data.begin() + 160 * 40 * 4);
    lo::output::sharpen(window.data.data(), 40, 60, 1.f);
    double worst = 0.0;
    for (int y = 120; y < 140; ++y)
        for (int i = 0; i < 40 * 4; ++i)
            worst = std::max(worst, std::fabs(static_cast<double>(whole.data[static_cast<std::size_t>(y) * 160 + i]) -
                                              window.data[static_cast<std::size_t>(y - 100) * 160 + i]));
    LO_CHECK_MSG(worst < 1e-6, "seam difference " + std::to_string(worst));
}

LO_TEST(watermark_matches_the_reference) {
    const ImageU8 mark = readPng(goldenPath("export/watermark_rgba8.png"));
    for (auto [x, y, opacity, name] : {std::tuple{80, 78, 0.6f, "watermark"}, std::tuple{100, -4, 1.f, "watermark_clipped"}}) {
        ImageF32 img = scene();
        lo::output::watermark(img.data.data(), img.width, img.height, mark.data.data(), mark.width, mark.height, x, y, opacity);
        const auto result = compareGolden(img, readNpyF32(goldenPath(std::string("export/") + name + "_f32.npy")), std::string("export_") + name);
        LO_CHECK_MSG(result.passed, result.message);
    }
}

LO_TEST(conversion_matches_the_reference_within_one_code) {
    const ImageF32 in = scene();
    for (auto [space, bits] : {std::pair{Space::Srgb, 8}, std::pair{Space::DisplayP3, 8}, std::pair{Space::Srgb, 16},
                               std::pair{Space::DisplayP3, 16}, std::pair{Space::Rec2020, 16}}) {
        const ImageF32 actual = convertImage(in, space, bits);
        const std::string name = std::string("export/convert_") + spaceName(space) + "_" + std::to_string(bits) + "_f32.npy";
        const double worst = maxCodeDiff(actual, readNpyF32(goldenPath(name)));
        LO_CHECK_MSG(worst <= 1.0, name + ": max code difference " + std::to_string(worst));
    }
}

LO_TEST(icc_profiles_equal_the_reference_bytes) {
    for (Space s : {Space::Srgb, Space::DisplayP3, Space::Rec2020}) {
        const std::vector<std::uint8_t> expected = readBytes(goldenPath(std::string("export/icc/") + spaceName(s) + ".icc"));
        const std::vector<std::uint8_t> actual = lo::output::iccProfile(s);
        std::size_t first = 0;
        while (first < std::min(actual.size(), expected.size()) && actual[first] == expected[first]) ++first;
        LO_CHECK_MSG(actual == expected, std::string(spaceName(s)) + ": " + std::to_string(actual.size()) + " vs " +
                                             std::to_string(expected.size()) + " bytes, first difference at " + std::to_string(first));
    }
}

LO_TEST(gainmap_matches_the_reference) {
    const ImageF32 in = scene();
    for (Space s : {Space::Srgb, Space::DisplayP3}) {
        std::vector<std::uint8_t> codes;
        int w = 0, h = 0;
        const float ratio = lo::output::rebuildGainmap(in.data.data(), in.width, in.height, s, 4, &codes, &w, &h);
        const ImageF32 expected = readNpyF32(goldenPath(std::string("export/gainmap_") + spaceName(s) + "_f32.npy"));
        LO_CHECK(w == expected.width && h == expected.height);
        LO_CHECK_MSG(std::fabs(ratio - expected.data[1]) < 1e-3 * expected.data[1], "ratio " + std::to_string(ratio));
        double worst = 0.0;
        for (std::size_t i = 0; i < codes.size(); ++i) worst = std::max(worst, std::fabs(codes[i] - static_cast<double>(expected.data[4 * i])));
        LO_CHECK_MSG(worst <= 1.0, "gain map code difference " + std::to_string(worst));
    }
    // An SDR image has no gain map.
    std::vector<float> flat(16 * 16 * 4, 0.5f);
    std::vector<std::uint8_t> codes;
    int w = 0, h = 0;
    LO_CHECK(lo::output::rebuildGainmap(flat.data(), 16, 16, Space::Srgb, 4, &codes, &w, &h) == 0.f);
}

LO_TEST(png_round_trips_with_icc_and_exif) {
    const ImageF32 in = scene();
    const std::string dir = outDir();
    for (auto [space, bits] : {std::pair{Space::DisplayP3, 8}, std::pair{Space::Srgb, 16}, std::pair{Space::Rec2020, 16}}) {
        const ImageF32 codes = convertImage(in, space, bits);
        const std::string path = dir + "/" + spaceName(space) + "_" + std::to_string(bits) + ".png";
        std::FILE* f = std::fopen(path.c_str(), "wb");
        std::string error;
        const auto icc = lo::output::iccProfile(space);
        LO_CHECK_MSG(lo::output::writePng(f, codes.width, codes.height, bits, icc, testExif(), rowsOf(codes, bits), &error), error);
        std::fclose(f);
        std::vector<std::uint8_t> readIcc, readExif;
        const ImageF32 back = decodePng(path, bits, &readIcc, &readExif);
        LO_CHECK_MSG(maxCodeDiff(back, codes) == 0.0, path + " codes differ");
        LO_CHECK_MSG(readIcc == icc, path + " ICC profile missing or changed");
        LO_CHECK_MSG(readExif == testExif(), path + " EXIF missing or changed");
    }
}

LO_TEST(tiff_round_trips_with_icc) {
    const ImageF32 in = scene();
    const std::string dir = outDir();
    for (auto [space, bits] : {std::pair{Space::Srgb, 8}, std::pair{Space::DisplayP3, 16}, std::pair{Space::Rec2020, 16}}) {
        const ImageF32 codes = convertImage(in, space, bits);
        const std::string path = dir + "/" + spaceName(space) + "_" + std::to_string(bits) + ".tif";
        std::FILE* f = std::fopen(path.c_str(), "wb");
        std::string error;
        const auto icc = lo::output::iccProfile(space);
        lo::output::TiffText text;
        text.software = "LineOver";
        text.copyright = "(c) Test";
        LO_CHECK_MSG(lo::output::writeTiff(f, codes.width, codes.height, bits, icc, text, rowsOf(codes, bits), &error), error);
        std::fclose(f);
        std::vector<std::uint8_t> readIcc;
        std::string copyright;
        const ImageF32 back = decodeTiff(path, &readIcc, &copyright);
        LO_CHECK_MSG(maxCodeDiff(back, codes) == 0.0, path + " codes differ");
        LO_CHECK_MSG(readIcc == icc, path + " ICC profile missing or changed");
        LO_CHECK(copyright == "(c) Test");
    }
}

LO_TEST(mapped_image_is_file_backed_and_writable) {
    std::string error;
    auto image = lo::output::Image::create(".", 300, 200, &error);
    LO_CHECK_MSG(image != nullptr, error);
    image->row(199)[4 * 299 + 2] = 0.75f;
    LO_CHECK(image->data()[(static_cast<std::size_t>(199) * 300 + 299) * 4 + 2] == 0.75f);
    LO_CHECK(lo::output::Image::create("/nonexistent-dir", 10, 10, &error) == nullptr && !error.empty());
}

int main() { return runAllTests(); }
