// Mip pyramid, visible tiles, EXIF orientation and region -> tile conversion.
#include <cmath>
#include <cstdint>
#include <vector>

#include "lineover/color.h"
#include "lineover/tiling.h"
#include "lo_test.h"

LO_TEST(pyramid_of_a_50_megapixel_photo) {
    const int w = 8160, h = 6120;
    LO_CHECK(lo::levelCount(w, h) == 5);  // 8160, 4080, 2040, 1020, 510
    const lo::LevelInfo l0 = lo::levelInfo(w, h, 0);
    LO_CHECK(l0.tilesX == 16 && l0.tilesY == 12);
    const lo::LevelInfo l2 = lo::levelInfo(w, h, 2);
    LO_CHECK(l2.width == 2040 && l2.height == 1530 && l2.tilesX == 4 && l2.tilesY == 3);
    const lo::Rect edge = lo::tileRect(l0, 15, 11);
    LO_CHECK(edge.x == 7680 && edge.w == 480 && edge.y == 5632 && edge.h == 488);
}

LO_TEST(level_for_scale_picks_pixels_no_smaller_than_the_screen) {
    LO_CHECK(lo::levelForScale(1.0f, 5) == 0);
    LO_CHECK(lo::levelForScale(4.0f, 5) == 0);
    LO_CHECK(lo::levelForScale(0.5f, 5) == 1);
    LO_CHECK(lo::levelForScale(0.3f, 5) == 1);
    LO_CHECK(lo::levelForScale(0.176f, 5) == 2);
    LO_CHECK(lo::levelForScale(0.001f, 5) == 4);
}

LO_TEST(visible_tiles_at_100_percent_in_the_middle) {
    // 1440 x 2400 view at 100 % centred on the middle of a 8160 x 6120 photo.
    lo::Affine t;
    t.e = 720.0f - 4080.0f;
    t.f = 1200.0f - 3060.0f;
    const auto tiles = lo::visibleTiles(t, 1440, 2400, 8160, 6120, 0);
    // x: 3360..4800 -> tiles 6..9 ; y: 1860..4260 -> tiles 3..8
    LO_CHECK(tiles.size() == 4 * 6);
    // Nearest the centre (4080, 3060) first: tile (7, 5) covers 3584..4096 x 2560..3072.
    LO_CHECK(tiles.front().tx == 7 && tiles.front().ty == 5);
    for (const auto& k : tiles) LO_CHECK(k.tx >= 6 && k.tx <= 9 && k.ty >= 3 && k.ty <= 8 && k.level == 0);
}

LO_TEST(visible_tiles_of_a_rotated_view_cover_its_bounding_box) {
    lo::Affine t;  // 90 degrees, scale 0.5
    t.a = 0.0f;
    t.b = 0.5f;
    t.c = -0.5f;
    t.d = 0.0f;
    t.e = 1000.0f;
    t.f = 0.0f;
    LO_CHECK(std::fabs(t.scale() - 0.5f) < 1e-6f);
    const auto tiles = lo::visibleTiles(t, 1000, 1000, 4000, 3000, 1);
    LO_CHECK(!tiles.empty());
    for (const auto& k : tiles) LO_CHECK(k.level == 1);
}

LO_TEST(nothing_is_visible_when_the_view_is_off_the_image) {
    lo::Affine t;
    t.e = 50000.0f;
    LO_CHECK(lo::visibleTiles(t, 100, 100, 1000, 1000, 0).empty());
}

LO_TEST(all_eight_exif_orientations_map_corners_correctly) {
    // A 4 x 2 raw image; check where raw (0,0) appears in the oriented image.
    const int w = 4, h = 2;
    struct Case {
        int orientation, ow, oh, u, v;  // oriented position of raw (0, 0)
    };
    const Case cases[] = {{1, 4, 2, 0, 0}, {2, 4, 2, 3, 0}, {3, 4, 2, 3, 1}, {4, 4, 2, 0, 1},
                          {5, 2, 4, 0, 0}, {6, 2, 4, 1, 0}, {7, 2, 4, 1, 3}, {8, 2, 4, 0, 3}};
    for (const Case& c : cases) {
        int ow, oh;
        lo::orientedSize(w, h, c.orientation, &ow, &oh);
        LO_CHECK(ow == c.ow && oh == c.oh);
        int x, y;
        lo::orientedToRaw(c.u, c.v, w, h, c.orientation, &x, &y);
        if (x != 0 || y != 0) std::cerr << "  orientation " << c.orientation << " -> (" << x << "," << y << ")\n";
        LO_CHECK(x == 0 && y == 0);
        // Every oriented pixel maps inside the raw image, and the mapping is a bijection.
        std::vector<int> hits(w * h, 0);
        for (int v = 0; v < oh; ++v)
            for (int u = 0; u < ow; ++u) {
                lo::orientedToRaw(u, v, w, h, c.orientation, &x, &y);
                LO_CHECK(x >= 0 && x < w && y >= 0 && y < h);
                ++hits[y * w + x];
            }
        for (int n : hits) LO_CHECK(n == 1);
    }
}

LO_TEST(raw_rect_for_a_rotated_tile) {
    // Orientation 6 (90 CW) of a 3000 x 2000 raw image: oriented is 2000 x 3000.
    const lo::Rect raw = lo::rawRectFor({0, 0, 512, 512}, 3000, 2000, 6);
    LO_CHECK(raw.x == 0 && raw.w == 512 && raw.y == 2000 - 512 && raw.h == 512);
}

LO_TEST(region_to_tile_orients_and_converts) {
    // 2 x 1 raw region: red, green (sRGB 8-bit). Orientation 6 makes it 1 x 2: red on top.
    const std::uint8_t px[8] = {255, 0, 0, 255, 0, 255, 0, 255};
    lo::RegionSource source;
    source.pixels = px;
    source.stride = 8;
    source.format = lo::PixelFormat::Rgba8Srgb;
    source.regionW = 2;
    source.regionH = 1;
    source.rawLevelW = 2;
    source.rawLevelH = 1;
    source.orientation = 6;
    float out[8];
    lo::regionToTile(source, 0, 0, 1, 2, out);
    float red[4], green[4];
    lo::color::srgb8ToWorking(px, red, 1);
    lo::color::srgb8ToWorking(px + 4, green, 1);
    for (int c = 0; c < 4; ++c) {
        LO_CHECK(out[c] == red[c]);
        LO_CHECK(out[4 + c] == green[c]);
    }
}

LO_TEST(premultiplied_pixels_are_unpremultiplied_before_conversion) {
    // 50 % alpha, premultiplied encoded red 128 -> straight 255.
    const std::uint8_t px[4] = {128, 0, 0, 128};
    lo::RegionSource source;
    source.pixels = px;
    source.stride = 4;
    source.regionW = source.regionH = source.rawLevelW = source.rawLevelH = 1;
    source.premultiplied = true;
    float out[4];
    lo::regionToTile(source, 0, 0, 1, 1, out);
    const std::uint8_t straight[4] = {255, 0, 0, 128};
    float expected[4];
    lo::color::srgb8ToWorking(straight, expected, 1);
    for (int c = 0; c < 4; ++c) LO_CHECK(out[c] == expected[c]);
}

int main() { return lo::testing::runAllTests(); }
