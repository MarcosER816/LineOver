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
import json
import sys
from pathlib import Path

import cv2
import numpy as np

import detail_reference as detail
import lensfun_reference
import lut_reference as lut
import reference

REPO_ROOT = Path(__file__).resolve().parents[2]
GOLDEN_DIR = REPO_ROOT / "testdata" / "golden"
PHOTO_DIR = REPO_ROOT / "testdata" / "photos"

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
        "exposure_test/minus1_srgb_display_f32.npy": reference.gpu_display(
            lambda im: reference.exposure_test(im, -1.0), pattern_f32
        ),
    }
    outputs.update(light_outputs())
    outputs.update(color_outputs())
    outputs.update(grade_outputs())
    outputs.update(photo_outputs())
    outputs.update(geometry_outputs())
    outputs.update(lensfun_outputs())
    outputs.update(detail_outputs())
    outputs.update(lut_outputs())
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
        outputs[f"light/{name}_2_srgb_display_f32.npy"] = reference.gpu_display(
            lambda im, op=op, params=settings[1]: op(im, *params), scene
        )
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
        outputs[f"colorops/{name}_gpu_srgb_display_f32.npy"] = reference.gpu_display(
            lambda im, op=op, params=middle: op(im, *params), image
        )
    # Posterize and Threshold jump at step edges: their GPU goldens are computed from the
    # float16 values the GPU texture holds, on cases with every pixel well away from a step
    # (reference.discontinuity_margin), so float32 GPU arithmetic cannot flip a pixel.
    for name, (input_name, params) in COLOR_GPU_DISCONTINUOUS.items():
        image = (chart if input_name == "chart" else scene).astype(np.float16).astype(np.float32)
        out = reference.COLOR_OPS[name](image, *params)
        outputs[f"colorops/{name}_gpu_srgb_display_f32.npy"] = reference.working_to_display(reference.f16(out), "srgb")
    return outputs


GRADE_SCENE_OPS = ("curves", "parametric_curve", "levels", "color_balance")


def grade_outputs() -> dict[str, np.ndarray]:
    """Stage 7: Curves and grading at three settings each, GPU display goldens (middle).

    Tone ops run on the HDR scene, colour ops on the OKLCh chart.
    """
    scene = reference.scene_rec2020()
    chart = reference.colors_rec2020()
    outputs = {}
    for name, settings in reference.GRADE_SETTINGS.items():
        op = reference.GRADE_OPS[name]
        image = scene if name in GRADE_SCENE_OPS else chart
        for index, params in enumerate(settings, start=1):
            outputs[f"grade/{name}_{index}_f32.npy"] = op(image, params)
        outputs[f"grade/{name}_gpu_srgb_display_f32.npy"] = reference.gpu_display(
            lambda im, op=op, params=settings[1]: op(im, params), image
        )
    return outputs


def photo_outputs() -> dict[str, np.ndarray]:
    """Real photos (testdata/photos): 32 x 32 block means of each photo in the working
    space (the device's full-resolution decode is compared with them, and they are the
    96 x 128 inputs of the ctest / GPU cases), block means of the checklist edits run at
    full resolution, and each edit on the 96 x 128 photo (working + sRGB display)."""
    outputs = {}
    small = {}
    for name, file in reference.PHOTOS.items():
        full = reference.photo_rec2020(PHOTO_DIR / file)
        small[name] = reference.block_mean(full)
        outputs[f"photos/{name}_rec2020_f32.npy"] = small[name]
        for edit, (photo, fn) in reference.PHOTO_EDITS.items():
            if photo == name:
                outputs[f"photos/{edit}_full_f32.npy"] = reference.block_mean(reference.pointwise_chunked(fn, full))
        del full
    for edit, (photo, fn) in {**reference.PHOTO_EDITS, **reference.PHOTO_REGION_EDITS}.items():
        out = fn(small[photo]).astype(np.float32)
        outputs[f"photos/{edit}_f32.npy"] = out
        outputs[f"photos/{edit}_gpu_srgb_display_f32.npy"] = reference.gpu_display(fn, small[photo])
    return outputs


