# Test LUTs

`malformed/*.cube` are hand-written broken files for the .cube parser tests
(`CubeParserTest`): each must be refused with a clear message.

`../golden/luts/*.cube` are LineOver's own test LUTs, written by `tools/golden/generate.py`
(`lut_reference.fixture_cubes`). No third-party LUT is stored in this repository.
