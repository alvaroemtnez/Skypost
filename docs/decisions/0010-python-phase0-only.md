# 0010. Python only for the Phase 0 prototype

Status: accepted (2026-10-18); amended by [0014](0014-bridges-sidecars.md)

## Context

Real propagation data is needed before investing in PHY work. JS8Call has a TCP API.

## Decision

A stdlib-only Python prototype drives JS8Call and calls the Rust `keryx` CLI for all protocol work. It is thrown away in Phase 1.

## Consequences

- Phase 0 already uses the real wire format and crypto.
- No second protocol implementation to keep in sync.
- Exception (0014): the MeshCore bridge is a small Python sidecar on the official
  `meshcore_py`. It carries no Keryx protocol logic.
