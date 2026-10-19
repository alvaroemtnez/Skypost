# 0017. The app is a chat client, not a two-profile button panel

Status: accepted (2026-10-19). Replaces the family/advanced profiles in
`design.md` §9.

## Context

The first sketch had a four-button "family mode" and a PIN-protected advanced
mode. With identities, groups and bridged chats the app has to show several
conversations, and everyone already knows the Signal/WhatsApp pattern.

## Decision

- One Material 3 chat app: onboarding (create identity / restore backup), a
  unified chat list with filter chips (direct, groups, Signal, WhatsApp,
  MeshCore), a bubble timeline, a "new chat" picker, a Pharos screen, settings.
- SOS stays one tap away in the top bar of the chat list (with a confirm step).
- Quick messages (stock phrases, "Estoy bien" with position) live in the
  composer, so a non-operator still sends them with two taps.
- Emergency encryption is a per-chat switch with a confirmation, not a global
  PIN-protected mode.

## Consequences

- `ui/family` and `ui/advanced` are replaced by `ui/chats`, `ui/chat`,
  `ui/newchat`, `ui/pharos`, `ui/settings`, `ui/onboarding`.
- The family-friendliness requirement moves into details: big SOS target,
  plain Spanish, quick-message chips, airtime estimate before sending.
- QR codes: rendered and scanned on the phone (ZXing, camera via CameraX).
  Google's code scanner was tried first and dropped: it failed on the test
  phone and needs Play services. Pasting codes and `skypost:` links also work.
- Back navigation slides the screen out on release, like Signal (predictive
  back off), not the shrinking-card animation.
- English, Spanish and Valencian, following the system language.
- Signal/WhatsApp numbers come from the system contact picker (one-off read
  grant), so the app needs no contacts permission.
