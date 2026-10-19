# Identities, contacts and `skypost:` URIs — protocol v1

Implemented in `core/crates/keryx/src/uri.rs`. Crypto background:
[crypto.md](crypto.md).

## What a station is

| Field | Notes |
|---|---|
| Public key | X25519, 32 bytes. The identity. |
| Alias | 1–16 characters: letters (any script), digits, `_`, `-`, `.`. What people see and what `@alias` addresses on a Pharos. Not unique globally. |
| Callsign | Optional. Licensed operators and every Pharos. Sent in clear for identification (Art. 29), never used for addressing. |
| Kind | `person` or `pharos`. The app keeps Pharos stations out of the contact list. |

Short ids and fingerprints: see [crypto.md](crypto.md#identities).

## URIs

All QR codes, clipboard shares and deep links use the `skypost:` scheme.
Base32 is RFC 4648, no padding, upper case (it packs well into QR
alphanumeric mode). Query values are percent-encoded UTF-8.

| URI | Meaning |
|---|---|
| `skypost:id/<BASE32 public key>?n=<alias>[&c=<callsign>][&k=pharos]` | A person or a Pharos |
| `skypost:group/<BASE32 group key>?n=<group name>` | A group invitation. **Secret:** whoever has it is a member. |

Examples:

```
skypost:id/5P2V…Q4?n=%C3%81lvaro&c=EA5XXX
skypost:id/H7KD…2A?n=Casa&c=EA5YYY&k=pharos
skypost:group/Q6M3…7Z?n=Familia
```

- The app registers an intent filter for `skypost:`, so scanning a code with
  the phone camera opens the app on an "add contact / add Pharos / join group"
  confirmation.
- Unknown query keys are ignored (forward compatible).
- A group URI is a secret: the app warns before showing or copying it, and
  never shares it through other apps without confirmation.

## Groups

- Created on a phone: 32 random bytes and a name.
- Joining: scan the group URI, then the app sends one `GroupJoin` (public key
  and alias, tagged with the group key) so members learn the new public key.
- Each member keeps a roster (public key, alias) built from `GroupJoin`
  frames and from contacts it already has. A frame from an unknown member
  cannot be verified and is dropped: the app suggests re-sending the join.

## MeshCore contacts and channels

The app also understands MeshCore's own QR payloads
(`meshcore://contact/add?name=…&public_key=…&type=…` and channel codes) and
turns them into `BridgeOpen` requests for a Pharos. Parsing them is app-side
convenience; the Pharos MeshCore sidecar does the MeshCore work.
