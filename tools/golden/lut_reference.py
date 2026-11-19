"""Stage 11 references: .cube 3D LUTs, tetrahedral interpolation, the LUT op and the built-in
profiles (base looks as LUTs). The C++ LUT op (lut.cpp: cpuRef and GLSL) implements exactly
these formulas; ctest `lut_test` and `EngineGpuTest` compare them.

The LUT op works where .cube files are made for: sRGB-encoded Rec.709 values.
  1. working linear Rec.2020 -> linear sRGB (matrix) -> sRGB transfer (odd-symmetric), e;
  2. c = (e - domain_min) / (domain_max - domain_min), clamped to 0..1 for the lookup;
     out = tetrahedral(LUT, c) + Rec.709 luma of (e - e_clamped): outside the domain the LUT
     continues with slope 1 in luminance, so HDR highlights are not clipped (out-of-gamut
     chroma is mapped by the LUT like the rest);
  3. e' = e + amount * (out - e);  4. decode, linear sRGB -> Rec.2020.

.cube (Adobe / Resolve): `LUT_3D_SIZE N` (2..65 here), optional `DOMAIN_MIN r g b` /
`DOMAIN_MAX r g b` and `TITLE "..."`, `#` comments, then N^3 lines "r g b" with red
changing fastest, then green, then blue.
"""

import numpy as np

import reference

MIN_SIZE = 2
MAX_SIZE = 65
REC2020_TO_SRGB = np.array(
    [
        [1.6603034854214451, -0.58757014253161666, -0.072890060215055993],
        [-0.12437559530838646, 1.1328344814319582, -0.008359737187401451],
        [-0.018112279959916489, -0.10058360850721247, 1.1187703261570985],
    ]
)
SRGB_TO_REC2020 = np.array(
    [
        [0.62744137205797812, 0.32929745952190931, 0.043351458394494675],
        [0.069027617147078099, 0.91958066688702822, 0.011361422575400862],
        [0.016364235071680722, 0.088017162471726973, 0.89556497272598279],
    ]
)


LUMA709 = np.array([0.2126, 0.7152, 0.0722])


class CubeError(ValueError):
    pass


class Cube:
    """table[b, g, r] = (r, g, b) output; domain per channel."""

    def __init__(self, table, domain_min=(0.0, 0.0, 0.0), domain_max=(1.0, 1.0, 1.0), title=""):
        self.table = np.asarray(table, dtype=np.float64)
        self.size = self.table.shape[0]
        self.domain_min = np.asarray(domain_min, dtype=np.float64)
        self.domain_max = np.asarray(domain_max, dtype=np.float64)
        self.title = title


def parse_cube(text: str) -> Cube:
    """Parses a .cube file; raises CubeError with the line number on anything malformed."""
    size = None
    dmin, dmax = (0.0, 0.0, 0.0), (1.0, 1.0, 1.0)
    title = ""
    rows = []
    seen = set()
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        word = line.split()[0]
        if not _is_number(word):
            key = word.upper()
            if rows:
                raise CubeError(f"line {number}: {key} after the table")
            if key in seen:
                raise CubeError(f"line {number}: {key} twice")
            seen.add(key)
            rest = line[len(word):].strip()
            if key == "TITLE":
                title = rest.strip('"')
            elif key == "LUT_1D_SIZE":
                raise CubeError(f"line {number}: 1D LUTs are not supported")
            elif key == "LUT_3D_SIZE":
                try:
                    size = int(rest)
                except ValueError:
                    raise CubeError(f"line {number}: LUT_3D_SIZE is not a whole number") from None
                if not MIN_SIZE <= size <= MAX_SIZE:
                    raise CubeError(f"line {number}: LUT_3D_SIZE {size} is outside {MIN_SIZE}..{MAX_SIZE}")
            elif key in ("DOMAIN_MIN", "DOMAIN_MAX"):
                v = _numbers(rest, number, key)
                if key == "DOMAIN_MIN":
                    dmin = v
                else:
                    dmax = v
            elif key in ("LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE"):
                lo, hi = _numbers(rest, number, key, count=2)
                dmin, dmax = (lo, lo, lo), (hi, hi, hi)
            else:
                raise CubeError(f"line {number}: unknown keyword {key}")
            continue
        if size is None:
            raise CubeError(f"line {number}: table data before LUT_3D_SIZE")
        rows.append(_numbers(line, number, "table"))
    if size is None:
        raise CubeError("no LUT_3D_SIZE")
    if len(rows) != size**3:
        raise CubeError(f"{len(rows)} table rows, LUT_3D_SIZE {size} needs {size ** 3}")
    if any(hi <= lo for lo, hi in zip(dmin, dmax)):
        raise CubeError("DOMAIN_MAX must be above DOMAIN_MIN")
    table = np.array(rows, dtype=np.float64).reshape(size, size, size, 3)
    return Cube(table, dmin, dmax, title)


