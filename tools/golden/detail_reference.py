"""Stage 10 references: Detail (Sharpen, Noise reduction) and Effects (Clarity, Texture,
Dehaze, post-crop Vignette, Grain). The C++ cpuRef / cpuRefRegion and the GLSL passes
implement exactly these formulas; tests compare them (float ops 1e-3, GPU 3/255).

Conventions shared with the engine:
- images are linear Rec.2020 RGBA (float); luminance Y = Rec.2020 luma;
- `scale` = buffer pixels per full-resolution pixel (1 at export, < 1 on the preview):
  every radius is given in full-resolution pixels and multiplied by `scale`, so the preview
  shows what the export will look like (Stage 10 brief);
- sampling outside the buffer clamps to its edge (tiles carry an apron of their neighbours);
- Clarity, Texture and Dehaze read image-wide fields computed once from the unedited
  preview (fast guided filter: base = a * guide + b with a, b box-filtered, He 2015) and the
  luminance of the image as it enters the first of them ("original"); the fields here are
  computed from the test image itself;
- Vignette and Grain are output-space finishing (after the geometry), at output uv.
"""

import numpy as np

import dehaze_reference as he

LUMA = np.array([0.2627, 0.6780, 0.0593])
LUMA_EPS = 1e-6
MAX_TAPS = 12


# ---------------------------------------------------------------------------- helpers


def encode(x):
    """sRGB transfer, odd-symmetric (as reference.encode)."""
    a = np.abs(x)
    e = np.where(a <= 0.0031308, a * 12.92, 1.055 * np.power(a, 1.0 / 2.4) - 0.055)
    return np.sign(x) * e


def decode(e):
    a = np.abs(e)
    x = np.where(a <= 0.04045, a / 12.92, np.power((a + 0.055) / 1.055, 2.4))
    return np.sign(e) * x


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def fetch(a, x, y):
    """a[y, x] with integer coordinates clamped to the buffer."""
    h, w = a.shape[:2]
    return a[np.clip(y, 0, h - 1), np.clip(x, 0, w - 1)]


def bilinear(a, x, y):
    """Bilinear sample at pixel coordinates (centres at integers), clamped."""
    x0 = np.floor(x)
    y0 = np.floor(y)
    fx = x - x0
    fy = y - y0
    x0 = x0.astype(np.int64)
    y0 = y0.astype(np.int64)
    top = fetch(a, x0, y0) * (1 - fx) + fetch(a, x0 + 1, y0) * fx
    bottom = fetch(a, x0, y0 + 1) * (1 - fx) + fetch(a, x0 + 1, y0 + 1) * fx
    return top * (1 - fy) + bottom * fy


def gaussian_weights(sigma):
    """Normalised taps -K..K, K = min(12, ceil(3 sigma)); [1] when sigma is negligible."""
    if sigma < 0.01:
        return np.array([1.0])
    k = min(MAX_TAPS, int(np.ceil(3.0 * sigma)))
    x = np.arange(-k, k + 1, dtype=np.float64)
    w = np.exp(-x * x / (2.0 * sigma * sigma))
    return w / w.sum()


def gaussian(a, sigma):
    """Separable Gaussian, clamped edges (horizontal then vertical)."""
    w = gaussian_weights(sigma)
    k = len(w) // 2
    h, wd = a.shape
    y, x = np.mgrid[0:h, 0:wd]
    out = sum(w[i] * fetch(a, x + i - k, y) for i in range(len(w)))
    return sum(w[i] * fetch(out, x, y + i - k) for i in range(len(w)))


def apply_luminance_ratio(image, y_new, y_old):
    """RGB scaled by Y' / Y where Y > eps (alpha unchanged)."""
    out = image.astype(np.float64).copy()
    valid = y_old > LUMA_EPS
    ratio = np.where(valid, y_new / np.where(valid, y_old, 1.0), 1.0)
    out[..., :3] *= ratio[..., None]
    return out


# --------------------------------------------------------------------------- Sharpen

SHARPEN_LIMIT_MIN = 0.01
SHARPEN_LIMIT_RANGE = 0.25
SHARPEN_MASK_EDGE = 0.02  # masking 100: edges below 0.02 encoded units per pixel are protected


