# prototype/ (Phase 0, throwaway)

Stdlib-only Python 3.11+. It drives unmodified JS8Call through its TCP API and
calls the Rust `nvl` CLI for every protocol step. Do not add protocol logic
here, and do not add dependencies.

- Run tests: `python3 -m pytest -q` (the end-to-end test needs `core/target/*/nvl`).
- `js8api.py` encodes assumptions about the JS8Call 3.x API: verify them.
- The JSONL logs are the propagation dataset; keep their fields stable.
- `field.py --encrypt` must keep refusing without `--dummy-load` or `--emergency`.
