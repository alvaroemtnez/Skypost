# Pharos — discovery, registration, store and forward, bridge bot

Implemented in `core/crates/pharos` (`station.rs` on air, `registry.rs`
clients, `router.rs` bridge bot, `admin.rs` commands). Decisions: [0014](../decisions/0014-bridges-sidecars.md),
[0016](../decisions/0016-pharos-registration-and-bridging.md). Frames:
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
2. Each Pharos that hears it, and has not announced in the last 10 minutes,
   waits a random 0–2 frame slots, picks a random audio offset and sends
   `Announce` to the querier's short id with the same query id, on the lane
   of the `Discover`.
3. The app lists answers with name, callsign, SNR, and whether it is already
   trusted (matched by public key).

### Access

Decision [0019](../decisions/0019-pharos-access.md). `[access] mode`:

| Mode | Who registers | `Announce` flag |
|---|---|---|
| `open` | anyone | — |
| `code` | whoever proves the access code | `CODE_REQUIRED` |
| `closed` (default) | the allow list; others wait for an admin | `CLOSED` |

`discoverable = false` makes the Pharos ignore `Discover`; clients then need
its QR.

The access code never goes on air. `Register` carries
`proof = HMAC-SHA256(K, "keryx-v1/access" ‖ client key ‖ Pharos key)[0..8]`
with `K = Argon2id(code, salt = SHA-256("keryx-v1/access-salt" ‖ Pharos
key)[0..16])` (19 MiB, 2 passes), or 8 zero bytes without a code. A recorded
proof lets an attacker guess the code offline: use four or more random words.

### Registration

1. Client: `Register` (its public key, the proof, its alias), tagged with the
   pair key.
2. Pharos ACKs it, then decides: allow list → registered; otherwise by mode
   (open → registered; code → registered if the proof verifies; closed →
   pending, and every admin gets "¿Aceptar a @mama (4F2A 9C11 …)?").
3. Result as a `Notice` (1 registered, 2 pending, 3 refused, 4 alias taken,
   5 access code needed), sent at once if the client is listening, else
   queued. After 4 or 5 the client may register again.

Clients added at home on both sides (QR both ways) are registered without
sending `Register`. Blocked clients get nothing, not even ACKs.

### Admin commands

From admin bridge accounts (`[bot] admins`) or admin field users over the
radio (`[[clients]] admin = true`, plain text to the Pharos):

| Command | Effect |
|---|---|
| `ayuda` | list the commands |
| `estado` | registered, pending, listening, queued, authorised contacts |
| `clientes` | each field user, status and when it was last heard |
| `aceptar @alias` / `rechazar @alias` | decide a pending registration |
| `bloquear @alias\|número` / `desbloquear …` | ignore a field user or a bridged contact |
| `permitir número` | authorise a bridged contact |

A number is a phone with country code (on the admin's own network unless
`signal`/`whatsapp` comes first), a Signal username or a MeshCore key. Admins
on the radio always name the network. Anything else: SSH and the config.

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
- Authorised contacts may write: listed in the config (`[[clients]]
  contacts`), written to first by a field user, allowed by an admin, or
  admitted with the access code. Blocked contacts are ignored silently.
- `inbound = "code"` (default): an unauthorised contact is asked for the
  access code; its first message is held meanwhile. The right code
  authorises the account for good and the bot asks to delete the message with
  the code (bots cannot delete other people's messages). Five wrong codes in
  an hour: no answers for an hour.
- `inbound = "contacts"`: no code; one "only authorised contacts" note per
  hour. `inbound = "anyone"`: no check.
- An unknown alias gets "No puedo hacer llegar mensajes a @x", whether or not
  it exists.

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

## 3. Configuration (sketch)

See `core/crates/pharos/base.example.toml`:

```toml
[access]
mode = "code"                          # "open", "code" or "closed"
code_file = "/etc/pharos/access-code"  # 0600
discoverable = true

[bot]
inbound = "code"                       # "code", "contacts" or "anyone"
admins = ["+34600000000"]

[[clients]]
key = "skypost:id/5P2V…Q4?n=Mam%C3%A1"
admin = true
contacts = ["+34611111111"]            # authorised bridged contacts
sos = ["+34611111111"]
```
