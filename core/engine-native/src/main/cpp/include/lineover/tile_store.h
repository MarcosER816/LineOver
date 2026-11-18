// CPU side of the tile store: float mirrors of decoded tiles (working space RGBA),
// kept under a memory budget with LRU eviction. An evicted tile is spilled to the
// cache directory as zstd-compressed half floats and reloaded on demand, so a 50 MP
// photo never has to fit in memory. Thread-safe.
#pragma once

#include <cstddef>
#include <cstdint>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "lineover/tiling.h"

namespace lo {

struct Tile {
    int width = 0;
    int height = 0;
    std::vector<float> pixels;  // RGBA, width * height * 4
    std::size_t bytes() const { return pixels.size() * sizeof(float); }
};

struct TileStoreStats {
    std::size_t bytesInMemory = 0;
    std::size_t tilesInMemory = 0;
    std::size_t tilesSpilled = 0;
    std::size_t spillBytesOnDisk = 0;
    std::size_t spills = 0;   // evictions written to disk
    std::size_t reloads = 0;  // spilled tiles read back
};

class TileStore {
public:
    // `spillDir` must exist and be private to this store (its *.zst files are owned by it).
    TileStore(std::size_t memoryBudgetBytes, std::string spillDir);
    ~TileStore();
    TileStore(const TileStore&) = delete;
    TileStore& operator=(const TileStore&) = delete;

    void put(const TileKey& key, std::shared_ptr<const Tile> tile);

    // The tile from memory or (reloaded) from disk; null if the store never had it or
    // its spill file is unreadable.
    std::shared_ptr<const Tile> get(const TileKey& key);

    bool contains(const TileKey& key);
    TileStoreStats stats();

    // Forgets every tile and deletes the spill files.
    void clear();

private:
    struct Entry {
        std::shared_ptr<const Tile> tile;  // null when spilled
        bool spilled = false;
        std::size_t spillBytes = 0;
        std::list<TileKey>::iterator lru;  // valid only when in memory
    };

    std::string pathFor(const TileKey& key) const;
    void touch(const TileKey& key, Entry& entry);
    void evictLocked(const TileKey& keep);
    bool spillLocked(const TileKey& key, Entry& entry);
    std::shared_ptr<const Tile> reloadLocked(const TileKey& key, Entry& entry);

    const std::size_t budget_;
    const std::string dir_;
    std::mutex mutex_;
    std::unordered_map<TileKey, Entry, TileKeyHash> entries_;
    std::list<TileKey> lru_;  // front = most recently used
    TileStoreStats stats_;
};

// Spill format helpers (exposed for tests): half floats, zstd-compressed, with a small
// header (magic, width, height).
std::vector<std::uint8_t> encodeSpill(const Tile& tile);
bool decodeSpill(const std::vector<std::uint8_t>& data, Tile* out);

}  // namespace lo
