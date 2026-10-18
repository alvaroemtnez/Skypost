# 0006. Do not build the core on Reticulum

Status: accepted (2026-10-18)

## Context

Reticulum/LXMF give routing and E2E encryption, but announces (~157 B), link setup (~265 B) and LXMF overhead (111 B) dwarf our 13–50-byte messages at ~5 bit/s.

## Decision

Own minimal framing on the JS8 lane. Reticulum may run over the Mercury lane or on the internet side later.

## Consequences

- Messages fit in seconds-to-minutes, not many minutes.
- We maintain our own small protocol.
