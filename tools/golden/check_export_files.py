#!/usr/bin/env python3
"""Checks the PNG and TIFF files ctest output_test wrote (Stage 12) with independent readers:
OpenCV decodes the pixels (8 and 16 bit), Pillow / LittleCMS reads and parses the embedded
ICC profile. The pixels must be within one code of the reference conversion (the C++ and
Python conversions round independently; output_test checks the writers themselves are
exact) and the profile must be LineOver's, byte for byte.

    python3 tools/golden/check_export_files.py build/native-host/export_out
"""

from __future__ import annotations

import io
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageCms

GOLDEN = Path(__file__).resolve().parents[2] / "testdata" / "golden" / "export"
FILES = ["p3_8.png", "srgb_16.png", "rec2020_16.png", "srgb_8.tif", "p3_16.tif", "rec2020_16.tif"]


def main() -> int:
    out_dir = Path(sys.argv[1])
    failures = []
    for name in FILES:
        path = out_dir / name
        stem = path.stem  # e.g. p3_8
        space, bits = stem.rsplit("_", 1)
        expected = np.load(GOLDEN / f"convert_{space}_{bits}_f32.npy")[..., :3]
        bgr = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
        if bgr is None:
            failures.append(f"{name}: OpenCV cannot read it")
            continue
        want_dtype = np.uint8 if bits == "8" else np.uint16
        if bgr.dtype != want_dtype:
            failures.append(f"{name}: {bgr.dtype}, expected {want_dtype}")
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.float64)
        worst = float(np.max(np.abs(rgb - expected)))
        if worst > 1.0:
            failures.append(f"{name}: pixels differ from the reference codes by {worst}")
        with Image.open(path) as im:
            icc = im.info.get("icc_profile")
        if icc is None:
            failures.append(f"{name}: no ICC profile")
            continue
        if icc != (GOLDEN / "icc" / f"{space}.icc").read_bytes():
            failures.append(f"{name}: the ICC profile is not LineOver's {space} profile")
        description = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))).strip()
        print(f"{name}: {bgr.shape[1]}x{bgr.shape[0]} {bgr.dtype}, ICC '{description}', max |diff| {worst}")
    for f in failures:
        print("FAIL", f, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