def geometry_outputs() -> dict:
    """Stage 9: warps vs OpenCV (warpPerspective / remap, bicubic), vignetting, homography fixtures.

    geometry/<case>.graph.json is the graph (one geometry op) the engine runs for each case.
    """
    pattern = reference.srgb8_to_working(reference.test_pattern_rgba8())
    scene = reference.scene_rec2020()
    chart = reference.colors_rec2020()
    reference.geometry_self_check(pattern, scene, chart)
    outputs = {}
    for name, input_name, image, w, h, matrix, extra in reference.geometry_cases(pattern, scene, chart):
        op = reference.geometry_op(matrix, w, h, extra)
        op["sourceWidth"] = float(image.shape[1])
        op["sourceHeight"] = float(image.shape[0])
        graph = {"schemaVersion": 1, "layers": [{"id": "background", "ops": [op]}]}
        # The graph the engine reads for this case (input: input/<input>_rec2020_f32.npy).
        outputs[f"geometry/{name}.graph.json"] = json.dumps(graph, separators=(",", ":")) + "\n"

        def warp(im, matrix=matrix, w=w, h=h, extra=extra):
            return reference.geometry_warp(im, matrix, w, h, extra.get("k1", 0.0), extra.get("k2", 0.0),
                                           extra.get("caRed", 0.0), extra.get("caBlue", 0.0),
                                           tuple(extra.get("lensDistortion", (0.0,) * 4)),
                                           tuple(extra.get("lensTcaRed", (0.0,) * 3)),
                                           tuple(extra.get("lensTcaBlue", (0.0,) * 3)))

        outputs[f"geometry/{name}_f32.npy"] = warp(image)
        outputs[f"geometry/{name}_gpu_srgb_display_f32.npy"] = reference.gpu_display(warp, image)
    outputs["geometry/homographies.json"] = json.dumps({"cases": reference.homography_fixtures()}, indent=1) + "\n"
    for index, (amount, mid) in enumerate(reference.VIGNETTING_SETTINGS, start=1):
        outputs[f"geometry/vignetting_{index}_f32.npy"] = reference.vignetting(scene, amount, mid)
    amount, mid = reference.VIGNETTING_SETTINGS[0]
    outputs["geometry/vignetting_gpu_srgb_display_f32.npy"] = reference.gpu_display(lambda im: reference.vignetting(im, amount, mid), scene)
    amount, mid, profile = reference.VIGNETTING_PROFILE
    outputs["geometry/vignetting_profile_f32.npy"] = reference.vignetting(scene, amount, mid, profile)
    outputs["geometry/vignetting_profile_gpu_srgb_display_f32.npy"] = reference.gpu_display(
        lambda im: reference.vignetting(im, amount, mid, profile), scene)
    return outputs


def lensfun_outputs() -> dict:
    """Stage 9: lens profile corrections as the Lensfun library computes them, for the
    fixture lenses (testdata/lensfun/fixture.xml, CC BY-SA 3.0)."""
    golden = lensfun_reference.corrections_golden(REPO_ROOT / "testdata" / "lensfun" / "fixture.xml")
    return {"lensfun/corrections.json": json.dumps(golden, indent=1) + "\n"}


# Stage 10 settings (also in detail_test.cpp and EngineGpuTest).
SHARPEN_SETTINGS = [(80.0, 1.0, 25.0, 0.0, 1.0, False), (150.0, 2.5, 80.0, 40.0, 1.0, False),
                    (60.0, 1.5, 25.0, 60.0, 1.0, True), (120.0, 2.0, 50.0, 30.0, 0.5, False)]
NR_SETTINGS = [(60.0, 50.0, 0.0, 25.0, 1.0), (100.0, 0.0, 60.0, 100.0, 1.0), (80.0, 30.0, 30.0, 60.0, 0.5)]
CLARITY_SETTINGS = [60.0, -50.0]
TEXTURE_SETTINGS = [70.0, -60.0]
DEHAZE_SETTINGS = [100.0, 40.0, -50.0]
VIGNETTE_SETTINGS = [(-60.0, 50.0, 0.0, 50.0, 0.0), (-100.0, 20.0, -80.0, 10.0, 60.0), (50.0, 70.0, 100.0, 80.0, 0.0)]
GRAIN_SETTINGS = [(60.0, 30.0, 40.0, 1.0), (100.0, 100.0, 100.0, 1.0), (60.0, 30.0, 40.0, 0.5)]