def sharpen(image, amount, radius, detail, masking, scale=1.0, show_mask=False):
    """Unsharp mask on encoded luminance P = encode(Y).

    D = P - gauss(P, radius * scale), softly limited to +-limit (tanh; Detail 0 keeps only
    fine detail, 100 lets wide halos through), weighted by the Masking edge mask (Sobel
    magnitude of the blurred P in units per full-resolution pixel, smoothstep around
    masking / 100 * 0.02) and added with Amount / 100. show_mask returns the mask as grey.
    """
    img = image.astype(np.float64)
    y = img[..., :3] @ LUMA
    p = encode(np.maximum(y, 0.0))
    blurred = gaussian(p, radius * scale)
    d = p - blurred
    limit = SHARPEN_LIMIT_MIN + SHARPEN_LIMIT_RANGE * (detail / 100.0) ** 2
    d = limit * np.tanh(d / limit)
    mask = sobel_mask(blurred, masking, scale)
    if show_mask:
        out = img.copy()
        out[..., :3] = decode(mask)[..., None]
        return out
    p2 = p + (amount / 100.0) * d * mask
    return apply_luminance_ratio(img, decode(p2), y)


def sobel_mask(p, masking, scale):
    if masking <= 0.0:
        return np.ones_like(p)
    h, w = p.shape
    y, x = np.mgrid[0:h, 0:w]

    def f(dx, dy):
        return fetch(p, x + dx, y + dy)

    gx = (f(1, -1) + 2 * f(1, 0) + f(1, 1) - f(-1, -1) - 2 * f(-1, 0) - f(-1, 1)) / 8.0
    gy = (f(-1, 1) + 2 * f(0, 1) + f(1, 1) - f(-1, -1) - 2 * f(0, -1) - f(1, -1)) / 8.0
    g = np.sqrt(gx * gx + gy * gy) * scale
    t = masking / 100.0 * SHARPEN_MASK_EDGE
    return smoothstep(0.5 * t, 1.5 * t, g)


# ------------------------------------------------------------------- Noise reduction

NR_LEVELS = 4
NR_BASE = (0.035, 0.02, 0.012, 0.006)  # soft thresholds at Luminance 100, finest first
NR_DETAIL_KEEP = 0.6
NR_CONTRAST = 0.6
B3 = np.array([1.0, 4.0, 6.0, 4.0, 1.0]) / 16.0
YCC_R = 1.4746  # Rec.2020 Y'CbCr
YCC_B = 1.8814


def to_ycc(image):
    e = encode(image[..., :3].astype(np.float64))
    ey = e @ LUMA
    return ey, (e[..., 2] - ey) / YCC_B, (e[..., 0] - ey) / YCC_R


def from_ycc(image, ey, cb, cr):
    er = ey + YCC_R * cr
    eb = ey + YCC_B * cb
    eg = (ey - LUMA[0] * er - LUMA[2] * eb) / LUMA[1]
    out = image.astype(np.float64).copy()
    out[..., 0] = decode(er)
    out[..., 1] = decode(eg)
    out[..., 2] = decode(eb)
    return out


def a_trous_level(c, spacing):
    """One B3-spline a-trous step with holes `spacing` pixels apart (bilinear, clamped)."""
    h, w = c.shape
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    out = np.zeros_like(c)
    for j in range(5):
        for i in range(5):
            out += B3[i] * B3[j] * bilinear(c, x + (i - 2) * spacing, y + (j - 2) * spacing)
    return out


def soft(w, t):
    return np.sign(w) * np.maximum(np.abs(w) - t, 0.0)


