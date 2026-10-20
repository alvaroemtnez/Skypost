# Pharos — discovery, registration, store and forward, bridge bot

Implemented in `core/crates/pharos` (`station.rs` on air, `registry.rs`
clients, `access.rs` switches, admins and requests, `router.rs` bridge bot,
`admin.rs` commands, `hub.rs` wiring). Decisions: [0014](../decisions/0014-bridges-sidecars.md),
[0016](../decisions/0016-pharos-registration-and-bridging.md),
[0021](../decisions/0021-pharos-access-v2.md). Frames:
[frame-format.md](frame-format.md).

## 1. On air

### Trust

The app keeps a list of trusted Pharos stations (public key, name, callsign,
verified flag). Two ways in:

1. **QR at home** (recommended): scan the Pharos URI shown by
   `pharos identity`. Verified.
2. **Discovery** (trust on first use): pick one from the scan results. Marked
   unverified until the user compares fingerprints, e.g. over the phone.

A spoofed Pharos would need the real one's secret to produce any valid tag
after the first contact, so impersonation is limited to the first discovery.

### Discovery

1. Client: `Discover` (4 bytes, counter = random query id), most robust lane
   (Js8Slow).
2. Each Pharos that hears it, is not in stealth, and has not announced in the last 10 minutes,
   waits a random 0–2 frame slots, picks a random audio offset and sends
   `Announce` to the querier's short id with the same query id, on the lane
   of the `Discover`.
3. The app lists answers with name, callsign, SNR, and whether it is already
   trusted (matched by public key).

### Access

Decision [0021](../decisions/0021-pharos-access-v2.md). Five switches, kept
in the state file (they survive restarts; `base.toml` only seeds them on the
first run) and changed by admin command or `pharos set` over SSH:

| Switch | On | Default |
|---|---|---|
| `open` | anyone may use the Pharos; off: registered users and accepted contacts only | off |
| `requests` | while closed, unknown radio users and bridged contacts may ask to join | on |
| `login` | the admin password works | on |
| `emergency` | encrypted only (below); stays on until an admin turns it off | off |
| `stealth` | no `Discover` answers, no automatic messages of any kind | off |

`Announce` flags: `CLOSED` when closed, plus `REQUESTS` if it takes requests
and `CODE_REQUIRED` if it has an access code.

The access code (`[access] code_file`) is a shortcut: proving it admits at
once. It never goes on air: `Register` carries
`proof = HMAC-SHA256(K, "keryx-v1/access" ‖ client key ‖ Pharos key)[0..8]`
with `K = Argon2id(code, salt = SHA-256("keryx-v1/access-salt" ‖ Pharos
key)[0..16])` (19 MiB, 2 passes), or 8 zero bytes without a code. A recorded
proof lets an attacker guess the code offline: use four or more random words.

### Registration and access requests

1. Client: `Register` (its public key, the proof, its alias), tagged with the
   pair key. In the app: "Request access" (or "I have a code").
2. Pharos ACKs it, then decides: open or a valid proof → registered; closed
   with requests on → an **access request** (pending); otherwise refused, or
   "access code needed" if it has one.
3. Result as a `Notice` (1 registered, 2 pending, 3 refused, 4 alias taken,
   5 access code needed), sent at once if the client is listening, else
   queued. After 3, 4 or 5 the client may register again.

Access requests, from the radio and the bridges alike:

- Numbered 0–255, counting up and wrapping, skipping numbers in use; at most
  20 pending; each expires after 72 h (`pharos set --state … request-ttl-hours`).
- Admins list them (`requests`) and decide (`accept n` / `refuse n`); the
  reply names who it was. A number only acts while its request is pending.
- Admins get a notice without personal data ("new WhatsApp request", an
  `AdminReply` of 3 bytes on air), at most once an hour each, merged; none in
  stealth.
- MeshCore contacts get in only through a request. A MeshCore direct message
  carries only one byte of the sender's key, so it only arrives from nodes
  whose advert reached the companion; the advert name is therefore known, and
  is shown with the first 4 key bytes (names are self-chosen).

Clients added at home on both sides (QR both ways) are registered without
sending `Register`. Blocked clients get nothing, not even ACKs.

### Emergency mode

While `emergency` is on, signed-plaintext frames are ACKed (so they are not
retried) but dropped, except SOS, pings, `Register` (requests carry no
secret) and the `emergency` switch itself. A client that wrote in plain gets
one `Notice` 6 (encrypted only), sent encrypted; none in stealth. Turning
emergency off re-arms that notice. Replies keep mirroring the client's mode.

### Remote management

