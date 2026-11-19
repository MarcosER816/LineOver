"""Lens profile corrections exactly as the Lensfun library applies them (Stage 9).

This mirrors lensfun's own arithmetic (libs/lensfun/modifier.cpp, mod-coord.cpp,
mod-subpix.cpp, mod-color.cpp at commit 37238fc): pixel coordinates in units of the focal
length (NormScale), calibration coefficients rescaled from the Hugin coordinate system
(r = 1 at half the short side of the calibration sensor; vignetting: r = 1 at its corner),
undistortion then anti-TCA as a coordinate lookup, devignetting as a gain. LineOver's Kotlin
code converts the same calibration to its own lens model (r = 1 at the image corner, a
Brown-Conrady k1 / k2 fit) independently; `testdata/golden/lensfun/corrections.json` lets
the Kotlin test compare the two. Pixel coordinates here are lensfun's: pixel centres at
integers, the image centre at ((w - 1) / 2, (h - 1) / 2).

No lensfun code is used or shipped; the database fixture is CC BY-SA 3.0 (testdata/lensfun).
"""

import math
import xml.etree.ElementTree as ET

FULL_FRAME_DIAGONAL = math.hypot(36.0, 24.0)

# (lens model, focal, aperture, distance, image width, height, image crop factor, focal interpolation)
CASES = [
    ("FE 24-70mm f/2.8 GM", 24.0, 2.8, 1000.0, 6000, 4000, 1.0, "exact"),
    ("FE 24-70mm f/2.8 GM", 70.0, 2.8, 1000.0, 6000, 4000, 1.0, "exact"),
    ("FE 24-70mm f/2.8 GM", 34.0, None, None, 6000, 4000, 1.0, "linear"),
    ("E 24mm f/1.8 ZA", 24.0, None, None, 6000, 4000, 1.534, "exact"),
    # A full-frame lens on an APS-C body: calibration and image crop factors differ.
    ("FE 24-70mm f/2.8 GM", 38.0, None, None, 6000, 4000, 1.534, "exact"),
    ("Canon EF-M 22mm f/2 STM", 22.0, 2.0, 10.0, 6000, 4000, 1.613, "exact"),
    ("Canon PowerShot G12 & compatibles (Standard)", 6.1, None, None, 3648, 2736, 4.63, "exact"),
]

GRID_X = 9
GRID_Y = 7


def _aspect(text):
    if not text:
        return 1.5
    if ":" in text:
        a, b = text.split(":")
        return float(a) / float(b)
    return float(text)


def load_lens(path, model):
    root = ET.parse(path).getroot()
    for lens in root.findall("lens"):
        if lens.findtext("model") == model:
            cal = lens.find("calibration")
            return {
                "crop": float(lens.findtext("cropfactor")),
                "aspect": _aspect(lens.findtext("aspect-ratio")),
                "distortion": [e.attrib for e in cal.findall("distortion")],
                "tca": [e.attrib for e in cal.findall("tca")],
                "vignetting": [e.attrib for e in cal.findall("vignetting")],
            }
    raise KeyError(model)


DIST_TERMS = {"poly3": ["k1"], "poly5": ["k1", "k2"], "ptlens": ["a", "b", "c"]}
TCA_TERMS = {"linear": ["kr", "kb"], "poly3": ["vr", "vb", "cr", "cb", "br", "bb"]}
TCA_DEFAULTS = {"kr": 1.0, "kb": 1.0, "vr": 1.0, "vb": 1.0}


def _entry(entries, focal, interpolation, names):
    """The calibration at `focal`: exact, or linear between the two calibrated neighbours."""
    if not entries:
        return None
    model = entries[0]["model"]
    rows = sorted(entries, key=lambda e: float(e["focal"]))

    def terms(e):
        return [float(e.get(n, TCA_DEFAULTS.get(n, 0.0))) for n in names[model]]

    for e in rows:
        if abs(float(e["focal"]) - focal) < 1e-9:
            return model, terms(e)
    assert interpolation == "linear", f"no calibration at {focal}"
    lo = max((e for e in rows if float(e["focal"]) < focal), key=lambda e: float(e["focal"]))
    hi = min((e for e in rows if float(e["focal"]) > focal), key=lambda e: float(e["focal"]))
    t = (focal - float(lo["focal"])) / (float(hi["focal"]) - float(lo["focal"]))
    return model, [a + (b - a) * t for a, b in zip(terms(lo), terms(hi))]