def _is_number(word):
    try:
        float(word)
        return True
    except ValueError:
        return False


def _numbers(text, number, what, count=3):
    parts = text.split()
    if len(parts) != count:
        raise CubeError(f"line {number}: {what} needs {count} numbers")
    try:
        values = tuple(float(p) for p in parts)
    except ValueError:
        raise CubeError(f"line {number}: {what} has a value that is not a number") from None
    if not all(np.isfinite(values)):
        raise CubeError(f"line {number}: {what} has a value that is not finite")
    return values


def write_cube(cube: Cube) -> str:
    lines = []
    if cube.title:
        lines.append(f'TITLE "{cube.title}"')
    lines.append(f"LUT_3D_SIZE {cube.size}")
    if np.any(cube.domain_min != 0.0) or np.any(cube.domain_max != 1.0):
        lines.append("DOMAIN_MIN " + " ".join(f"{v:.6f}" for v in cube.domain_min))
        lines.append("DOMAIN_MAX " + " ".join(f"{v:.6f}" for v in cube.domain_max))
    flat = cube.table.reshape(-1, 3)
    lines.extend(f"{r:.6f} {g:.6f} {b:.6f}" for r, g, b in flat)
    return "\n".join(lines) + "\n"


def tetrahedral(table: np.ndarray, c: np.ndarray) -> np.ndarray:
    """Tetrahedral interpolation of table[b, g, r] at c (..., 3) in 0..1 (rgb)."""
    n = table.shape[0]
    x = np.clip(c, 0.0, 1.0) * (n - 1)
    i = np.minimum(np.floor(x), n - 2).astype(np.int64)
    f = x - i
    ir, ig, ib = i[..., 0], i[..., 1], i[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]

    def at(dr, dg, db):
        return table[ib + db, ig + dg, ir + dr]

    c000, c111 = at(0, 0, 0), at(1, 1, 1)
    c100, c010, c001 = at(1, 0, 0), at(0, 1, 0), at(0, 0, 1)
    c110, c101, c011 = at(1, 1, 0), at(1, 0, 1), at(0, 1, 1)
    # The six tetrahedra of the cube, chosen by the order of the fractions (the same
    # comparisons in C++ and GLSL).
    cases = [
        ((fr >= fg) & (fg >= fb), c000 + fr * (c100 - c000) + fg * (c110 - c100) + fb * (c111 - c110)),
        ((fr >= fb) & (fb > fg), c000 + fr * (c100 - c000) + fb * (c101 - c100) + fg * (c111 - c101)),
        ((fb > fr) & (fr >= fg), c000 + fb * (c001 - c000) + fr * (c101 - c001) + fg * (c111 - c101)),
        ((fg > fr) & (fr >= fb), c000 + fg * (c010 - c000) + fr * (c110 - c010) + fb * (c111 - c110)),
        ((fg >= fb) & (fb > fr), c000 + fg * (c010 - c000) + fb * (c011 - c010) + fr * (c111 - c011)),
    ]
    out = c000 + fb * (c001 - c000) + fg * (c011 - c001) + fr * (c111 - c011)  # fb > fg > fr
    for cond, value in reversed(cases):
        out = np.where(cond, value, out)
    return out


def encode(x):
    a = np.abs(x)
    e = np.where(a <= 0.0031308, a * 12.92, 1.055 * np.power(a, 1.0 / 2.4) - 0.055)
    return np.sign(x) * e


def decode(e):
    a = np.abs(e)
    x = np.where(a <= 0.04045, a / 12.92, np.power((a + 0.055) / 1.055, 2.4))
    return np.sign(e) * x


def apply_lut(image: np.ndarray, cube: Cube, amount: float) -> np.ndarray:
    """The LUT op on working-space RGBA, Amount 0..1 (alpha unchanged)."""
    img = image.astype(np.float64)
    e = encode(img[..., :3] @ REC2020_TO_SRGB.T)
    span = cube.domain_max - cube.domain_min
    c = (e - cube.domain_min) / span
    cc = np.clip(c, 0.0, 1.0)
    # Outside the domain the LUT continues with slope 1 in luminance only (HDR brightness
    # is kept; out-of-gamut chroma goes through the LUT like the rest).
    residual = ((c - cc) * span) @ LUMA709
    out = tetrahedral(cube.table, cc) + residual[..., None]
    e2 = e + amount * (out - e)
    result = img.copy()
    result[..., :3] = decode(e2) @ SRGB_TO_REC2020.T
    return result


