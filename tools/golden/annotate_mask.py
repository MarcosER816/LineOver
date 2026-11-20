"""Stage 16: the reference masks of testdata/smart (see testdata/smart/SOURCES.md).

Usage: annotate_mask.py <photo> <hints.json> <out-prefix>. The photo is resized to 768 px on the
long edge; the hints (a box, foreground / background strokes and polygons placed by hand) seed
cv2.grabCut; the largest region is kept and small holes filled. Writes <prefix>_image.jpg,
<prefix>_mask.png and <prefix>_overlay.jpg (for checking the outline by eye).
"""
import sys, json, cv2, numpy as np
src, hints_path, out_prefix = sys.argv[1], sys.argv[2], sys.argv[3]
im = cv2.imread(src); h, w = im.shape[:2]; s = 768 / max(h, w)
im = cv2.resize(im, (round(w*s), round(h*s)), interpolation=cv2.INTER_AREA); h, w = im.shape[:2]
H = json.load(open(hints_path))
mask = np.full((h, w), cv2.GC_BGD, np.uint8)
x0,y0,x1,y1 = H["rect"]; mask[y0:y1, x0:x1] = cv2.GC_PR_FGD
for key, val in (("pr_bg", cv2.GC_PR_BGD), ("bg", cv2.GC_BGD), ("fg", cv2.GC_FGD)):
    for poly in H.get(key, []):
        r = poly[0]; pts = np.array(poly[1:], np.int32).reshape(-1, 2)
        if len(pts) == 1: cv2.circle(mask, tuple(pts[0]), r, val, -1)
        else: cv2.polylines(mask, [pts], False, val, r * 2)
    for poly in H.get(key + "_poly", []):
        cv2.fillPoly(mask, [np.array(poly, np.int32).reshape(-1, 2)], val)
cv2.setRNGSeed(1); bgd = np.zeros((1, 65)); fgd = np.zeros((1, 65))
cv2.grabCut(im, mask, None, bgd, fgd, 8, cv2.GC_INIT_WITH_MASK)
m = ((mask == 1) | (mask == 3)).astype(np.uint8)
# keep the largest component(s) listed, fill holes
n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
keep = np.zeros_like(m)
if n > 1:
    big = 1 + np.argsort(-st[1:, cv2.CC_STAT_AREA])[: H.get("components", 1)]
    for b in big: keep[lab == b] = 1
inv = (1 - keep).astype(np.uint8); n2, lab2, st2, _ = cv2.connectedComponentsWithStats(inv, 4)
for i in range(1, n2):
    x, y, ww, hh, a = st2[i]
    if x > 0 and y > 0 and x + ww < w and y + hh < h and a < H.get("hole", 400): keep[lab2 == i] = 1
cv2.imwrite(out_prefix + "_image.jpg", im, [cv2.IMWRITE_JPEG_QUALITY, 92])
cv2.imwrite(out_prefix + "_mask.png", keep * 255)
ov = im.copy(); ov[keep == 0] = (ov[keep == 0] * 0.35).astype(np.uint8)
cnts, _ = cv2.findContours(keep, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE); cv2.drawContours(ov, cnts, -1, (0, 255, 255), 1)
cv2.rectangle(ov, (x0, y0), (x1, y1), (255, 0, 255), 1)
cv2.imwrite(out_prefix + "_overlay.jpg", ov)
print(w, h, keep.mean())
