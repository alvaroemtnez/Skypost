# 0019. Pharos access: access code, closed mode, admin commands

Status: accepted (2026-10-19). Refines the registration and inbound rules of
[0016](0016-pharos-registration-and-bridging.md).

## Context

A Pharos relays on behalf of its operator, so it must decide who uses it, on
air and on the bridges, without the operator approving every person. MeshCore
repeaters solve this with an admin password. Two constraints here: Spanish
amateur rules forbid hiding meaning on air (so a password cannot be sent
encrypted), and Signal/WhatsApp do not let a bot delete other people's
messages.

## Decision

- Three access modes, per Pharos: `open`, `code` (an access code) and
  `closed` (allow list; anyone else waits for an admin). The `Announce` flags
  say `code required` or `closed`. A Pharos can also stop answering
  discovery (`discoverable = false`): only clients that scanned its QR find it.
- **On air the code never travels.** `Register` carries an 8-byte proof,
  HMAC(Argon2id(code), client key ‖ Pharos key), so it cannot be replayed for
  another key and hides nothing. Whoever records it can guess the code
  offline, so codes are passphrases (four or more random words).
- **Bridged contacts** (`inbound = "code"`, default) get "send the access
  code"; the right code authorises that account for good. The bot asks them
  to delete the message with the code, since it cannot. Five wrong codes an
  hour silence the bot for that account. Contacts a field user writes to
  first, contacts listed in the config and contacts an admin allows are
  authorised without a code. `contacts` mode drops the code; `anyone` drops
  the check.
- **Admin commands** in plain Spanish from admin bridge accounts or admin
  field users over the radio: `estado`, `clientes`, `aceptar`, `rechazar`,
  `bloquear`, `desbloquear`, `permitir`, `ayuda`. Anything more goes through
  SSH and the config.
- **Groups and channels are never bridged**, on any network, MeshCore
  included: the bot is a one-to-one contact.
- **Every refusal is answered**, quoting the message when it was not sent
  (too long, not text, unknown alias, queue full, expired after 72 h), so
  nobody assumes a message went out.

## Consequences

- `Register` grows by 8 bytes (6 JS8 frames), once per client.
- One shared code per Pharos: rotating it does not lock out clients and
  contacts already admitted; `bloquear` does.
- Protocol v1 was not released yet, so the `Register` layout and the
  `Announce` flags changed in place (vectors regenerated, no version bump).