Decisions [0020](../decisions/0020-remote-management.md) and
[0021](../decisions/0021-pharos-access-v2.md). Admins live in the state file:
added over SSH (`pharos admin add`), by another admin (`grant`), or with the
admin password (`[access] admin_password_file`) while `login` is on. Any admin
may remove any other, SSH ones included; the last admin cannot be removed
while password login is unavailable (off or no password). Admin and switch
changes are announced to the other admins (not in stealth).

| Channel | Admins | Others, with the admin password |
|---|---|---|
| Radio, signed plaintext | yes | refused even if right; the admins are told to change it |
| Radio, encrypted | yes | `login` once, then admin for good |
| Signal, WhatsApp, MeshCore | yes | `/admin …` → the bot asks for the password → admin for good; "DELETE THE MESSAGE WITH THE PASSWORD NOW" |

On air, commands are `Admin` messages (one opcode byte, compact arguments; a
switch change is one byte) and replies are structured `AdminReply`s that the
app shows in its language ([frame-format.md](frame-format.md#admin-and-adminreply)).
On the bridges they are English text after `/admin`, with English replies:

| Command | Effect |
|---|---|
| `help`, or `/admin` alone | list the commands (bridges only; the app has its own) |
| `status` | switches, registered, requests, listening, queued, authorised contacts |
| `requests` | pending access requests, numbered |
| `accept n` / `refuse n` | decide a request |
| `clients` | each field user, status and when it was last heard |
| `remove who` | take a field user or a contact off without blocking (they may ask again) |
| `blocked` | who is blocked |
| `allow number` | let a Signal or WhatsApp contact write (MeshCore: by request only) |
| `block who` / `unblock who` | ignore a field user or a bridged contact |
| `admins` | who administers |
| `grant who` / `revoke who` | add or remove an admin |
| `set open\|requests\|login\|emergency\|stealth on\|off` | flip a switch |
| `login password` | become admin |

`who` is never an alias: aliases are chosen by their owners, so anyone can
claim one. It is a field user's id (16 hex digits, the start of its safety
code; `clients` lists them), `meshcore` and a MeshCore contact's id, or a
phone with country code (on the admin's own network unless
`signal`/`whatsapp` comes first) or a Signal username. An id that matches two
keys matches nothing. In the app nobody types ids: people are picked from
the user's contacts or the Pharos's lists, shown as `@bea · 4F2A 9C11` with
the verified badge when they are a verified contact.
Five wrong passwords an hour: no answers for an hour. The app warns before
sending in plain a command whose request or reply puts a name or number on
air.

SSH only: the access code, the admin password, bridges and their accounts,
callsign, frequencies, SOS contacts, restarts and updates.

### Ping and the "Pharos heard" indicator

- The ping is a compact `Poll` carrying `listen` minutes. Pharos ACKs it;
  `more = 1` means messages are queued, which then follow.
- Pings and their ACKs use Js8Slow: they are one frame, and their job is to
  get through when nothing else does.
- The app pings: when opened, if nothing was heard for 15 minutes; on demand
  from the indicator; and every N minutes if the user enables it (off, 30,
  60, 120; default off). Every authenticated frame from a Pharos, ACKs
  included, refreshes "last heard" and its SNR.
- Footprint: each ping is two frames on air. The callsign must be sent in
  clear anyway, so pings add no identification, only more transmissions to
  locate by direction finding. Hence off by default.

### Store and forward

- A client is **listening** until `last frame heard + listen minutes` (default
  10 minutes when the last frame was not a Poll).
- While listening: Pharos sends queued messages one at a time (stop-and-wait,
  same ARQ as the app, up to 4 attempts with lane step-down).
- Not listening, or 4 attempts failed: the message stays queued. The next
  authenticated frame from the client (Poll or anything else) restarts
  delivery; its ACK carries `more = 1`.
- Queue limits: 72 hours, 50 messages per client; expired messages produce a
  bot reply to the bridged sender (§2) and nothing on air.
- Replies use the mode of the client's last message (mirrored mode).
- The queue and registry are persisted before transmitting.

### Bridged chats from the client side

- New chat with a bridged contact: the client binds a free handle (0..=127)
  with `BridgeOpen`, then sends `Bridged` messages on it.
- Messages from a bridged contact the client has no handle for: Pharos binds
  a handle (128..=255) with its own `BridgeOpen` (address plus the network
  display name), then sends `Bridged` messages.
- Delivery ticks mean "Pharos has it". Read receipts, typing indicators and
  similar metadata never go on air.
- Errors come back as `Notice` codes 16–21 with the handle.
- SOS to a Pharos: forwarded to the client's SOS contacts (Pharos config) on
  every bridge, with a map link, and marked 🆘.

## 2. The bridge bot

The Pharos has one account per network (a Signal number or username, a
WhatsApp number, a MeshCore companion node). People there write to it like to
any contact; the bot routes by alias. All bot texts are Spanish.

### Who can write

- One-to-one chats only, on every network: group and channel messages are
  ignored (and the Pharos leaves groups where the network allows it).
- Open Pharos: anyone. Closed: authorised contacts (written to first by a
  field user, accepted, allowed by an admin, admins, or admitted with the
  access code). Blocked contacts are ignored silently.
- Closed, someone else writes:
  - The access code admits at once; the bot asks, in capitals, to delete the
    message with it (bots cannot delete other people's messages).
  - `requests` on: the first message becomes an access request and is held
    (up to 3); the bot says so and that a code also works. Further messages
    are held, with one "still pending" note an hour. Accepted: "Tu solicitud
    ha sido aceptada" and the held messages wait for an alias. Refused: one
    note, then silence until an admin allows them.
  - `requests` off: asked for the access code if there is one (five wrong
    codes an hour: silence), else one "only authorised contacts" note an
    hour.
- An unknown alias gets "No puedo hacer llegar mensajes a @x", whether or not
  it exists.

### Welcome and help

- Signal and WhatsApp: each contact gets one welcome (who the Pharos is, how
  to write with `@alias`, limits, what 📡 ✅ ❌ mean, `/ayuda`). It comes
  after the access code when one is needed, on the contact's first message
  otherwise, or just before a field user's first message to them.
- `/ayuda`, `/help` and `/ajuda` repeat the instructions, in Spanish, English
  or Valencian, and list recent aliases. They are never held or routed as a
  message; asking for help first counts as the welcome. The bot's other user
  texts are Spanish; admin texts English.
- `/admin` commands: see "Remote management" above.

### Choosing the field user

State per bridged contact: a sticky **target** (alias + time of last message in
either direction) and the field users it **talked with in the last hour**.

For each incoming message:

1. Text starting with `@alias` → that alias, if allowed. The rest is the
   message; if empty, set the target and answer "Ahora hablas con @alias.".
2. A pending question ("¿Para quién es?") is open and the text is just an
   alias (with or without `@`) → set the target and deliver the held messages.
3. Two or more field users talked with this contact in the last hour → every
   message needs `@alias`. Hold the message and ask, listing them.
4. A target active within the last hour → deliver to it.
5. Otherwise hold the message and ask "¿Para quién es? Responde con su
   alias." plus up to 5 recent aliases ("Recientes: @alvaro, @mama").

A field user writing first sets the contact's target to that user, so the
contact can just reply. Held messages: at most 3, dropped after 1 hour with a
note.

### What the contact sees

- Messages from the field: `alias: text` (SOS: `🆘 SOS de alias` + map link;
  positions as map links).
- Feedback on its own messages, without spending airtime: a 📡 reaction when
  queued, ✅ when the field user's station ACKed it, ❌ plus a note quoting
  the message when it will not arrive (72 h expiry, queue full). One text
  note per hour at most if the user is not listening: "@alias no está a la
  escucha; se entregará cuando conecte".
- Limits: text only, 200 characters at most (≈ 4 minutes of airtime at
  Normal), 10 queued messages per contact. Every refusal says "no se ha
  enviado" and quotes the message where it helps, so it can be resent.

### MeshCore specifics

- Direct messages to the companion node follow the rules above. Channels
  are never bridged.
- Made for short messages: no welcome, and every reply has a short form
  ("Envía el código de acceso de este Pharos.", "Solo contactos
  autorizados. No se ha enviado."). The help commands answer with one line.

## 3. Configuration (sketch)

See `core/crates/pharos/base.example.toml`. Files are read on every start;
the switches, admins, allow list and authorised contacts only on the first
run (then they live in the state file).

```toml
[access]
code_file = "/etc/pharos/access-code"               # 0600, optional
admin_password_file = "/etc/pharos/admin-password"  # 0600, optional
open = false             # first run only, like the four below
requests = true
login = true
stealth = false
request_ttl_hours = 72

[bot]
admins = ["+34600000000"]

[[clients]]
key = "skypost:id/5P2V…Q4?n=Mam%C3%A1"
admin = true
contacts = ["+34611111111"]            # authorised bridged contacts
sos = ["+34611111111"]
```

Over SSH, with the service stopped: `pharos status --state …`,
`pharos set --state … <switch> on|off`, `pharos set --state …
request-ttl-hours 48`, `pharos admin --state … add|remove <who>`.
