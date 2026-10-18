# 0005. Mercury as the fast lane (later)

Status: accepted (2026-10-18)

## Context

With good SNR, JS8 wastes time. Mercury v2 is a mature, GPL, adaptive OFDM data modem with ARQ and a TCP interface.

## Decision

Add Mercury as the OFDM lane for linear SSB radios (G90) in Phase 3, driven over its TCP interface.

## Consequences

- Large speed-up at decent SNR (tens of bit/s to ~140 B/s goodput).
- Not for the QDX unless its FSK modes mature.
- Two ARQs: hand whole messages to Mercury's.
