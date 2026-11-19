"""Stage 12 export: reference implementations of the pixel steps between the full-resolution
render (linear Rec. 2020, float) and the encoder, and of the ICC profiles LineOver embeds.

Steps, in the export's order (core/engine-native export.cpp runs the same):
  resize (Lanczos-3, linear light, never upscales) -> sharpen for screen -> watermark ->
  convert (gamut map to the target, transfer, quantise to 8 or 16 bit).
"""

from __future__ import annotations

import math
import struct

import numpy as np

import reference

# --- Colour spaces --------------------------------------------------------------------------

D65_XY = (0.3127, 0.3290)
D50_PCS = (0.9642, 1.0, 0.8249)  # the ICC profile connection space illuminant
PRIMARIES = {
    "srgb": ((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)),
    "p3": ((0.680, 0.320), (0.265, 0.690), (0.150, 0.060)),
    "rec2020": ((0.708, 0.292), (0.170, 0.797), (0.131, 0.046)),
}
NAMES = {"srgb": "LineOver sRGB", "p3": "LineOver Display P3", "rec2020": "LineOver Rec. 2020"}
COPYRIGHT = "No copyright, use freely"

# Transfer curves as ICC parametric type 3: Y = (a X + b)^g for X >= d, else c X.
# sRGB / Display P3: the sRGB curve. Rec. 2020: the BT.2020 / BT.709 curve, as Android's
# ColorSpace.Named.BT2020 has it (so decoders recognise the profile).
SRGB_CURVE = (2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)
BT2020_CURVE = (1 / 0.45, 1 / 1.0993, 0.0993 / 1.0993, 1 / 4.5, 0.08145)
CURVES = {"srgb": SRGB_CURVE, "p3": SRGB_CURVE, "rec2020": BT2020_CURVE}

BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])


def _xyz(xy) -> np.ndarray:
    x, y = xy
    return np.array([x / y, 1.0, (1.0 - x - y) / y])


def rgb_to_xyz_d65(space: str) -> np.ndarray:
    """Normalised primary matrix from the xy primaries and D65 (columns: R, G, B)."""
    p = np.stack([_xyz(c) for c in PRIMARIES[space]], axis=1)
    s = np.linalg.inv(p) @ _xyz(D65_XY)
    return p * s[None, :]


def chad_d65_to_d50() -> np.ndarray:
    src = BRADFORD @ _xyz(D65_XY)
    dst = BRADFORD @ np.array(D50_PCS)
    return np.linalg.inv(BRADFORD) @ np.diag(dst / src) @ BRADFORD


def bt2020_oetf(x: np.ndarray) -> np.ndarray:
    """Inverse of BT2020_CURVE: linear 0..1 -> encoded."""
    g, a, b, c, d = BT2020_CURVE
    beta = d * c  # the linear value where the two pieces meet
    return np.where(x >= beta, (np.maximum(x, beta) ** (1.0 / g) - b) / a, x / c)


def srgb_oetf(x: np.ndarray) -> np.ndarray:
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.maximum(x, 0.0031308) ** (1.0 / 2.4) - 0.055)


def convert(image: np.ndarray, space: str, bits: int) -> np.ndarray:
    """Working RGBA -> integer codes (as float32, alpha = max code). sRGB and Display P3 are
    gamut-mapped like the display; Rec. 2020 is the working space's own primaries (clipped
    to 0..1). Values above 1 (HDR) clip: the Ultra HDR gain map carries them."""
    rgb = image[..., :3].astype(np.float64)
    if space in ("srgb", "p3"):
        encoded = srgb_oetf(reference.gamut_map(rgb, space))
    else:
        encoded = bt2020_oetf(np.clip(rgb, 0.0, 1.0))
    top = float((1 << bits) - 1)
    codes = np.floor(np.clip(encoded, 0.0, 1.0) * top + 0.5)
    out = np.full(image.shape[:2] + (4,), top)
    out[..., :3] = codes
    return out.astype(np.float32)


