// Tile store: LRU under a memory budget, zstd half-float spill and reload.
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <memory>
#include <thread>
#include <vector>

#include "lineover/color.h"
#include "lineover/tile_store.h"
#include "lo_test.h"

namespace fs = std::filesystem;

namespace {

std::shared_ptr<lo::Tile> makeTile(int w, int h, float seed) {
    auto tile = std::make_shared<lo::Tile>();
    tile->width = w;
    tile->height = h;
    tile->pixels.resize(static_cast<std::size_t>(w) * h * 4);
    for (std::size_t i = 0; i < tile->pixels.size(); ++i) tile->pixels[i] = seed + 0.001f * static_cast<float>(i % 997) - 0.3f;
    return tile;
}

std::string freshDir(const char* name) {
    const fs::path dir = fs::temp_directory_path() / (std::string("lo_tiles_") + name);
    fs::remove_all(dir);
    fs::create_directories(dir);
    return dir.string();
}

std::size_t spillFiles(const std::string& dir) {
    std::size_t n = 0;
    for (const auto& entry : fs::directory_iterator(dir)) n += entry.path().extension() == ".zst";
    return n;
}

bool closeToHalf(const lo::Tile& a, const lo::Tile& b) {
    if (a.width != b.width || a.height != b.height || a.pixels.size() != b.pixels.size()) return false;
    for (std::size_t i = 0; i < a.pixels.size(); ++i) {
        if (lo::color::halfToFloat(lo::color::floatToHalf(a.pixels[i])) != b.pixels[i]) return false;
    }
    return true;
}

}  // namespace

LO_TEST(spill_format_round_trips_at_half_precision) {
    const auto tile = makeTile(512, 300, 2.0f);
    lo::Tile back;
    const auto data = lo::encodeSpill(*tile);
    LO_CHECK(!data.empty() && data.size() < tile->bytes() / 2);  // half floats, then compressed
    LO_CHECK(lo::decodeSpill(data, &back));
    LO_CHECK(closeToHalf(*tile, back));
    auto corrupt = data;
    corrupt[0] ^= 0xFF;
    LO_CHECK(!lo::decodeSpill(corrupt, &back));
}

LO_TEST(tiles_over_the_budget_spill_to_disk_and_reload) {
    const std::string dir = freshDir("budget");
    const std::size_t tileBytes = 512 * 512 * 4 * sizeof(float);  // 4 MiB
    lo::TileStore store(3 * tileBytes, dir);
    std::vector<std::shared_ptr<lo::Tile>> originals;
    for (int i = 0; i < 8; ++i) {
        originals.push_back(makeTile(512, 512, static_cast<float>(i)));
        store.put({0, i, 0}, originals.back());
        LO_CHECK(store.stats().bytesInMemory <= 3 * tileBytes);
    }
    auto stats = store.stats();
    LO_CHECK(stats.tilesInMemory == 3 && stats.tilesSpilled == 5 && stats.spills == 5);
    LO_CHECK(spillFiles(dir) == 5);
    // The oldest tile comes back from disk, equal at half precision.
    const auto first = store.get({0, 0, 0});
    LO_CHECK(first != nullptr && closeToHalf(*originals[0], *first));
    stats = store.stats();
    LO_CHECK(stats.reloads == 1 && stats.tilesInMemory == 3 && stats.bytesInMemory <= 3 * tileBytes);
    LO_CHECK(store.contains({0, 7, 0}) && store.contains({0, 1, 0}) && !store.contains({1, 0, 0}));
    LO_CHECK(store.get({2, 0, 0}) == nullptr);
    store.clear();
    LO_CHECK(spillFiles(dir) == 0 && store.stats().tilesInMemory == 0 && !store.contains({0, 0, 0}));
}

LO_TEST(recently_used_tiles_stay_in_memory) {
    const std::string dir = freshDir("lru");
    const std::size_t tileBytes = 64 * 64 * 4 * sizeof(float);
    lo::TileStore store(2 * tileBytes, dir);
    store.put({0, 0, 0}, makeTile(64, 64, 0));
    store.put({0, 1, 0}, makeTile(64, 64, 1));
    store.get({0, 0, 0});                       // 0 becomes most recent
    store.put({0, 2, 0}, makeTile(64, 64, 2));  // evicts 1, not 0
    const auto reloadsBefore = store.stats().reloads;
    store.get({0, 0, 0});
    LO_CHECK(store.stats().reloads == reloadsBefore);  // 0 was still in memory
    store.get({0, 1, 0});
    LO_CHECK(store.stats().reloads == reloadsBefore + 1);
}

LO_TEST(replacing_a_tile_keeps_accounting_right) {
    const std::string dir = freshDir("replace");
    lo::TileStore store(1 << 30, dir);
    store.put({0, 0, 0}, makeTile(10, 10, 0));
    store.put({0, 0, 0}, makeTile(20, 20, 0));
    const auto stats = store.stats();
    LO_CHECK(stats.tilesInMemory == 1 && stats.bytesInMemory == 20 * 20 * 4 * sizeof(float));
    LO_CHECK(store.get({0, 0, 0})->width == 20);
}

LO_TEST(concurrent_use_is_safe) {
    const std::string dir = freshDir("threads");
    lo::TileStore store(4 * 128 * 128 * 4 * sizeof(float), dir);
    std::vector<std::thread> threads;
    for (int t = 0; t < 4; ++t) {
        threads.emplace_back([&store, t] {
            for (int i = 0; i < 50; ++i) {
                store.put({0, t, i % 10}, makeTile(128, 128, static_cast<float>(i)));
                store.get({0, (t + 1) % 4, (i * 3) % 10});
            }
        });
    }
    for (auto& thread : threads) thread.join();
    LO_CHECK(store.stats().bytesInMemory <= 4 * 128 * 128 * 4 * sizeof(float));
    for (int t = 0; t < 4; ++t)
        for (int i = 0; i < 10; ++i) LO_CHECK(store.get({0, t, i}) != nullptr);
}

int main() { return lo::testing::runAllTests(); }
