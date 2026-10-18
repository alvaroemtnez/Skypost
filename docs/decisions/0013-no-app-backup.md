# 0013. No Android backup or device transfer

Status: accepted (2026-10-18)

## Context

The app will keep the session state (keys and message counters) in
app-private storage. Android can copy app data to Google's cloud backup and to
a new phone during device-to-device transfer. A restored state file has old
counters, so the next messages would reuse nonces. A copied file also puts
the key on more devices than the owner provisioned. Since Android 12,
`allowBackup="false"` no longer blocks device-to-device transfer.

## Decision

Nothing in the app is backed up or transferred: `allowBackup="false"`, plus
`data_extraction_rules.xml` (Android 12+) and `backup_rules.xml` (older),
which exclude every domain for cloud backup and for device transfer.

## Consequences

- A new or reset phone has to be provisioned again (QR). That is the intended
  path: it starts fresh counters.
- Settings that are not secret are lost with the phone too. If that becomes
  a problem, export them explicitly; do not re-enable backup.