# --- Resize ---------------------------------------------------------------------------------

LANCZOS = 3


def lanczos(t: np.ndarray) -> np.ndarray:
    t = np.abs(t)
    out = np.zeros_like(t)
    inside = (t < LANCZOS) & (t > 0)
    pt = math.pi * t[inside]
    out[inside] = LANCZOS * np.sin(pt) * np.sin(pt / LANCZOS) / (pt * pt)
    out[t == 0] = 1.0
    return out


def _resize_axis(image: np.ndarray, size: int, axis: int) -> np.ndarray:
    n = image.shape[axis]
    scale = n / size
    f = max(scale, 1.0)
    support = LANCZOS * f
    rows = []
    for o in range(size):
        centre = (o + 0.5) * scale - 0.5
        first = math.floor(centre - support) + 1
        last = math.floor(centre + support)
        taps = np.arange(first, last + 1)
        w = lanczos((taps - centre) / f)
        w = w / w.sum()
        idx = np.clip(taps, 0, n - 1)
        part = np.take(image, idx, axis=axis)
        shape = [1] * image.ndim
        shape[axis] = len(w)
        rows.append(np.sum(part * w.reshape(shape), axis=axis))
    return np.stack(rows, axis=axis)


def resize(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Lanczos-3 in linear light, horizontal then vertical; the kernel widens when it
    shrinks (no aliasing). Never used to enlarge."""
    h = _resize_axis(image.astype(np.float64), width, 1)
    return _resize_axis(h, height, 0).astype(np.float32)


# --- Sharpen for screen ---------------------------------------------------------------------

SHARPEN_SIGMA = 0.7  # output pixels
SHARPEN_RADIUS = 3
SHARPEN_AMOUNTS = {"low": 0.35, "standard": 0.7, "high": 1.2}
SHARPEN_MAX_RATIO = 4.0
SHARPEN_EPS = 1e-6


def _gauss_weights() -> np.ndarray:
    k = np.arange(-SHARPEN_RADIUS, SHARPEN_RADIUS + 1)
    w = np.exp(-(k * k) / (2 * SHARPEN_SIGMA * SHARPEN_SIGMA))
    return w / w.sum()


def _blur(a: np.ndarray) -> np.ndarray:
    w = _gauss_weights()
    h, wd = a.shape
    out = np.zeros_like(a)
    for i, k in enumerate(range(-SHARPEN_RADIUS, SHARPEN_RADIUS + 1)):
        out += w[i] * a[:, np.clip(np.arange(wd) + k, 0, wd - 1)]
    res = np.zeros_like(a)
    for i, k in enumerate(range(-SHARPEN_RADIUS, SHARPEN_RADIUS + 1)):
        res += w[i] * out[np.clip(np.arange(h) + k, 0, h - 1), :]
    return res


def sharpen(image: np.ndarray, amount: float) -> np.ndarray:
    """Unsharp mask on cube-root luminance (a perceptual lightness), applied to RGB as a
    luminance ratio so colours keep their chroma."""
    rgb = image[..., :3].astype(np.float64)
    y = np.maximum(rgb @ reference.LUMA, 0.0)
    lightness = np.cbrt(y)
    sharp = np.maximum(lightness + amount * (lightness - _blur(lightness)), 0.0)
    ratio = np.where(lightness > SHARPEN_EPS, np.minimum((sharp / np.maximum(lightness, SHARPEN_EPS)) ** 3, SHARPEN_MAX_RATIO), 1.0)
    out = image.astype(np.float64).copy()
    out[..., :3] = rgb * ratio[..., None]
    return out.astype(np.float32)


# --- Watermark ------------------------------------------------------------------------------

def test_watermark() -> np.ndarray:
    """A 40 x 12 premultiplied sRGB RGBA8 mark: a white bar with a soft edge and a red dot."""
    h, w = 12, 40
    yy, xx = np.mgrid[0:h, 0:w]
    alpha = np.clip(1.0 - np.abs(yy - 5.5) / 6.0, 0.0, 1.0) * np.clip(xx / 4.0, 0.0, 1.0)
    rgb = np.ones((h, w, 3))
    dot = (xx - 30) ** 2 + (yy - 6) ** 2 < 16
    rgb[dot] = (0.9, 0.1, 0.1)
    a8 = np.floor(alpha * 255 + 0.5)
    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[..., :3] = np.floor(rgb * a8[..., None] + 0.5)  # premultiplied, as Android stores it
    out[..., 3] = a8
    return out


def watermark(image: np.ndarray, mark: np.ndarray, x: int, y: int, opacity: float) -> np.ndarray:
    """Composites a premultiplied sRGB RGBA8 mark at (x, y) with opacity, in linear light."""
    out = image.astype(np.float64).copy()
    h, w = mark.shape[:2]
    a = mark[..., 3].astype(np.float64) / 255.0
    c = np.where(a[..., None] > 0, mark[..., :3] / 255.0 / np.maximum(a[..., None], 1e-12), 0.0)
    c = np.clip(c, 0.0, 1.0)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.linalg.inv(rgb_to_xyz_d65("rec2020")) @ rgb_to_xyz_d65("srgb")
    lin = lin @ m.T
    k = (a * opacity)[..., None]
    y0, x0 = max(y, 0), max(x, 0)
    y1, x1 = min(y + h, out.shape[0]), min(x + w, out.shape[1])
    if y1 <= y0 or x1 <= x0:
        return out.astype(np.float32)
    sub = (slice(y0 - y, y1 - y), slice(x0 - x, x1 - x))
    region = out[y0:y1, x0:x1, :3]
    out[y0:y1, x0:x1, :3] = region * (1 - k[sub]) + lin[sub] * k[sub]
    return out.astype(np.float32)


# --- Ultra HDR gain map --------------------------------------------------------------------

GAINMAP_EPS = 1.0 / 64.0
GAINMAP_MIN_LOG = 1e-4


def base_linear(image: np.ndarray, space: str) -> np.ndarray:
    """The linear values the conversion encodes (target primaries), for the gain map."""
    rgb = image[..., :3].astype(np.float64)
    return reference.gamut_map(rgb, space) if space in ("srgb", "p3") else np.clip(rgb, 0.0, 1.0)


def gainmap(image: np.ndarray, space: str, factor: int) -> tuple[np.ndarray, float]:
    """Per factor x factor block: mean log2((Y_hdr + eps) / (Y_sdr + eps)) (>= 0), mapped to
    0..255 over 0..log2(ratioMax). Returns (codes, ratioMax); ratioMax 0 = no HDR."""
    y_hdr = np.maximum(image[..., :3].astype(np.float64) @ rgb_to_xyz_d65("rec2020")[1], 0.0)
    y_sdr = base_linear(image, space) @ rgb_to_xyz_d65(space)[1]
    log = np.log2(np.maximum((y_hdr + GAINMAP_EPS) / (y_sdr + GAINMAP_EPS), 1.0))
    h, w = log.shape
    gh, gw = -(-h // factor), -(-w // factor)
    means = np.zeros((gh, gw))
    for by in range(gh):
        for bx in range(gw):
            means[by, bx] = log[by * factor:(by + 1) * factor, bx * factor:(bx + 1) * factor].mean()
    top = means.max()
    if top < GAINMAP_MIN_LOG:
        return np.zeros((gh, gw)), 0.0
    return np.floor(np.clip(means / top, 0.0, 1.0) * 255.0 + 0.5), float(2.0 ** top)


# --- ICC ------------------------------------------------------------------------------------

def _s15f16(v: float) -> bytes:
    return struct.pack(">i", math.floor(v * 65536.0 + 0.5))


def _mluc(text: str) -> bytes:
    s = text.encode("utf-16-be")
    body = b"mluc" + b"\0" * 4 + struct.pack(">II", 1, 12) + b"enUS" + struct.pack(">II", len(s), 28) + s
    return body


def _xyz_tag(v) -> bytes:
    return b"XYZ " + b"\0" * 4 + b"".join(_s15f16(x) for x in v)


def _pad(b: bytes) -> bytes:
    return b + b"\0" * (-len(b) % 4)


def icc_profile(space: str) -> bytes:
    """An ICC v4.3 display profile (matrix / TRC): D50-adapted colorants (Bradford), a
    parametric curve, chad, D50 white; no profile ID (zero, allowed by the spec)."""
    chad = chad_d65_to_d50()
    colorants = chad @ rgb_to_xyz_d65(space)
    g, a, b, c, d = CURVES[space]
    para = b"para" + b"\0" * 4 + struct.pack(">HH", 3, 0) + b"".join(_s15f16(x) for x in (g, a, b, c, d))
    tags = [
        (b"desc", _mluc(NAMES[space])),
        (b"cprt", _mluc(COPYRIGHT)),
        (b"wtpt", _xyz_tag(D50_PCS)),
        (b"chad", b"sf32" + b"\0" * 4 + b"".join(_s15f16(x) for x in chad.reshape(-1))),
        (b"rXYZ", _xyz_tag(colorants[:, 0])),
        (b"gXYZ", _xyz_tag(colorants[:, 1])),
        (b"bXYZ", _xyz_tag(colorants[:, 2])),
        (b"rTRC", para),
        (b"gTRC", para),
        (b"bTRC", para),
    ]
    table_size = 4 + 12 * len(tags)
    offset = 128 + table_size
    data = b""
    entries = b""
    placed: dict[bytes, int] = {}
    for sig, body in tags:
        if body in placed:  # the three TRCs share one curve
            at = placed[body]
        else:
            at = offset + len(data)
            placed[body] = at
            data += _pad(body)
        entries += sig + struct.pack(">II", at, len(body))
    size = offset + len(data)
    header = (
        struct.pack(">I", size)
        + b"\0" * 4
        + struct.pack(">I", 0x04300000)
        + b"mntr"
        + b"RGB "
        + b"XYZ "
        + struct.pack(">6H", 2026, 1, 1, 0, 0, 0)
        + b"acsp"
        + b"\0" * 4 * 5  # platform, flags, manufacturer, model, attributes (8 bytes) -> 20
        + b"\0" * 4  # attributes, second half
        + b"\0" * 4  # rendering intent: perceptual
        + b"".join(_s15f16(x) for x in D50_PCS)
        + b"\0" * 4  # creator
        + b"\0" * 16  # profile ID
        + b"\0" * 28
    )
    assert len(header) == 128, len(header)
    return header + struct.pack(">I", len(tags)) + entries + data


def self_check() -> None:
    # The sRGB colorants match the published D50 values of sRGB profiles to 1e-4.
    m = chad_d65_to_d50() @ rgb_to_xyz_d65("srgb")
    assert np.allclose(m[:, 0], [0.4361, 0.2225, 0.0139], atol=2e-4), m
    # Encoding inverts the ICC curves.
    x = np.linspace(0, 1, 101)
    g, a, b, c, d = BT2020_CURVE
    e = bt2020_oetf(x)
    back = np.where(e >= d, (a * e + b) ** g, c * e)
    assert np.allclose(back, x, atol=1e-9)
    # Resizing a constant keeps it; Lanczos at scale 1 is the identity.
    flat = np.full((9, 13, 4), 0.25, dtype=np.float32)
    assert np.allclose(resize(flat, 5, 4), 0.25, atol=1e-6)
    scene = reference.scene_rec2020()
    assert np.allclose(resize(scene, scene.shape[1], scene.shape[0]), scene, atol=1e-6)
    for space in PRIMARIES:
        assert len(icc_profile(space)) % 4 == 0
