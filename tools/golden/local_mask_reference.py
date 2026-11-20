"""Local masks (Stage 17): the reference for the mask components of core/engine-native masks.cpp
(ctest local_mask_test).

A local mask is an adjustment layer whose mask is a group of components, evaluated in document
space at buffer pixel centres ((i + 0.5) / scale, ...) and combined in order from 0:

- add: max(M, A); subtract: min(M, 1 - A); intersect: min(M, A) (as selections, Stage 15).
- Each component may be inverted (A -> 1 - A) before it is combined.

Components (A in 0..1):

- linear: 1 at (x0, y0), 0 at (x1, y1), linear along the line between them (the mask gradient of
  Stage 14 from 1 to 0).
- radial: an ellipse centred (cx, cy) with radii (rx, ry) rotated by `angle` degrees; with
  d = |R(-angle) (p - c) / r| and f = feather / 100: 1 for d <= 1 - f, 0 for d >= 1, else
  1 - smoothstep((d - (1 - f)) / f).
- brush: strokes over 0 (value 1 paints, 0 erases), radius, hardness = 1 - feather / 100,
  opacity = flow; the Stage 14 round brush. With auto-mask the coverage is multiplied by the
  colour similarity to each painting stroke's `color` (OKLab x 100 of the pixel under its first
  point, stored when the stroke starts so tiles agree; 1 - dE / 20 clamped, the best stroke)
  smoothed by a colour guided filter of radius max(1, round(radius *
  scale / 4)), eps 1e-3, against sqrt(rgb) (smart_reference).
- color_range: 1 - (smallest delta E to the stored OKLab x 100 colours) / fuzziness, clamped.
- luminance_range: on L = OKLab lightness of the image: ramp(L; lo, hi, smooth) below.
- depth_range: the same ramp on a depth raster (1 = near) sampled bilinearly over the document.
- raster: a model mask sampled bilinearly over the document (pixel centres, edges clamped).

ramp(v; lo, hi, s) = smoothstep((v - lo + s) / s) * (1 - smoothstep((v - hi) / s)) for s > 0,
else 1 inside [lo, hi].
"""

from __future__ import annotations

import numpy as np

import blend_reference as blend
import reference
import smart_reference as smart

AUTO_DE = 20.0


