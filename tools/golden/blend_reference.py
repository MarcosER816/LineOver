"""Stage 13 layers: reference blend modes and compositor (core/engine-native blend.cpp and
compositor.cpp implement the same, ctest blend_test checks them against these goldens).

Blending follows the W3C Compositing and Blending Level 1 formulas on NON-premultiplied colour:
    ao = as + ab (1 - as)
    co = (as (1 - ab) Cs + as ab B(Cb, Cs) + (1 - as) ab Cb) / ao
B is the blend mode. Modes outside the W3C list use their standard definitions (Linear Burn
= a + b - 1, Linear Dodge = a + b, ...). Non-separable modes use luminosity weights
0.3 / 0.59 / 0.11 with the spec's SetLum / ClipColor / SetSat. The inputs of every mode but
Normal are clamped to 0..1 (HDR values pass through Normal and where a layer is transparent).

The blending space is a document setting: "gamma" (default, like Photoshop: the sRGB curve on
the working Rec. 2020 primaries, odd-symmetric) or "linear" (the working values themselves).
The whole formula runs in that space; the result returns to linear.
"""

from __future__ import annotations

import math

import numpy as np

MODES = [
    "normal", "dissolve", "darken", "multiply", "color_burn", "linear_burn", "darker_color",
    "lighten", "screen", "color_dodge", "linear_dodge", "lighter_color", "overlay", "soft_light",
    "hard_light", "vivid_light", "linear_light", "pin_light", "hard_mix", "difference",
    "exclusion", "subtract", "divide", "hue", "saturation", "color", "luminosity",
]
LUM = np.array([0.3, 0.59, 0.11])

# --- Blending space ---------------------------------------------------------------------------


def encode(x: np.ndarray) -> np.ndarray:
    """Linear -> sRGB-curve encoded, odd-symmetric, values above 1 continue the curve."""
    a = np.abs(x)
    e = np.where(a <= 0.0031308, 12.92 * a, 1.055 * np.maximum(a, 0.0031308) ** (1.0 / 2.4) - 0.055)
    return np.sign(x) * e


def decode(e: np.ndarray) -> np.ndarray:
    a = np.abs(e)
    x = np.where(a <= 0.04045, a / 12.92, ((np.maximum(a, 0.04045) + 0.055) / 1.055) ** 2.4)
    return np.sign(e) * x


# --- Blend modes B(Cb, Cs), inputs in 0..1 ----------------------------------------------------


def _lum(c):
    return c @ LUM


def _clip_color(c):
    lum = _lum(c)[..., None]
    n = c.min(axis=-1, keepdims=True)
    x = c.max(axis=-1, keepdims=True)
    c = np.where(n < 0, lum + (c - lum) * lum / np.where(lum - n == 0, 1, lum - n), c)
    c = np.where(x > 1, lum + (c - lum) * (1 - lum) / np.where(x - lum == 0, 1, x - lum), c)
    return c


def _set_lum(c, lum):
    d = lum - _lum(c)
    return _clip_color(c + d[..., None])


def _sat(c):
    return c.max(axis=-1) - c.min(axis=-1)


def _set_sat(c, s):
    cmax = c.max(axis=-1, keepdims=True)
    cmin = c.min(axis=-1, keepdims=True)
    span = cmax - cmin
    out = np.where(span > 0, (c - cmin) * s[..., None] / np.where(span > 0, span, 1), 0.0)
    return out


def _burn(b, s):
    return np.where(b >= 1, 1.0, np.where(s <= 0, 0.0, 1 - np.minimum(1.0, (1 - b) / np.where(s <= 0, 1, s))))


def _dodge(b, s):
    return np.where(b <= 0, 0.0, np.where(s >= 1, 1.0, np.minimum(1.0, b / np.where(s >= 1, 1, 1 - s))))


def _soft_light(b, s):
    d = np.where(b <= 0.25, ((16 * b - 12) * b + 4) * b, np.sqrt(b))
    return np.where(s <= 0.5, b - (1 - 2 * s) * b * (1 - b), b + (2 * s - 1) * (d - b))