def noise_reduction(image, luminance, detail, contrast, color, scale=1.0):
    """Luminance: a-trous wavelet (B3 spline, 4 levels, holes 2^k * scale pixels) on Y'
    with soft thresholds Luminance / 100 * NR_BASE[k], the two finest relaxed by Detail;
    Contrast puts back part of what the two coarsest levels lost. Colour: a bilateral
    filter of Cb / Cr (7 x 7 taps over a radius 1 + 6 * Color / 100 full-resolution pixels,
    weighted by distance, chroma difference and Y' difference)."""
    img = image.astype(np.float64)
    ey, cb, cr = to_ycc(img)
    if luminance > 0.0 or contrast > 0.0:
        c = ey
        acc = np.zeros_like(ey)
        removed = np.zeros_like(ey)
        for k in range(NR_LEVELS):
            nxt = a_trous_level(c, (2.0**k) * scale)
            w = c - nxt
            keep = 1.0 - NR_DETAIL_KEEP * detail / 100.0 if k < 2 else 1.0
            t = luminance / 100.0 * NR_BASE[k] * keep
            s = soft(w, t)
            acc += s
            if k >= 2:
                removed += w - s
            c = nxt
        ey2 = c + acc + contrast / 100.0 * NR_CONTRAST * removed
    else:
        ey2 = ey
    if color > 0.0:
        cb, cr = chroma_bilateral(ey, cb, cr, color, scale)
    return from_ycc(img, ey2, cb, cr)


NR_SIGMA_Y = 0.1


def chroma_bilateral(ey, cb, cr, color, scale):
    radius = 1.0 + 6.0 * color / 100.0
    step = radius * scale / 3.0
    sigma_s = radius / 2.0
    sigma_c = 0.01 + 0.05 * color / 100.0
    h, w = ey.shape
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    num_b = np.zeros_like(cb)
    num_r = np.zeros_like(cr)
    den = np.zeros_like(cb)
    for j in range(-3, 4):
        for i in range(-3, 4):
            sx = x + i * step
            sy = y + j * step
            sb = bilinear(cb, sx, sy)
            sr = bilinear(cr, sx, sy)
            sy_ = bilinear(ey, sx, sy)
            dist2 = (i * i + j * j) * (radius / 3.0) ** 2
            wgt = (np.exp(-dist2 / (2 * sigma_s * sigma_s))
                   * np.exp(-((sb - cb) ** 2 + (sr - cr) ** 2) / (2 * sigma_c * sigma_c))
                   * np.exp(-((sy_ - ey) ** 2) / (2 * NR_SIGMA_Y * NR_SIGMA_Y)))
            num_b += wgt * sb
            num_r += wgt * sr
            den += wgt
    return num_b / den, num_r / den


# ------------------------------------------------------------ Clarity / Texture fields

CLARITY_RADIUS = 0.02
CLARITY_EPS = 0.04
CLARITY_STRENGTH = 1.0
TEXTURE_RADIUS = 0.003
TEXTURE_EPS = 0.005
TEXTURE_STRENGTH = 0.7


def log_luminance(image):
    return np.log2(np.maximum(image[..., :3].astype(np.float64) @ LUMA, LUMA_EPS))


def field_radius(fraction, width, height):
    return max(1, int(np.floor(fraction * max(width, height) + 0.5)))


def self_guided(guide, r, eps):
    mean = he.box_mean(guide, r)
    var = he.box_mean(guide * guide, r) - mean * mean
    a = var / (var + eps)
    b = mean - a * mean
    return he.box_mean(a, r), he.box_mean(b, r)


def detail_fields(preview):
    """The image-wide fields of the unedited preview: (clarity a, b), (texture a, b) on
    log2 luminance, and the haze model (A, a, b) on the encoded image."""
    h, w = preview.shape[:2]
    lum = log_luminance(preview)
    clarity = self_guided(lum, field_radius(CLARITY_RADIUS, w, h), CLARITY_EPS)
    texture = self_guided(lum, field_radius(TEXTURE_RADIUS, w, h), TEXTURE_EPS)
    encoded = np.clip(encode(preview[..., :3].astype(np.float64)), 0.0, 1.0)
    a_light, fa, fb, _ = he.haze_model(encoded)
    return {"clarity": clarity, "texture": texture, "haze": (a_light, fa, fb)}


def _local_contrast(image, original, amount, field, strength, midtones):
    img = image.astype(np.float64)
    a, b = field
    l0 = log_luminance(original)
    d = l0 - (a * l0 + b)
    y = img[..., :3] @ LUMA
    l = np.log2(np.maximum(y, LUMA_EPS))
    if midtones:
        pc = np.clip(encode(np.maximum(y, 0.0)), 0.0, 1.0)
        weight = 4.0 * pc * (1.0 - pc)
    else:
        weight = 1.0
    l2 = l + amount / 100.0 * strength * d * weight
    return apply_luminance_ratio(img, np.exp2(l2), y)


