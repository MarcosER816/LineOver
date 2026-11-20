# Smart-selection test photos (Stage 16)

Six photographs from Wikimedia Commons, each published under the
**Creative Commons CC0 1.0 Universal Public Domain Dedication** (read on its File: page,
2026-11-20). They were downscaled to 768 px on the long edge (`tools/golden/annotate_mask.py`).

| File | Commons page | Author |
| --- | --- | --- |
| `cat_tabby.jpg` | https://commons.wikimedia.org/wiki/File:Red_tabby_sitting_on_a_cat_house.jpg | Roc0ast3r |
| `cat_gray.jpg` | https://commons.wikimedia.org/wiki/File:Cute_gray_cat_sitting_in_bed.jpg | Aleenamustlive |
| `labrador.jpg` | https://commons.wikimedia.org/wiki/File:Portrait_of_a_labrador_retriever.jpg | Dktue |
| `sheltie.jpg` | https://commons.wikimedia.org/wiki/File:Dog-portrait-1367008135LpJ.jpg | Karen Arnold |
| `boxer.jpg` | https://commons.wikimedia.org/wiki/File:Boxer_dog_posing_on_the_grass.jpg | Joselodos (Jose Lodos Benavente) |
| `sky.jpg` | https://commons.wikimedia.org/wiki/File:Clouds_in_the_sky_landscape.jpg | Aammell |

## Reference masks (`*_mask.png`, 255 = subject / sky)

Made by hand-placed hints, not by any of the models under test: for each photo a box around the
subject and foreground / background strokes and polygons (`*_hints.json`, chosen by looking at
the photo on a pixel grid), cut with OpenCV's GrabCut (8 iterations) by
`tools/golden/annotate_mask.py`, keeping the largest region and filling small holes. Every
outline was then checked by eye on an overlay and the hints corrected until it followed the
subject. They are traced references, not pixel-perfect mattes (fur and hair edges are binary);
Mark may replace any of them with his own hand-drawn masks.

Reproduce one: `python3 tools/golden/annotate_mask.py <original.jpg> testdata/smart/<name>_hints.json <out-prefix>`.
