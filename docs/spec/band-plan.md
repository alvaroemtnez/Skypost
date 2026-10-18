# Band plan

Implemented as a default table in `core/crates/keryx/src/bandplan.rs`
(`keryx band --month M --hour H`). Both ends must use the same table, so it is
versioned with the protocol and delivered in the provisioning profile later.

## Default schedule (initial guess)

Approximate sunrise/sunset for Valencia in UTC, by month:

- **40 m:** from sunrise + 1.5 h to sunset − 1 h.
- **60 m:** sunrise to sunrise + 1.5 h, and sunset − 1 h to sunset + 1.5 h.
- **80 m:** the rest of the night.

Fallback order: the primary band, then its neighbours.

Known gap: winter pre-dawn near solar minimum, when foF2 < 3.5 MHz; only
160 m reflects vertically then.

## Frequencies — TBD

- Spain 60 m: 5351.5–5366.5 kHz, secondary, 15 W EIRP max, any modulation.
  JS8Call lists 5363 kHz for its 60 m activity.
- Pick dial frequencies inside the IARU Region 1 HF band plan segments for
  narrow-band data (and automatically controlled stations for the base),
  **away from JS8Call's calling frequencies**: our binary frames would show as
  noise or garbage to JS8Call users. Verify against the current IARU R1 plan.

## Making it data-driven

Phase 0 logs give, per band and hour: delivered or not, SNR both ways. Replace
the guessed table with measured best bands per month and hour band; consider
a short "band beacon" so the field side can verify before sending.
