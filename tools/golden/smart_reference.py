"""Smart selections (Stage 16): the reference for core/engine-native smart_select.cpp and the
new selection kinds in selection.cpp (ctest smart_select_test).

- Guide: the grid's colour as sqrt(clamp(rgb, 0, 1)) (a gamma-like encoding of the linear
  working space), used by the joint bilateral upsampling, the matting and GrabCut.
- Joint bilateral upsampling (Kopf et al. 2007): a low-resolution mask S (lh x lw) is brought to
  the h x w grid. Output pixel p sits at low-resolution coordinates u = (i + 0.5) lw / w - 0.5
  (likewise v); every low pixel q with |qx - u| <= 2 and |qy - v| <= 2 contributes
  S(q) exp(-|q - (u, v)|^2 / (2 sigma_s^2)) exp(-|G(p) - Glow(q)|^2 / (2 sigma_r^2)), normalised,
  with sigma_s = 1 (low pixels) and sigma_r = 0.1. Glow(q) is the mean of the guide pixels whose
  centres fall inside q's footprint (the nearest one when none does).
- Refine edge: a colour guided filter (He, Sun and Tang 2013) of the selection with that guide,
  box means of radius r with BORDER_REFLECT_101, eps = 2e-4, then contrast k = 1 + c / 20
  around 0.5 (c in 0..100), clamped.
- Live-wire (Intelligent Scissors, Mortensen and Barrett 1995) on a grey image: link cost
  0.43 fZ(q) + 0.43 fG(q) s + 0.14 fD(p, q), where fZ is 0 on a Laplacian zero crossing,
  fG = 1 - |grad| / max |grad| (Sobel), s = 1 for diagonal links and 1 / sqrt(2) otherwise,
  fD the gradient direction term 2 / (3 pi) (acos dp + acos dq). Dijkstra from the anchor over
  8 neighbours, ties broken by the smaller pixel index; the path is read back from the target.
- GrabCut (Rother et al. 2004, as cv2.grabCut): the reference IS cv2.grabCut. The engine has
  its own implementation (GMMs, a Boykov-Kolmogorov graph cut) and is held to it by IoU.
"""

from __future__ import annotations

import heapq
import math

import cv2
import numpy as np

SIGMA_S = 1.0
SIGMA_R = 0.1
JBU_REACH = 2
EPS = 2e-4
W_Z, W_G, W_D = 0.43, 0.43, 0.14


def guide(rgba: np.ndarray) -> np.ndarray:
    return np.sqrt(np.clip(rgba[..., :3].astype(np.float64), 0.0, 1.0))


def low_guide(g: np.ndarray, lh: int, lw: int) -> np.ndarray:
    """Mean of the guide pixels whose centres fall in each low pixel's footprint."""
    h, w = g.shape[:2]
    qy = np.minimum(((np.arange(h) + 0.5) * lh / h).astype(np.int64), lh - 1)
    qx = np.minimum(((np.arange(w) + 0.5) * lw / w).astype(np.int64), lw - 1)
    acc = np.zeros((lh, lw, 3))
    cnt = np.zeros((lh, lw))
    idx = (qy[:, None] * lw + qx[None, :]).ravel()
    for c in range(3):
        acc[..., c] = np.bincount(idx, weights=g[..., c].ravel(), minlength=lh * lw).reshape(lh, lw)
    cnt[:] = np.bincount(idx, minlength=lh * lw).reshape(lh, lw)
    out = np.empty((lh, lw, 3))
    have = cnt > 0
    out[have] = acc[have] / cnt[have][:, None]
    if not have.all():  # no centre inside: the nearest guide pixel
        jj, ii = np.nonzero(~have)
        y = np.clip(np.floor((jj + 0.5) * h / lh).astype(np.int64), 0, h - 1)
        x = np.clip(np.floor((ii + 0.5) * w / lw).astype(np.int64), 0, w - 1)
        out[jj, ii] = g[y, x]
    return out


