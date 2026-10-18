# 0001. One monorepo

Status: accepted (2026-10-18)

## Context

The Android app and the base daemon share one Rust core and one wire format. Separate repos would need version pinning and cross-repo changes for every protocol edit.

## Decision

Single repository: `core/` (Rust workspace), `android/`, `prototype/`, `docs/`. One root `CLAUDE.md` plus per-directory ones.

## Consequences

- A protocol change lands in core, app and daemon in one commit, with the golden vectors.
- One CI, one issue tracker, one place for docs.
- Gradle and Cargo coexist without friction.
- Split later only if a part needs a different licence, release cadence or visibility.
