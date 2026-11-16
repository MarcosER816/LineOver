#!/usr/bin/env python3
"""Generates the golden reference images in testdata/golden.

The Python code in this directory is the reference implementation of every image
operation of LineOver. The engine (C++, CPU and GPU) is tested against its output
with GoldenAssert.

    python tools/golden/generate.py            # (re)write testdata/golden
    python tools/golden/generate.py --check    # fail if committed files are stale

Changing a committed golden file needs an explanation in the stage notes (rule R4).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

import reference

REPO_ROOT = Path(__file__).resolve().parents[2]
GOLDEN_DIR = REPO_ROOT / "testdata" / "golden"

# Float files are compared with this tolerance in --check so that a different
# BLAS / colour-science patch release does not make committed files look stale.
FLOAT_CHECK_TOLERANCE = 1e-6


def build_outputs() -> dict[str, np.ndarray]:
    """Returns every golden file as {relative path: array}."""
    pattern_u8 = reference.test_pattern_rgba8()
    pattern_f32 = reference.srgb8_to_working(pattern_u8)

    return {
        # Inputs shared by all operations.
        "input/pattern_rgba8.png": pattern_u8,
        "input/pattern_rgba8_as_f32.npy": (pattern_u8.astype(np.float32) / np.float32(255.0)),
        "input/pattern_rec2020_f32.npy": pattern_f32,
        # Identity op.
        "identity/expected_rgba8.png": reference.identity(pattern_u8),
        "identity/expected_rec2020_f32.npy": reference.identity(pattern_f32),
    }


def write_file(path: Path, array: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.suffix == ".png":
        assert array.dtype == np.uint8 and array.shape[2] == 4
        bgra = cv2.cvtColor(array, cv2.COLOR_RGBA2BGRA)
        if not cv2.imwrite(str(path), bgra, [cv2.IMWRITE_PNG_COMPRESSION, 9]):
            raise OSError(f"cannot write {path}")
    elif path.suffix == ".npy":
        assert array.dtype == np.float32 and array.ndim == 3
        np.save(path, np.ascontiguousarray(array.astype("<f4")))
    else:
        raise ValueError(f"unsupported golden file type: {path}")


def read_file(path: Path) -> np.ndarray:
    if path.suffix == ".png":
        bgra = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
        if bgra is None:
            raise OSError(f"cannot read {path}")
        return cv2.cvtColor(bgra, cv2.COLOR_BGRA2RGBA)
    return np.load(path)


def is_stale(path: Path, expected: np.ndarray) -> str | None:
    """Returns a description of the difference, or None if the file is up to date."""
    if not path.is_file():
        return "missing"
    actual = read_file(path)
    if actual.shape != expected.shape or actual.dtype != expected.dtype:
        return f"shape/dtype {actual.shape} {actual.dtype}, expected {expected.shape} {expected.dtype}"
    if expected.dtype == np.uint8:
        if not np.array_equal(actual, expected):
            return "pixels differ"
    else:
        worst = float(np.max(np.abs(actual.astype(np.float64) - expected.astype(np.float64))))
        if not worst <= FLOAT_CHECK_TOLERANCE:
            return f"max |diff| {worst:g} > {FLOAT_CHECK_TOLERANCE:g}"
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="verify committed files instead of writing them")
    args = parser.parse_args()

    reference.self_check()
    outputs = build_outputs()

    if args.check:
        stale = {name: why for name, array in outputs.items() if (why := is_stale(GOLDEN_DIR / name, array))}
        for name, why in stale.items():
            print(f"STALE  {name}: {why}", file=sys.stderr)
        expected_files = set(outputs)
        on_disk = {p.relative_to(GOLDEN_DIR).as_posix() for p in GOLDEN_DIR.rglob("*") if p.suffix in (".png", ".npy")}
        orphans = sorted(on_disk - expected_files)
        for name in orphans:
            print(f"ORPHAN {name}: not produced by generate.py", file=sys.stderr)
        if stale or orphans:
            return 1
        print(f"{len(outputs)} golden files are up to date")
        return 0

    for name, array in outputs.items():
        write_file(GOLDEN_DIR / name, array)
        print(f"wrote {name}  {array.shape} {array.dtype}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