def detail_outputs() -> dict:
    """Stage 10: Detail and Effects on the HDR scene (dehaze on a hazy version of it)."""
    scene = reference.scene_rec2020()
    h, w = scene.shape[:2]
    encoded = np.clip(detail.encode(scene[..., :3].astype(np.float64)), 0.0, 1.0)
    hazy = np.ones_like(scene, dtype=np.float64)
    hazy[..., :3] = detail.decode(detail.hazy(encoded))
    hazy = hazy.astype(np.float32)
    out = {"input/hazy_rec2020_f32.npy": hazy}
    f32 = lambda a: a.astype(np.float32)  # noqa: E731
    for i, s in enumerate(SHARPEN_SETTINGS, start=1):
        out[f"detail/sharpen_{i}_f32.npy"] = f32(detail.sharpen(scene, *s[:4], scale=s[4], show_mask=s[5]))
    for i, s in enumerate(NR_SETTINGS, start=1):
        out[f"detail/noise_reduction_{i}_f32.npy"] = f32(detail.noise_reduction(scene, *s[:4], scale=s[4]))
    fields = detail.detail_fields(scene)
    for i, a in enumerate(CLARITY_SETTINGS, start=1):
        out[f"effects/clarity_{i}_f32.npy"] = f32(detail.clarity(scene, a, fields))
    for i, a in enumerate(TEXTURE_SETTINGS, start=1):
        out[f"effects/texture_{i}_f32.npy"] = f32(detail.texture(scene, a, fields))
    haze_fields = detail.detail_fields(hazy)
    for i, a in enumerate(DEHAZE_SETTINGS, start=1):
        out[f"effects/dehaze_{i}_f32.npy"] = f32(detail.dehaze(hazy, a, haze_fields))
    for i, s in enumerate(VIGNETTE_SETTINGS, start=1):
        out[f"effects/vignette_{i}_f32.npy"] = f32(detail.vignette(scene, *s, out_w=w, out_h=h))
    for i, s in enumerate(GRAIN_SETTINGS, start=1):
        sc = s[3]
        out[f"effects/grain_{i}_f32.npy"] = f32(detail.grain(scene, *s[:3], scale=sc))
    # The He 2009 script itself: amount 100 is exactly its recovery (omega 0.95, t0 0.1).
    he_j = np.ones_like(hazy, dtype=np.float64)
    he_j[..., :3] = detail.decode(detail.he.dehaze(np.clip(detail.encode(hazy[..., :3].astype(np.float64)), 0.0, 1.0)))
    assert np.max(np.abs(he_j - out["effects/dehaze_1_f32.npy"])) < 1e-5, "Dehaze 100 must equal He et al."
    # GPU (display transform, float16 textures) for one setting each.
    gpu = {
        "detail/sharpen_gpu": (lambda im: detail.sharpen(im, *SHARPEN_SETTINGS[1][:4]), scene),
        "detail/noise_reduction_gpu": (lambda im: detail.noise_reduction(im, *NR_SETTINGS[0][:4]), scene),
        "effects/clarity_gpu": (lambda im: detail.clarity(im, CLARITY_SETTINGS[0], fields), scene),
        "effects/texture_gpu": (lambda im: detail.texture(im, TEXTURE_SETTINGS[0], fields), scene),
        "effects/dehaze_gpu": (lambda im: detail.dehaze(im, DEHAZE_SETTINGS[0], haze_fields), hazy),
        "effects/vignette_gpu": (lambda im: detail.vignette(im, *VIGNETTE_SETTINGS[0], out_w=w, out_h=h), scene),
        "effects/grain_gpu": (lambda im: detail.grain(im, *GRAIN_SETTINGS[0][:3]), scene),
    }
    for name, (fn, image) in gpu.items():
        out[f"{name}_srgb_display_f32.npy"] = reference.gpu_display(lambda im, fn=fn: f32(fn(im)), image)
    return out