def clarity(image, amount, fields, original=None):
    """Clarity: midtone-weighted detail of log luminance against its edge-aware base
    (guided filter, radius 2 % of the long edge)."""
    return _local_contrast(image, image if original is None else original, amount, fields["clarity"], CLARITY_STRENGTH, True)


def texture(image, amount, fields, original=None):
    """Texture: the small-scale band (guided filter, radius 0.3 %), all tones."""
    return _local_contrast(image, image if original is None else original, amount, fields["texture"], TEXTURE_STRENGTH, False)


# ---------------------------------------------------------------------------- Dehaze


def dehaze(image, amount, fields, original=None):
    """Dehaze in [-100, 100] on the encoded image: positive recovers J = (I - A) / t + A
    with t = max(1 - 0.95 * amount / 100 * G, 0.1) (He et al.; 100 is the paper's omega);
    negative adds haze, I t + A (1 - t) with t = 1 - |amount| / 100 * (0.25 + 0.5 G).
    G = a * grey + b is the refined dark channel of the original (dehaze_reference)."""
    img = image.astype(np.float64)
    src = img if original is None else original.astype(np.float64)
    a_light, fa, fb = fields["haze"]
    grey = np.clip(encode(src[..., :3]), 0.0, 1.0) @ he.LUMA
    g = fa * grey + fb
    e = encode(img[..., :3])
    if amount >= 0:
        t = np.maximum(1.0 - he.OMEGA * amount / 100.0 * g, he.T0)
        j = (e - a_light) / t[..., None] + a_light
    else:
        t = 1.0 - (-amount / 100.0) * (0.25 + 0.5 * np.clip(g, 0.0, 1.0))
        j = e * t[..., None] + a_light * (1.0 - t[..., None])
    out = img.copy()
    out[..., :3] = decode(j)
    return out


# ------------------------------------------------------------- Vignette and Grain


def output_coordinates(h, w, scale, origin=(0.0, 0.0)):
    """Full-resolution output pixel coordinates of buffer pixel centres."""
    j, i = np.mgrid[0:h, 0:w].astype(np.float64)
    return origin[0] + (i + 0.5) / scale, origin[1] + (j + 0.5) / scale


def vignette(image, amount, midpoint, roundness, feather, highlights, out_w, out_h, scale=1.0, origin=(0.0, 0.0)):
    """Post-crop vignette on the encoded image, by output position: a superellipse distance
    (Roundness: -100 rectangular .. 0 the frame's ellipse .. 100 a circle), a smoothstep
    from Midpoint over Feather; darkens (Highlights brings bright areas back) or lightens."""
    img = image.astype(np.float64)
    h, w = img.shape[:2]
    xf, yf = output_coordinates(h, w, scale, origin)
    qx = np.abs(xf / out_w - 0.5) * 2.0
    qy = np.abs(yf / out_h - 0.5) * 2.0
    aspect = out_w / out_h
    rho = roundness / 100.0
    sx = 1.0 + (aspect - 1.0) * max(rho, 0.0) if aspect >= 1.0 else 1.0
    sy = 1.0 + (1.0 / aspect - 1.0) * max(rho, 0.0) if aspect < 1.0 else 1.0
    pw = 2.0 + 6.0 * max(-rho, 0.0)
    d = np.power(np.power(qx * sx, pw) + np.power(qy * sy, pw), 1.0 / pw)
    r0 = 0.3 + 0.9 * midpoint / 100.0
    t = smoothstep(r0, r0 + 0.05 + 0.95 * feather / 100.0, d)
    e = encode(img[..., :3])
    k = amount / 100.0
    if k < 0:
        darker = e * (1.0 + k * t)[..., None]
        ey = np.clip(e @ LUMA, 0.0, 1.0)
        keep = highlights / 100.0 * smoothstep(0.7, 1.0, ey)
        e2 = darker + (e - darker) * keep[..., None]
    else:
        e2 = e + np.maximum(1.0 - e, 0.0) * (k * t)[..., None]
    out = img.copy()
    out[..., :3] = decode(e2)
    return out


GRAIN_SEED = 0x2545F491


