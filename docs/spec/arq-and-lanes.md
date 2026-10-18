# ARQ and lanes — protocol v0

Implemented in `core/crates/nvl-proto/src/lane.rs` and `arq.rs`; base-side
flow in `core/crates/nvl-daemon/src/station.rs`.

## Lanes

| Lane | Frame / burst | Floor (dB) | Radio |
|---|---|---|---|
| Js8Slow | 30 s | −28 | any |
| Js8Normal | 15 s | −24 | any |
| Js8Fast | 10 s | −20 | any |
| Js8Turbo | 6 s | −18 | any |
| OfdmFast (Mercury) | ~5 s (Mercury runs its own ARQ) | −9.5 | linear SSB only |

JS8 floors are published figures in 2.5 kHz; Mercury's is DATAC15's 90 %
delivery point on AWGN in 3 kHz. Real fading channels need margin.

## Choosing a lane

`choose(reported_snr, policy)`: the fastest lane with
`floor + margin_db ≤ reported_snr`, skipping OFDM if the radio cannot do it.
No report yet → `policy.initial` (Js8Normal). Nothing qualifies → Js8Slow.
Default margin: 6 dB (to tune from Phase 0 logs).

The reported SNR is what the **other end** measured, carried in its ACK. No
separate negotiation: the base decodes all lanes in parallel.

## Stop-and-wait sender

- One message in flight per station.
- Deadline = now + (frames + 1) × frame time + guard (5 s): our frames, one
  ACK slot, slack.
- On timeout: retry on the next more robust lane; after `max_attempts`
  (default 4) report `Failed`.
- On an authenticated ACK whose counter low byte matches: `Delivered`;
  remember the reported SNR for the next lane choice.

## Receiver (base)

- Every authentic message → ACK with measured SNR and clock offset.
- Duplicate of a delivered frame → re-ACK, no second delivery.
- `Poll` → ACK, then send the first queued message for that member; each ACK
  of a queued message releases the next.
- Replies mirror the member's last mode (encrypted iff it last sent encrypted).

## Clock offset

JS8 needs both ends within ~1–2 s of UTC. The ACK carries the offset the base
measured (deciseconds); the field app corrects its slot timing. Off-grid, the
phone's UTC comes from GNSS (or the QMX+'s GPS).

## Open items

- Listen windows: when the field station listens (battery) and polls.
- Mercury integration: hand a whole message to Mercury's ARQ over its TCP
  interface instead of running ours.
- Soft combining of repeated JS8 frames.