def jbu(low: np.ndarray, glow: np.ndarray, g: np.ndarray) -> np.ndarray:
    """Joint bilateral upsampling of [low] (lh x lw) to the guide [g] (h x w x 3)."""
    lh, lw = low.shape
    h, w = g.shape[:2]
    u = (np.arange(w) + 0.5) * lw / w - 0.5
    v = (np.arange(h) + 0.5) * lh / h - 0.5
    uu, vv = np.meshgrid(u, v)
    fu, fv = np.floor(uu).astype(np.int64), np.floor(vv).astype(np.int64)
    num = np.zeros((h, w))
    den = np.zeros((h, w))
    for ky in range(-JBU_REACH, JBU_REACH + 1):
        qy = fv + ky
        dy = qy - vv
        for kx in range(-JBU_REACH, JBU_REACH + 1):
            qx = fu + kx
            dx = qx - uu
            ok = (np.abs(dx) <= JBU_REACH) & (np.abs(dy) <= JBU_REACH) & (qx >= 0) & (qx < lw) & (qy >= 0) & (qy < lh)
            cy, cx = np.clip(qy, 0, lh - 1), np.clip(qx, 0, lw - 1)
            diff = g - glow[cy, cx]
            d2 = diff[..., 0] ** 2 + diff[..., 1] ** 2 + diff[..., 2] ** 2
            wgt = np.exp(-(dx * dx + dy * dy) / (2 * SIGMA_S * SIGMA_S)) * np.exp(-d2 / (2 * SIGMA_R * SIGMA_R))
            wgt = np.where(ok, wgt, 0.0)
            num += wgt * low[cy, cx]
            den += wgt
    return num / den


def box_mean(x: np.ndarray, r: int) -> np.ndarray:
    return cv2.boxFilter(x, cv2.CV_64F, (2 * r + 1, 2 * r + 1), normalize=True, borderType=cv2.BORDER_REFLECT_101)


def guided_matte(s: np.ndarray, g: np.ndarray, r: int, eps: float = EPS) -> np.ndarray:
    """Colour guided filter of [s] with guide [g] (h x w x 3), radius r."""
    mi = [box_mean(g[..., c], r) for c in range(3)]
    mp = box_mean(s, r)
    cov_ip = [box_mean(g[..., c] * s, r) - mi[c] * mp for c in range(3)]
    sig = np.empty(s.shape + (3, 3))
    for a in range(3):
        for b in range(a, 3):
            v = box_mean(g[..., a] * g[..., b], r) - mi[a] * mi[b]
            if a == b:
                v = v + eps
            sig[..., a, b] = v
            sig[..., b, a] = v
    rhs = np.stack(cov_ip, axis=-1)[..., None]
    coef = np.linalg.solve(sig, rhs)[..., 0]  # a_k (3)
    b = mp - (coef[..., 0] * mi[0] + coef[..., 1] * mi[1] + coef[..., 2] * mi[2])
    ma = [box_mean(coef[..., c], r) for c in range(3)]
    mb = box_mean(b, r)
    return ma[0] * g[..., 0] + ma[1] * g[..., 1] + ma[2] * g[..., 2] + mb


def refine(s: np.ndarray, rgba: np.ndarray, radius_px: float, contrast: float) -> np.ndarray:
    r = max(1, int(round(radius_px)))
    q = guided_matte(s, guide(rgba), r)
    k = 1.0 + contrast / 20.0
    return np.clip((q - 0.5) * k + 0.5, 0.0, 1.0)


# --- Live-wire --------------------------------------------------------------------------------

def _reflect(i: int, n: int) -> int:
    if n == 1:
        return 0
    while i < 0 or i >= n:
        i = -i if i < 0 else 2 * n - 2 - i
    return i