def _u32(x):
    return np.asarray(x, dtype=np.int64).astype(np.uint64) & np.uint64(0xFFFFFFFF)


def grain_hash(ix, iy, seed):
    """32-bit integer hash of a lattice point (same arithmetic in C++ and GLSL)."""
    m = np.uint64(0xFFFFFFFF)
    h = (_u32(ix) * np.uint64(0x8DA6B343)) & m
    h ^= (_u32(iy) * np.uint64(0xD8163841)) & m
    h ^= (_u32(seed) * np.uint64(0xCB1AB31F)) & m
    h ^= h >> np.uint64(16)
    h = (h * np.uint64(0x7FEB352D)) & m
    h ^= h >> np.uint64(15)
    h = (h * np.uint64(0x846CA68B)) & m
    h ^= h >> np.uint64(16)
    return h


GRADIENTS = np.array([[1, 0], [-1, 0], [0, 1], [0, -1], [0.7071067811865476, 0.7071067811865476],
                      [-0.7071067811865476, 0.7071067811865476], [0.7071067811865476, -0.7071067811865476],
                      [-0.7071067811865476, -0.7071067811865476]])


GRAIN_TABLE = 1024  # the lattice repeats every 1024 cells (the GPU reads a 1024 x 1024 table)


def grain_gradient(ix, iy, seed):
    """Gradient index (0..7) of a lattice point: the hash of its position modulo the table."""
    t = np.int64(GRAIN_TABLE - 1)
    return (grain_hash(np.asarray(ix) & t, np.asarray(iy) & t, seed) & np.uint64(7)).astype(np.int64)


def perlin(x, y, seed):
    """2D gradient noise (Perlin, quintic fade, 8 gradients by hash), about -0.7 .. 0.7."""
    x0 = np.floor(x)
    y0 = np.floor(y)
    fx = x - x0
    fy = y - y0
    ix = x0.astype(np.int64)
    iy = y0.astype(np.int64)

    def corner(dx, dy):
        g = GRADIENTS[grain_gradient(ix + dx, iy + dy, seed)]
        return g[..., 0] * (fx - dx) + g[..., 1] * (fy - dy)

    def fade(t):
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

    u = fade(fx)
    v = fade(fy)
    top = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * u
    bottom = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * u
    return top + (bottom - top) * v


GRAIN_STRENGTH = 0.12


def grain(image, amount, size, roughness, scale=1.0, origin=(0.0, 0.0)):
    """Film grain on the encoded image: Perlin noise with cells of 1 + 3 * Size / 100
    output pixels plus a finer octave (Roughness), strongest in the midtones. Where a buffer
    pixel covers more than a cell (the zoomed-out preview) the noise is attenuated by
    cell / footprint, so the preview matches the downsampled export."""
    img = image.astype(np.float64)
    h, w = img.shape[:2]
    xf, yf = output_coordinates(h, w, scale, origin)
    footprint = 1.0 / scale
    cell = 1.0 + 3.0 * size / 100.0
    rough = roughness / 100.0
    n1 = perlin(xf / cell, yf / cell, GRAIN_SEED) * min(1.0, cell / footprint)
    n2 = perlin(2.0 * xf / cell, 2.0 * yf / cell, GRAIN_SEED + 1) * min(1.0, 0.5 * cell / footprint)
    n = (n1 + rough * n2) / (1.0 + 0.5 * rough)
    e = encode(img[..., :3])
    ey = np.clip(e @ LUMA, 0.0, 1.0)
    weight = 0.25 + 3.0 * ey * (1.0 - ey)
    e2 = e + (amount / 100.0 * GRAIN_STRENGTH * n * weight)[..., None]
    out = img.copy()
    out[..., :3] = decode(e2)
    return out


# ------------------------------------------------------------------- test images


def hazy(scene_encoded_rgb, airlight=(0.86, 0.88, 0.92)):
    """A hazy version of an encoded image: I = J t + A (1 - t), t falling with height (depth)."""
    h, w = scene_encoded_rgb.shape[:2]
    y = np.mgrid[0:h, 0:w][0].astype(np.float64)
    t = 0.35 + 0.6 * (y / (h - 1))
    a = np.array(airlight)
    return scene_encoded_rgb * t[..., None] + a * (1.0 - t[..., None])
