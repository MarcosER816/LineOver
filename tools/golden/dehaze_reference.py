"""Single image haze removal using the dark channel prior (He, Sun and Tang, CVPR 2009 /
TPAMI 2011), with the transmission refined by the guided filter (He, Sun and Tang, ECCV
2010) instead of soft matting, as in the authors' later work. This is LineOver's reference
for Effects -> Dehaze (Stage 10); `detail_reference.dehaze` builds on it.

Images are gamma-encoded RGB in [0, 1] (the paper's setting). Steps:

1. dark channel: J_dark(x) = min over the patch Omega(x) of min over c of I_c(y);
2. atmospheric light A: among the brightest 0.1 % pixels of the dark channel, the pixel of
   highest intensity in I;
3. transmission: t(x) = 1 - omega * dark channel of I / A (omega = 0.95 keeps a little haze);
4. refinement: the guided filter of t with the grey image as guide (radius 4x the patch);
5. recovery: J = (I - A) / max(t, t0) + A, t0 = 0.1.
"""

import numpy as np

OMEGA = 0.95
T0 = 0.1
TOP_FRACTION = 0.001
PATCH_FRACTION = 0.01  # patch radius: 1 % of the long edge (15 x 15 on a 700 px image)
GUIDED_EPS = 1e-3
LUMA = np.array([0.2627, 0.6780, 0.0593])  # Rec.2020: the guide is the grey image
A_MIN = 0.05


def patch_radius(width: int, height: int) -> int:
    return max(1, int(np.floor(PATCH_FRACTION * max(width, height) + 0.5)))


def min_filter(a: np.ndarray, r: int) -> np.ndarray:
    """Minimum over the (2r+1)^2 window, clipped to the image (separable)."""
    h, w = a.shape
    out = a.copy()
    padded = np.pad(out, ((0, 0), (r, r)), mode="edge")
    out = np.min(np.stack([padded[:, k : k + w] for k in range(2 * r + 1)]), axis=0)
    padded = np.pad(out, ((r, r), (0, 0)), mode="edge")
    return np.min(np.stack([padded[k : k + h, :] for k in range(2 * r + 1)]), axis=0)


def box_mean(a: np.ndarray, r: int) -> np.ndarray:
    """Mean over the (2r+1)^2 window clipped to the image (count-normalised)."""
    h, w = a.shape
    s = np.zeros((h + 1, w + 1))
    s[1:, 1:] = np.cumsum(np.cumsum(a, axis=0), axis=1)
    y, x = np.mgrid[0:h, 0:w]
    y0, y1 = np.maximum(y - r, 0), np.minimum(y + r + 1, h)
    x0, x1 = np.maximum(x - r, 0), np.minimum(x + r + 1, w)
    total = s[y1, x1] - s[y0, x1] - s[y1, x0] + s[y0, x0]
    return total / ((y1 - y0) * (x1 - x0))


def dark_channel(image: np.ndarray, r: int) -> np.ndarray:
    return min_filter(np.min(image, axis=2), r)


def atmospheric_light(image: np.ndarray, dark: np.ndarray) -> np.ndarray:
    """The highest-intensity pixel among the brightest 0.1 % of the dark channel."""
    flat_dark = dark.reshape(-1)
    n = max(1, int(np.floor(TOP_FRACTION * flat_dark.size)))
    # Stable: equal dark values keep pixel order.
    order = np.argsort(-flat_dark, kind="stable")[:n]
    flat = image.reshape(-1, 3)
    intensity = flat[order].sum(axis=1)
    best = order[int(np.argmax(intensity))]
    return np.maximum(flat[best], A_MIN)


def guided_coefficients(guide: np.ndarray, p: np.ndarray, r: int, eps: float) -> tuple[np.ndarray, np.ndarray]:
    """Guided filter (grey guide): q = mean(a) * guide + mean(b)."""
    mean_i = box_mean(guide, r)
    mean_p = box_mean(p, r)
    cov = box_mean(guide * p, r) - mean_i * mean_p
    var = box_mean(guide * guide, r) - mean_i * mean_i
    a = cov / (var + eps)
    b = mean_p - a * mean_i
    return box_mean(a, r), box_mean(b, r)


def haze_model(image: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """(A, field a, field b, grey guide): the refined dark channel of I / A is a * grey + b, so
    for any omega the refined transmission is 1 - omega (a * grey + b) (the guided filter is
    linear in its input and keeps constants)."""
    h, w = image.shape[:2]
    r = patch_radius(w, h)
    dark = dark_channel(image, r)
    a_light = atmospheric_light(image, dark)
    normalised = dark_channel(image / a_light, r)
    grey = image @ LUMA
    fa, fb = guided_coefficients(grey, normalised, 4 * r, GUIDED_EPS)
    return a_light, fa, fb, grey


def dehaze(image: np.ndarray, omega: float = OMEGA) -> np.ndarray:
    """He et al.: the haze-free image J of the encoded RGB image I (float64, 0..1)."""
    a_light, fa, fb, grey = haze_model(image)
    t = np.maximum(1.0 - omega * (fa * grey + fb), T0)
    return (image - a_light) / t[..., None] + a_light
