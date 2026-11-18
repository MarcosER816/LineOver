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
    encoded = reference.color_samples_encoded()
    linear = reference.color_samples_linear()

    outputs = {
        # Inputs shared by all operations.
        "input/pattern_rgba8.png": pattern_u8,
        "input/pattern_rgba8_as_f32.npy": (pattern_u8.astype(np.float32) / np.float32(255.0)),
        "input/pattern_rec2020_f32.npy": pattern_f32,
        # Identity op.
        "identity/expected_rgba8.png": reference.identity(pattern_u8),
        "identity/expected_rec2020_f32.npy": reference.identity(pattern_f32),
        # ExposureTest op (+1.5 stops).
        "exposure_test/expected_rec2020_f32.npy": reference.exposure_test(pattern_f32),
        # Colour pipeline (colour-science): transfer functions, matrices, display transforms.
        "color/encoded_f32.npy": encoded,
        "color/linear_f32.npy": linear,
        "color/srgb_eotf_f32.npy": reference.srgb_eotf(encoded),
        "color/srgb_oetf_f32.npy": reference.srgb_oetf(encoded),
        "color/srgb_to_rec2020_f32.npy": reference.rgb_to_rgb(linear, "srgb", "rec2020"),
        "color/rec2020_to_srgb_f32.npy": reference.rgb_to_rgb(linear, "rec2020", "srgb"),
        "color/p3_to_rec2020_f32.npy": reference.rgb_to_rgb(linear, "p3", "rec2020"),
        "color/rec2020_to_p3_f32.npy": reference.rgb_to_rgb(linear, "rec2020", "p3"),
        "color/working_to_srgb_display_f32.npy": reference.working_to_display(pattern_f32, "srgb"),
        "color/working_to_p3_display_f32.npy": reference.working_to_display(pattern_f32, "p3"),
        # GPU-vs-reference test on the device: ExposureTest(-1) then the sRGB display transform.
        "exposure_test/minus1_srgb_display_f32.npy": reference.working_to_display(
            reference.exposure_test(pattern_f32, -1.0), "srgb"
        ),
    }
    outputs.update(light_outputs())
    outputs.update(color_outputs())
    return outputs


def light_outputs() -> dict[str, np.ndarray]:
    """Stage 5: the Light ops at three settings each, the histogram, GPU display goldens."""
    scene = reference.scene_rec2020()
    outputs = {"input/scene_rec2020_f32.npy": scene}
    for name, settings in reference.LIGHT_SETTINGS.items():
        op = reference.LIGHT_OPS[name]
        for index, params in enumerate(settings, start=1):
            outputs[f"light/{name}_{index}_f32.npy"] = op(scene, *params)
        # GPU vs reference on the device: the middle setting through the sRGB display transform.
        outputs[f"light/{name}_2_srgb_display_f32.npy"] = reference.working_to_display(op(scene, *settings[1]), "srgb")
    outputs["histogram/scene_srgb_hist_f32.npy"] = reference.histogram_srgb(scene)
    return outputs


COLOR_CHART_OPS = ("vibrance", "saturation", "hsl_mixer")


def color_outputs() -> dict[str, np.ndarray]:
    """Stage 6: Color basics at three settings each (Invert: one), GPU display goldens.

    Vibrance, Saturation and HSL run on the OKLCh chart, the others on the HDR scene.
    """
    scene = reference.scene_rec2020()
    chart = reference.colors_rec2020()
    outputs = {"input/colors_rec2020_f32.npy": chart}
    for name, settings in reference.COLOR_SETTINGS.items():
        op = reference.COLOR_OPS[name]
        image = chart if name in COLOR_CHART_OPS else scene
        for index, params in enumerate(settings, start=1):
            outputs[f"colorops/{name}_{index}_f32.npy"] = op(image, *params)
        if name in COLOR_GPU_DISCONTINUOUS:
            continue
        middle = settings[min(1, len(settings) - 1)]
        outputs[f"colorops/{name}_gpu_srgb_display_f32.npy"] = reference.working_to_display(op(image, *middle), "srgb")
    # Posterize and Threshold jump at step edges: their GPU goldens are computed from the
    # float16 values the GPU texture holds, on cases with every pixel well away from a step
    # (reference.discontinuity_margin), so float32 GPU arithmetic cannot flip a pixel.
    for name, (input_name, params) in COLOR_GPU_DISCONTINUOUS.items():
        image = (chart if input_name == "chart" else scene).astype(np.float16).astype(np.float32)
        out = reference.COLOR_OPS[name](image, *params)
        outputs[f"colorops/{name}_gpu_srgb_display_f32.npy"] = reference.working_to_display(out, "srgb")
    return outputs


# GPU cases of the step ops: (input, params); see color_outputs.
COLOR_GPU_DISCONTINUOUS = {"posterize": ("chart", (12.0,)), "threshold": ("scene", (0.5,))}


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
    reference.light_self_check()
    reference.color_self_check()
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
