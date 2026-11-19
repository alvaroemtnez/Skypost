# 0025. XARA highways use Skypost's own keying, from a published recipe

Status: accepted (2026-11-19)

## Context

A XARA highway (VARA HF protocol, [highway-modems.md](../highway-modems.md))
needs a *keying*: the interleaver, pilot and phase tables and the
control-code seed offset that both ends must share. Three exist
(highway-modems §10):

- **Legacy:** computed from VB6 `Rnd` with seed 43. Used by xara and by VARA
  up to 4.9.0.
- **2027:** VARA HF 4.9.1 and FM 4.4.1 switch to it on 1 January 2027. Its
  tables ship as files in VARA's install, and no VB6 `Rnd` stream produces
  them. It needs VARA's proprietary files and gives no performance gain.
- **Named:** XaraNext's generator builds a keying from a published name
  (SplitMix64 seeded by FNV-1a, its `docs/keying.md`, MIT).

Skypost highways only ever link Skypost stations, so we need none of VARA's
compatibility.

## Decision

- A Skypost XARA highway uses **its own keying, computed from a recipe
  published in `docs/spec/`.** No table files are shipped or read, and
  nothing comes from VARA.
- The recipe is **XaraNext's named-keying generator, version 1**, with a
  fixed name (proposed: `skypost-1`). It is plain, documented code with no
  hidden inputs, and anyone can rebuild the tables with `xara-keygen` to
  monitor or debug.
- The keying is part of the wire format. It gets a spec page that restates
  the generator in full (not only a link to XaraNext), regenerated test
  vectors (table hashes), and a new name if it ever changes.
- Legacy stays available as a debug and bench setting until our keying has
  been tested over the air. The 2027 keying is never used.

## Consequences

- Same performance as legacy or 2027, as the tables are equally random.
- VARA and Winlink stations can't connect to Skypost highways, and
  Skypost ignores their control codes.
- No proprietary data, so Pharos and the app stay buildable from source
  (F-Droid, [0024](0024-reproducible-builds.md)), and the emission is fully
  documented for the Spanish rules ([regulatory-es.md](../regulatory-es.md)).
- Not encryption: the keying is public by design.
  [0008](0008-signed-plaintext-default.md) still holds.
- Named keyings have been tested only in simulation. The on-air checks in
  highway-modems §9 must run on our keying, not only on legacy.
