"""Selections (Stage 15): the reference for core/engine-native selection.cpp (ctest selection_test).

A selection is a recipe of operations evaluated on a grid of buffer pixels (the document at a
scale; pixel (i, j) is the document point ((i + 0.5) / scale, (j + 0.5) / scale)) into a soft
mask in 0..1, stored as R16F at the end:

- shapes (rect, ellipse, lasso, polygon) cover 4 x 4 samples per pixel (anti-aliased) or the
  centre only; polygons use the even-odd rule on half-open edges;
- magic wand: pixels whose OKLab distance (x 100, "delta E") to the seed pixel is within the
  tolerance; contiguous ones are a 4-connected flood fill from the seed (cv2.floodFill);
- color range: 1 - (nearest add sample's delta E) / fuzziness, times the subtract samples'
  (delta E / fuzziness), each clamped to 0..1;
- modes: new A, add max(S, A), subtract min(S, 1 - A), intersect min(S, A);
- invert 1 - S; grow / shrink by an exact Euclidean distance transform of S >= 0.5
  (cv2.distanceTransform, DIST_MASK_PRECISE); feather a Gaussian (cv2.GaussianBlur); smooth a
  Gaussian of half the radius, steepened around 0.5; border a band around the edge.
"""

from __future__ import annotations

import cv2
import numpy as np

import reference

SUB = 4  # samples per pixel side when anti-aliased


def sample_offsets(anti_alias: bool) -> np.ndarray:
    return (np.arange(SUB) + 0.5) / SUB if anti_alias else np.array([0.5])


def _coverage(inside, h: int, w: int, scale: float, anti_alias: bool) -> np.ndarray:
    """Mean of inside(doc_x, doc_y) over each pixel's samples."""
    offs = sample_offsets(anti_alias)
    acc = np.zeros((h, w))
    jj, ii = np.mgrid[0:h, 0:w].astype(np.float64)
    for oy in offs:
        for ox in offs:
            acc += inside((ii + ox) / scale, (jj + oy) / scale)
    return acc / (len(offs) ** 2)


def polygon_inside(points):
    pts = np.asarray(points, dtype=np.float64).reshape(-1, 2)

    def inside(x, y):
        odd = np.zeros(x.shape, dtype=bool)
        n = len(pts)
        for k in range(n):
            x0, y0 = pts[k]
            x1, y1 = pts[(k + 1) % n]
            if y0 == y1:
                continue
            crosses = (y0 <= y) != (y1 <= y)
            xc = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
            odd ^= crosses & (xc < x)
        return odd.astype(np.float64)

    return inside


