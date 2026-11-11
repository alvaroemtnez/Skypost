# 0023. The app's UI follows MeshConnect's

Status: accepted (2026-11-11).

## Context

The owner also maintains MeshConnect, a MeshCore client. A family that uses
both apps should find the same gestures, menus and cards in each, but must
not mistake one app for the other.

## Decision

Skypost borrows MeshConnect's patterns, ported by hand into `ui/components`
(the apps share no code):

- **Chat list:** 52 dp avatars with up to two initials (`AvatarInitials`),
  MeshConnect's row sizes, a rounded long-press menu (Delete in red, the
  checklist icon for Select), swipe right for read/unread and left for
  archive, a red confirmation before deleting, and search that grows out of
  the top bar. New chat is a floating button above SOS. The filter chips stay
  text-only, unlike MeshConnect's.
- **Status in the top bar:** signal bars plus the active Pharos's name, and
  the radio icon plus its short name (`RadioModel`: QDX, QMX, QMX+, G90, or
  Custom with CAT set by hand). They stay two buttons because they open
  different things. The traffic-light colours are gone.
- **Cards over the screen** (bottom sheets): Pharos, radio and new chat,
  opened from the top bar or Settings. The Pharos card's Manage button is
  filled so it stands out. It opens the admin screen, not another card.
  My QR code and shared codes are popups.
- **Screens below the chat list** (Settings, Pharos admin, SOS chats,
  Blocked) get a top bar in its own colour. The chat list keeps a bar the
  colour of the page.
- **Settings** use grouped rounded cards with icon discs. **Pharos admin**
  follows MeshConnect's repeater screens: an identity card with a grant chip,
  then folding card sections.

Colour, logo and wording stay Skypost's own, and so do SOS, the stock
phrases and the composer.

## Consequences

- Routes `NewChat`, `NewChatOf`, `PharosRoute`, `RadioRoute`, `MyCode`,
  `CardCode` and `GroupInvite` are gone. `MainActivity` holds the open sheet
  and the QR popup.
- `Settings` gains `radio` and `customRadio`. The radio service (phase 2)
  will take its CAT protocol and speed from them.
- A pattern changed in one app should be weighed for the other.
