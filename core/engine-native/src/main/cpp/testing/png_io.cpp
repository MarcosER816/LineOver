#include "png_io.h"

#include <zlib.h>

#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>

namespace lo::testing {
namespace {

const unsigned char kSignature[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

std::uint32_t readU32(const std::uint8_t* p) {
    return (std::uint32_t{p[0]} << 24) | (std::uint32_t{p[1]} << 16) | (std::uint32_t{p[2]} << 8) | p[3];
}

void appendU32(std::vector<std::uint8_t>* out, std::uint32_t value) {
    out->push_back(static_cast<std::uint8_t>(value >> 24));
    out->push_back(static_cast<std::uint8_t>(value >> 16));
    out->push_back(static_cast<std::uint8_t>(value >> 8));
    out->push_back(static_cast<std::uint8_t>(value));
}

void appendChunk(std::vector<std::uint8_t>* out, const char type[4], const std::vector<std::uint8_t>& payload) {
    appendU32(out, static_cast<std::uint32_t>(payload.size()));
    const std::size_t crcStart = out->size();
    out->insert(out->end(), type, type + 4);
    out->insert(out->end(), payload.begin(), payload.end());
    const uLong crc = crc32(0L, out->data() + crcStart, static_cast<uInt>(out->size() - crcStart));
    appendU32(out, static_cast<std::uint32_t>(crc));
}

int paeth(int a, int b, int c) {
    const int p = a + b - c;
    const int pa = std::abs(p - a);
    const int pb = std::abs(p - b);
    const int pc = std::abs(p - c);
    if (pa <= pb && pa <= pc) return a;
    return pb <= pc ? b : c;
}

// Reverses the per-scanline PNG filters in place. `raw` holds, for every row, one
// filter byte followed by `stride` bytes.
void unfilter(std::vector<std::uint8_t>* raw, ImageU8* image) {
    const std::size_t bpp = static_cast<std::size_t>(image->channels);
    const std::size_t stride = static_cast<std::size_t>(image->width) * bpp;
    image->data.resize(stride * static_cast<std::size_t>(image->height));

    for (int y = 0; y < image->height; ++y) {
        const std::uint8_t* in = raw->data() + static_cast<std::size_t>(y) * (stride + 1);
        const std::uint8_t filter = in[0];
        std::uint8_t* row = image->data.data() + static_cast<std::size_t>(y) * stride;
        const std::uint8_t* up = y > 0 ? row - stride : nullptr;

        for (std::size_t x = 0; x < stride; ++x) {
            const int left = x >= bpp ? row[x - bpp] : 0;
            const int above = up != nullptr ? up[x] : 0;
            const int aboveLeft = (up != nullptr && x >= bpp) ? up[x - bpp] : 0;
            int predictor = 0;
            switch (filter) {
                case 0: predictor = 0; break;
                case 1: predictor = left; break;
                case 2: predictor = above; break;
                case 3: predictor = (left + above) / 2; break;
                case 4: predictor = paeth(left, above, aboveLeft); break;
                default: throw std::runtime_error("PNG: unknown filter type");
            }
            row[x] = static_cast<std::uint8_t>(in[1 + x] + predictor);
        }
    }
}

}  // namespace

ImageU8 readPng(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("PNG: cannot open " + path);
    const std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());

    if (bytes.size() < sizeof(kSignature) || std::memcmp(bytes.data(), kSignature, sizeof(kSignature)) != 0) {
        throw std::runtime_error("PNG: bad signature in " + path);
    }