def _ss(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def doc_points(h: int, w: int, scale: float):
    jj, ii = np.mgrid[0:h, 0:w].astype(np.float64)
    return (ii + 0.5) / scale, (jj + 0.5) / scale


def oklab100(rgba: np.ndarray) -> np.ndarray:
    return reference.to_oklab(rgba[..., :3].astype(np.float64)) * 100.0


def ramp(v: np.ndarray, lo: float, hi: float, s: float) -> np.ndarray:
    if s <= 0:
        return ((v >= lo) & (v <= hi)).astype(np.float64)
    return _ss((v - lo + s) / s) * (1.0 - _ss((v - hi) / s))


def bilinear(raster: np.ndarray, doc_x, doc_y, doc_w: float, doc_h: float) -> np.ndarray:
    rh, rw = raster.shape
    fx = np.clip(doc_x * rw / doc_w - 0.5, 0.0, rw - 1.0)
    fy = np.clip(doc_y * rh / doc_h - 0.5, 0.0, rh - 1.0)
    x0 = fx.astype(np.int64)
    y0 = fy.astype(np.int64)
    x1 = np.minimum(x0 + 1, rw - 1)
    y1 = np.minimum(y0 + 1, rh - 1)
    tx, ty = fx - x0, fy - y0
    top = raster[y0, x0] + (raster[y0, x1] - raster[y0, x0]) * tx
    bottom = raster[y1, x0] + (raster[y1, x1] - raster[y1, x0]) * tx
    return top + (bottom - top) * ty


def linear(c: dict, dx, dy) -> np.ndarray:
    gx, gy = c["x1"] - c["x0"], c["y1"] - c["y0"]
    ll = gx * gx + gy * gy
    if ll <= 0:
        return np.ones(dx.shape)
    t = np.clip(((dx - c["x0"]) * gx + (dy - c["y0"]) * gy) / ll, 0.0, 1.0)
    return 1.0 - t


def radial(c: dict, dx, dy) -> np.ndarray:
    a = np.deg2rad(c.get("angle", 0.0))
    ca, sa = np.cos(a), np.sin(a)
    px, py = dx - c["cx"], dy - c["cy"]
    u = (ca * px + sa * py) / max(c["rx"], 1e-9)
    v = (-sa * px + ca * py) / max(c["ry"], 1e-9)
    d = np.sqrt(u * u + v * v)
    f = np.clip(c.get("feather", 50.0) / 100.0, 0.0, 1.0)
    if f <= 0:
        return (d <= 1.0).astype(np.float64)
    return 1.0 - _ss((d - (1.0 - f)) / f)


def brush(c: dict, dx, dy, scale: float, image: np.ndarray | None) -> np.ndarray:
    m = np.zeros(dx.shape)
    for s in c["strokes"]:
        stroke = {"points": s["points"], "radius": s["radius"], "hardness": s.get("hardness", 1.0)}
        cov = blend.stroke_coverage(stroke, dx, dy, scale) * s.get("opacity", 100.0) / 100.0
        m = m + (s.get("value", 1.0) - m) * cov
    if c.get("autoMask") and image is not None:
        lab = oklab100(image)
        sim = np.zeros(m.shape)
        for s in c["strokes"]:
            if s.get("value", 1.0) <= 0.0:
                continue
            de = np.linalg.norm(lab - np.asarray(s["color"], dtype=np.float64), axis=-1)
            sim = np.maximum(sim, np.clip(1.0 - de / AUTO_DE, 0.0, 1.0))
        radius = max(s["radius"] for s in c["strokes"])
        r = max(1, int(round(radius * scale / 4.0)))
        g = smart.guided_matte(sim, smart.guide(image), r, 1e-3)
        m = m * np.clip(g, 0.0, 1.0)
    return m


def color_range(c: dict, image: np.ndarray) -> np.ndarray:
    lab = oklab100(image)
    cols = np.asarray(c["colors"], dtype=np.float64).reshape(-1, 3)
    best = np.full(lab.shape[:2], np.inf)
    for col in cols:
        best = np.minimum(best, np.linalg.norm(lab - col, axis=-1))
    return np.clip(1.0 - best / max(c["fuzziness"], 1e-6), 0.0, 1.0)


def component(c: dict, dx, dy, scale: float, image, rasters: dict) -> np.ndarray:
    k = c["kind"]
    if k == "linear":
        a = linear(c, dx, dy)
    elif k == "radial":
        a = radial(c, dx, dy)
    elif k == "brush":
        a = brush(c, dx, dy, scale, image)
    elif k == "color_range":
        a = color_range(c, image)
    elif k == "luminance_range":
        a = ramp(oklab100(image)[..., 0] / 100.0, c["lo"], c["hi"], c.get("smooth", 0.0))
    elif k == "depth_range":
        a = ramp(bilinear(rasters[c["source"]], dx, dy, c["docWidth"], c["docHeight"]), c["lo"], c["hi"], c.get("smooth", 0.0))
    elif k == "raster":
        a = bilinear(rasters[c["source"]], dx, dy, c["docWidth"], c["docHeight"])
    else:
        raise ValueError(k)
    return 1.0 - a if c.get("invert") else a


def combine(m: np.ndarray, a: np.ndarray, mode: str) -> np.ndarray:
    if mode == "subtract":
        return np.minimum(m, 1.0 - a)
    if mode == "intersect":
        return np.minimum(m, a)
    return np.maximum(m, a)


def group(components: list, image: np.ndarray, scale: float = 1.0, rasters: dict | None = None) -> np.ndarray:
    """The raw mask of [components] on a grid the size of [image] (the layer's input)."""
    h, w = image.shape[:2]
    dx, dy = doc_points(h, w, scale)
    m = np.zeros((h, w))
    for c in components:
        m = combine(m, component(c, dx, dy, scale, image, rasters or {}), c.get("mode", "add"))
    return m


def scene_colors(scene: np.ndarray) -> list:
    """The color range's stored colours: OKLab x 100 of two scene pixels, rounded (as picked)."""
    lab = oklab100(scene)
    return [round(float(v), 2) for p in ((30, 20), (100, 60)) for v in lab[p[1], p[0]]]


def stroke_colors(c: dict, scene: np.ndarray) -> dict:
    """[c] with each stroke's auto-mask colour: OKLab x 100 of the scene pixel under its first point."""
    lab = oklab100(scene)
    h, w = scene.shape[:2]
    out = dict(c)
    out["strokes"] = []
    for s in c["strokes"]:
        i = min(w - 1, max(0, int(np.floor(s["points"][0]))))
        j = min(h - 1, max(0, int(np.floor(s["points"][1]))))
        out["strokes"].append(dict(s, color=[round(float(v), 2) for v in lab[j, i]]))
    return out


# --- Test inputs ------------------------------------------------------------------------------

def depth_raster() -> np.ndarray:
    """A 32 x 24 depth map over the 128 x 96 scene: near (1) at the bottom, far (0) at the top."""
    yy, xx = np.mgrid[0:24, 0:32] + 0.5
    return np.clip(yy / 24.0 + 0.1 * np.sin(xx / 4.0), 0.0, 1.0).astype(np.float32)


def subject_raster() -> np.ndarray:
    yy, xx = np.mgrid[0:24, 0:32] + 0.5
    return (np.hypot((xx - 18.2) / 7.1, (yy - 11.4) / 6.3) <= 1.0).astype(np.float32)


RASTERS = {"depth": depth_raster, "subject": subject_raster}

GENERATORS = {
    "linear": {"kind": "linear", "x0": 20.3, "y0": 10.7, "x1": 90.2, "y1": 70.4},
    "radial": {"kind": "radial", "cx": 60.4, "cy": 45.3, "rx": 40.2, "ry": 25.6, "angle": 30.0, "feather": 60.0},
    "radial_inverted": {"kind": "radial", "cx": 60.4, "cy": 45.3, "rx": 40.2, "ry": 25.6, "angle": -15.0, "feather": 35.0,
                        "invert": True},
    "brush": {"kind": "brush", "strokes": [
        {"points": [20.3, 20.6, 60.4, 30.2, 100.7, 25.1], "radius": 9.3, "hardness": 0.5, "opacity": 80.0, "value": 1.0},
        {"points": [55.2, 22.4, 65.1, 32.6], "radius": 5.2, "hardness": 1.0, "opacity": 100.0, "value": 0.0}]},
    "brush_auto": {"kind": "brush", "autoMask": True, "strokes": [
        {"points": [30.3, 50.6, 70.4, 52.2], "radius": 12.3, "hardness": 0.3, "opacity": 100.0, "value": 1.0}]},
    "color_range": {"kind": "color_range", "fuzziness": 25.0, "colors": [62.3, 12.1, -8.4, 40.2, -5.3, 20.6]},
    "luminance_range": {"kind": "luminance_range", "lo": 0.35, "hi": 0.7, "smooth": 0.12},
    "depth_range": {"kind": "depth_range", "source": "depth", "lo": 0.55, "hi": 1.0, "smooth": 0.15,
                    "docWidth": 128.0, "docHeight": 96.0},
    "raster": {"kind": "raster", "source": "subject", "docWidth": 128.0, "docHeight": 96.0},
}

GROUP = [  # a linear gradient intersected with the subject, plus a radial, minus a brush
    dict(GENERATORS["linear"], mode="add"),
    dict(GENERATORS["raster"], mode="intersect"),
    dict(GENERATORS["radial"], mode="add"),
    dict(GENERATORS["brush"], mode="subtract"),
]
