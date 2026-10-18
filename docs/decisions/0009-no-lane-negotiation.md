# 0009. No lane negotiation; SNR in ACKs

Status: accepted (2026-10-18)

## Context

Mode negotiation adds round trips and failure points. A base can afford to decode everything.

## Decision

The base decodes every lane on every band in parallel (wideband SDR). Each ACK reports the SNR and clock offset it measured; the sender picks its next lane with a margin and steps down on retries.

## Consequences

- Field side never asks; fewer failure modes.
- Base needs CPU for parallel decoders.
- First message of a session starts on Normal.
