# Hardware

## Radios on hand

| Radio | Power | Modes | Lanes | Notes |
|---|---|---|---|---|
| QRP Labs QDX | ~5 W | FSK digital only (single tone) | JS8 only | One USB cable for audio + CAT. Check which bands your QDX covers. No tuner. |
| Xiegu G90 | 20 W | SSB, CW, digital via audio | JS8 + OFDM | Built-in tuner. No USB audio: needs a USB sound card interface (XGGComms adapter today). |

## Candidate for the family kit: QMX+

- 160–6 m, 3–5 W, CW, FSK digital and SSB (polar modulation firmware).
- Optional internal GPS and battery-backed RTC: solves JS8 clock sync on its own.
- 160 m covers the winter pre-dawn NVIS gap.
- SSB mode is recommended by QRP Labs for multi-tone modes, so it **might**
  carry Mercury's OFDM at 5 W. Bench-test first.

## Field kit (mum's)

- Radio (QDX now, QMX+ later), LiFePO4 battery, phone with the app in family
  mode, USB OTG cable.
- Antenna: pre-cut NVIS dipole for 40/60/80 m (fan or linked), 2–4 m up on a
  telescopic pole, colour-coded. The QDX has no tuner, so it must be resonant.
- Pre-flight: battery voltage, SWR (QMX reports it; G90 via CAT), GPS fix.

## Base station

- Raspberry Pi 5 or a small x86 box; `nvl-daemon`.
- Receive: wideband HF SDR covering 80/60/40 m at once, so the base hears
  every band without retuning.
- Transmit: G90 (≤ 50 W for an unattended HF station in Spain).
- Remote on/off and ≥ 6 h backup power (see regulatory notes).

## Android and USB

- FT8CN and qFT8 already run QDX audio + CAT over one USB OTG cable on Android.
- Radios without USB audio (G90) work through a USB sound card interface.
- Build native. WebUSB cannot claim the QDX's CAT interface on Android because
  the kernel binds CDC-ACM devices itself.
- Audio: route `AudioRecord`/`AudioTrack` to the USB device, input preset
  `UNPROCESSED`. CAT/PTT: usb-serial-for-android (CP210x, CH34x, FTDI, CDC-ACM).
- Clock: UTC from GNSS off-grid; the base's ACKs carry a clock-offset correction.
- Some phones supply little OTG power; the QDX is powered from its own 12 V.
