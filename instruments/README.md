# Instrument files

Transcriptions of makers' manuals, in the format the Instruments tool imports
(Tools → Instruments → Import). They are **data, not part of the app** — the
app ships with no instrument content, and this stays true even when a file is
committed here.

| File | Transcribed from |
|---|---|
| `GX-8000.json` | Riken Keiki GX-8000 — `PT0E-09811` Operating Manual and `H4E-0050` User Maintenance Manual, supplied by the vessel |

Every step carries the document and page it came from, so anyone using it
aboard can check it against the paper copy. Nothing in these files is written
from memory.

## Two things that had to be got right

**The arrow keys.** The maintenance manual sets its ▲ and ▼ key symbols in
Wingdings 3 with no Unicode mapping, so every text extractor reads them as the
letters **S** and **T**. Taken at face value that produces instructions to press
keys the instrument does not have. The glyph outlines were read directly to
settle it: both are three-point triangles, and the one encoded as `S` has a
single apex at the top (▲) while `T` has a single apex at the bottom (▼).

**The alarm setpoints are not one table.** The operating manual gives different
factory presets for TIIS (Japan) and ATEX/IECEx approvals, and the oxygen
setpoints differ — 18/25 vol% against 19.5/23.5 vol%. Both are recorded in the
instrument notes, because picking one would be wrong half the time.

## What is deliberately left blank

The bump test interval. Neither manual states one — they specify a *monthly
alarm test* and *regular maintenance at least every six months*, which is not
the same thing. It comes from company and flag requirements, so the ship sets
it rather than inheriting a number nobody wrote down.

Serial numbers, calibration dates and logs are also absent: those belong to a
physical unit, not to a model.