def livewire_features(grey: np.ndarray):
    """fZ, fG and the unit edge direction D = (gy, -gx) / |grad| per pixel (as Python lists of
    floats, computed in the engine's order)."""
    h, w = grey.shape
    g = [[float(grey[y, x]) for x in range(w)] for y in range(h)]

    def at(x, y):
        return g[_reflect(y, h)][_reflect(x, w)]

    gx = [[0.0] * w for _ in range(h)]
    gy = [[0.0] * w for _ in range(h)]
    mag = [[0.0] * w for _ in range(h)]
    lap = [[0.0] * w for _ in range(h)]
    mx = 0.0
    for y in range(h):
        for x in range(w):
            sx = (at(x + 1, y - 1) + 2.0 * at(x + 1, y) + at(x + 1, y + 1)) - (at(x - 1, y - 1) + 2.0 * at(x - 1, y) + at(x - 1, y + 1))
            sy = (at(x - 1, y + 1) + 2.0 * at(x, y + 1) + at(x + 1, y + 1)) - (at(x - 1, y - 1) + 2.0 * at(x, y - 1) + at(x + 1, y - 1))
            gx[y][x] = sx / 8.0
            gy[y][x] = sy / 8.0
            mag[y][x] = math.sqrt(gx[y][x] * gx[y][x] + gy[y][x] * gy[y][x])
            mx = max(mx, mag[y][x])
            lap[y][x] = at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1) - 4.0 * at(x, y)
    fz = [[1.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            c = lap[y][x]
            if c == 0.0:
                fz[y][x] = 0.0
                continue
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h:
                    n = lap[ny][nx]
                    if (c > 0.0) != (n > 0.0) and abs(c) <= abs(n):
                        fz[y][x] = 0.0
                        break
    fg = [[1.0 - (mag[y][x] / mx if mx > 0.0 else 0.0) for x in range(w)] for y in range(h)]
    dx = [[(gy[y][x] / mag[y][x]) if mag[y][x] > 0.0 else 0.0 for x in range(w)] for y in range(h)]
    dy = [[(-gx[y][x] / mag[y][x]) if mag[y][x] > 0.0 else 0.0 for x in range(w)] for y in range(h)]
    return fz, fg, dx, dy


NEIGHBOURS = ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1))


def link_cost(f, px, py, qx, qy) -> float:
    fz, fg, dx, dy = f
    lx, ly = qx - px, qy - py
    diagonal = lx != 0 and ly != 0
    norm = math.sqrt(2.0) if diagonal else 1.0
    ux, uy = lx / norm, ly / norm
    if dx[py][px] * ux + dy[py][px] * uy < 0.0:
        ux, uy = -ux, -uy
    dp = max(-1.0, min(1.0, dx[py][px] * ux + dy[py][px] * uy))
    dq = max(-1.0, min(1.0, ux * dx[qy][qx] + uy * dy[qy][qx]))
    f_d = (2.0 / (3.0 * math.pi)) * (math.acos(dp) + math.acos(dq))
    s = 1.0 if diagonal else 1.0 / math.sqrt(2.0)
    return W_Z * fz[qy][qx] + W_G * fg[qy][qx] * s + W_D * f_d


