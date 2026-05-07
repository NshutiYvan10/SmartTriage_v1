# Rwanda location reference data

This directory holds the **lower-level** (sector / cell / village) data
for Rwanda's 5-level administrative hierarchy. The 5 provinces and 30
districts are seeded by Flyway migration `V47` and don't need a CSV.

## Currently loaded — Phase 1 (DevRW @devrw/rwanda-location v1.1.2)

The shipping CSVs were generated from
[`@devrw/rwanda-location` v1.1.2](https://www.npmjs.com/package/@devrw/rwanda-location)
(MIT-licensed, last released 2025-10-25), the most recently maintained
machine-readable Rwanda-administrative-units dataset publicly available
at the time of import.

Counts on import (verified against the official tallies):

| Level     | Loaded | Official tally | Δ |
|-----------|-------:|---------------:|--:|
| Sectors   |    416 |            416 |  0 |
| Cells     |  2,148 |          2,148 |  0 |
| Villages  | 14,842 |         14,837 | +5 |

The 5-village delta is from the upstream dataset (DevRW carries the
extras; the conversion script is deterministic and does not invent
rows). **Phase 2 will validate this against the NISR / RGB
authoritative source before the system handles real patient records.**
Until that validation lands, the village layer should be considered
"best available" rather than "officially stamped".

Code scheme: districts use the V47 codes (`RW.<province>.<district>`,
e.g. `RW.01.03` for Nyarugenge). Sectors / cells / villages extend
their parent code with a deterministic alphabetical sequence
(`.S01`, `.C01`, `.V001`) so the codes remain stable across regenerations
and patient/hospital FK references survive a re-import.

## What goes here

Three CSV files. All UTF-8, comma-separated, with a single header row.
Place exactly:

```
rw-locations/sectors.csv
rw-locations/cells.csv
rw-locations/villages.csv
```

### `sectors.csv`

```
district_code,sector_code,sector_name
RW.01.01,RW.01.01.01,Bumbogo
RW.01.01,RW.01.01.02,Gatsata
…
```

`district_code` must match the codes seeded by V47 (`RW.01.01` …
`RW.05.07`). `sector_code` is your stable internal join key — pick
once and don't change it.

### `cells.csv`

```
sector_code,cell_code,cell_name
RW.01.01.01,RW.01.01.01.01,Kinyaga
RW.01.01.01,RW.01.01.01.02,Nkuzuzu
…
```

### `villages.csv`

```
cell_code,village_code,village_name
RW.01.01.01.01,RW.01.01.01.01.001,Akabingo
…
```

## Loading behaviour

The `RwandaLocationCsvLoader` runs on application start, after JPA
and Flyway. It:

- Reads each file if present; logs a warning and continues if any are
  missing (the form falls back to district-only granularity until the
  file is provided).
- Inserts each row only when its `code` is not already in the
  database — re-running on a populated DB is a safe no-op.
- Skips rows whose parent code does not exist (with a warning), so a
  partially-loaded sectors file doesn't block cells.

## Where to get the data

The authoritative source is the **National Institute of Statistics of
Rwanda (NISR)** combined with the **Rwanda Governance Board (RGB)**
administrative-units register. Common formats they publish:

- An Excel workbook with one sheet per level
- The RGB GIS shapefile (administrative boundaries) which carries the
  same names + codes as attributes

A common community-maintained transformation of these into CSV is
also available on GitHub — search for "rwanda-administrative-divisions"
or "rwanda-locations". Verify the name spellings against the
government source before importing into production: **administrative
data accuracy is non-negotiable for a clinical EHR**, and a single
mis-assigned sector parent will mis-route patient records and MoH
reports.

## What if I only have data for some districts?

That's fine. Load only the rows you have. Patients in covered areas
get full village-level granularity; patients elsewhere get
district-only and supplement with the free-text `address` field.
