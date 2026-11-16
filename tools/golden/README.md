# Golden image tools

Python is the **reference implementation** of every LineOver image operation.
`generate.py` writes the reference results into `testdata/golden/`; the engine is
tested against them with `GoldenAssert` (C++: `core/engine-native/src/main/cpp/testing`,
Kotlin: `core/engine/src/testFixtures`).

```sh
python3 -m pip install -r tools/golden/requirements.txt
python3 tools/golden/generate.py            # regenerate testdata/golden
python3 tools/golden/generate.py --check    # CI: committed files must match the generator
```

## Adding an operation

1. Add the reference function to `reference.py` (NumPy / OpenCV / colour-science, clarity over speed).
2. Add its outputs to `build_outputs()` in `generate.py` under `<op>/…`.
3. Run `generate.py`, commit the new files, and write the tests that compare the engine with them.

## File formats

| Kind | Format | Notes |
| --- | --- | --- |
| 8-bit images | PNG, RGBA, non-interlaced | sRGB-encoded |
| Float images | `.npy`, little-endian float32, shape `(H, W, 4)` | linear Rec.2020, straight alpha |

## Tolerances (GoldenAssert)

| Comparison | Limit |
| --- | --- |
| 8-bit result vs reference | max \|diff\| ≤ 2/255 **and** mean \|diff\| ≤ 0.5/255 |
| Float operation vs reference | max \|diff\| ≤ 1e-3 |
| GPU result vs CPU reference | max \|diff\| ≤ 3/255 |

A failed comparison writes `<name>_diff.png`, a heatmap: black = identical,
blue = within tolerance, red → yellow → white = 1× … 4× the limit and beyond.

Golden files never change without an explanation in the stage notes (rule R4).