def _hard_light(b, s):
    return np.where(s <= 0.5, b * 2 * s, b + (2 * s - 1) - b * (2 * s - 1))


def blend(mode: str, cb: np.ndarray, cs: np.ndarray) -> np.ndarray:
    """B(Cb, Cs) for RGB arrays (..., 3)."""
    if mode in ("normal", "dissolve"):
        return cs
    b = np.clip(cb, 0, 1)
    s = np.clip(cs, 0, 1)
    if mode == "darken":
        return np.minimum(b, s)
    if mode == "multiply":
        return b * s
    if mode == "color_burn":
        return _burn(b, s)
    if mode == "linear_burn":
        return np.maximum(0.0, b + s - 1)
    if mode == "darker_color":
        return np.where((_lum(s) < _lum(b))[..., None], s, b)
    if mode == "lighten":
        return np.maximum(b, s)
    if mode == "screen":
        return b + s - b * s
    if mode == "color_dodge":
        return _dodge(b, s)
    if mode == "linear_dodge":
        return np.minimum(1.0, b + s)
    if mode == "lighter_color":
        return np.where((_lum(s) > _lum(b))[..., None], s, b)
    if mode == "overlay":
        return _hard_light(s, b)
    if mode == "soft_light":
        return _soft_light(b, s)
    if mode == "hard_light":
        return _hard_light(b, s)
    if mode == "vivid_light":
        return np.where(s <= 0.5, _burn(b, 2 * s), _dodge(b, 2 * s - 1))
    if mode == "linear_light":
        return np.clip(b + 2 * s - 1, 0, 1)
    if mode == "pin_light":
        return np.where(s <= 0.5, np.minimum(b, 2 * s), np.maximum(b, 2 * s - 1))
    if mode == "hard_mix":
        return np.where(b + s >= 1, 1.0, 0.0)
    if mode == "difference":
        return np.abs(b - s)
    if mode == "exclusion":
        return b + s - 2 * b * s
    if mode == "subtract":
        return np.maximum(0.0, b - s)
    if mode == "divide":
        return np.where(s <= 0, np.where(b <= 0, 0.0, 1.0), np.minimum(1.0, b / np.where(s <= 0, 1, s)))
    if mode == "hue":
        return _set_lum(_set_sat(s, _sat(b)), _lum(b))
    if mode == "saturation":
        return _set_lum(_set_sat(b, _sat(s)), _lum(b))
    if mode == "color":
        return _set_lum(s, _lum(b))
    if mode == "luminosity":
        return _set_lum(b, _lum(s))
    raise ValueError(mode)


# --- Dissolve ---------------------------------------------------------------------------------

DISSOLVE_SIZE = 256


def dissolve_table() -> np.ndarray:
    """256 x 256 thresholds 0..255 from an LCG (Numerical Recipes constants), row-major."""
    state = 0x12345678
    out = np.zeros(DISSOLVE_SIZE * DISSOLVE_SIZE, dtype=np.int64)
    for i in range(out.size):
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        out[i] = state >> 24
    return out.reshape(DISSOLVE_SIZE, DISSOLVE_SIZE)


_TABLE = None


def _dissolve_alpha(alpha: np.ndarray, doc_x: np.ndarray, doc_y: np.ndarray) -> np.ndarray:
    global _TABLE
    if _TABLE is None:
        _TABLE = dissolve_table()
    t = (_TABLE[np.floor(doc_y).astype(np.int64) % DISSOLVE_SIZE, np.floor(doc_x).astype(np.int64) % DISSOLVE_SIZE] + 0.5) / 256.0
    return np.where(t < alpha, 1.0, 0.0)


# --- Compositing one layer --------------------------------------------------------------------


