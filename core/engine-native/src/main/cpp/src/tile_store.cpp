#include "lineover/tile_store.h"

#include <cstdio>
#include <cstring>
#include <utility>

#include "lineover/color.h"
#include "zstd.h"

namespace lo {
namespace {

constexpr std::uint32_t kSpillMagic = 0x4C4F5431;  // "LOT1"
constexpr int kZstdLevel = 1;                       // fast; half floats compress well enough

struct SpillHeader {
    std::uint32_t magic;
    std::uint32_t width;
    std::uint32_t height;
};

bool writeFile(const std::string& path, const std::vector<std::uint8_t>& data) {
    std::FILE* file = std::fopen(path.c_str(), "wb");
    if (file == nullptr) return false;
    const bool ok = std::fwrite(data.data(), 1, data.size(), file) == data.size();
    return std::fclose(file) == 0 && ok;
}

bool readFile(const std::string& path, std::vector<std::uint8_t>* out) {
    std::FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;
    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    bool ok = size >= 0;
    if (ok) {
        out->resize(static_cast<std::size_t>(size));
        ok = std::fread(out->data(), 1, out->size(), file) == out->size();
    }
    std::fclose(file);
    return ok;
}

}  // namespace

std::vector<std::uint8_t> encodeSpill(const Tile& tile) {
    std::vector<std::uint16_t> halves(tile.pixels.size());
    for (std::size_t i = 0; i < halves.size(); ++i) halves[i] = color::floatToHalf(tile.pixels[i]);
    const std::size_t rawBytes = halves.size() * sizeof(std::uint16_t);
    std::vector<std::uint8_t> out(sizeof(SpillHeader) + ZSTD_compressBound(rawBytes));
    const SpillHeader header{kSpillMagic, static_cast<std::uint32_t>(tile.width), static_cast<std::uint32_t>(tile.height)};
    std::memcpy(out.data(), &header, sizeof(header));
    const std::size_t written =
        ZSTD_compress(out.data() + sizeof(header), out.size() - sizeof(header), halves.data(), rawBytes, kZstdLevel);
    if (ZSTD_isError(written)) return {};
    out.resize(sizeof(header) + written);
    return out;
}

bool decodeSpill(const std::vector<std::uint8_t>& data, Tile* out) {
    if (data.size() < sizeof(SpillHeader)) return false;
    SpillHeader header;
    std::memcpy(&header, data.data(), sizeof(header));
    if (header.magic != kSpillMagic || header.width == 0 || header.height == 0 || header.width > 4096 || header.height > 4096) {
        return false;
    }
    const std::size_t values = static_cast<std::size_t>(header.width) * header.height * 4;
    std::vector<std::uint16_t> halves(values);
    const std::size_t read = ZSTD_decompress(halves.data(), values * sizeof(std::uint16_t), data.data() + sizeof(header),
                                             data.size() - sizeof(header));
    if (ZSTD_isError(read) || read != values * sizeof(std::uint16_t)) return false;
    out->width = static_cast<int>(header.width);
    out->height = static_cast<int>(header.height);
    out->pixels.resize(values);
    for (std::size_t i = 0; i < values; ++i) out->pixels[i] = color::halfToFloat(halves[i]);
    return true;
}

TileStore::TileStore(std::size_t memoryBudgetBytes, std::string spillDir)
    : budget_(memoryBudgetBytes), dir_(std::move(spillDir)) {}

TileStore::~TileStore() { clear(); }

std::string TileStore::pathFor(const TileKey& key) const {
    return dir_ + "/tile_" + std::to_string(key.level) + "_" + std::to_string(key.tx) + "_" + std::to_string(key.ty) + ".zst";
}

void TileStore::touch(const TileKey& key, Entry& entry) {
    if (entry.lru != lru_.end()) lru_.erase(entry.lru);
    lru_.push_front(key);
    entry.lru = lru_.begin();
}

void TileStore::put(const TileKey& key, std::shared_ptr<const Tile> tile) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto [it, inserted] = entries_.try_emplace(key);
    Entry& entry = it->second;
    if (inserted) {
        entry.lru = lru_.end();
    } else if (entry.tile) {
        stats_.bytesInMemory -= entry.tile->bytes();
        --stats_.tilesInMemory;
    } else if (entry.spilled) {
        std::remove(pathFor(key).c_str());
        stats_.spillBytesOnDisk -= entry.spillBytes;
        --stats_.tilesSpilled;
        entry.spilled = false;
    }
    stats_.bytesInMemory += tile->bytes();
    ++stats_.tilesInMemory;
    entry.tile = std::move(tile);
    touch(key, entry);
    evictLocked(key);
}

std::shared_ptr<const Tile> TileStore::get(const TileKey& key) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = entries_.find(key);
    if (it == entries_.end()) return nullptr;
    Entry& entry = it->second;
    if (!entry.tile) {
        if (!entry.spilled) return nullptr;
        if (!reloadLocked(key, entry)) return nullptr;
    }
    touch(key, entry);
    std::shared_ptr<const Tile> tile = entry.tile;
    evictLocked(key);
    return tile;
}

bool TileStore::contains(const TileKey& key) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = entries_.find(key);
    return it != entries_.end() && (it->second.tile || it->second.spilled);
}

TileStoreStats TileStore::stats() {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

void TileStore::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& [key, entry] : entries_) {
        if (entry.spilled) std::remove(pathFor(key).c_str());
    }
    entries_.clear();
    lru_.clear();
    const std::size_t spills = stats_.spills;
    const std::size_t reloads = stats_.reloads;
    stats_ = TileStoreStats{};
    stats_.spills = spills;
    stats_.reloads = reloads;
}

void TileStore::evictLocked(const TileKey& keep) {
    while (stats_.bytesInMemory > budget_ && !lru_.empty()) {
        const TileKey victim = lru_.back();
        if (victim == keep) break;  // never evict the tile being used
        Entry& entry = entries_[victim];
        lru_.pop_back();
        entry.lru = lru_.end();
        if (!spillLocked(victim, entry)) {
            // Could not write the spill file (disk full?): drop the tile; it is decoded again when needed.
            stats_.bytesInMemory -= entry.tile->bytes();
            --stats_.tilesInMemory;
            entries_.erase(victim);
        }
    }
}

bool TileStore::spillLocked(const TileKey& key, Entry& entry) {
    const std::vector<std::uint8_t> data = encodeSpill(*entry.tile);
    if (data.empty() || !writeFile(pathFor(key), data)) return false;
    stats_.bytesInMemory -= entry.tile->bytes();
    --stats_.tilesInMemory;
    entry.tile.reset();
    entry.spilled = true;
    entry.spillBytes = data.size();
    stats_.spillBytesOnDisk += data.size();
    ++stats_.tilesSpilled;
    ++stats_.spills;
    return true;
}

std::shared_ptr<const Tile> TileStore::reloadLocked(const TileKey& key, Entry& entry) {
    std::vector<std::uint8_t> data;
    auto tile = std::make_shared<Tile>();
    if (!readFile(pathFor(key), &data) || !decodeSpill(data, tile.get())) return nullptr;
    std::remove(pathFor(key).c_str());
    stats_.spillBytesOnDisk -= entry.spillBytes;
    --stats_.tilesSpilled;
    entry.spilled = false;
    entry.spillBytes = 0;
    entry.tile = tile;
    stats_.bytesInMemory += tile->bytes();
    ++stats_.tilesInMemory;
    ++stats_.reloads;
    return tile;
}

}  // namespace lo
