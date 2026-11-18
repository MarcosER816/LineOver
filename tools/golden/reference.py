"""Reference implementations (NumPy / OpenCV / colour-science) of LineOver image ops.

Conventions
-----------
* Images are H x W x 4 arrays in RGBA order.
* 8-bit images are sRGB-encoded uint8.
* Float images are float32 in the engine working space: linear Rec.2020,
  D65, straight (un-premultiplied) alpha. Values may exceed 1.0.
"""

from __future__ import annotations

import colour
import cv2
import numpy as np

PATTERN_WIDTH = 64
PATTERN_HEIGHT = 48

_SRGB = colour.RGB_COLOURSPACES["sRGB"]
_P3 = colour.RGB_COLOURSPACES["Display P3"]
_REC2020 = colour.RGB_COLOURSPACES["ITU-R BT.2020"]

COLOR_GRID = 16


def test_pattern_rgba8() -> np.ndarray:
    """A small deterministic RGBA test image built from integer arithmetic only.

    Rows 0-11:  horizontal grey ramp 0..255.
    Rows 12-23: eight colour bars (white, yellow, cyan, green, magenta, red, blue, black).
    Rows 24-35: red ramp across, green ramp down, blue checker; alpha ramps 255..3.
    Rows 36-47: pseudo-random texture from a multiplicative hash, opaque.
    """
    h, w = PATTERN_HEIGHT, PATTERN_WIDTH
    y, x = np.mgrid[0:h, 0:w].astype(np.int64)
    image = np.zeros((h, w, 4), dtype=np.uint8)
    image[..., 3] = 255

    ramp = (x * 255 // (w - 1)).astype(np.uint8)
    band = y < 12
    for c in range(3):
        image[..., c][band] = ramp[band]

    bars = np.array(
        [[255, 255, 255], [255, 255, 0], [0, 255, 255], [0, 255, 0], [255, 0, 255], [255, 0, 0], [0, 0, 255], [0, 0, 0]],
        dtype=np.uint8,
    )
    band = (y >= 12) & (y < 24)
    image[..., :3][band] = bars[(x * 8 // w)[band]]

    band = (y >= 24) & (y < 36)
    image[..., 0][band] = ramp[band]
    image[..., 1][band] = ((y - 24) * 255 // 11).astype(np.uint8)[band]
    image[..., 2][band] = (((x // 4 + y // 4) % 2) * 255).astype(np.uint8)[band]
    image[..., 3][band] = (255 - x * 4).astype(np.uint8)[band]

    band = y >= 36
    for c in range(3):
        noise = ((x * 73856093) ^ (y * 19349663) ^ ((c + 1) * 83492791)) % 256
        image[..., c][band] = noise.astype(np.uint8)[band]

    return image


def srgb8_to_working(rgba8: np.ndarray) -> np.ndarray:
    """sRGB-encoded uint8 RGBA -> linear Rec.2020 float32 RGBA (alpha is linear, unchanged)."""
    encoded = rgba8[..., :3].astype(np.float64) / 255.0
    linear_rec2020 = colour.RGB_to_RGB(
        encoded,
        _SRGB,
        _REC2020,
        apply_cctf_decoding=True,
        apply_cctf_encoding=False,
    )
    alpha = rgba8[..., 3:4].astype(np.float64) / 255.0
    return np.concatenate([linear_rec2020, alpha], axis=-1).astype(np.float32)


def working_to_srgb8(rgba_f32: np.ndarray) -> np.ndarray:
    """Linear Rec.2020 float RGBA -> sRGB-encoded uint8 RGBA, clipped to the sRGB gamut."""
    linear_srgb = colour.RGB_to_RGB(
        rgba_f32[..., :3].astype(np.float64),
        _REC2020,
        _SRGB,
        apply_cctf_decoding=False,
        apply_cctf_encoding=False,
    )
    encoded = colour.cctf_encoding(np.clip(linear_srgb, 0.0, 1.0), function="sRGB")
    rgb8 = np.rint(encoded * 255.0)
    alpha8 = np.rint(np.clip(rgba_f32[..., 3:4].astype(np.float64), 0.0, 1.0) * 255.0)
    return np.concatenate([rgb8, alpha8], axis=-1).astype(np.uint8)


def color_samples_encoded() -> np.ndarray:
    """16 x 16 RGBA grid of encoded values in 0..1 covering every channel combination step."""
    n = COLOR_GRID
    y, x = np.mgrid[0:n, 0:n].astype(np.float64)
    rgba = np.empty((n, n, 4))
    rgba[..., 0] = x / (n - 1)
    rgba[..., 1] = y / (n - 1)
    rgba[..., 2] = ((x * 7 + y * 3) % n) / (n - 1)
    rgba[..., 3] = 1.0 - x / (2 * (n - 1))
    return rgba.astype(np.float32)


def color_samples_linear() -> np.ndarray:
    """Linear samples beyond 0..1 (negative and above 1), as wide-gamut / HDR values are."""
    rgba = color_samples_encoded().astype(np.float64)
    rgba[..., :3] = rgba[..., :3] * 2.5 - 0.5
    return rgba.astype(np.float32)


def _with_alpha(rgb: np.ndarray, rgba_in: np.ndarray) -> np.ndarray:
    return np.concatenate([rgb, rgba_in[..., 3:4].astype(np.float64)], axis=-1).astype(np.float32)


def srgb_eotf(rgba: np.ndarray) -> np.ndarray:
    """sRGB / Display P3 transfer: encoded -> linear (0..1 inputs)."""
    return _with_alpha(colour.cctf_decoding(rgba[..., :3].astype(np.float64), function="sRGB"), rgba)


def srgb_oetf(rgba: np.ndarray) -> np.ndarray:
    """sRGB / Display P3 transfer: linear -> encoded (0..1 inputs)."""
    return _with_alpha(colour.cctf_encoding(rgba[..., :3].astype(np.float64), function="sRGB"), rgba)


def rgb_to_rgb(rgba: np.ndarray, source: str, target: str) -> np.ndarray:
    """Linear RGB -> linear RGB between colour spaces (all D65, no adaptation)."""
    spaces = {"srgb": _SRGB, "p3": _P3, "rec2020": _REC2020}
    rgb = colour.RGB_to_RGB(
        rgba[..., :3].astype(np.float64),
        spaces[source],
        spaces[target],
        chromatic_adaptation_transform=None,
        apply_cctf_decoding=False,
        apply_cctf_encoding=False,
    )
    return _with_alpha(rgb, rgba)


# Display gamut mapping: colours outside the display's gamut keep their OKLab lightness
# (clamped to 0..1) and hue and lose chroma until they fit: bisection with a fixed number of
# steps (the GPU display pass and color.cpp run the same steps) along the OKLab line from
# the colour to the grey RGB = L^3 of the same lightness (that grey, not a = b = 0, so that
# white maps exactly to white whatever the OKLab matrices' rounding).
GAMUT_STEPS = 16
GAMUT_EPS = 5e-4


def _display_matrix(display: str) -> np.ndarray:
    target = {"srgb": _SRGB, "p3": _P3}[display]
    return np.array(colour.matrix_RGB_to_RGB(_REC2020, target, chromatic_adaptation_transform=None))


def gamut_map(rgb: np.ndarray, display: str) -> np.ndarray:
    """Linear working RGB -> linear display RGB in 0..1: in-gamut colours unchanged (clipped
    by at most GAMUT_EPS), out-of-gamut colours reduced in OKLab chroma at constant hue."""
    m = _display_matrix(display)
    rgb = rgb.astype(np.float64)
    d = rgb @ m.T
    inside = lambda x: np.all((x >= -GAMUT_EPS) & (x <= 1.0 + GAMUT_EPS), axis=-1)  # noqa: E731
    out = np.clip(d, 0.0, 1.0)
    bad = ~inside(d)
    if np.any(bad):
        lab = to_oklab(rgb[bad])
        lab[:, 0] = np.clip(lab[:, 0], 0.0, 1.0)
        grey = to_oklab(np.repeat(lab[:, :1] ** 3, 3, axis=1))
        lo = np.zeros(len(lab))
        hi = np.ones(len(lab))
        for _ in range(GAMUT_STEPS):
            mid = 0.5 * (lo + hi)
            ok = inside(from_oklab(grey + mid[:, None] * (lab - grey)) @ m.T)
            lo = np.where(ok, mid, lo)
            hi = np.where(ok, hi, mid)
        out[bad] = np.clip(from_oklab(grey + lo[:, None] * (lab - grey)) @ m.T, 0.0, 1.0)
    return out


def working_to_display(rgba_f32: np.ndarray, display: str) -> np.ndarray:
    """Working space -> encoded display values (0..1), gamut-mapped to the display."""
    linear = gamut_map(rgba_f32[..., :3], display)
    encoded = colour.cctf_encoding(linear, function="sRGB")
    alpha = np.clip(rgba_f32[..., 3:4].astype(np.float64), 0.0, 1.0)
    return np.concatenate([encoded, alpha], axis=-1).astype(np.float32)


EXPOSURE_TEST_STOPS = 1.5


def exposure_test(image: np.ndarray, stops: float = EXPOSURE_TEST_STOPS) -> np.ndarray:
    """Reference of the ExposureTest op: RGB x 2^stops, alpha unchanged."""
    out = image.astype(np.float64).copy()
    out[..., :3] *= 2.0**stops
    return out.astype(np.float32)


def identity(image: np.ndarray) -> np.ndarray:
    """Reference of the identity op: the output equals the input."""
    return image.copy()


def self_check() -> None:
    """Sanity checks of the reference code itself; raises AssertionError on failure."""
    pattern = test_pattern_rgba8()
    assert pattern.shape == (PATTERN_HEIGHT, PATTERN_WIDTH, 4) and pattern.dtype == np.uint8
    assert pattern.min() == 0 and pattern.max() == 255

    working = srgb8_to_working(pattern)
    assert working.dtype == np.float32
    # White stays white (to the precision of the published primaries, which are
    # rounded) and the conversion is invertible to the 8-bit grid.
    white = srgb8_to_working(np.full((1, 1, 4), 255, dtype=np.uint8))
    assert np.allclose(white, 1.0, atol=2e-4), white
    assert np.array_equal(working_to_srgb8(working), pattern)


# ---------------------------------------------------------------------------
# Stage 5: Light tools.
#
# All tone ops work on the Rec.2020 luminance Y and scale RGB by Y'/Y, so the chroma
# ratios R:G:B are preserved (alpha unchanged). Pixels with Y <= LUMA_EPS are left as
# they are. The engine applies them in a fixed order:
#     highlights_shadows -> exposure -> whites -> blacks -> contrast -> brightness
# ---------------------------------------------------------------------------

SCENE_WIDTH = 128
SCENE_HEIGHT = 96

LUMA = np.array([0.2627, 0.6780, 0.0593])
LUMA_EPS = 1e-6
MID_GREY = 0.18

CONTRAST_STRENGTH = 0.75
CONTRAST_WIDTH = 2.5  # stops: where the S-curve starts to flatten
WHITE_POINT = 1.0
BLACK_POINT = 0.002

GUIDED_RADIUS_FRACTION = 0.015  # of the long edge
GUIDED_EPS = 0.01
TONE_STOPS = 1.5  # Highlights / Shadows at +-100
HIGHLIGHT_RANGE = 2.5  # stops above mid grey where the highlight mask reaches 1
SHADOW_RANGE = 5.0  # stops below mid grey where the shadow mask reaches 1

HISTOGRAM_BINS = 256


def _hash01(x: np.ndarray, y: np.ndarray, seed: int) -> np.ndarray:
    """Deterministic pseudo-random values in [0, 1) from integer coordinates."""
    h = (x.astype(np.int64) * 73856093) ^ (y.astype(np.int64) * 19349663) ^ (seed * 83492791)
    return (h % 1024).astype(np.float64) / 1024.0


def scene_rec2020() -> np.ndarray:
    """A small procedural HDR scene in the working space (linear Rec.2020, alpha 1).

    Rows 0-39:  sky, a log ramp 0.5 .. 4 to the right with a sun disk (8.0) at (96, 16).
    Rows 40-63: mid-grey patches, saturated colour patches and a hard dark/bright edge.
    Rows 64-95: dark textured foreground, a log ramp 0.0005 .. 0.05 plus noise.
    """
    h, w = SCENE_HEIGHT, SCENE_WIDTH
    y, x = np.mgrid[0:h, 0:w]
    rgb = np.zeros((h, w, 3))

    sky = y < 40
    level = 0.5 * 2.0 ** (3.0 * x / (w - 1)) * (1.0 - 0.3 * y / 40.0) * (1.0 + 0.05 * (_hash01(x, y, 2) - 0.5))
    sky_rgb = np.stack([level * 0.8, level * 0.9, level * 1.2], axis=-1)
    rgb[sky] = sky_rgb[sky]
    sun = (x - 96) ** 2 + (y - 16) ** 2 <= 36
    rgb[sun] = 8.0

    mid = (y >= 40) & (y < 64)
    patches = np.array(
        [
            [0.18, 0.18, 0.18],
            [0.55, 0.08, 0.055],
            [0.06, 0.40, 0.07],
            [0.05, 0.07, 0.50],
            [0.18, 0.18, 0.18],
            [0.70, 0.55, 0.10],
            [0.02, 0.02, 0.02],
            [0.90, 0.90, 0.90],
        ]
    )
    patch_rgb = patches[np.minimum(x * 8 // w, 7)]
    rgb[mid] = patch_rgb[mid]

    ground = y >= 64
    base = 0.0005 * 100.0 ** (x / (w - 1))
    texture = 1.0 + 0.6 * (_hash01(x, y, 1) - 0.5)
    g = base * texture
    ground_rgb = np.stack([g * 1.1, g, g * 0.8], axis=-1)
    rgb[ground] = ground_rgb[ground]

    rgba = np.concatenate([rgb, np.ones((h, w, 1))], axis=-1)
    return rgba.astype(np.float32)


def luminance(rgb: np.ndarray) -> np.ndarray:
    return rgb @ LUMA


def _apply_luminance(image: np.ndarray, tone) -> np.ndarray:
    """Maps Y -> tone(Y) and scales RGB by the ratio (alpha unchanged)."""
    out = image.astype(np.float64).copy()
    y = luminance(out[..., :3])
    valid = y > LUMA_EPS
    safe = np.where(valid, y, 1.0)
    ratio = np.where(valid, tone(safe) / safe, 1.0)
    out[..., :3] *= ratio[..., None]
    return out.astype(np.float32)


def _log_tone(shift):
    """A tone function working in stops relative to mid grey: l' = l + shift(l)."""

    def tone(y):
        l = np.log2(y / MID_GREY)
        return MID_GREY * np.exp2(l + shift(l))

    return tone


def exposure(image: np.ndarray, ev: float) -> np.ndarray:
    """Exposure: RGB x 2^ev, ev in [-5, 5]."""
    out = image.astype(np.float64).copy()
    out[..., :3] *= 2.0**ev
    return out.astype(np.float32)


def contrast(image: np.ndarray, amount: float) -> np.ndarray:
    """Contrast in [-100, 100]: an S-curve in log space around mid grey (monotonic)."""
    s = amount / 100.0
    return _apply_luminance(
        image, _log_tone(lambda l: s * CONTRAST_STRENGTH * l / (1.0 + (l / CONTRAST_WIDTH) ** 2))
    )


def brightness(image: np.ndarray, amount: float) -> np.ndarray:
    """Brightness in [-100, 100]: a midtone gamma Y^g on 0..1, continued linearly above 1."""
    g = 2.0 ** (-amount / 100.0)
    return _apply_luminance(image, lambda y: np.where(y <= 1.0, np.power(y, g), 1.0 + g * (y - 1.0)))


def _endpoint(amount: float, point: float):
    """Shifts the stops beyond `point` by amount/100 stops, easing in from mid grey."""
    lp = np.log2(point / MID_GREY)
    k = amount / 100.0
    return _log_tone(lambda l: k * np.clip(l / lp, 0.0, 1.0) ** 2)


def whites(image: np.ndarray, amount: float) -> np.ndarray:
    """Whites in [-100, 100]: moves the white endpoint (1.0) by up to +-1 stop."""
    return _apply_luminance(image, _endpoint(amount, WHITE_POINT))


def blacks(image: np.ndarray, amount: float) -> np.ndarray:
    """Blacks in [-100, 100]: moves the black endpoint (0.002) by up to +-1 stop."""
    return _apply_luminance(image, _endpoint(amount, BLACK_POINT))


def guided_radius(width: int, height: int) -> int:
    return max(1, int(np.floor(GUIDED_RADIUS_FRACTION * max(width, height) + 0.5)))


def _box_mean(a: np.ndarray, r: int) -> np.ndarray:
    """Mean over the (2r+1)^2 window clipped to the image (count-normalised)."""
    h, w = a.shape
    s = np.zeros((h + 1, w + 1))
    s[1:, 1:] = np.cumsum(np.cumsum(a, axis=0), axis=1)
    y, x = np.mgrid[0:h, 0:w]
    y0, y1 = np.maximum(y - r, 0), np.minimum(y + r + 1, h)
    x0, x1 = np.maximum(x - r, 0), np.minimum(x + r + 1, w)
    total = s[y1, x1] - s[y0, x1] - s[y1, x0] + s[y0, x0]
    return total / ((y1 - y0) * (x1 - x0))


def log_luminance(image: np.ndarray) -> np.ndarray:
    return np.log2(np.maximum(luminance(image[..., :3].astype(np.float64)), LUMA_EPS))


def guided_coefficients(image: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Self-guided filter of log2 luminance: base = A * L + B (He et al. 2010)."""
    h, w = image.shape[:2]
    r = guided_radius(w, h)
    lum = log_luminance(image)
    mean = _box_mean(lum, r)
    var = _box_mean(lum * lum, r) - mean * mean
    a = var / (var + GUIDED_EPS)
    b = mean - a * mean
    return _box_mean(a, r), _box_mean(b, r)


def _smoothstep(e0: float, e1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def highlights_shadows(image: np.ndarray, highlights: float, shadows: float) -> np.ndarray:
    """Highlights / Shadows in [-100, 100] on an edge-aware base of log luminance.

    The base is the guided filter of L = log2 Y (radius 1.5 % of the long edge, eps 0.01).
    Smoothstep masks of the base pick highlights (0 .. +2.5 stops over mid grey) and
    shadows (-5 .. 0 stops); the base moves by up to +-1.5 stops and the detail L - base
    is kept, i.e. Y' = Y * 2^delta(base). RGB scales by the same factor (chroma kept).
    """
    a, b = guided_coefficients(image)
    base = a * log_luminance(image) + b
    rel = base - np.log2(MID_GREY)
    mh = _smoothstep(0.0, HIGHLIGHT_RANGE, rel)
    ms = 1.0 - _smoothstep(-SHADOW_RANGE, 0.0, rel)
    delta = (highlights / 100.0) * TONE_STOPS * mh + (shadows / 100.0) * TONE_STOPS * ms
    out = image.astype(np.float64).copy()
    valid = luminance(out[..., :3]) > LUMA_EPS
    out[..., :3] *= np.where(valid, np.exp2(delta), 1.0)[..., None]
    return out.astype(np.float32)


def histogram_srgb(image: np.ndarray) -> np.ndarray:
    """256-bin histograms of the sRGB display values R, G, B and of luminance L.

    The engine computes it on the GPU from the RGBA16F preview, so the working values
    are rounded to float16 first. Display values: Rec.2020 -> sRGB primaries, clip to
    0..1, sRGB transfer; L = transfer(clip(Y)). bin = min(floor(v * 256), 255).
    Returns float32 counts of shape (1, 256, 4) in R, G, B, L order.
    """
    working = image.astype(np.float16).astype(np.float64)
    rgb = rgb_to_rgb(working, "rec2020", "srgb").astype(np.float64)[..., :3]
    encoded = colour.cctf_encoding(np.clip(rgb, 0.0, 1.0), function="sRGB")
    lum = colour.cctf_encoding(np.clip(luminance(working[..., :3]), 0.0, 1.0), function="sRGB")
    values = np.concatenate([encoded, lum[..., None]], axis=-1).reshape(-1, 4)
    bins = np.minimum(np.floor(values * HISTOGRAM_BINS), HISTOGRAM_BINS - 1).astype(np.int64)
    counts = np.zeros((HISTOGRAM_BINS, 4))
    for c in range(4):
        counts[:, c] = np.bincount(bins[:, c], minlength=HISTOGRAM_BINS)
    return counts[None].astype(np.float32)


def histogram_edge_crowding(image: np.ndarray, margin: float = 2e-4) -> int:
    """Most pixels of one channel lying within `margin` bins of the same bin edge.

    The GPU computes in float32 and may put a value that sits almost exactly on a bin
    edge in the neighbouring bin (2e-4 bins = 7.8e-7 in display value, about 6 float32
    ulps at 1.0; float16 inputs make some values repeat). The histogram test allows 1 count per bin, so a test
    image must not crowd more than one pixel next to any edge (flat patches would move
    together). Values in the top bin are safe (255.x and 256 both land in bin 255).
    """
    working = image.astype(np.float16).astype(np.float64)
    rgb = rgb_to_rgb(working, "rec2020", "srgb").astype(np.float64)[..., :3]
    encoded = colour.cctf_encoding(np.clip(rgb, 0.0, 1.0), function="sRGB")
    lum = colour.cctf_encoding(np.clip(luminance(working[..., :3]), 0.0, 1.0), function="sRGB")
    values = np.concatenate([encoded, lum[..., None]], axis=-1).reshape(-1, 4) * HISTOGRAM_BINS
    worst = 0
    for c in range(4):
        v = values[:, c]
        v = v[(v > 0.0) & (v < HISTOGRAM_BINS - 0.5)]
        near = np.abs(v - np.rint(v)) < margin
        if near.any():
            worst = max(worst, int(np.unique(np.rint(v[near]), return_counts=True)[1].max()))
    return worst


LIGHT_SETTINGS = {
    "exposure": [(-2.5,), (1.0,), (3.7,)],
    "contrast": [(-60.0,), (40.0,), (100.0,)],
    "brightness": [(-70.0,), (30.0,), (100.0,)],
    "whites": [(-100.0,), (50.0,), (80.0,)],
    "blacks": [(-100.0,), (-30.0,), (70.0,)],
    "highlights_shadows": [(-100.0, 0.0), (0.0, 100.0), (-60.0, 60.0)],
}

LIGHT_OPS = {
    "exposure": exposure,
    "contrast": contrast,
    "brightness": brightness,
    "whites": whites,
    "blacks": blacks,
    "highlights_shadows": highlights_shadows,
}


def light_self_check() -> None:
    scene = scene_rec2020()
    assert scene.shape == (SCENE_HEIGHT, SCENE_WIDTH, 4)
    grey = np.full((1, 1, 4), MID_GREY, dtype=np.float32)
    grey[..., 3] = 1.0
    # Mid grey is a fixed point of the log-space curves.
    for op in (contrast, whites, blacks):
        for amount in (-100.0, 100.0):
            assert np.allclose(op(grey, amount), grey, atol=1e-6)
    # The endpoints move by one stop at +-100.
    white = np.full((1, 1, 4), 1.0, dtype=np.float32)
    assert np.allclose(whites(white, 100.0)[..., :3], 2.0, atol=1e-5)
    black = np.full((1, 1, 4), BLACK_POINT, dtype=np.float32)
    black[..., 3] = 1.0
    assert np.allclose(blacks(black, -100.0)[..., :3], BLACK_POINT / 2.0, rtol=1e-5)
    # Every tone curve is monotonic over 2^-14 .. 16.
    ramp = np.zeros((1, 2000, 4), dtype=np.float32)
    ramp[..., :3] = (2.0 ** np.linspace(-14, 4, 2000))[None, :, None]
    ramp[..., 3] = 1.0
    for name in ("contrast", "brightness", "whites", "blacks"):
        for amount in (-100.0, 100.0):
            y = luminance(LIGHT_OPS[name](ramp, amount)[0, :, :3].astype(np.float64))
            assert np.all(np.diff(y) > 0), (name, amount)
    assert histogram_edge_crowding(scene) <= 1, histogram_edge_crowding(scene)
    assert histogram_srgb(scene).sum() == SCENE_WIDTH * SCENE_HEIGHT * 4


# ---------------------------------------------------------------------------
# Stage 6: Color basics.
#
# White balance is a Bradford chromatic adaptation in XYZ (colour-science computes the
# matrix here). Vibrance, Saturation and the HSL mixer work in OKLCh (never in sRGB
# gamma). Invert, Posterize and Threshold work on transfer-encoded working values
# (the sRGB curve, extended by symmetry), so mid grey stays mid grey. Nothing is
# clipped: out-of-gamut and HDR values pass through.
# ---------------------------------------------------------------------------

COLORS_WIDTH = 120
COLORS_HEIGHT = 48

WB_DEFAULT_KELVIN = 6504.0
WB_MIN_KELVIN = 2000.0
WB_MAX_KELVIN = 50000.0
WB_TINT_DUV_PER_UNIT = 0.05 / 150.0  # tint +-150 = +-0.05 duv (green-magenta axis)
D65_XY = np.array([0.3127, 0.3290])

# OKLCh hues (degrees) of saturated sRGB colours at the classic HSV band hues
# 0, 30, 60, 120, 180, 240, 270, 300 (checked in color_self_check).
HSL_CENTRES = np.array([29.2, 52.8, 109.8, 142.5, 194.8, 264.1, 293.8, 328.4])
HSL_BANDS = ["red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"]
HSL_HUE_DEGREES = 30.0  # hue +-100 = +-30 degrees
HSL_LUMINANCE = 0.4  # luminance +-100 = L x (1 +- 0.4)
HSL_CHROMA_FADE = 0.03  # effects fade in from grey over this OKLCh chroma

VIBRANCE_CHROMA_REF = 0.3  # chroma counted as fully saturated
SKIN_HUES = (34.5, 84.2)  # OKLCh image of HSV hues 15..45 degrees
SKIN_EDGE = 10.0  # soft edges of the skin band, degrees
SKIN_PROTECT = 0.75  # how much of the vibrance boost skin is spared

_REC2020_TO_XYZ = np.array(_REC2020.matrix_RGB_to_XYZ)
_XYZ_TO_REC2020 = np.array(_REC2020.matrix_XYZ_to_RGB)


def uv_planck(kelvin: float) -> np.ndarray:
    """CIE 1960 uv of the Planckian locus (Krystek 1985, as in colour-science)."""
    return np.asarray(colour.temperature.CCT_to_uv_Krystek1985(kelvin), dtype=np.float64)


def _locus_normal(kelvin: float) -> np.ndarray:
    """Unit normal of the locus at T pointing to green (+v side)."""
    h = kelvin * 1e-4
    d = uv_planck(kelvin + h) - uv_planck(kelvin - h)
    n = np.array([-d[1], d[0]])
    n /= np.linalg.norm(n)
    return n if n[1] > 0 else -n


def wb_source_uv(kelvin: float, tint: float) -> np.ndarray:
    """The scene white for (T, tint): D65 moved by the locus offset from 6504 K.

    T = 6504 K and tint 0 is exactly D65 (no change). Tint moves along the locus normal,
    + towards green (the image then turns magenta).
    """
    d65 = np.asarray(colour.xy_to_UCS_uv(D65_XY), dtype=np.float64)
    offset = uv_planck(kelvin) - uv_planck(WB_DEFAULT_KELVIN)
    return d65 + offset + tint * WB_TINT_DUV_PER_UNIT * _locus_normal(kelvin)


def _uv_to_xyz(uv: np.ndarray) -> np.ndarray:
    xy = colour.UCS_uv_to_xy(uv)
    return colour.xy_to_XYZ(xy)


def white_balance_matrix(kelvin: float, tint: float) -> np.ndarray:
    """Working-space 3x3: Bradford adaptation from the (T, tint) white to D65."""
    source = _uv_to_xyz(wb_source_uv(kelvin, tint))
    target = colour.xy_to_XYZ(D65_XY)
    cat = colour.adaptation.matrix_chromatic_adaptation_VonKries(source, target, transform="Bradford")
    return _XYZ_TO_REC2020 @ cat @ _REC2020_TO_XYZ


def white_balance(image: np.ndarray, kelvin: float, tint: float) -> np.ndarray:
    out = image.astype(np.float64).copy()
    out[..., :3] = out[..., :3] @ white_balance_matrix(kelvin, tint).T
    return out.astype(np.float32)


def to_oklab(rgb: np.ndarray) -> np.ndarray:
    return colour.XYZ_to_Oklab(rgb @ _REC2020_TO_XYZ.T)


def from_oklab(lab: np.ndarray) -> np.ndarray:
    return colour.Oklab_to_XYZ(lab) @ _XYZ_TO_REC2020.T


def _lch(rgb: np.ndarray):
    lab = to_oklab(rgb)
    c = np.hypot(lab[..., 1], lab[..., 2])
    h = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360.0
    return lab[..., 0], c, h


def _from_lch(l, c, h) -> np.ndarray:
    r = np.radians(h)
    return from_oklab(np.stack([l, c * np.cos(r), c * np.sin(r)], axis=-1))


def _apply_lch(image: np.ndarray, fn) -> np.ndarray:
    out = image.astype(np.float64).copy()
    l, c, h = _lch(out[..., :3])
    l2, c2, h2 = fn(l, c, h)
    out[..., :3] = _from_lch(l2, c2, h2)
    return out.astype(np.float32)


def saturation(image: np.ndarray, amount: float) -> np.ndarray:
    """Saturation in [-100, 100]: OKLCh chroma x (1 + amount/100); -100 = grey."""
    return _apply_lch(image, lambda l, c, h: (l, c * (1.0 + amount / 100.0), h))


def skin_weight(h: np.ndarray) -> np.ndarray:
    lo, hi = SKIN_HUES
    return _smoothstep(lo - SKIN_EDGE, lo, h) * (1.0 - _smoothstep(hi, hi + SKIN_EDGE, h))


def vibrance(image: np.ndarray, amount: float) -> np.ndarray:
    """Vibrance in [-100, 100]: chroma x (1 + v (1 - s) protect), s = min(C / 0.3, 1).

    Less saturated colours move more; skin hues (OKLCh 34.5..84.2) are 75 % protected.
    """
    v = amount / 100.0

    def fn(l, c, h):
        s = np.minimum(c / VIBRANCE_CHROMA_REF, 1.0)
        protect = 1.0 - SKIN_PROTECT * skin_weight(h)
        return l, c * (1.0 + v * (1.0 - s) * protect), h

    return _apply_lch(image, fn)


def hsl_weights(h: np.ndarray) -> np.ndarray:
    """Raised-cosine weights of the 8 bands at OKLCh hue h (degrees); they sum to 1."""
    h = np.asarray(h, dtype=np.float64) % 360.0
    w = np.zeros(h.shape + (8,))
    for i in range(8):
        c0 = HSL_CENTRES[i]
        c1 = HSL_CENTRES[(i + 1) % 8] + (360.0 if i == 7 else 0.0)
        d = (h - c0) % 360.0
        inside = d < (c1 - c0)
        t = np.where(inside, d / (c1 - c0), 0.0)
        a = 0.5 * (1.0 + np.cos(np.pi * t))
        w[..., i] += np.where(inside, a, 0.0)
        w[..., (i + 1) % 8] += np.where(inside, 1.0 - a, 0.0)
    return w


def hsl_mixer(image: np.ndarray, hue, sat, lum) -> np.ndarray:
    """HSL / Color Mixer: 8 bands x (hue, saturation, luminance) in [-100, 100] each."""
    hue, sat, lum = (np.asarray(x, dtype=np.float64) / 100.0 for x in (hue, sat, lum))

    def fn(l, c, h):
        w = hsl_weights(h)
        fade = _smoothstep(0.0, HSL_CHROMA_FADE, c)
        dh = fade * (w @ hue) * HSL_HUE_DEGREES
        cs = np.maximum(1.0 + fade * (w @ sat), 0.0)
        ls = 1.0 + fade * HSL_LUMINANCE * (w @ lum)
        return l * ls, c * cs, h + dh

    return _apply_lch(image, fn)


def encode(x: np.ndarray) -> np.ndarray:
    """The sRGB transfer extended by symmetry (and beyond 1 by the same power law)."""
    a = np.abs(x)
    e = np.where(a <= 0.0031308, a * 12.92, 1.055 * np.power(a, 1.0 / 2.4) - 0.055)
    return np.sign(x) * e


def decode(e: np.ndarray) -> np.ndarray:
    a = np.abs(e)
    x = np.where(a <= 0.04045, a / 12.92, np.power((a + 0.055) / 1.055, 2.4))
    return np.sign(e) * x


def _on_encoded(image: np.ndarray, fn) -> np.ndarray:
    out = image.astype(np.float64).copy()
    out[..., :3] = decode(fn(encode(out[..., :3])))
    return out.astype(np.float32)


def invert(image: np.ndarray) -> np.ndarray:
    """Invert on encoded values: e -> 1 - e (mid grey stays mid grey)."""
    return _on_encoded(image, lambda e: 1.0 - e)


def posterize(image: np.ndarray, levels: float) -> np.ndarray:
    """Posterize to `levels` steps (2..32) of the encoded value, per channel."""
    n = levels - 1.0
    return _on_encoded(image, lambda e: np.floor(e * n + 0.5) / n)


def threshold(image: np.ndarray, level: float) -> np.ndarray:
    """Threshold at `level` (0..1) of encoded luminance: white above, black below."""
    out = image.astype(np.float64).copy()
    y = encode(luminance(out[..., :3]))
    v = np.where(y >= level, 1.0, 0.0)
    out[..., :3] = v[..., None]
    return out.astype(np.float32)


def colors_rec2020() -> np.ndarray:
    """An OKLCh test chart: hue across (0..357 deg), chroma 0..0.32 down in 6 bands of
    lightness, plus a grey ramp and a few HDR / out-of-sRGB-gamut values. Alpha 1."""
    h, w = COLORS_HEIGHT, COLORS_WIDTH
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    hue = x * 3.0
    band = (y // 8).astype(np.int64)
    row = y % 8
    lightness = np.array([0.35, 0.5, 0.62, 0.72, 0.85, 0.95])[np.minimum(band, 5)]
    chroma = row / 7.0 * 0.32
    rgb = _from_lch(lightness, chroma, hue)
    grey = band == 5
    rgb[grey & (row < 2)] = (np.linspace(0.0, 2.0, w)[:, None] * np.ones(3))[x[grey & (row < 2)].astype(int)]
    rgba = np.concatenate([rgb, np.ones((h, w, 1))], axis=-1)
    return rgba.astype(np.float32)


COLOR_SETTINGS = {
    "white_balance": [(3000.0, 0.0), (9000.0, 40.0), (5000.0, -60.0)],
    "vibrance": [(-50.0,), (40.0,), (100.0,)],
    "saturation": [(-100.0,), (-30.0,), (60.0,)],
    "hsl_mixer": [
        ([0, 40, 0, 0, 0, -30, 0, 0], [0, 60, 0, 0, 0, 0, 0, 0], [0, 0, 0, 0, 0, 0, 0, 0]),
        ([0] * 8, [-100, -100, -100, -100, -100, 100, 0, 0], [0, 0, 0, 0, 50, -60, 0, 0]),
        ([20, -20, 30, -40, 50, -60, 70, -80], [10, 20, 30, 40, -50, -60, -70, -80], [-10, 20, -30, 40, -50, 60, -70, 80]),
    ],
    "invert": [()],
    "posterize": [(2.0,), (5.0,), (16.0,)],
    "threshold": [(0.25,), (0.5,), (0.75,)],
}

COLOR_OPS = {
    "white_balance": white_balance,
    "vibrance": vibrance,
    "saturation": saturation,
    "hsl_mixer": hsl_mixer,
    "invert": invert,
    "posterize": posterize,
    "threshold": threshold,
}


def discontinuity_margin(image: np.ndarray, name: str, param: float) -> float:
    """How far (in encoded units) the float16-rounded image stays from Posterize's or
    Threshold's step edges."""
    rgb = image[..., :3].astype(np.float16).astype(np.float64)
    if name == "posterize":
        x = encode(rgb) * (param - 1.0) + 0.5
        return float(np.min(np.abs(x - np.round(x))) / (param - 1.0))
    return float(np.min(np.abs(encode(luminance(rgb)) - param)))


def color_self_check() -> None:
    # Band centres are the OKLCh hues of the classic HSV band hues.
    for hsv, centre in zip([0, 30, 60, 120, 180, 240, 270, 300], HSL_CENTRES):
        rgb = colour.cctf_decoding(colour.HSV_to_RGB(np.array([hsv / 360.0, 1.0, 1.0])), function="sRGB")
        lab = colour.XYZ_to_Oklab(colour.RGB_to_XYZ(rgb, _SRGB, apply_cctf_decoding=False))
        hue = np.degrees(np.arctan2(lab[2], lab[1])) % 360.0
        assert abs(hue - centre) < 0.1, (hsv, hue, centre)
    # Band weights are a partition of unity and peak at their centre.
    w = hsl_weights(np.linspace(0, 360, 7201))
    assert np.allclose(w.sum(axis=-1), 1.0, atol=1e-12)
    assert np.allclose(hsl_weights(HSL_CENTRES), np.eye(8), atol=1e-12)
    # 6504 K, tint 0 is no change; the default white is D65.
    assert np.allclose(white_balance_matrix(WB_DEFAULT_KELVIN, 0.0), np.eye(3), atol=1e-9)
    # A colour adapted with the source white of (T, tint) becomes neutral.
    for k, t in [(3200.0, 20.0), (12000.0, -40.0)]:
        white = _uv_to_xyz(wb_source_uv(k, t)) @ _XYZ_TO_REC2020.T
        out = white_balance_matrix(k, t) @ white
        assert np.allclose(out / out.mean(), 1.0, atol=1e-6), out
    # Out-of-gamut and HDR values survive (no clipping).
    chart = colors_rec2020()
    assert chart[..., :3].min() < 0.0 and chart[..., :3].max() > 1.0
    assert np.allclose(invert(invert(chart)), chart, atol=1e-4)
    # The GPU cases of the step ops keep every pixel away from a step (see generate.py).
    assert discontinuity_margin(chart, "posterize", 12.0) > 1e-5
    assert discontinuity_margin(scene_rec2020(), "threshold", 0.5) > 1e-3


# ---------------------------------------------------------------------------
# Stage 7: Curves and grading.
#
# Tone ops (curves, parametric curve, levels, colour balance, selective colour, colour
# grading) work on transfer-encoded values (`encode`, the sRGB curve extended by
# symmetry); curves extend with slope 1 outside 0..1, so nothing clips. Curves are
# Fritsch-Carlson monotone cubics sampled into a 4096-entry LUT read with linear
# interpolation (the engine uploads the same LUT as a texture).
# ---------------------------------------------------------------------------

LUT_SIZE = 4096


def monotone_tangents(xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    """Fritsch-Carlson tangents (sequential limiting, as the engine computes them)."""
    n = len(xs)
    d = np.diff(ys) / np.diff(xs)
    m = np.zeros(n)
    m[0] = d[0]
    m[-1] = d[-1]
    for k in range(1, n - 1):
        m[k] = 0.0 if d[k - 1] * d[k] <= 0.0 else 0.5 * (d[k - 1] + d[k])
    for k in range(n - 1):
        if d[k] == 0.0:
            m[k] = 0.0
            m[k + 1] = 0.0
            continue
        a = m[k] / d[k]
        b = m[k + 1] / d[k]
        s = a * a + b * b
        if s > 9.0:
            t = 3.0 / np.sqrt(s)
            m[k] = t * a * d[k]
            m[k + 1] = t * b * d[k]
    return m


def spline_eval(points, x: np.ndarray) -> np.ndarray:
    """The monotone cubic through `points` [(x, y), ...] (x increasing) at x; flat outside."""
    pts = np.asarray(points, dtype=np.float64).reshape(-1, 2)
    xs, ys = pts[:, 0], pts[:, 1]
    m = monotone_tangents(xs, ys)
    x = np.asarray(x, dtype=np.float64)
    k = np.clip(np.searchsorted(xs, x, side="right") - 1, 0, len(xs) - 2)
    h = xs[k + 1] - xs[k]
    t = np.clip((x - xs[k]) / h, 0.0, 1.0)
    t2, t3 = t * t, t * t * t
    out = (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
    out = np.where(x <= xs[0], ys[0], out)
    return np.where(x >= xs[-1], ys[-1], out)


def curve_lut(points) -> np.ndarray:
    return spline_eval(points, np.arange(LUT_SIZE) / (LUT_SIZE - 1.0))


def lut_apply(lut: np.ndarray, e: np.ndarray) -> np.ndarray:
    """LUT on 0..1 with linear interpolation; slope 1 outside (no clipping)."""
    c = np.clip(e, 0.0, 1.0)
    p = c * (LUT_SIZE - 1)
    i = np.minimum(np.floor(p).astype(np.int64), LUT_SIZE - 2)
    f = p - i
    return lut[i] * (1.0 - f) + lut[i + 1] * f + (e - c)


IDENTITY_CURVE = [(0.0, 0.0), (1.0, 1.0)]


def curves(image: np.ndarray, rgb=None, red=None, green=None, blue=None, luma=None) -> np.ndarray:
    """Point curves on encoded values: R, G, B per channel, then RGB, then Luma (on encoded
    luminance; RGB scaled by the linear luminance ratio)."""
    out = image.astype(np.float64).copy()
    e = encode(out[..., :3])
    for c, pts in enumerate((red, green, blue)):
        if pts:
            e[..., c] = lut_apply(curve_lut(pts), e[..., c])
    if rgb:
        e = lut_apply(curve_lut(rgb), e)
    out[..., :3] = decode(e)
    if luma:
        y = luminance(out[..., :3])
        y2 = decode(lut_apply(curve_lut(luma), encode(y)))
        ratio = np.where(np.abs(y) > LUMA_EPS, y2 / np.where(np.abs(y) > LUMA_EPS, y, 1.0), 1.0)
        out[..., :3] *= ratio[..., None]
    return out.astype(np.float32)


PARAMETRIC_REACH = 0.45  # a region's centre moves up to 45 % of the region width


def parametric_points(highlights, lights, darks, shadows, s1=0.25, s2=0.5, s3=0.75):
    """The 9 curve points of the parametric curve: region centres moved within their region
    (so the curve stays monotone), region edges fixed."""
    edges = [0.0, s1, s2, s3, 1.0]
    amounts = [shadows, darks, lights, highlights]
    pts = [(0.0, 0.0)]
    for i, a in enumerate(amounts):
        lo, hi = edges[i], edges[i + 1]
        c = 0.5 * (lo + hi)
        pts.append((c, c + a / 100.0 * PARAMETRIC_REACH * (hi - lo)))
        pts.append((hi, hi))
    return pts


def parametric_curve(image, highlights, lights, darks, shadows, s1=0.25, s2=0.5, s3=0.75):
    return curves(image, rgb=parametric_points(highlights, lights, darks, shadows, s1, s2, s3))


def _spow(v, p):
    return np.sign(v) * np.power(np.abs(v), p)


def _levels_channel(e, in_black, gamma, in_white, out_black, out_white):
    t = (e - in_black) / max(in_white - in_black, 0.01)
    return out_black + (out_white - out_black) * _spow(t, 1.0 / max(gamma, 0.01))


LEVELS_IDENTITY = [0.0, 1.0, 1.0, 0.0, 1.0]


def levels(image: np.ndarray, values) -> np.ndarray:
    """Levels on encoded values: rows master, red, green, blue of (in black, gamma, in white,
    out black, out white); per-channel rows first, then the master row."""
    v = np.asarray(values, dtype=np.float64).reshape(4, 5)
    out = image.astype(np.float64).copy()
    e = encode(out[..., :3])
    for c in range(3):
        e[..., c] = _levels_channel(e[..., c], *v[c + 1])
    e = _levels_channel(e, *v[0])
    out[..., :3] = decode(e)
    return out.astype(np.float32)


def tint_direction(hue_degrees: float) -> np.ndarray:
    """A luminance-neutral-ish working-space direction for a hue (OKLCh), max |v| = 1."""
    r = np.radians(hue_degrees)
    rgb = from_oklab(np.array([0.7, 0.1 * np.cos(r), 0.1 * np.sin(r)])) - 0.7 ** 3
    return rgb / np.max(np.abs(rgb))


def _tone_weights(ye, mid, width):
    t = _smoothstep(mid - width, mid + width, ye)
    return (1 - t) ** 2, 2 * t * (1 - t), t * t


GRADE_HIGHLIGHT_TINT = 0.2
GRADE_WHITE_FADE = (0.9, 1.05)


def color_grading(image, shadows, midtones, highlights, global_, blending=50.0, balance=0.0):
    """Color Grading as CDL in encoded (log-like) space: shadows = offset, midtones = power,
    highlights = slope, global = offset everywhere. Each wheel is (hue deg, saturation 0..100,
    luminance -100..100). Balance moves the shadow/highlight split, Blending widens the
    overlap. Split toning is this with the shadow and highlight wheels only."""
    out = image.astype(np.float64).copy()
    e = encode(out[..., :3])
    ye = encode(luminance(out[..., :3]))
    width = 0.15 + 0.35 * blending / 100.0
    ws, wm, wh = _tone_weights(ye, 0.5 - 0.25 * balance / 100.0, width)
    sh, mi, hi, gl = (np.asarray(w, dtype=np.float64) for w in (shadows, midtones, highlights, global_))
    vs, vm, vh, vg = (tint_direction(w[0]) for w in (sh, mi, hi, gl))
    # Highlights tint: as strong as the shadows' offset, faded out towards white so that
    # white and HDR highlights stay neutral (encoded luminance 0.9 -> 1.05).
    fade = 1.0 - _smoothstep(GRADE_WHITE_FADE[0], GRADE_WHITE_FADE[1], ye)
    slope = 1.0 + wh[..., None] * (GRADE_HIGHLIGHT_TINT * hi[1] / 100.0 * vh * fade[..., None] + 0.5 * hi[2] / 100.0)
    offset = ws[..., None] * (0.08 * sh[1] / 100.0 * vs + 0.1 * sh[2] / 100.0) + (0.05 * gl[1] / 100.0 * vg + 0.1 * gl[2] / 100.0)
    power = 1.0 / np.maximum(1.0 + wm[..., None] * (0.6 * mi[1] / 100.0 * vm + 0.5 * mi[2] / 100.0), 0.05)
    out[..., :3] = decode(_spow(e * slope + offset, power))
    return out.astype(np.float32)


def color_balance(image, shadows, midtones, highlights, preserve=True):
    """Color Balance: per tone range (cyan-red, magenta-green, yellow-blue) in -100..100,
    added in encoded space; preserve luminosity adds back the lost linear luminance as grey."""
    out = image.astype(np.float64).copy()
    y0 = luminance(out[..., :3])
    e = encode(out[..., :3])
    ws, wm, wh = _tone_weights(encode(y0), 0.5, 0.3)
    d = 0.15 * (ws[..., None] * np.asarray(shadows) + wm[..., None] * np.asarray(midtones) + wh[..., None] * np.asarray(highlights)) / 100.0
    out[..., :3] = decode(e + d)
    if preserve:
        # Additive (grey) correction: exact, and well conditioned even where the shift
        # takes the luminance near zero (a ratio would explode there).
        out[..., :3] += (y0 - luminance(out[..., :3]))[..., None]
    return out.astype(np.float32)


SELECTIVE_FAMILIES = ["reds", "yellows", "greens", "cyans", "blues", "magentas", "whites", "neutrals", "blacks"]


def _selective_amounts(e):
    c = np.clip(e, 0.0, 1.0)
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    mx, mn = c.max(axis=-1), c.min(axis=-1)
    z = np.zeros_like(r)
    return [
        np.maximum(z, r - np.maximum(g, b)),
        np.maximum(z, np.minimum(r, g) - b),
        np.maximum(z, g - np.maximum(r, b)),
        np.maximum(z, np.minimum(g, b) - r),
        np.maximum(z, b - np.maximum(r, g)),
        np.maximum(z, np.minimum(r, b) - g),
        np.maximum(z, 2 * mn - 1),
        np.maximum(z, 1 - np.abs(mx + mn - 1) - (mx - mn)),
        np.maximum(z, 1 - 2 * mx),
    ]


def selective_color(image, values, relative=True):
    """Selective Color: 9 families x (cyan, magenta, yellow, black) in -100..100. Inks are
    1 - encoded value; relative mode scales the change by the ink already there."""
    v = np.asarray(values, dtype=np.float64).reshape(9, 4) / 100.0
    out = image.astype(np.float64).copy()
    e = encode(out[..., :3])
    ink = 1.0 - e
    delta = np.zeros_like(e)
    for f, a in enumerate(_selective_amounts(e)):
        scale = ink if relative else 1.0
        delta += a[..., None] * scale * (v[f, :3] + v[f, 3])
    out[..., :3] = decode(1.0 - (ink + delta))
    return out.astype(np.float32)


def channel_mixer(image, matrix, constant, monochrome=False):
    """Channel Mixer on linear working values: out = M in + k (percent); monochrome uses the
    first row for all three outputs."""
    m = np.asarray(matrix, dtype=np.float64).reshape(3, 3) / 100.0
    if monochrome:
        m = np.repeat(m[:1], 3, axis=0)
    k = np.asarray(constant, dtype=np.float64) / 100.0
    if monochrome:
        k = np.repeat(k[:1], 3)
    out = image.astype(np.float64).copy()
    out[..., :3] = out[..., :3] @ m.T + k
    return out.astype(np.float32)


BW_CENTRES = np.array([29.2, 109.8, 142.5, 194.8, 264.1, 328.4])  # reds .. magentas (OKLCh)


def _band_weights(h, centres):
    n = len(centres)
    h = np.asarray(h, dtype=np.float64) % 360.0
    w = np.zeros(h.shape + (n,))
    for i in range(n):
        c0 = centres[i]
        c1 = centres[(i + 1) % n] + (360.0 if i == n - 1 else 0.0)
        d = (h - c0) % 360.0
        inside = d < (c1 - c0)
        a = 0.5 * (1.0 + np.cos(np.pi * np.where(inside, d / (c1 - c0), 0.0)))
        w[..., i] += np.where(inside, a, 0.0)
        w[..., (i + 1) % n] += np.where(inside, 1.0 - a, 0.0)
    return w


def black_and_white(image, mix, tint_hue=0.0, tint_saturation=0.0):
    """Black & White: luminance moved by +-1 stop per colour band (reds, yellows, greens,
    cyans, blues, magentas; -100..100), faded in from grey by chroma; optional tint."""
    out = image.astype(np.float64).copy()
    l, c, h = _lch(out[..., :3])
    y = luminance(out[..., :3])
    w = _band_weights(h, BW_CENTRES)
    fade = _smoothstep(0.0, HSL_CHROMA_FADE, c)
    grey = y * np.exp2(fade * (w @ (np.asarray(mix, dtype=np.float64) / 100.0)))
    tint = 0.5 * tint_saturation / 100.0 * tint_direction(tint_hue)
    out[..., :3] = grey[..., None] * (1.0 + tint)
    return out.astype(np.float32)


def filter_color(hue: float, saturation: float) -> np.ndarray:
    r = np.radians(hue)
    c = 0.15 * saturation / 100.0
    rgb = from_oklab(np.array([0.75, c * np.cos(r), c * np.sin(r)]))
    return rgb / np.max(rgb)


def photo_filter(image, hue, saturation, density, preserve=True):
    """Photo Filter: multiply by a filter colour (OKLCh hue / saturation, max channel 1) at
    `density` %, optionally keeping the linear luminance."""
    out = image.astype(np.float64).copy()
    y0 = luminance(out[..., :3])
    f = 1.0 + density / 100.0 * (filter_color(hue, saturation) - 1.0)
    out[..., :3] *= f
    if preserve:
        y1 = luminance(out[..., :3])
        ok = np.abs(y1) > LUMA_EPS
        out[..., :3] *= np.where(ok, y0 / np.where(ok, y1, 1.0), 1.0)[..., None]
    return out.astype(np.float32)


def gradient_lut(stops) -> np.ndarray:
    """4096 x 3 working-space colours: stops (position, OKLab L, a, b) interpolated in OKLab."""
    s = np.asarray(stops, dtype=np.float64).reshape(-1, 4)
    s = s[np.argsort(s[:, 0], kind="stable")]
    t = np.arange(LUT_SIZE) / (LUT_SIZE - 1.0)
    lab = np.stack([np.interp(t, s[:, 0], s[:, k]) for k in (1, 2, 3)], axis=-1)
    return from_oklab(lab)


def gradient_map(image, stops) -> np.ndarray:
    """Gradient Map: encoded luminance (clamped to 0..1 for the lookup) -> gradient colour."""
    lut = gradient_lut(stops)
    out = image.astype(np.float64).copy()
    ye = np.clip(encode(luminance(out[..., :3])), 0.0, 1.0)
    p = ye * (LUT_SIZE - 1)
    i = np.minimum(np.floor(p).astype(np.int64), LUT_SIZE - 2)
    f = (p - i)[..., None]
    out[..., :3] = lut[i] * (1.0 - f) + lut[i + 1] * f
    return out.astype(np.float32)


def calibration_matrix(hue, saturation) -> np.ndarray:
    """Calibration: each working-space primary turned by hue x 30 deg and its chroma scaled
    by (1 + saturation / 100) in OKLCh; columns rescaled so white stays white."""
    cols = []
    for i in range(3):
        e = np.zeros(3)
        e[i] = 1.0
        lab = to_oklab(e)
        c = np.hypot(lab[1], lab[2]) * (1.0 + saturation[i] / 100.0)
        h = np.arctan2(lab[2], lab[1]) + np.radians(hue[i] / 100.0 * 30.0)
        cols.append(from_oklab(np.array([lab[0], c * np.cos(h), c * np.sin(h)])))
    m = np.stack(cols, axis=1)
    k = np.linalg.solve(m, np.ones(3))
    return m * k[None, :]


def calibration(image, hue, saturation) -> np.ndarray:
    out = image.astype(np.float64).copy()
    out[..., :3] = out[..., :3] @ calibration_matrix(hue, saturation).T
    return out.astype(np.float32)


GRADE_SETTINGS = {
    "curves": [
        dict(rgb=[(0, 0), (0.25, 0.18), (0.75, 0.84), (1, 1)]),
        dict(red=[(0, 0.05), (0.5, 0.56), (1, 1)], blue=[(0, 0), (0.5, 0.44), (1, 0.95)], luma=[(0, 0), (0.4, 0.5), (1, 1)]),
        dict(rgb=[(0, 1), (0.5, 0.3), (1, 0.0)], green=[(0, 0), (0.1, 0.4), (0.12, 0.42), (1, 1)]),
    ],
    "parametric_curve": [(30.0, 10.0, -20.0, -40.0), (-60.0, -20.0, 30.0, 50.0), (100.0, -100.0, 100.0, -100.0, 0.2, 0.45, 0.8)],
    "levels": [
        ([0.05, 1.2, 0.9, 0, 1] + LEVELS_IDENTITY * 3,),
        (LEVELS_IDENTITY + [0.1, 1, 1, 0, 1] + LEVELS_IDENTITY + [0, 0.8, 0.85, 0.05, 1],),
        ([0, 0.6, 1, 0.1, 0.9] + [0, 1.3, 1, 0, 1] + [0.02, 1, 0.95, 0, 1] + LEVELS_IDENTITY,),
    ],
    "color_grading": [
        ((200, 60, 0), (0, 0, 0), (40, 50, 0), (0, 0, 0), 50, 0),
        ((180, 80, -20), (300, 30, 10), (30, 70, 20), (90, 20, -10), 80, 30),
        ((250, 100, 0), (0, 0, -50), (60, 100, 0), (0, 0, 40), 10, -60),
    ],
    "color_balance": [
        ((-30, 0, 40), (0, 0, 0), (20, 0, -30), True),
        ((0, 50, 0), (60, -40, 20), (0, 0, 0), False),
        ((100, -100, 100), (-100, 100, -100), (100, 100, 100), True),
    ],
    "selective_color": [
        ([0, 0, 0, 0] * 9, True),
        ([-40, 10, 20, 0, 0, 0, -30, 0, 0, 20, 0, 10, 30, 0, 0, 0, 0, 0, 40, -10, 0, 0, 0, 0, 0, 0, 0, -20, 10, 0, 0, 0, 0, 0, 0, 20], True),
        ([-40, 10, 20, 0, 0, 0, -30, 0, 0, 20, 0, 10, 30, 0, 0, 0, 0, 0, 40, -10, 0, 0, 0, 0, 0, 0, 0, -20, 10, 0, 0, 0, 0, 0, 0, 20], False),
    ],
    "channel_mixer": [
        ([100, 0, 0, 0, 100, 0, 0, 0, 100], [0, 0, 0], False),
        ([80, 30, -10, 10, 90, 0, 0, -20, 120], [5, 0, -5], False),
        ([30, 60, 10, 0, 0, 0, 0, 0, 0], [0, 0, 0], True),
    ],
    "black_and_white": [([0, 0, 0, 0, 0, 0], 0, 0), ([40, 60, -20, -30, -60, 20], 0, 0), ([-50, 30, 80, 0, -100, 100], 40, 30)],
    "photo_filter": [(60.0, 80.0, 25.0, True), (250.0, 60.0, 60.0, False), (140.0, 100.0, 100.0, True)],
    "gradient_map": [
        ([0, 0.15, 0, 0, 1, 0.95, 0, 0],),
        ([0, 0.2, 0.02, -0.08, 0.5, 0.6, 0.08, 0.02, 1, 0.97, -0.01, 0.06],),
        ([0, 0.3, -0.05, -0.1, 0.3, 0.45, 0.12, 0.05, 0.7, 0.8, -0.04, 0.12, 1, 0.98, 0, 0],),
    ],
    "calibration": [((20, 0, 0), (0, 0, 0)), ((0, -30, 40), (30, 20, -20)), ((-60, 50, -40), (-50, 60, 80))],
}

GRADE_OPS = {
    "curves": lambda img, kw: curves(img, **kw),
    "parametric_curve": lambda img, p: parametric_curve(img, *p),
    "levels": lambda img, p: levels(img, *p),
    "color_grading": lambda img, p: color_grading(img, *p),
    "color_balance": lambda img, p: color_balance(img, *p),
    "selective_color": lambda img, p: selective_color(img, *p),
    "channel_mixer": lambda img, p: channel_mixer(img, *p),
    "black_and_white": lambda img, p: black_and_white(img, *p),
    "photo_filter": lambda img, p: photo_filter(img, *p),
    "gradient_map": lambda img, p: gradient_map(img, *p),
    "calibration": lambda img, p: calibration(img, *p),
}


def grade_self_check() -> None:
    rng = np.random.default_rng(7)
    # Monotone data gives a monotone curve (Fritsch-Carlson), on random point sets.
    for _ in range(300):
        n = rng.integers(2, 9)
        xs = np.sort(np.concatenate([[0.0, 1.0], rng.uniform(0.01, 0.99, n - 2)]))
        xs = np.unique(np.round(xs, 3))
        ys = np.sort(rng.uniform(0, 1, len(xs)))
        lut = curve_lut(list(zip(xs, ys)))
        assert np.all(np.diff(lut) >= -1e-12)
        assert np.allclose(spline_eval(list(zip(xs, ys)), xs), ys)
    assert np.allclose(curve_lut(IDENTITY_CURVE), np.arange(LUT_SIZE) / (LUT_SIZE - 1.0))
    # Parametric points stay monotone for any amounts.
    for _ in range(100):
        a = rng.uniform(-100, 100, 4)
        assert np.all(np.diff(np.asarray(parametric_points(*a))[:, 1]) >= 0)
    # Calibration and the neutral settings are identities; white stays white.
    assert np.allclose(calibration_matrix([0, 0, 0], [0, 0, 0]), np.eye(3), atol=1e-9)
    assert np.allclose(calibration_matrix([30, -20, 10], [20, 0, -40]) @ np.ones(3), 1.0)
    scene = scene_rec2020()
    assert np.allclose(levels(scene, LEVELS_IDENTITY * 4), scene, atol=1e-5)
    assert np.allclose(curves(scene, rgb=IDENTITY_CURVE), scene, atol=1e-5)
    assert np.allclose(color_grading(scene, (0, 0, 0), (0, 0, 0), (0, 0, 0), (0, 0, 0)), scene, atol=1e-5)


# ---------------------------------------------------------------------------------------
# Real photos (testdata/photos): the owner's POCO F7 Ultra Ultra HDR JPEGs, Display P3
# (the ICC colourants are exactly Display P3), 3072 x 4096, EXIF orientation 0 (as stored).
# ---------------------------------------------------------------------------------------

PHOTO_BLOCK = 32  # block size of the photo goldens: 3072 x 4096 -> 96 x 128
PHOTO_ROWS = 512  # rows per chunk when a pointwise op runs over a full-resolution photo

# name -> file in testdata/photos
PHOTOS = {
    "drop": "IMG_20261002_132928.jpg",  # conifer twig with a water drop, greens, soft background
    "bird": "IMG_20261028_114803.jpg",  # red bird, leaves, bright overcast sky
    "marigold": "IMG_20261031_165350.jpg",  # saturated orange marigolds (P3 gamut)
}


def _orange_saturation(image: np.ndarray, amount: float = 60.0) -> np.ndarray:
    sat = [0.0] * 8
    sat[1] = amount  # band 1 = orange
    return hsl_mixer(image, [0.0] * 8, sat, [0.0] * 8)


# The phone-checklist edits of Stages 6 and 7, as (photo, reference fn, graph op JSON).
# Pointwise, so they also run on the full-resolution photo (end-to-end goldens).
S_CURVE = [(0.0, 0.0), (0.25, 0.18), (0.75, 0.82), (1.0, 1.0)]
TEAL_ORANGE = ((200.0, 60.0, 0.0), (0.0, 0.0, 0.0), (55.0, 50.0, 0.0), (0.0, 0.0, 0.0), 50.0, 0.0)
PHOTO_EDITS = {
    "drop_s_curve": ("drop", lambda im: curves(im, rgb=S_CURVE)),
    "bird_teal_orange": ("bird", lambda im: color_grading(im, *TEAL_ORANGE)),
    "marigold_orange_saturation": ("marigold", _orange_saturation),
}
# Image-wide (not pointwise) cases, on the 96 x 128 photo only.
PHOTO_REGION_EDITS = {
    "bird_highlights_shadows": ("bird", lambda im: highlights_shadows(im, -80.0, 60.0)),
}


def photo_rec2020(path) -> np.ndarray:
    """A Display P3 JPEG -> linear Rec.2020 RGBA float32 (opaque), full resolution."""
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION)
    if bgr is None:
        raise FileNotFoundError(path)
    rgb = bgr[..., ::-1]
    out = np.empty(rgb.shape[:2] + (4,), dtype=np.float32)
    for y in range(0, rgb.shape[0], PHOTO_ROWS):
        rows = rgb[y : y + PHOTO_ROWS].astype(np.float64) / 255.0
        rgba = np.concatenate([rows, np.ones(rows.shape[:2] + (1,))], axis=-1)
        out[y : y + PHOTO_ROWS] = rgb_to_rgb(srgb_eotf(rgba), "p3", "rec2020")
    return out


def pointwise_chunked(fn, image: np.ndarray) -> np.ndarray:
    """A pointwise op over a large image, PHOTO_ROWS rows at a time (exact: no neighbours)."""
    out = np.empty_like(image, dtype=np.float32)
    for y in range(0, image.shape[0], PHOTO_ROWS):
        out[y : y + PHOTO_ROWS] = fn(image[y : y + PHOTO_ROWS])
    return out


def block_mean(image: np.ndarray, block: int = PHOTO_BLOCK) -> np.ndarray:
    """Mean of each block x block square (in float64), as the device test sums tiles."""
    h, w, c = image.shape
    blocks = image.reshape(h // block, block, w // block, block, c).astype(np.float64)
    return blocks.mean(axis=(1, 3)).astype(np.float32)