def shape_mask(op: dict, h: int, w: int, scale: float) -> np.ndarray:
    aa = op.get("antiAlias", True)
    kind = op["kind"]
    if kind == "rect":
        x0, x1 = sorted((op["x0"], op["x1"]))
        y0, y1 = sorted((op["y0"], op["y1"]))
        return _coverage(lambda x, y: ((x >= x0) & (x < x1) & (y >= y0) & (y < y1)).astype(np.float64), h, w, scale, aa)
    if kind == "ellipse":
        x0, x1 = sorted((op["x0"], op["x1"]))
        y0, y1 = sorted((op["y0"], op["y1"]))
        cx, cy, rx, ry = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
        if rx <= 0 or ry <= 0:
            return np.zeros((h, w))
        return _coverage(lambda x, y: ((((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2) <= 1.0).astype(np.float64), h, w, scale, aa)
    if kind in ("lasso", "polygon"):
        if len(op["points"]) < 6:
            return np.zeros((h, w))
        return _coverage(polygon_inside(op["points"]), h, w, scale, aa)
    raise ValueError(kind)


def oklab_grid(image: np.ndarray) -> np.ndarray:
    return reference.to_oklab(image[..., :3].astype(np.float64)) * 100.0


def seed_pixel(x: float, y: float, scale: float, h: int, w: int) -> tuple[int, int]:
    return (int(np.clip(np.floor(x * scale), 0, w - 1)), int(np.clip(np.floor(y * scale), 0, h - 1)))


def flood_fill(within: np.ndarray, seed: tuple[int, int], connectivity: int = 4) -> np.ndarray:
    """The connected part of [within] (bool) holding [seed] (x, y): OpenCV's floodFill."""
    img = within.astype(np.uint8)
    h, w = img.shape
    mask = np.zeros((h + 2, w + 2), np.uint8)
    if not img[seed[1], seed[0]]:
        return np.zeros((h, w))
    flags = connectivity | cv2.FLOODFILL_FIXED_RANGE | cv2.FLOODFILL_MASK_ONLY | (1 << 8)
    cv2.floodFill(img, mask, seed, 0, 0, 0, flags)
    return mask[1:-1, 1:-1].astype(np.float64)


def wand_mask(op: dict, lab: np.ndarray, scale: float) -> np.ndarray:
    h, w = lab.shape[:2]
    sx, sy = seed_pixel(op["x"], op["y"], scale, h, w)
    d = np.linalg.norm(lab - lab[sy, sx], axis=-1)
    within = d <= op["tolerance"]
    if op.get("contiguous", True):
        return flood_fill(within, (sx, sy), 4)
    return within.astype(np.float64)


def color_range_mask(op: dict, lab: np.ndarray, scale: float) -> np.ndarray:
    h, w = lab.shape[:2]
    fuzz = max(op["fuzziness"], 1e-6)

    def nearest(points):
        pts = np.asarray(points, dtype=np.float64).reshape(-1, 2)
        best = np.full((h, w), np.inf)
        for x, y in pts:
            sx, sy = seed_pixel(x, y, scale, h, w)
            best = np.minimum(best, np.linalg.norm(lab - lab[sy, sx], axis=-1))
        return best

    add = op.get("add", [])
    v = np.clip(1.0 - nearest(add) / fuzz, 0.0, 1.0) if add else np.zeros((h, w))
    sub = op.get("subtract", [])
    if sub:
        v = v * np.clip(nearest(sub) / fuzz, 0.0, 1.0)
    return v


def combine(s: np.ndarray, a: np.ndarray, mode: str) -> np.ndarray:
    if mode == "add":
        return np.maximum(s, a)
    if mode == "subtract":
        return np.minimum(s, 1.0 - a)
    if mode == "intersect":
        return np.minimum(s, a)
    return a


def distances(selected: np.ndarray) -> np.ndarray:
    """For every pixel, the Euclidean distance (pixel centres) to the nearest pixel that is not
    [selected]; 0 on those (cv2.distanceTransform, exact)."""
    return cv2.distanceTransform(selected.astype(np.uint8), cv2.DIST_L2, cv2.DIST_MASK_PRECISE).astype(np.float64)


def grow(s: np.ndarray, r: float) -> np.ndarray:
    """r > 0 grows, r < 0 shrinks (buffer pixels)."""
    b = s >= 0.5
    if r >= 0:
        d_out = distances(~b)  # outside: distance to the nearest selected pixel
        return np.maximum(s, np.clip(r + 1.0 - d_out, 0.0, 1.0))
    d_in = distances(b)  # inside: distance to the nearest unselected pixel
    return np.minimum(s, np.clip(d_in + r, 0.0, 1.0))


def border(s: np.ndarray, width: float) -> np.ndarray:
    b = s >= 0.5
    d = np.where(b, distances(b), distances(~b))
    return np.clip(width / 2.0 + 1.0 - d, 0.0, 1.0)


def smooth(s: np.ndarray, r: float) -> np.ndarray:
    blurred = cv2.GaussianBlur(s.astype(np.float32), (0, 0), r / 2.0).astype(np.float64) if r > 0 else s
    return np.clip((blurred - 0.5) * 4.0 + 0.5, 0.0, 1.0)


def evaluate(ops: list, image: np.ndarray, scale: float = 1.0, h: int | None = None, w: int | None = None) -> np.ndarray:
    """The selection of [ops] on a grid the size of [image] (RGBA working space, used by the
    wand and color range), rounded to R16F."""
    h = image.shape[0] if h is None else h
    w = image.shape[1] if w is None else w
    lab = None
    s = np.zeros((h, w))
    for op in ops:
        kind = op["kind"]
        mode = op.get("mode", "new")
        if kind in ("rect", "ellipse", "lasso", "polygon"):
            s = combine(s, shape_mask(op, h, w, scale), mode)
        elif kind in ("wand", "color_range"):
            if lab is None:
                lab = oklab_grid(image)
            a = wand_mask(op, lab, scale) if kind == "wand" else color_range_mask(op, lab, scale)
            s = combine(s, a, mode)
        elif kind == "all":
            s = np.ones((h, w))
        elif kind == "invert":
            s = 1.0 - s
        elif kind == "grow":
            s = grow(s, op["px"] * scale)
        elif kind == "feather":
            s = cv2.GaussianBlur(s.astype(np.float32), (0, 0), op["px"] * scale).astype(np.float64) if op["px"] > 0 else s
        elif kind == "smooth":
            s = smooth(s, op["px"] * scale)
        elif kind == "border":
            s = border(s, op["px"] * scale)
        else:
            raise ValueError(kind)
    return s.astype(np.float16).astype(np.float32)


# --- Test inputs ------------------------------------------------------------------------------

def flood_image() -> np.ndarray:
    """A 64 x 48 8-bit grey image with regions, gradients and thin bridges for the flood fill."""
    h, w = 48, 64
    yy, xx = np.mgrid[0:h, 0:w]
    img = ((xx * 3 + yy * 2) % 50).astype(np.int32)
    img[10:30, 8:28] = 120
    img[20, 28:50] = 121  # a one-pixel bridge (4-connected)
    img[12:40, 44:56] = 122
    img[30, 28] = 119  # a diagonal chain off the block's corner: joined only with 8-connectivity
    img[31, 29] = 120
    img[32, 30] = 121
    return np.clip(img, 0, 255).astype(np.uint8)


FLOOD_CASES = [  # (seed x, seed y, low, high, connectivity)
    (15, 15, 2, 2, 4),
    (15, 15, 2, 2, 8),
    (15, 15, 0, 0, 4),
    (3, 40, 10, 5, 4),
    (60, 2, 7, 7, 8),
]


def flood_reference(img: np.ndarray, seed, lo: int, hi: int, connectivity: int) -> np.ndarray:
    """cv2.floodFill with a fixed range around the seed's value (the wand's rule)."""
    h, w = img.shape
    mask = np.zeros((h + 2, w + 2), np.uint8)
    flags = connectivity | cv2.FLOODFILL_FIXED_RANGE | cv2.FLOODFILL_MASK_ONLY | (1 << 8)
    cv2.floodFill(img.copy(), mask, seed, 0, lo, hi, flags)
    return mask[1:-1, 1:-1].astype(np.float32)


def blob() -> np.ndarray:
    """A binary test shape for the distance transform: a rounded blob with a hole and a spur."""
    h, w = 64, 80
    yy, xx = np.mgrid[0:h, 0:w] + 0.5
    s = ((xx - 34.3) ** 2 / 22.1 ** 2 + (yy - 31.7) ** 2 / 18.6 ** 2 <= 1.0)
    s &= (xx - 30.2) ** 2 + (yy - 28.9) ** 2 > 6.3 ** 2
    s |= (np.abs(yy - 46.4) < 1.6) & (xx > 50.2) & (xx < 74.1)
    return s.astype(np.float32)


GROW_RADII = (2.5, 6.0, -1.5, -4.0)

SCENE_OPS = [
    {"kind": "rect", "mode": "new", "x0": 10.3, "y0": 8.7, "x1": 70.6, "y1": 50.2},
    {"kind": "ellipse", "mode": "add", "x0": 60.4, "y0": 30.1, "x1": 118.7, "y1": 88.3},
    {"kind": "lasso", "mode": "subtract", "points": [20.2, 20.6, 50.3, 15.1, 62.7, 44.9, 35.8, 60.3, 18.4, 45.2]},
    {"kind": "polygon", "mode": "add", "antiAlias": False, "points": [90.3, 5.2, 120.6, 10.4, 100.1, 25.7]},
    {"kind": "wand", "mode": "add", "x": 20.4, "y": 80.6, "tolerance": 12.0, "contiguous": True},
    {"kind": "color_range", "mode": "intersect", "fuzziness": 60.0, "add": [100.3, 60.2, 30.6, 70.1], "subtract": [5.3, 5.4]},
    {"kind": "grow", "px": 2.5},
    {"kind": "feather", "px": 1.5},
]

SCENE_TAIL = [  # a second recipe: invert, shrink, smooth, border, a non-contiguous wand
    {"kind": "ellipse", "mode": "new", "x0": 20.3, "y0": 15.6, "x1": 100.8, "y1": 80.2},
    {"kind": "wand", "mode": "intersect", "x": 64.4, "y": 48.3, "tolerance": 25.0, "contiguous": False},
    {"kind": "invert"},
    {"kind": "grow", "px": -2.0},
    {"kind": "border", "px": 4.0},
    {"kind": "smooth", "px": 3.0},
]


def tie_margin(ops: list, image: np.ndarray, scale: float = 1.0) -> float:
    """How close the recipe comes to a tie a compiler could round either way: a wand's delta E
    to its tolerance, or the selection before grow / border to 0.5. Goldens need a margin."""
    h, w = image.shape[:2]
    lab = oklab_grid(image)
    s = np.zeros((h, w))
    margin = np.inf
    for k, op in enumerate(ops):
        kind = op["kind"]
        if kind == "wand":
            sx, sy = seed_pixel(op["x"], op["y"], scale, h, w)
            d = np.linalg.norm(lab - lab[sy, sx], axis=-1)
            margin = min(margin, np.min(np.abs(d - op["tolerance"])))
        if kind in ("grow", "border"):
            margin = min(margin, np.min(np.abs(s - 0.5)[np.abs(s - 0.5) > 0]) if np.any(np.abs(s - 0.5) > 0) else np.inf)
        s = evaluate(ops[: k + 1], image, scale).astype(np.float64)
    return float(margin)
