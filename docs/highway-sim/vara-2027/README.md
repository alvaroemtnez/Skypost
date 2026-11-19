# VARA's 2027 tables: how they were checked

Reruns the analysis in [highway-modems.md](../../highway-modems.md) §10. This
folder holds code only. **Never commit VARA's files** (installers, `.dat`
files, dumps of their tables): they are VARA's proprietary data.

## What you need

- The VARA HF 4.9.1 installer (and VARA FM 4.4.1 for the FM checks), in a
  work directory outside the repo, as `WORK/hf/setup.exe` and
  `WORK/fm/setup.exe`.
- A copy of XaraNext (1.3, or xara 0.1.3 plus its keying code) in
  `WORK/xaranext`.
- podman and gcc.

## Steps

1. Unpack the installers. They are Inno Setup packages; `innoextract` reads
   them without running any Windows code:

       podman run --rm -v WORK:/w:Z registry.fedoraproject.org/fedora:45 sh -c \
         'dnf -y install innoextract && for m in hf fm; do innoextract -d /w/$m/out /w/$m/setup.exe; done'

   The tables land in `WORK/hf/out/app/` and `WORK/fm/out/app/`:
   `Binary1-3.dat`, `Mod1.dat`, `Mod2.dat` (HF 2027), `Binary4-6.dat` and
   `Data.dat` (HF legacy), `Binary1-2.dat` (FM 2027), `Binary3-4.dat` (FM
   legacy), and `Aleatorio.dat` in both.

2. Validate the files and dump the interleavers with XaraNext's own loader:

       cp dump.rs WORK/xaranext/crates/xara-phy/examples/
       podman run --rm -v WORK:/w:Z -w /w/xaranext -e CARGO_TARGET_DIR=/w/target \
         docker.io/library/rust:1.91 cargo run -q --release -p xara-phy --example dump

   It checks the legacy files against xara's generator byte for byte, parses
   the 2027 files (checksum, permutations, record shapes) and writes
   `WORK/dump/`.

3. Search all 2^24 VB6 `Rnd` start states:

       gcc -O2 -o vbsearch vbsearch.c && gcc -O2 -o vbsearch2 vbsearch2.c -lm
       python3 -I search.py WORK/hf/out/app ./vbsearch
       python3 -I analyze.py WORK ./vbsearch2

   `search.py` tries direct draws and xara's draw-and-reject permutation;
   the legacy files are the positive control. `analyze.py` adds strided
   draws, rounding, three Fisher-Yates shuffles (with a positive control),
   interleaver spread and the `Aleatorio.dat` checks.

Afterwards: remove `WORK/target` and the rust image
(`podman rmi docker.io/library/rust:1.91`).

## Files

- `vbsearch.c`: direct draws and draw-and-reject permutations.
- `vbsearch2.c`: strided draws, `CInt` rounding, Fisher-Yates variants.
- `search.py`, `analyze.py`: drivers.
- `dump.rs`: XaraNext example that validates and dumps the tables.