# GPU cases of the step ops: (input, params); see color_outputs.
COLOR_GPU_DISCONTINUOUS = {"posterize": ("chart", (12.0,)), "threshold": ("scene", (0.5,))}


def table_image(table: np.ndarray) -> np.ndarray:
    """A LUT table[b, g, r] as an RGBA float image: row b * N + g, column r, alpha 1."""
    n = table.shape[0]
    out = np.ones((n * n, n, 4), dtype=np.float32)
    out[..., :3] = table.reshape(n * n, n, 3)
    return out


def lut_outputs() -> dict:
    """Stage 11: the original test LUTs (.cube text and tables), the LUT op at two amounts,
    the profiles' tables and the profiles on the chart; GPU display references."""
    chart = reference.colors_rec2020()
    scene = reference.scene_rec2020()
    out = {}
    cubes = lut.fixture_cubes()
    for name, cube in cubes.items():
        text = lut.write_cube(cube)
        parsed = lut.parse_cube(text)
        assert np.max(np.abs(parsed.table - cube.table)) < 1e-6, name
        out[f"luts/{name}.cube"] = text
        out[f"luts/{name}_table_f32.npy"] = table_image(parsed.table)
    warm = lut.parse_cube(out["luts/warm_fade_17.cube"])
    teal = lut.parse_cube(out["luts/teal_amber_33.cube"])
    f32 = lambda a: a.astype(np.float32)  # noqa: E731
    out["luts/warm_fade_17_100_f32.npy"] = f32(lut.apply_lut(chart, warm, 1.0))
    out["luts/teal_amber_33_60_f32.npy"] = f32(lut.apply_lut(scene, teal, 0.6))
    for name in lut.PROFILES:
        table = lut.profile_table(name)
        out[f"profiles/{name}_table_f32.npy"] = table_image(table)
        cube = lut.Cube(table)
        out[f"profiles/{name}_100_f32.npy"] = f32(lut.apply_lut(chart, cube, 1.0))
    out["profiles/flat_50_f32.npy"] = f32(lut.apply_lut(chart, lut.Cube(lut.profile_table("flat")), 0.5))
    # GPU: the tables are RGBA32F 3D textures; the image passes through RGBA16F.
    out["luts/teal_amber_33_gpu_srgb_display_f32.npy"] = reference.gpu_display(
        lambda im: f32(lut.apply_lut(im, teal, 0.6)), scene)
    vivid = lut.Cube(lut.profile_table("vivid"))
    # The scene, not the chart: the chart's light yellow-greens on the sRGB gamut face make the
    # display gamut mapping steep enough to turn one float16 step into more than 3/255.
    out["profiles/vivid_gpu_srgb_display_f32.npy"] = reference.gpu_display(lambda im: f32(lut.apply_lut(im, vivid, 1.0)), scene)
    return out


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
    elif path.suffix in (".json", ".cube"):
        path.write_text(array)
    else:
        raise ValueError(f"unsupported golden file type: {path}")


def read_file(path: Path) -> np.ndarray:
    if path.suffix in (".json", ".cube"):
        return path.read_text()
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
    if isinstance(expected, str):
        return None if actual == expected else "text differs"
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
    reference.grade_self_check()
    outputs = build_outputs()

    if args.check:
        stale = {name: why for name, array in outputs.items() if (why := is_stale(GOLDEN_DIR / name, array))}
        for name, why in stale.items():
            print(f"STALE  {name}: {why}", file=sys.stderr)
        expected_files = set(outputs)
        on_disk = {p.relative_to(GOLDEN_DIR).as_posix() for p in GOLDEN_DIR.rglob("*") if p.suffix in (".png", ".npy", ".json", ".cube")}
        orphans = sorted(on_disk - expected_files)
        for name in orphans:
            print(f"ORPHAN {name}: not produced by generate.py", file=sys.stderr)
        if stale or orphans:
            return 1
        print(f"{len(outputs)} golden files are up to date")
        return 0

    for name, array in outputs.items():
        write_file(GOLDEN_DIR / name, array)
        print(f"wrote {name}  {getattr(array, 'shape', 'json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