    ImageU8 image;
    std::vector<std::uint8_t> compressed;
    std::size_t offset = sizeof(kSignature);
    bool sawEnd = false;
    while (!sawEnd && offset + 12 <= bytes.size()) {
        const std::uint32_t length = readU32(&bytes[offset]);
        const std::string type(reinterpret_cast<const char*>(&bytes[offset + 4]), 4);
        const std::size_t payload = offset + 8;
        if (payload + length + 4 > bytes.size()) throw std::runtime_error("PNG: truncated chunk in " + path);

        if (type == "IHDR") {
            if (length != 13) throw std::runtime_error("PNG: bad IHDR in " + path);
            image.width = static_cast<int>(readU32(&bytes[payload]));
            image.height = static_cast<int>(readU32(&bytes[payload + 4]));
            const int bitDepth = bytes[payload + 8];
            const int colorType = bytes[payload + 9];
            const int interlace = bytes[payload + 12];
            if (bitDepth != 8 || interlace != 0 || (colorType != 2 && colorType != 6)) {
                throw std::runtime_error("PNG: only 8-bit non-interlaced RGB/RGBA is supported: " + path);
            }
            image.channels = colorType == 6 ? 4 : 3;
        } else if (type == "IDAT") {
            compressed.insert(compressed.end(), bytes.begin() + static_cast<std::ptrdiff_t>(payload),
                              bytes.begin() + static_cast<std::ptrdiff_t>(payload + length));
        } else if (type == "IEND") {
            sawEnd = true;
        }
        offset = payload + length + 4;
    }
    if (!sawEnd || image.width <= 0 || image.height <= 0) throw std::runtime_error("PNG: incomplete file " + path);

    const std::size_t stride = static_cast<std::size_t>(image.width) * static_cast<std::size_t>(image.channels);
    std::vector<std::uint8_t> raw((stride + 1) * static_cast<std::size_t>(image.height));
    uLongf rawSize = static_cast<uLongf>(raw.size());
    if (uncompress(raw.data(), &rawSize, compressed.data(), static_cast<uLong>(compressed.size())) != Z_OK ||
        rawSize != raw.size()) {
        throw std::runtime_error("PNG: corrupt image data in " + path);
    }
    unfilter(&raw, &image);
    return image;
}

void writePng(const std::string& path, const ImageU8& image) {
    if (image.channels != 3 && image.channels != 4) throw std::runtime_error("PNG: can only write RGB/RGBA");
    const std::size_t stride = static_cast<std::size_t>(image.width) * static_cast<std::size_t>(image.channels);

    std::vector<std::uint8_t> raw;
    raw.reserve((stride + 1) * static_cast<std::size_t>(image.height));
    for (int y = 0; y < image.height; ++y) {
        raw.push_back(0);  // filter type "None"
        const std::uint8_t* row = image.data.data() + static_cast<std::size_t>(y) * stride;
        raw.insert(raw.end(), row, row + stride);
    }

    uLongf compressedSize = compressBound(static_cast<uLong>(raw.size()));
    std::vector<std::uint8_t> compressed(compressedSize);
    if (compress2(compressed.data(), &compressedSize, raw.data(), static_cast<uLong>(raw.size()),
                  Z_BEST_SPEED) != Z_OK) {
        throw std::runtime_error("PNG: compression failed");
    }
    compressed.resize(compressedSize);

    std::vector<std::uint8_t> header;
    appendU32(&header, static_cast<std::uint32_t>(image.width));
    appendU32(&header, static_cast<std::uint32_t>(image.height));
    header.push_back(8);                                               // bit depth
    header.push_back(static_cast<std::uint8_t>(image.channels == 4 ? 6 : 2));  // colour type
    header.push_back(0);                                               // compression
    header.push_back(0);                                               // filter method
    header.push_back(0);                                               // no interlace

    std::vector<std::uint8_t> out(kSignature, kSignature + sizeof(kSignature));
    appendChunk(&out, "IHDR", header);
    appendChunk(&out, "IDAT", compressed);
    appendChunk(&out, "IEND", {});

    std::ofstream file(path, std::ios::binary);
    file.write(reinterpret_cast<const char*>(out.data()), static_cast<std::streamsize>(out.size()));
    if (!file) throw std::runtime_error("PNG: cannot write " + path);
}

}  // namespace lo::testing
