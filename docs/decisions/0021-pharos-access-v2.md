# 0021. Pharos access v2: switches, requests, binary admin messages

Status: accepted (2026-10-20). Replaces the access modes of
[0019](0019-pharos-access.md) and refines [0020](0020-remote-management.md).

## Context

Feedback on 0019/0020: a closed Pharos offered no way to ask for access, the
access settings could only change over SSH, config admins came back after
every restart, and admin commands as text cost airtime (a MeshCore key is 64
hex characters). Airtime is a first principle of the project, and a Pharos
must keep working, unattended and without internet, in an emergency.

## Decision

**Where settings live.** `base.toml` holds what is fixed: callsign, paths,
radio, bridge accounts, the access code and admin password files. Access
switches, admins, allowed contacts and the allow list live in the Pharos state
file, which survives restarts. On the first run the matching config keys are
imported; afterwards they are ignored with a warning. Changes come from admin
commands (air or bridges) or `pharos` CLI commands over SSH.

**Switches** (each a command and a switch in the app):

| Switch | Meaning |
|---|---|
| `open` | on: anyone may use the Pharos; off (closed): registered users and accepted contacts only |
| `requests` | while closed, unknown radio users and bridged contacts may ask to join |
| `login` | the admin password works (bridges; radio only encrypted) |
| `emergency` | encrypted only; stays on until an admin turns it off |
| `stealth` | no discovery answers and no automatic messages of any kind |

The access code stays as a shortcut: proving it approves at once. It and the
admin password stay in files (SSH only).

**Requests.** Radio users ask with `Register` ("Request access" in the app);
bridged contacts ask by writing. Each request gets a number 0–255, counting
up and wrapping; at most 20 are pending; each expires after 72 h
(`pharos set request-ttl-hours`). `accept #n` / `refuse #n` act only on a
pending request and the reply names who it was. MeshCore contacts get in only
through a request: a direct message can only come from a node whose advert
reached the Pharos companion, so its advert name is always known; it is
shown with a short key prefix, since names are self-chosen. In an emergency,
requests in plain are still accepted (they carry no secret).

**Notifications** to admins, none in stealth: per admin, one small queued
notice ("new WhatsApp request", no personal data) at most once an hour,
merged; bridge admins get the same as text. Admin and switch changes are
announced to the other admins. Nothing else is automatic.

**Admins**: added over SSH, by another admin, or with the password while
`login` is on. Any admin may remove any other, SSH ones included. The last
admin cannot be removed while password login is off (or no password is set):
nobody could manage the Pharos remotely again.

**Binary admin messages on air.** New types `Admin` (command) and
`AdminReply` (structured reply or notice): one opcode byte plus compact
arguments; switch changes fit in one byte. The app renders replies in its own
language and shows the Pharos's real switches. The bridges keep the English
text commands. Before sending in plain a command or request list that puts a
name or number on air, the app warns.

**Emergency mode.** Plain frames are dropped except SOS, pings, ACKs,
`Register`, discovery and the `emergency` switch itself. A plain sender gets
one encrypted `ENCRYPTED_ONLY` notice (none in stealth).

**People are named by key, never by alias** (added the same day). An alias
is whatever its owner chose, so `@bea` names whoever claimed "bea" first,
not the person meant. Admin commands and lists carry an 8-byte id instead:
the start of a field user's key fingerprint (the first two groups of its
safety code) or of a MeshCore key. The app picks people from its contacts or
the Pharos's lists and shows `@bea · 4F2A 9C11`, with the verified badge for
verified contacts and a warning when the alias matches a contact but the key
does not. Bridge admins copy the 16-hex id from `/admin clients`.

## Consequences

- Wire format: `MsgType` 15 `Admin`, 16 `AdminReply`; `Notice` 6
  `ENCRYPTED_ONLY`; `Announce` flag bit 6 `REQUESTS`; `CODE_REQUIRED` now
  means "a code is accepted". v1 is unreleased: changed in place, vectors
  regenerated, no version bump.
- `[access] mode` and `[bot] inbound` are replaced by the switches.
- Until the Phase 1 daemon has a control socket, the `pharos` CLI edits the
  state file; stop the service first.
