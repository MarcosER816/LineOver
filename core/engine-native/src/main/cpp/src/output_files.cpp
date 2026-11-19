// Export files (Stage 12): the mapped float image, PNG (libspng) and TIFF writers.
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>

#include "lineover/output.h"
#include "spng.h"

namespace lo::output {

std::unique_ptr<Image> Image::create(const std::string& dir, int width, int height, std::string* error) {
    if (width <= 0 || height <= 0) {
        *error = "empty image";
        return nullptr;
    }
    std::unique_ptr<Image> image(new Image());
    image->width_ = width;
    image->height_ = height;
    image->bytes_ = static_cast<std::size_t>(width) * height * 4 * sizeof(float);
    if (dir.empty()) {
        image->heap_.assign(image->bytes_ / sizeof(float), 0.f);
        image->data_ = image->heap_.data();
        return image;
    }
    std::string path = dir + "/lo_export_XXXXXX";
    const int fd = mkstemp(path.data());
    if (fd < 0) {
        *error = std::string("cannot create a temporary file: ") + std::strerror(errno);
        return nullptr;
    }
    unlink(path.c_str());  // gone once unmapped and closed
    if (ftruncate(fd, static_cast<off_t>(image->bytes_)) != 0) {
        *error = std::string("not enough storage for the export: ") + std::strerror(errno);
        close(fd);
        return nullptr;
    }
    void* p = mmap(nullptr, image->bytes_, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) {
        *error = std::string("cannot map the export buffer: ") + std::strerror(errno);
        return nullptr;
    }
    image->data_ = static_cast<float*>(p);
    image->mapped_ = true;
    return image;
}

Image::~Image() {
    if (mapped_ && data_ != nullptr) munmap(data_, bytes_);
}

bool writePng(std::FILE* file, int width, int height, int bits, const std::vector<std::uint8_t>& icc,
              const std::vector<std::uint8_t>& exif, const RowSource& rows, std::string* error) {
    spng_ctx* ctx = spng_ctx_new(SPNG_CTX_ENCODER);
    if (ctx == nullptr) {
        *error = "cannot create a PNG encoder";
        return false;
    }
    auto fail = [&](int code, const char* what) {
        *error = std::string(what) + ": " + spng_strerror(code);
        spng_ctx_free(ctx);
        return false;
    };
    int r = spng_set_png_file(ctx, file);
    if (r != 0) return fail(r, "PNG file");
    spng_ihdr ihdr{};
    ihdr.width = static_cast<std::uint32_t>(width);
    ihdr.height = static_cast<std::uint32_t>(height);
    ihdr.bit_depth = static_cast<std::uint8_t>(bits);
    ihdr.color_type = SPNG_COLOR_TYPE_TRUECOLOR;
    if ((r = spng_set_ihdr(ctx, &ihdr)) != 0) return fail(r, "PNG header");
    // libspng keeps pointers to these until the image is encoded.
    std::vector<std::uint8_t> iccCopy = icc;
    if (!iccCopy.empty()) {
        spng_iccp iccp{};
        std::strncpy(iccp.profile_name, "ICC profile", sizeof(iccp.profile_name) - 1);
        iccp.profile_len = iccCopy.size();
        iccp.profile = reinterpret_cast<char*>(iccCopy.data());
        if ((r = spng_set_iccp(ctx, &iccp)) != 0) return fail(r, "PNG ICC profile");
    }
    std::vector<std::uint8_t> exifCopy = exif;
    if (!exifCopy.empty()) {
        spng_exif e{};
        e.length = exifCopy.size();
        e.data = reinterpret_cast<char*>(exifCopy.data());
        if ((r = spng_set_exif(ctx, &e)) != 0) return fail(r, "PNG EXIF");
    }
    spng_set_option(ctx, SPNG_IMG_COMPRESSION_LEVEL, 6);
    if ((r = spng_encode_image(ctx, nullptr, 0, SPNG_FMT_PNG, SPNG_ENCODE_PROGRESSIVE | SPNG_ENCODE_FINALIZE)) != 0)
        return fail(r, "PNG encoder");
    const std::size_t rowBytes = static_cast<std::size_t>(width) * 3 * (bits / 8);
    std::vector<std::uint8_t> row(rowBytes);
    for (int y = 0; y < height; ++y) {
        rows(y, row.data());
        r = spng_encode_row(ctx, row.data(), rowBytes);
        if (r != 0 && !(r == SPNG_EOI && y == height - 1)) return fail(r, "PNG row");
    }
    spng_ctx_free(ctx);
    if (std::fflush(file) != 0) {
        *error = std::string("cannot write the file: ") + std::strerror(errno);
        return false;
    }
    return true;
}

namespace {

// Little-endian TIFF builder: an IFD whose out-of-line values follow it, computed up front.
struct TiffEntry {
    std::uint16_t tag;
    std::uint16_t type;  // 2 ASCII, 3 SHORT, 4 LONG, 5 RATIONAL, 7 UNDEFINED
    std::uint32_t count;
    std::vector<std::uint8_t> value;  // little-endian bytes
};

void le16(std::vector<std::uint8_t>& b, std::uint32_t v) {
    b.push_back(static_cast<std::uint8_t>(v));
    b.push_back(static_cast<std::uint8_t>(v >> 8));
}
void le32(std::vector<std::uint8_t>& b, std::uint32_t v) {
    for (int s = 0; s < 32; s += 8) b.push_back(static_cast<std::uint8_t>(v >> s));
}

TiffEntry shorts(std::uint16_t tag, std::initializer_list<std::uint32_t> values) {
    TiffEntry e{tag, 3, static_cast<std::uint32_t>(values.size()), {}};
    for (auto v : values) le16(e.value, v);
    return e;
}
TiffEntry longs(std::uint16_t tag, const std::vector<std::uint32_t>& values) {
    TiffEntry e{tag, 4, static_cast<std::uint32_t>(values.size()), {}};
    for (auto v : values) le32(e.value, v);
    return e;
}
TiffEntry ascii(std::uint16_t tag, const std::string& s) {
    TiffEntry e{tag, 2, static_cast<std::uint32_t>(s.size() + 1), {}};
    e.value.assign(s.begin(), s.end());
    e.value.push_back(0);
    return e;
}
TiffEntry rational(std::uint16_t tag, std::uint32_t n, std::uint32_t d) {
    TiffEntry e{tag, 5, 1, {}};
    le32(e.value, n);
    le32(e.value, d);
    return e;
}

}  // namespace

bool writeTiff(std::FILE* file, int width, int height, int bits, const std::vector<std::uint8_t>& icc, const TiffText& text,
               const RowSource& rows, std::string* error) {
    constexpr int kRowsPerStrip = 64;
    const std::uint64_t rowBytes = static_cast<std::uint64_t>(width) * 3 * (bits / 8);
    const int strips = (height + kRowsPerStrip - 1) / kRowsPerStrip;
    if (rowBytes * height > 0xF0000000ull) {
        *error = "the image is too large for a classic TIFF (4 GB)";
        return false;
    }
    std::vector<TiffEntry> entries;
    entries.push_back(longs(256, {static_cast<std::uint32_t>(width)}));
    entries.push_back(longs(257, {static_cast<std::uint32_t>(height)}));
    entries.push_back(shorts(258, {static_cast<std::uint32_t>(bits), static_cast<std::uint32_t>(bits), static_cast<std::uint32_t>(bits)}));
    entries.push_back(shorts(259, {1}));  // no compression
    entries.push_back(shorts(262, {2}));  // RGB
    if (!text.make.empty()) entries.push_back(ascii(271, text.make));
    if (!text.model.empty()) entries.push_back(ascii(272, text.model));
    entries.push_back(longs(273, std::vector<std::uint32_t>(static_cast<std::size_t>(strips), 0)));  // patched below
    entries.push_back(shorts(274, {1}));
    entries.push_back(shorts(277, {3}));
    entries.push_back(longs(278, {static_cast<std::uint32_t>(kRowsPerStrip)}));
    std::vector<std::uint32_t> counts;
    for (int s = 0; s < strips; ++s)
        counts.push_back(static_cast<std::uint32_t>(rowBytes * std::min(kRowsPerStrip, height - s * kRowsPerStrip)));
    entries.push_back(longs(279, counts));
    entries.push_back(rational(282, 72, 1));
    entries.push_back(rational(283, 72, 1));
    entries.push_back(shorts(284, {1}));
    entries.push_back(shorts(296, {2}));
    if (!text.software.empty()) entries.push_back(ascii(305, text.software));
    if (!text.dateTime.empty()) entries.push_back(ascii(306, text.dateTime));
    if (!text.artist.empty()) entries.push_back(ascii(315, text.artist));
    if (!text.copyright.empty()) entries.push_back(ascii(33432, text.copyright));
    if (!icc.empty()) entries.push_back(TiffEntry{34675, 7, static_cast<std::uint32_t>(icc.size()), icc});

    // Layout: header (8) | IFD | out-of-line values | strips.
    const std::uint32_t ifdStart = 8;
    const std::uint32_t ifdSize = 2 + 12 * static_cast<std::uint32_t>(entries.size()) + 4;
    std::uint32_t extra = ifdStart + ifdSize;
    std::vector<std::uint32_t> valueAt(entries.size(), 0);
    for (std::size_t i = 0; i < entries.size(); ++i) {
        if (entries[i].value.size() > 4) {
            valueAt[i] = extra;
            extra += static_cast<std::uint32_t>((entries[i].value.size() + 1) & ~std::size_t{1});
        }
    }
    std::uint32_t at = extra;
    for (auto& e : entries) {
        if (e.tag != 273) continue;
        e.value.clear();
        for (int s = 0; s < strips; ++s) {
            le32(e.value, at);
            at += counts[static_cast<std::size_t>(s)];
        }
    }
    std::vector<std::uint8_t> head = {'I', 'I', 42, 0};
    le32(head, ifdStart);
    le16(head, static_cast<std::uint32_t>(entries.size()));
    for (std::size_t i = 0; i < entries.size(); ++i) {
        const TiffEntry& e = entries[i];
        le16(head, e.tag);
        le16(head, e.type);
        le32(head, e.count);
        if (e.value.size() > 4) {
            le32(head, valueAt[i]);
        } else {
            std::vector<std::uint8_t> inl = e.value;
            inl.resize(4, 0);
            head.insert(head.end(), inl.begin(), inl.end());
        }
    }
    le32(head, 0);  // no next IFD
    for (std::size_t i = 0; i < entries.size(); ++i) {
        if (entries[i].value.size() <= 4) continue;
        head.insert(head.end(), entries[i].value.begin(), entries[i].value.end());
        if (entries[i].value.size() % 2 != 0) head.push_back(0);
    }
    if (std::fwrite(head.data(), 1, head.size(), file) != head.size()) {
        *error = std::string("cannot write the file: ") + std::strerror(errno);
        return false;
    }
    std::vector<std::uint8_t> row(static_cast<std::size_t>(rowBytes));
    for (int y = 0; y < height; ++y) {
        rows(y, row.data());  // 16 bit: host order, little-endian on every supported CPU
        if (std::fwrite(row.data(), 1, row.size(), file) != row.size()) {
            *error = std::string("cannot write the file: ") + std::strerror(errno);
            return false;
        }
    }
    if (std::fflush(file) != 0) {
        *error = std::string("cannot write the file: ") + std::strerror(errno);
        return false;
    }
    return true;
}

}  // namespace lo::output