def composite(backdrop: np.ndarray, source: np.ndarray, mode: str, amount: float, space: str,
              doc_x: np.ndarray, doc_y: np.ndarray) -> np.ndarray:
    """Source (straight RGBA, working linear) over backdrop with [mode]; [amount] = opacity x
    fill (0..1) scales the source alpha. Both images are RGBA float; returns RGBA."""
    ab = backdrop[..., 3].astype(np.float64)
    a_s = source[..., 3].astype(np.float64) * amount
    if mode == "dissolve":
        a_s = _dissolve_alpha(a_s, doc_x, doc_y)
    cb = backdrop[..., :3].astype(np.float64)
    cs = source[..., :3].astype(np.float64)
    if space == "gamma":
        cb, cs = encode(cb), encode(cs)
    mixed = blend(mode, cb, cs)
    ao = a_s + ab * (1 - a_s)
    num = (a_s * (1 - ab))[..., None] * cs + (a_s * ab)[..., None] * mixed + ((1 - a_s) * ab)[..., None] * cb
    co = np.where(ao[..., None] > 0, num / np.where(ao > 0, ao, 1)[..., None], 0.0)
    if space == "gamma":
        co = decode(co)
    out = np.empty(backdrop.shape, dtype=np.float64)
    out[..., :3] = co
    out[..., 3] = ao
    return out


def lerp_space(before: np.ndarray, after: np.ndarray, k: float, space: str) -> np.ndarray:
    """A pass-through group's opacity: before -> after by k, in the blending space."""
    if k >= 1:
        return after
    out = before.astype(np.float64).copy()
    if space == "gamma":
        out[..., :3] = decode(encode(before[..., :3]) + k * (encode(after[..., :3]) - encode(before[..., :3])))
    else:
        out[..., :3] = before[..., :3] + k * (after[..., :3] - before[..., :3])
    out[..., 3] = before[..., 3] + k * (after[..., 3] - before[..., 3])
    return out


# --- Sampling a placed layer ------------------------------------------------------------------


def sample(image: np.ndarray, source_size, transform, doc_x: np.ndarray, doc_y: np.ndarray) -> np.ndarray:
    """The layer image (straight RGBA, any resolution of a source of [source_size] pixels)
    placed by [transform] (a, b, c, d, e, f: source px -> document px x = a sx + c sy + e,
    y = b sx + d sy + f), sampled bilinearly (premultiplied) at document points; transparent
    outside the source."""
    a, b, c, d, e, f = transform
    det = a * d - b * c
    x = doc_x - e
    y = doc_y - f
    sx = (d * x - c * y) / det
    sy = (-b * x + a * y) / det
    u = sx / source_size[0]
    v = sy / source_size[1]
    h, w = image.shape[:2]
    px = np.clip(u * w - 0.5, 0, w - 1)
    py = np.clip(v * h - 0.5, 0, h - 1)
    x0 = np.floor(px).astype(np.int64)
    y0 = np.floor(py).astype(np.int64)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = (px - x0)[..., None]
    fy = (py - y0)[..., None]
    pm = image.astype(np.float64).copy()
    pm[..., :3] *= pm[..., 3:4]
    top = pm[y0, x0] * (1 - fx) + pm[y0, x1] * fx
    bottom = pm[y1, x0] * (1 - fx) + pm[y1, x1] * fx
    out = top * (1 - fy) + bottom * fy
    alpha = out[..., 3:4]
    out[..., :3] = np.where(alpha > 0, out[..., :3] / np.where(alpha > 0, alpha, 1), 0.0)
    inside = (u >= 0) & (u < 1) & (v >= 0) & (v < 1)
    out[~inside] = 0.0
    return out


# --- The compositor ---------------------------------------------------------------------------


def apply_ops(image: np.ndarray, ops: list) -> np.ndarray:
    """The point ops layers may carry (the engine's buildOps for them)."""
    import reference

    out = image.astype(np.float32)
    for op in ops:
        if op["op"] == "exposure":
            out = reference.exposure(out, op["ev"])
        elif op["op"] == "invert":
            out = reference.invert(out)
        elif op["op"] == "saturation":
            out = reference.saturation(out, op["amount"])
        else:
            raise ValueError(op["op"])
    return out.astype(np.float64)


