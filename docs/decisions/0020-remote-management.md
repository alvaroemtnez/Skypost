# 0020. Remote management: config admins, admin password, `/admin`

Status: accepted (2026-10-20). Refines the admin commands of
[0019](0019-pharos-access.md).

## Context

0019 gave admins a few Spanish command words, from bridge accounts or field
users listed as admins in the config. Two gaps: nobody could become admin
without SSH, and plain command words on the bridges collide with ordinary chat
("estado" is also a normal word). Over the radio, signed plaintext hides
nothing (Spanish rules), so a password sent there is broadcast.

## Decision

- **Who is admin.** Admins listed in the config (`[bot] admins`,
  `[[clients]] admin = true`, set over SSH), plus whoever proves the admin
  password (`[access] admin_password_file`). Without that file, config admins
  only. Access to the Pharos (open, code, closed) is unchanged and separate.
- **On the radio** the password counts only inside an encrypted frame (the
  app's global emergency encryption). Sent in signed plaintext it is refused
  even when right, and the bridge admins are told to change it. In plain mode,
  remote management is therefore config admins only.
- **On the bridges** commands start with `/admin`. A non-admin's `/admin …` is
  held and the bot asks for the password; the right one makes the account
  admin for good and the bot asks, in capitals, to delete the message (bots
  cannot). The held command then runs. Five wrong passwords an hour silence
  the bot for that account; a wrong one ends the wait, so the next text is chat
  again. Other admins are told about every new admin.
- **Commands**: `ayuda`, `estado`, `clientes`, `admins`, `aceptar`,
  `rechazar`, `bloquear`, `desbloquear`, `permitir`, `revocar`, `acceder`.
  English verbs work too (`help`, `status`, …). Over the radio the `/admin`
  prefix is optional (every text to the Pharos is a command) and replies are
  short. `revocar` only removes rights granted by password; config admins are
  removed over SSH.
- **SSH only**: access mode, access code, admin password, discoverability,
  bridges and their accounts, callsign, frequencies, config admins, SOS
  contacts, restarts and updates.
- **Help and welcome**: on Signal and WhatsApp the bot welcomes each contact
  once (after the access code if one is needed, or before a field user's
  first message to them) and answers `/ayuda` (`/help`). On MeshCore, made for
  short messages, there is no welcome and every reply has a short form.

## Consequences

- The app gets a Pharos management screen (gear beside the active Pharos)
  that sends these commands as text to the Pharos and shows the replies. Its
  password sign-in is enabled only while emergency encryption is on.
- A granted admin is a persisted flag (`admin_granted` in the registry,
  `admins` in the router state), so it survives restarts and changing the
  password does not undo it; `revocar` does.
- Nothing changes on air: commands are ordinary `Text` messages.
- Signing in over the radio inherits the limits of encrypted mode
  (`docs/regulatory-es.md`): an emergency or dummy-load tool, not routine. In
  practice admins are set up over SSH or sign in from a bridge account.
