# zstd (vendored)

- Version: 1.5.7 — https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz
  (SHA-256 `eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3`)
- Copied unchanged: `lib/common`, `lib/compress`, `lib/decompress`, `lib/zstd.h`,
  `lib/zstd_errors.h`, `LICENSE`, `COPYING`. Removed: the x86-64 assembly file
  (`huf_decompress_amd64.S`; built with `ZSTD_DISABLE_ASM`) and Buck files.
- Licence: zstd is dual-licensed BSD-3-Clause (`LICENSE`) or GPL-2.0 (`COPYING`).
  LineOver uses it under the **BSD** licence (rule R10).
- Use: compresses spilled tiles (half floats) in `src/tile_store.cpp`.