def livewire_path(grey: np.ndarray, anchor, target) -> list:
    """Pixels (x, y) from [anchor] to [target], inclusive."""
    h, w = grey.shape
    f = livewire_features(grey)
    inf = float("inf")
    dist = [inf] * (w * h)
    parent = [-1] * (w * h)
    done = [False] * (w * h)
    a = anchor[1] * w + anchor[0]
    dist[a] = 0.0
    heap = [(0.0, a)]
    while heap:
        d, p = heapq.heappop(heap)
        if done[p]:
            continue
        done[p] = True
        px, py = p % w, p // w
        for ox, oy in NEIGHBOURS:
            qx, qy = px + ox, py + oy
            if not (0 <= qx < w and 0 <= qy < h):
                continue
            q = qy * w + qx
            if done[q]:
                continue
            nd = d + link_cost(f, px, py, qx, qy)
            if nd < dist[q]:
                dist[q] = nd
                parent[q] = p
                heapq.heappush(heap, (nd, q))
    path = []
    p = target[1] * w + target[0]
    while p != -1:
        path.append((p % w, p // w))
        p = parent[p]
    return path[::-1]


# --- GrabCut ----------------------------------------------------------------------------------

def grabcut(rgb8: np.ndarray, trimap: np.ndarray, iterations: int) -> np.ndarray:
    """cv2.grabCut from a trimap (0 bg, 1 fg, 2 probably bg, 3 probably fg): 1 = foreground."""
    mask = trimap.astype(np.uint8).copy()
    bgd = np.zeros((1, 65), np.float64)
    fgd = np.zeros((1, 65), np.float64)
    cv2.setRNGSeed(0x1234)
    cv2.grabCut(np.ascontiguousarray(rgb8[..., ::-1]), mask, None, bgd, fgd, iterations, cv2.GC_INIT_WITH_MASK)
    return ((mask == 1) | (mask == 3)).astype(np.float32)


# --- Test inputs ------------------------------------------------------------------------------

def _noise(h: int, w: int, seed: int) -> np.ndarray:
    return np.random.default_rng(seed).random((h, w))


def livewire_image() -> np.ndarray:
    """A 64 x 48 grey image: a bright disk (radius 15) on a dark gradient, with noise."""
    h, w = 48, 64
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    disk = np.hypot(xx - 30.5, yy - 23.5) <= 15.0
    img = 0.15 + 0.1 * xx / w + np.where(disk, 0.55, 0.0) + 0.04 * _noise(h, w, 16)
    return img.astype(np.float32)


# Edge to edge along the shorter arc (OpenCV's IntelligentScissorsMB takes the same way), and
# across the disk (either arc: only the distance to the edge is checked against OpenCV).
LIVEWIRE_CASES = [((15, 23), (30, 38)), ((30, 8), (45, 23)), ((30, 8), (30, 39)), ((20, 12), (41, 35))]
LIVEWIRE_UNIQUE = 3


def disk_truth(h: int, w: int, cx: float, cy: float, r: float) -> np.ndarray:
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    return (np.hypot(xx + 0.5 - cx, yy + 0.5 - cy) <= r).astype(np.float32)


def grabcut_image():
    """A 96 x 72 RGB image: a two-colour textured object on a two-colour background, its true
    mask, and the quick-selection trimap of a brush stroke across the object."""
    h, w = 72, 96
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    obj = (((xx - 44) / 26) ** 2 + ((yy - 37) / 20) ** 2 <= 1.0) | ((np.abs(xx - 66) < 6) & (np.abs(yy - 30) < 14))
    rng = np.random.default_rng(1607)
    img = np.zeros((h, w, 3))
    stripes = (xx // 7 + yy // 9) % 2 == 0
    img[...] = np.where(stripes[..., None], [40, 90, 150], [70, 130, 60])
    spots = np.sin(xx * 0.7) * np.cos(yy * 0.5) > 0.2
    fg = np.where(spots[..., None], [210, 120, 40], [230, 200, 80])
    img = np.where(obj[..., None], fg, img)
    img = np.clip(img + rng.normal(0, 9, img.shape), 0, 255).astype(np.uint8)
    trimap = np.full((h, w), 2, np.uint8)
    trimap[:2, :] = trimap[-2:, :] = 0
    trimap[:, :2] = trimap[:, -2:] = 0
    for x in range(28, 62):  # the stroke: a horizontal line through the object, radius 3
        trimap[34:41, x] = 1
    return img, obj.astype(np.float32), trimap


def matte_image():
    """A 64 x 48 RGBA working-space image with a soft, hairy edge between two colours and the
    hard selection to refine."""
    h, w = 48, 64
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    edge = 32 + 4 * np.sin(yy * 0.9) + 2 * np.sin(yy * 2.3)
    alpha = np.clip((edge - xx) / 3.0 + 0.5, 0.0, 1.0)
    fg = np.array([0.8, 0.5, 0.2])
    bg = np.array([0.1, 0.2, 0.6])
    rgb = alpha[..., None] * fg + (1 - alpha[..., None]) * bg + 0.02 * _noise(h, w, 7)[..., None]
    rgba = np.concatenate([rgb, np.ones((h, w, 1))], axis=-1).astype(np.float32)
    hard = (xx + 0.5 < 32).astype(np.float64)
    return rgba, hard, alpha


def jbu_inputs():
    """A 96 x 72 RGBA image with a sharp object edge and its mask at 24 x 18 (box-averaged)."""
    h, w = 72, 96
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    obj = np.hypot(xx - 47.3, yy - 35.6) <= 22.0 + 3 * np.sin(np.arctan2(yy - 35.6, xx - 47.3) * 5)
    rgb = np.where(obj[..., None], [0.7, 0.3, 0.25], [0.15, 0.35, 0.5]) + 0.02 * _noise(h, w, 9)[..., None]
    rgba = np.concatenate([rgb, np.ones((h, w, 1))], axis=-1).astype(np.float32)
    low = cv2.resize(obj.astype(np.float32), (24, 18), interpolation=cv2.INTER_AREA).astype(np.float64)
    return rgba, low, obj.astype(np.float32)


def iou(a: np.ndarray, b: np.ndarray) -> float:
    a, b = a >= 0.5, b >= 0.5
    return float((a & b).sum() / max((a | b).sum(), 1))