# ------------------------------------------------------------------- built-in profiles

PROFILES = ("neutral", "vivid", "mono", "flat")
PROFILE_SIZE = 33
VIVID_CHROMA = 1.25
VIVID_CONTRAST = 0.6
FLAT_CHROMA = 0.85
FLAT_CONTRAST = 0.5
FLAT_LIFT = 0.05
GAMUT_STEPS = 12


def _in_gamut(rgb):
    return np.all((rgb >= -1e-9) & (rgb <= 1.0 + 1e-9), axis=-1)


def to_oklab_srgb(rgb_linear_srgb):
    return reference.to_oklab(rgb_linear_srgb @ SRGB_TO_REC2020.T)


def from_oklab_srgb(lab):
    return reference.from_oklab(lab) @ REC2020_TO_SRGB.T


def profile_table(name: str, size: int = PROFILE_SIZE) -> np.ndarray:
    """The profile's LUT on the sRGB-encoded grid (table[b, g, r]): an OKLab look.
    Vivid: chroma x 1.25 (pulled back towards the original until it fits sRGB) and a mild
    S-curve on lightness; Mono: the OKLab lightness as grey; Flat: chroma x 0.85, lightness
    contrast halved around the midtones and blacks lifted; Neutral: identity."""
    g = np.linspace(0.0, 1.0, size)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")
    e = np.stack([r, gg, b], axis=-1)
    if name == "neutral":
        return e
    lin = decode(e)
    lab = to_oklab_srgb(lin)
    L = lab[..., 0]
    if name == "vivid":
        L2 = L + VIVID_CONTRAST * (L - 0.5) * L * (1.0 - L)
        target = np.stack([L2, lab[..., 1] * VIVID_CHROMA, lab[..., 2] * VIVID_CHROMA], axis=-1)
        base = np.stack([L2, lab[..., 1], lab[..., 2]], axis=-1)
        rgb = from_oklab_srgb(target)
        ok = _in_gamut(rgb)
        lo = np.zeros(L.shape)
        hi = np.ones(L.shape)
        for _ in range(GAMUT_STEPS):
            mid = 0.5 * (lo + hi)
            fits = _in_gamut(from_oklab_srgb(base + mid[..., None] * (target - base)))
            lo = np.where(fits, mid, lo)
            hi = np.where(fits, hi, mid)
        t = np.where(ok, 1.0, lo)
        rgb = from_oklab_srgb(base + t[..., None] * (target - base))
    elif name == "mono":
        y = np.clip(L, 0.0, 1.0) ** 3
        rgb = np.repeat(y[..., None], 3, axis=-1)
    elif name == "flat":
        L2 = L - FLAT_CONTRAST * (L - 0.5) * L * (1.0 - L)
        L2 = FLAT_LIFT + (1.0 - FLAT_LIFT) * L2
        rgb = from_oklab_srgb(np.stack([L2, lab[..., 1] * FLAT_CHROMA, lab[..., 2] * FLAT_CHROMA], axis=-1))
    else:
        raise ValueError(name)
    return np.clip(encode(np.clip(rgb, 0.0, 1.0)), 0.0, 1.0)


# ---------------------------------------------------------------- fixture LUTs (ours)


def fixture_cubes() -> dict:
    """Original test LUTs (made here, no third-party looks): a warm fade at 17, a teal /
    amber split at 33 with a custom domain, both written as .cube."""
    out = {}
    g = np.linspace(0.0, 1.0, 17)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")
    warm = np.stack([0.06 + 0.92 * r ** 0.95, 0.04 + 0.9 * gg, 0.08 + 0.8 * b ** 1.08], axis=-1)
    out["warm_fade_17"] = Cube(np.clip(warm, 0.0, 1.0), title="LineOver test: warm fade")
    g = np.linspace(0.0, 1.0, 33)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")
    y = 0.2627 * r + 0.678 * gg + 0.0593 * b
    shadow = np.array([-0.06, 0.02, 0.07])
    highlight = np.array([0.07, 0.02, -0.06])
    w = np.clip(y, 0.0, 1.0)[..., None]
    split = np.stack([r, gg, b], axis=-1) + (1.0 - w) * shadow * 1.2 + w * highlight * 1.2
    # Domain 0..1.25: values up to 1.25 map inside the table.
    out["teal_amber_33"] = Cube(np.clip(split, 0.0, 1.25), (0.0, 0.0, 0.0), (1.25, 1.25, 1.25), "LineOver test: teal amber")
    return out