def render_layers(layers: list, backdrop: np.ndarray, images: dict, space: str, doc_x, doc_y) -> np.ndarray:
    out = backdrop.astype(np.float64)
    for layer in layers:
        if not layer.get("visible", True):
            continue
        kind = layer["type"]
        amount = layer.get("opacity", 100.0) / 100.0 * layer.get("fill", 100.0) / 100.0
        mode = layer.get("blend", "normal")
        if kind == "group":
            if layer.get("isolated", False):
                empty = np.zeros_like(out)
                group = render_layers(layer.get("children", []), empty, images, space, doc_x, doc_y)
                out = composite(out, group, mode, amount, space, doc_x, doc_y)
            else:
                before = out
                out = render_layers(layer.get("children", []), out, images, space, doc_x, doc_y)
                out = lerp_space(before, out, amount, space)
        elif kind in ("raster", "smart"):
            image = images[layer["source"]]
            if kind == "smart" and layer.get("ops"):
                image = apply_ops(image, layer["ops"])
            src = sample(image, layer["sourceSize"], layer["transform"], doc_x, doc_y)
            out = composite(out, src, mode, amount, space, doc_x, doc_y)
        elif kind == "adjustment":
            src = apply_ops(out, layer.get("ops", []))
            src[..., 3] = 1.0
            out = composite(out, src, mode, amount, space, doc_x, doc_y)
        # text / shape (Stages 23 / 24): nothing yet
    return out


def composite_document(background: np.ndarray, layers: list, images: dict, space: str = "gamma",
                       scale: float = 1.0, origin=(0, 0)) -> np.ndarray:
    """[background] is the rendered background in buffer pixels; buffer pixel (i, j) is the
    document point ((ox + i + 0.5) / scale, (oy + j + 0.5) / scale)."""
    h, w = background.shape[:2]
    jj, ii = np.mgrid[0:h, 0:w]
    doc_x = (origin[0] + ii + 0.5) / scale
    doc_y = (origin[1] + jj + 0.5) / scale
    return render_layers(layers, background, images, space, doc_x, doc_y).astype(np.float32)


# --- Test inputs --------------------------------------------------------------------------------

GRID = 17


def grid_values() -> tuple[np.ndarray, np.ndarray]:
    """Backdrop and source colours in the blending space, laid out GRID x (2 GRID): no value is
    0 or 1 and no b + s is 1 or 0.5 times 2, so thresholds never tie (float rounding stays
    away from Hard Mix / Vivid Light / Divide switches)."""
    v = (np.arange(GRID) + 0.3) / GRID
    w = (np.arange(GRID) + 0.55) / GRID
    i = np.arange(GRID)
    cb = np.stack([v[i], v[(i * 7) % GRID], v[(i * 11) % GRID]], axis=-1)  # per row
    cs = np.stack([w[i], w[(i * 5) % GRID], w[(i * 3 + 4) % GRID]], axis=-1)  # per column
    back = np.zeros((GRID, 2 * GRID, 4))
    src = np.zeros((GRID, 2 * GRID, 4))
    back[..., :3] = cb[:, None, :]
    back[..., 3] = 1.0
    src[..., :3] = np.concatenate([cs, cs])[None, :, :]
    src[:, :GRID, 3] = 1.0
    src[:, GRID:, 3] = 0.6
    return back, src


def mode_inputs(space: str) -> tuple[np.ndarray, np.ndarray]:
    """The grid as working-space (linear) RGBA: decoded for the gamma space."""
    back, src = grid_values()
    if space == "gamma":
        back[..., :3] = decode(back[..., :3])
        src[..., :3] = decode(src[..., :3])
    return back.astype(np.float32), src.astype(np.float32)


def mode_output(mode: str, space: str) -> np.ndarray:
    back, src = mode_inputs(space)
    h, w = back.shape[:2]
    jj, ii = np.mgrid[0:h, 0:w]
    return composite(back, src, mode, 1.0, space, ii + 0.5, jj + 0.5).astype(np.float32)


