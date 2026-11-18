// Tile geometry: the mip pyramid, which tiles a view needs, and EXIF orientation.
//
// Level 0 is the original resolution; level L is downsampled by 2^L (the decoder's
// inSampleSize). Tiles are 512 x 512 in their level's pixel grid. All image coordinates
// are in the *oriented* image (EXIF orientation applied).
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace lo {

constexpr int kTileSize = 512;

struct TileKey {
    int level = 0;
    int tx = 0;
    int ty = 0;
    bool operator==(const TileKey& o) const { return level == o.level && tx == o.tx && ty == o.ty; }
};

struct TileKeyHash {
    std::size_t operator()(const TileKey& k) const {
        return (static_cast<std::size_t>(k.level) << 48) ^ (static_cast<std::size_t>(k.tx) << 24) ^
               static_cast<std::size_t>(k.ty);
    }
};

struct Rect {
    int x = 0;
    int y = 0;
    int w = 0;
    int h = 0;
};

struct LevelInfo {
    int width = 0;
    int height = 0;
    int tilesX = 0;
    int tilesY = 0;
};

// Size and tile grid of `level` for an image of width x height.
LevelInfo levelInfo(int width, int height, int level);

// Number of levels: level count-1 is the first whose long edge fits one tile.
int levelCount(int width, int height);

// Rect of tile (tx, ty) in its level's pixel grid (edge tiles are smaller).
Rect tileRect(const LevelInfo& level, int tx, int ty);

// view = (a*x + c*y + e, b*x + d*y + f) for original-image pixel (x, y).
struct Affine {
    float a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
    // View pixels per original pixel (the transform is a similarity).
    float scale() const;
    bool invert(Affine* out) const;
    void apply(float x, float y, float* vx, float* vy) const;
};

// The level whose pixels are closest to (not smaller than) screen pixels at `scale`
// view pixels per original pixel: floor(log2(1 / scale)), clamped to the pyramid.
int levelForScale(float scale, int levels);

// Tiles of `level` that intersect the view, nearest to the view centre first (so the
// middle of the screen sharpens first).
std::vector<TileKey> visibleTiles(const Affine& imageToView, int viewWidth, int viewHeight, int imageWidth,
                                  int imageHeight, int level);

// EXIF orientation (1..8) ------------------------------------------------------------

// Size of the oriented image of a raw (stored) image.
void orientedSize(int rawWidth, int rawHeight, int orientation, int* width, int* height);

// Maps an oriented pixel to the raw pixel it comes from.
void orientedToRaw(int u, int v, int rawWidth, int rawHeight, int orientation, int* x, int* y);

// The raw rectangle whose pixels make up `oriented` (both in the same pixel grid).
Rect rawRectFor(const Rect& oriented, int rawWidth, int rawHeight, int orientation);

// Pixel formats a decoded region can arrive in (Android Bitmap configs + colour spaces).
enum class PixelFormat : int {
    Rgba8Srgb = 0,             // ARGB_8888, sRGB, straight alpha
    Rgba8DisplayP3 = 1,        // ARGB_8888, Display P3, straight alpha
    RgbaF16LinearExtSrgb = 2,  // RGBA_F16, LINEAR_EXTENDED_SRGB, straight alpha
};

std::size_t bytesPerPixel(PixelFormat format);

// Fills an oriented tile (out: tileW x tileH RGBA float, working space) from a decoded
// raw region. The region covers raw-level pixels [regionX, regionX + regionW) x
// [regionY, regionY + regionH) of a raw level of rawLevelW x rawLevelH; the tile starts
// at oriented-level pixel (tileX, tileY). Out-of-region samples clamp to the edge.
struct RegionSource {
    const std::uint8_t* pixels = nullptr;
    std::size_t stride = 0;  // bytes per row
    PixelFormat format = PixelFormat::Rgba8Srgb;
    int regionX = 0, regionY = 0, regionW = 0, regionH = 0;
    int rawLevelW = 0, rawLevelH = 0;
    int orientation = 1;
};
void regionToTile(const RegionSource& source, int tileX, int tileY, int tileW, int tileH, float* out);

}  // namespace lo