def _vignetting(entries, focal, aperture, distance):
    for e in entries:
        if (abs(float(e["focal"]) - focal) < 1e-9 and abs(float(e["aperture"]) - aperture) < 1e-9
                and abs(float(e["distance"]) - distance) < 1e-9):
            return [float(e["k1"]), float(e["k2"]), float(e["k3"])]
    raise KeyError((focal, aperture, distance))


def corrections(lens, focal, aperture, distance, width, height, crop, interpolation):
    """For a grid of output (corrected) pixels: the source position per channel and the
    devignetting gain, as lensfun computes them."""
    norm_scale = FULL_FRAME_DIAGONAL / crop / math.hypot(width, height) / focal
    cx = (width - 1) / 2.0 * norm_scale
    cy = (height - 1) / 2.0 * norm_scale
    hugin_mm = FULL_FRAME_DIAGONAL / lens["crop"] / math.hypot(lens["aspect"], 1.0) / 2.0
    hs = focal / hugin_mm

    dist = _entry(lens["distortion"], focal, interpolation, DIST_TERMS)
    if dist is not None:
        model, t = dist
        if model == "poly3":
            d = 1.0 - t[0]
            coeff = [t[0] * hs ** 2 / d ** 3]
        elif model == "poly5":
            coeff = [t[0] * hs ** 2, t[1] * hs ** 4]
        else:
            d = 1.0 - t[0] - t[1] - t[2]
            coeff = [t[0] * hs ** 3 / d ** 4, t[1] * hs ** 2 / d ** 3, t[2] * hs / d ** 2]
    tca = _entry(lens["tca"], focal, interpolation, TCA_TERMS)
    vig = None
    if aperture is not None and lens["vignetting"]:
        k = _vignetting(lens["vignetting"], focal, aperture, distance)
        hv = focal / (FULL_FRAME_DIAGONAL / lens["crop"] / 2.0)
        vig = [k[0] * hv ** 2, k[1] * hv ** 4, k[2] * hv ** 6]

    def distort(x, y):
        if dist is None:
            return x, y
        model = dist[0]
        r2 = x * x + y * y
        if model == "poly3":
            p = coeff[0] * r2 + 1.0
        elif model == "poly5":
            p = 1.0 + coeff[0] * r2 + coeff[1] * r2 * r2
        else:
            r = math.sqrt(r2)
            p = coeff[0] * r2 * r + coeff[1] * r2 + coeff[2] * r + 1.0
        return x * p, y * p

    def channel(x, y, c):
        if tca is None or c == 1:
            return x, y
        model, t = tca
        if model == "linear":
            k = t[0] if c == 0 else t[1]
            return x * k, y * k
        v, cc, b = (t[0], t[2], t[4]) if c == 0 else (t[1], t[3], t[5])
        cc *= hs
        b *= hs * hs
        r2 = x * x + y * y
        p = b * r2 + cc * math.sqrt(r2) + v
        return x * p, y * p

    points = []
    for j in range(GRID_Y):
        for i in range(GRID_X):
            px = (width - 1) * i / (GRID_X - 1)
            py = (height - 1) * j / (GRID_Y - 1)
            x = px * norm_scale - cx
            y = py * norm_scale - cy
            dx, dy = distort(x, y)
            source = []
            for c in range(3):
                sx, sy = channel(dx, dy, c)
                source.append([(sx + cx) / norm_scale, (sy + cy) / norm_scale])
            gain = 1.0
            if vig is not None:
                r2 = x * x + y * y
                gain = 1.0 / (1.0 + vig[0] * r2 + vig[1] * r2 * r2 + vig[2] * r2 * r2 * r2)
            points.append({"pixel": [px, py], "source": source, "gain": gain})
    return points


def corrections_golden(fixture_path):
    cases = []
    for model, focal, aperture, distance, w, h, crop, interpolation in CASES:
        lens = load_lens(fixture_path, model)
        cases.append({
            "lens": model,
            "focal": focal,
            "aperture": aperture,
            "distance": distance,
            "width": w,
            "height": h,
            "crop": crop,
            "interpolation": interpolation,
            "points": corrections(lens, focal, aperture, distance, w, h, crop, interpolation),
        })
    return {"source": "lensfun 37238fc arithmetic (tools/golden/lensfun_reference.py)", "cases": cases}
