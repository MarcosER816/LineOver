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
_REC2020 = colour.RGB_COLOURSPACES["ITU-R BT.2020"]


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
