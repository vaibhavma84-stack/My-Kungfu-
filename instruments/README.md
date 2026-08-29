# Instrument files

Transcriptions of makers' manuals, in the format the Instruments tool imports
(Tools → Instruments → Import). They are **data, not part of the app** — the
app ships with no instrument content, and this stays true even when a file is
committed here.

| File | Transcribed from |
|---|---|
| `GX-8000.json` | Riken Keiki GX-8000 **TYPE A** — `PT0E-09811` Operating Manual and `H4E-0050` User Maintenance Manual |
| `GX-8000-TYPE-O2.json` | Riken Keiki GX-8000 **TYPE O2** — `PT0E-1089` Operating Manual and `H4E-0050` |

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

`instrument-files-check.py` runs in `run.sh` and checks every file here: that
each step cites a document, that both approval tables survive, that no step
tells anyone to press an "S" or "T" switch, and that no bump interval has been
invented.

## The variant is not a detail

TYPE A and TYPE O2 are different instruments behind the same model name.
TYPE A has the vol% combustible range, and with `HC RANGE` set to vol%-only
**no gas alarm is triggered at all** — the screen shows `[No ALARM]`, and the
%LEL-only screen looks identical to auto-range. TYPE A also has the four-step
span order (the high-concentration sensor reads differently on an air base than
an N2 base), the 30-second AIR CAL countdown, and STEL/TWA.

TYPE O2 has none of that, but its sensor **must be replaced within two years** —
a limit the multi-gas manual does not state — and it splits again into `L`
(has a gas alarm) and `N` (none at all; its setpoint display reads `[OFF]`).

Writing one file for "a GX-8000" would have been wrong for whichever unit was
picked up.

## What is deliberately left blank

The bump test interval. Neither manual states one — they specify a *monthly
alarm test* and *regular maintenance at least every six months*, which is not
the same thing. It comes from company and flag requirements, so the ship sets
it rather than inheriting a number nobody wrote down.

Serial numbers, calibration dates and logs are also absent: those belong to a
physical unit, not to a model.
