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
