# Regulatory notes (Spain)

Not legal advice. Confirm the open points with URE or the Jefatura Provincial
de Inspección de Telecomunicaciones. Source: [Orden IET/1311/2013](https://www.boe.es/buscar/act.php?id=BOE-A-2013-7624)
(Reglamento de uso del dominio público radioeléctrico por radioaficionados).

## Rules that shape the design

| Article | Rule | Design consequence |
|---|---|---|
| 30.2 | Transmissions must not be coded to hide their meaning (except satellite control). | Signed plaintext is the default; encryption is an explicit emergency mode. |
| 30.3 | Traffic on behalf of third parties only in emergencies or disasters. | Everyday bridging of family messages is a grey area; see below. |
| 29 | Callsign at the start and end of each transmission, and at least every 10 minutes. | Callsign in clear in every transmission (JS8 directed messages do this in Phase 0; Phase 1 must add it to binary frames or send a CW/JS8 ID). |
| 18.4, 18.6, Annex I | Unattended automatic stations are collective stations; only recognised radio associations can hold them. | A 24/7 auto-replying base needs an association route. |
| 25.i | Unattended HF automatic stations: ≤ 50 W output. | Base TX power cap. |
| 25.g | Repeaters and beacons need remote on/off and ≥ 6 h of backup power. | Plan the base for both, in case it is classed alike. |
| Annex I 4.3 | Tests that need no radiation must use a dummy load. | Encrypted-mode tests go into a dummy load or attenuator. |
| 60 m resolution | 5351.5–5366.5 kHz, 15 W EIRP, any modulation. | 60 m is usable for the transitions. |

ITU Radio Regulations allow a station in distress to use any means at its
disposal; that is the basis for emergency mode.

## Open points

1. **Is compressed signed plaintext "coded to hide meaning"?** The text is
   Huffman-coded binary, not readable on a JS8Call screen, although the code
   is public and nothing is secret. JS8 itself compresses text. Mitigation:
   publish the spec and a decoder (this repo), and offer a readable decode on
   request. Ask URE.
2. **Base station status.** Receive-only bridging has no licensing issue.
   Auto-replying (ACKs, store and forward) makes it an unattended automatic
   station. Route: the community network association becomes a recognised
   radio association, or partners with a radio club.
3. **Third-party traffic.** Messages from a licensed field operator to family
   via the bridge are third-party traffic outside emergencies. Ask URE how
   they read it for a station-to-own-station link that then leaves via the
   internet.
4. **Mum as operator.** Without a licence she can only transmit in a real
   distress situation.
