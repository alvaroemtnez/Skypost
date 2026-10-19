# ARQ and lanes — protocol v1

Implemented in `core/crates/keryx/src/lane.rs` and `arq.rs`; base-side
flow in `core/crates/pharos/src/station.rs`.

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
separate negotiation: the base decodes all lanes in parallel. Reports older
than 30 minutes are ignored (HF changes).

Fixed lanes:

- `Discover`, `Announce`, pings (compact `Poll`) and their ACKs: Js8Slow.
  They are short and must get through when nothing else does.
- An ACK goes out on the lane of the frame it acknowledges (the sender is
  listening there).

## Stop-and-wait sender

- One message in flight per station.
- Deadline = now + (frames + 1) × frame time + guard (5 s): our frames, one
  ACK slot, slack.
- On timeout: retry on the next more robust lane; after `max_attempts`
  (default 4) report `Failed`.
- On an authenticated ACK whose counter low byte matches: `Delivered`;
  remember the reported SNR for the next lane choice.

## Receiver

- Every authentic message → ACK with measured SNR and clock offset.
- Duplicate of a delivered frame → re-ACK, no second delivery.
- Pharos: `Poll` → ACK with `more` set if messages are queued, then send them
  one at a time while the client listens (see [pharos.md](pharos.md)).
- Replies mirror the peer's last mode (encrypted iff it last sent encrypted).

## Pinging a contact

The compact `Poll` also works between phones: "are you listening?". The other
phone answers with an ACK (SNR and clock offset) without showing anything to
its user, only if the poller is one of its contacts, not blocked, and the tag
verifies. For a bridged chat the app pings the Pharos instead. Groups are not
pinged.

## Groups

Every member that verifies a group message ACKs it, after a random delay of
0–2 frame slots and at a random audio offset (JS8 decodes several signals in
the passband). The sender shows how many members acknowledged (✓ 2/3) and
does not retry once at least one ACK arrived. Groups are meant for a handful
of people; larger groups should not expect every ACK.

## Clock offset

JS8 needs both ends within ~1–2 s of UTC. The ACK carries the offset the base
measured (deciseconds); the field app corrects its slot timing. Off-grid, the
phone's UTC comes from GNSS (or the QMX+'s GPS).

## Open items

- Mercury integration: hand a whole message to Mercury's ARQ over its TCP
  interface instead of running ours.
- Soft combining of repeated JS8 frames.
