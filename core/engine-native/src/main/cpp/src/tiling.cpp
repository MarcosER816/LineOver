#include "lineover/tiling.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "lineover/color.h"

namespace lo {

LevelInfo levelInfo(int width, int height, int level) {
    LevelInfo info;
    const int factor = 1 << level;
    info.width = (width + factor - 1) / factor;
    info.height = (height + factor - 1) / factor;
    info.tilesX = (info.width + kTileSize - 1) / kTileSize;
    info.tilesY = (info.height + kTileSize - 1) / kTileSize;
    return info;
}

int levelCount(int width, int height) {
    int levels = 1;
    while (std::max(levelInfo(width, height, levels - 1).width, levelInfo(width, height, levels - 1).height) > kTileSize) {
        ++levels;
    }
    return levels;
}

Rect tileRect(const LevelInfo& level, int tx, int ty) {
    Rect r;
    r.x = tx * kTileSize;
    r.y = ty * kTileSize;
    r.w = std::min(kTileSize, level.width - r.x);
    r.h = std::min(kTileSize, level.height - r.y);
    return r;
}

float Affine::scale() const { return std::sqrt(std::fabs(a * d - b * c)); }

bool Affine::invert(Affine* out) const {
    const float det = a * d - b * c;
    if (std::fabs(det) < 1e-12f) return false;
    out->a = d / det;
    out->b = -b / det;
    out->c = -c / det;
    out->d = a / det;
    out->e = -(out->a * e + out->c * f);
    out->f = -(out->b * e + out->d * f);
    return true;
}

void Affine::apply(float x, float y, float* vx, float* vy) const {
    *vx = a * x + c * y + e;
    *vy = b * x + d * y + f;
}

int levelForScale(float scale, int levels) {
    if (!(scale > 0.0f)) return levels - 1;
    const int level = static_cast<int>(std::floor(std::log2(1.0f / scale)));
    return std::clamp(level, 0, levels - 1);
}

std::vector<TileKey> visibleTiles(const Affine& imageToView, int viewWidth, int viewHeight, int imageWidth,
                                  int imageHeight, int level) {
    std::vector<TileKey> tiles;
    Affine viewToImage;
    if (!imageToView.invert(&viewToImage) || viewWidth <= 0 || viewHeight <= 0) return tiles;
    float minX = 1e30f, minY = 1e30f, maxX = -1e30f, maxY = -1e30f;
    const float corners[4][2] = {{0, 0}, {static_cast<float>(viewWidth), 0}, {0, static_cast<float>(viewHeight)},
                                 {static_cast<float>(viewWidth), static_cast<float>(viewHeight)}};
    for (const auto& corner : corners) {
        float x, y;
        viewToImage.apply(corner[0], corner[1], &x, &y);
        minX = std::min(minX, x);
        minY = std::min(minY, y);
        maxX = std::max(maxX, x);
        maxY = std::max(maxY, y);
    }
    const LevelInfo info = levelInfo(imageWidth, imageHeight, level);
    const float tileSpan = static_cast<float>(kTileSize << level);  // tile size in original pixels
    const int x0 = std::max(0, static_cast<int>(std::floor(minX / tileSpan)));
    const int y0 = std::max(0, static_cast<int>(std::floor(minY / tileSpan)));
    const int x1 = std::min(info.tilesX - 1, static_cast<int>(std::floor(maxX / tileSpan)));
    const int y1 = std::min(info.tilesY - 1, static_cast<int>(std::floor(maxY / tileSpan)));
    if (x1 < x0 || y1 < y0) return tiles;

    float centerX, centerY;
    viewToImage.apply(viewWidth / 2.0f, viewHeight / 2.0f, &centerX, &centerY);
    for (int ty = y0; ty <= y1; ++ty) {
        for (int tx = x0; tx <= x1; ++tx) tiles.push_back({level, tx, ty});
    }
    std::sort(tiles.begin(), tiles.end(), [&](const TileKey& p, const TileKey& q) {
        auto distance = [&](const TileKey& k) {
            const float cx = (k.tx + 0.5f) * tileSpan - centerX;
            const float cy = (k.ty + 0.5f) * tileSpan - centerY;
            return cx * cx + cy * cy;
        };
        return distance(p) < distance(q);
    });
    return tiles;
}

void orientedSize(int rawWidth, int rawHeight, int orientation, int* width, int* height) {
    const bool swaps = orientation >= 5 && orientation <= 8;
    *width = swaps ? rawHeight : rawWidth;
    *height = swaps ? rawWidth : rawHeight;
}

void orientedToRaw(int u, int v, int w, int h, int orientation, int* x, int* y) {
    switch (orientation) {
        case 2: *x = w - 1 - u; *y = v; break;           // mirror horizontal
        case 3: *x = w - 1 - u; *y = h - 1 - v; break;   // rotate 180
        case 4: *x = u; *y = h - 1 - v; break;           // mirror vertical
        case 5: *x = v; *y = u; break;                   // transpose
        case 6: *x = v; *y = h - 1 - u; break;           // rotate 90 clockwise
        case 7: *x = w - 1 - v; *y = h - 1 - u; break;   // transverse
        case 8: *x = w - 1 - v; *y = u; break;           // rotate 270 clockwise
        default: *x = u; *y = v; break;                  // 1 (and unknown): as stored
    }
}

Rect rawRectFor(const Rect& oriented, int rawWidth, int rawHeight, int orientation) {
    int ax, ay, bx, by;
    orientedToRaw(oriented.x, oriented.y, rawWidth, rawHeight, orientation, &ax, &ay);
    orientedToRaw(oriented.x + oriented.w - 1, oriented.y + oriented.h - 1, rawWidth, rawHeight, orientation, &bx, &by);
    Rect raw;
    raw.x = std::min(ax, bx);
    raw.y = std::min(ay, by);
    raw.w = std::abs(bx - ax) + 1;
    raw.h = std::abs(by - ay) + 1;
    return raw;
}

std::size_t bytesPerPixel(PixelFormat format) { return format == PixelFormat::RgbaF16LinearExtSrgb ? 8 : 4; }

void regionToTile(const RegionSource& source, int tileX, int tileY, int tileW, int tileH, float* out) {
    const std::size_t bpp = bytesPerPixel(source.format);
    const std::size_t pixels = static_cast<std::size_t>(tileW) * tileH;
    // Gather the source pixels in tile order (same format), then convert in one pass.
    std::vector<std::uint8_t> staging(pixels * bpp);
    for (int j = 0; j < tileH; ++j) {
        for (int i = 0; i < tileW; ++i) {
            int x, y;
            orientedToRaw(tileX + i, tileY + j, source.rawLevelW, source.rawLevelH, source.orientation, &x, &y);
            const int rx = std::clamp(x - source.regionX, 0, source.regionW - 1);
            const int ry = std::clamp(y - source.regionY, 0, source.regionH - 1);
            std::memcpy(&staging[(static_cast<std::size_t>(j) * tileW + i) * bpp],
                        source.pixels + static_cast<std::size_t>(ry) * source.stride + static_cast<std::size_t>(rx) * bpp, bpp);
        }
    }
    switch (source.format) {
        case PixelFormat::Rgba8Srgb: color::srgb8ToWorking(staging.data(), out, pixels); break;
        case PixelFormat::Rgba8DisplayP3: color::p38ToWorking(staging.data(), out, pixels); break;
        case PixelFormat::RgbaF16LinearExtSrgb: {
            std::vector<std::uint16_t> halves(pixels * 4);
            std::memcpy(halves.data(), staging.data(), staging.size());
            color::linearExtendedSrgbHalfToWorking(halves.data(), out, pixels);
            break;
        }
    }
}

}  // namespace lo
