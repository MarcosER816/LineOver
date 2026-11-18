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


def working_to_display(rgba_f32: np.ndarray, display: str) -> np.ndarray:
    """Working space -> encoded display values (0..1), clipped to the display gamut."""
    linear = rgb_to_rgb(rgba_f32, "rec2020", display).astype(np.float64)
    encoded = colour.cctf_encoding(np.clip(linear[..., :3], 0.0, 1.0), function="sRGB")
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