def layer_source() -> np.ndarray:
    """A 40 x 30 colourful layer image with a soft round alpha (straight RGBA, linear)."""
    h, w = 30, 40
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot((xx - w / 2 + 0.5) / (w / 2), (yy - h / 2 + 0.5) / (h / 2))
    out = np.zeros((h, w, 4))
    out[..., 0] = 0.05 + 0.9 * xx / (w - 1)
    out[..., 1] = 0.6 * (0.5 + 0.5 * np.sin(yy / 4.0))
    out[..., 2] = 0.9 - 0.8 * yy / (h - 1)
    out[..., 3] = np.clip(1.2 - r, 0, 1)
    return out.astype(np.float32)


def place(scale: float, degrees: float, tx: float, ty: float) -> list:
    t = math.radians(degrees)
    return [scale * math.cos(t), scale * math.sin(t), -scale * math.sin(t), scale * math.cos(t), tx, ty]


def scene_layers(isolated: bool = True) -> list:
    """The compositor scene (ctest blend_test reads the same layer tree from scene.json).
    Placements keep every pixel centre (full and half scale) clear of a layer's edge: a centre
    exactly on the edge is a tie that floating-point rounding decides differently per compiler."""
    return [
        {"type": "raster", "source": "s1", "sourceSize": [80, 60], "transform": place(1.2, 10.0, 20.0, 12.0),
         "blend": "multiply", "opacity": 80.0, "fill": 90.0},
        {"type": "group", "isolated": False, "opacity": 70.0, "children": [
            {"type": "adjustment", "blend": "screen", "opacity": 60.0, "ops": [{"op": "exposure", "ev": 1.0}]},
            {"type": "raster", "source": "s1", "sourceSize": [80, 60], "transform": place(0.6, -20.0, 70.0, 40.0)},
        ]},
        {"type": "group", "isolated": isolated, "blend": "difference", "fill": 80.0, "children": [
            {"type": "smart", "source": "s1", "sourceSize": [80, 60], "transform": place(0.9, 0.0, 30.0, 30.0),
             "ops": [{"op": "exposure", "ev": -0.5}]},
            {"type": "raster", "source": "s1", "sourceSize": [80, 60], "transform": place(1.0, 0.0, 0.0, 0.0), "visible": False},
            {"type": "adjustment", "ops": [{"op": "invert"}], "opacity": 50.0},
        ]},
        {"type": "text"},
        {"type": "raster", "source": "s1", "sourceSize": [80, 60], "transform": place(0.5, 45.0, 100.37, 50.21), "blend": "color"},
        {"type": "raster", "source": "s1", "sourceSize": [80, 60], "transform": place(0.7, 0.0, 5.13, 60.29), "blend": "soft_light"},
    ]


def self_check() -> None:
    one = np.ones((1, 3))
    zero = np.zeros((1, 3))
    # The brief's edge cases: dodge(0, 1) = 0 and burn(1, 0) = 1 (backdrop first).
    assert np.all(blend("color_dodge", zero, one) == 0)
    assert np.all(blend("color_burn", one, zero) == 1)
    assert np.all(blend("color_dodge", 0.5 * one, one) == 1)
    assert np.all(blend("color_burn", 0.5 * one, zero) == 0)
    assert np.allclose(blend("linear_burn", 0.7 * one, 0.6 * one), 0.3)
    assert np.allclose(blend("linear_dodge", 0.3 * one, 0.4 * one), 0.7)
    # Non-separable modes keep the backdrop's luminosity (Hue / Saturation / Color) or the source's.
    rng = np.random.default_rng(1)
    b = rng.uniform(0.05, 0.95, (64, 3))
    s = rng.uniform(0.05, 0.95, (64, 3))
    for mode in ("hue", "saturation", "color"):
        assert np.allclose(_lum(blend(mode, b, s)), _lum(b), atol=1e-9), mode
    assert np.allclose(_lum(blend("luminosity", b, s)), _lum(s), atol=1e-9)
    # A grey source in Normal at amount 1 replaces the backdrop exactly.
    back, src = mode_inputs("linear")
    out = composite(back, src, "normal", 1.0, "linear", np.zeros(back.shape[:2]), np.zeros(back.shape[:2]))
    assert np.allclose(out[:, :GRID, :3], src[:, :GRID, :3], atol=1e-7)
    assert len(MODES) == 27
