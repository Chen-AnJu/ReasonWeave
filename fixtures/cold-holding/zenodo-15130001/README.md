# Zenodo 15130001 fixed validation excerpt

This fixture is a small, deterministic transformation of version `v1` of
“Temperature and Humidity Time Series of Cold Storage Room Monitoring” by Elia
Henrichs, Florian Stoll, and Christian Krupitzer, DOI
`10.5281/zenodo.15130001`, licensed under CC BY 4.0.

It aligns the September 4, 2024 `Door opened` interval from 10:10–10:40 and the
10:10 `Products in` action with exact five-minute samples selected from
`Raw.zip/SENSOR01.CSV`. The source dataset explains that Sensor 1 was placed in
the water crates, so the product action also exercises the collector's handling
of a moving sensor context. The source timestamps do not include an offset; the
fixture explicitly records the experiment's local September time as `+02:00`.
That conversion is part of this fixture and must not be generalized to other
datasets.

Transformation rules:

- preserve the selected Sensor 1 temperatures without interpolation;
- expand the recorded door interval to five-minute boolean state samples;
- map `Products in` to `warm_load_introduced=true` at its recorded time;
- omit humidity because the collector's first contract does not accept it;
- set a test-only 12 °C threshold to exercise sustained-excursion parsing. It
  is not a food-safety, disposal, or regulatory threshold.

Upstream integrity recorded on 2026-08-29:

- `experiment_actions.csv`: MD5 `63507fe89c3ff8047baba0d279735a93`,
  SHA-256 `8ac92d6c4c628233aa083ef53cdf86e30275ec73d01670ca342922c5dc8b4f2e`;
- `Raw.zip`: MD5 `9af88d88cd9aba6f893e5e76eff2d3dd`, SHA-256
  `3590d19a2002cb473061af9ffbffacd7036e962b0f79b86d237590a74f0ec324`.

Source: <https://zenodo.org/records/15130001>. License:
<https://creativecommons.org/licenses/by/4.0/>. No complete upstream file is
redistributed here.
