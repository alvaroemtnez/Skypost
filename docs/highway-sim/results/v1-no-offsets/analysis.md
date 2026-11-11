## Thresholds: SNR (dB, average power, 2500 Hz) for 50 % / 90 % frame success

| system | mode | awgn | ccir-good | ccir-moderate | ccir-poor | itur-mdv |
|---|---|---|---|---|---|---|
| js8 | Slow | -21.8 / -20.0 | -19.5 / -15.3 | -18.5 / -16.1 | -18.1 / -15.5 | -18.4 / -15.4 |
| js8 | Normal | -20.1 / -19.1 | -18.2 / -13.0 | -17.2 / -14.6 | -17.1 / -14.6 | -17.0 / -15.0 |
| js8 | Fast | -17.9 / -14.5 | -15.3 / -9.8 | -14.9 / -11.4 | -15.0 / -11.8 | -14.8 / -12.4 |
| js8 | JS8 40 | -13.1 / -11.6 | -11.2 / n/a | -10.6 / -6.4 | -10.1 / -6.5 | -10.7 / -6.9 |
| js8 | JS8 60 | n/a / n/a | n/a / n/a | n/a / n/a | n/a / n/a | n/a / n/a |
| mercury | DATAC1 | 1.5 / 2.0 | 3.6 / 8.0 | 4.4 / 6.8 | 4.2 / 6.0 | n/a / n/a |
| mercury | DATAC15 | -10.1 / -9.1 | -7.6 / 1.0 | -6.8 / 0.2 | -6.9 / -2.0 | -5.3 / 4.0 |
| mercury | DATAC17 | 3.5 / 3.9 | 6.8 / 11.2 | 7.2 / 9.9 | 7.1 / 8.8 | n/a / n/a |
| mercury | DATAC3 | -3.4 / -2.8 | -1.7 / 5.5 | -0.2 / 3.7 | -0.8 / 1.8 | n/a / n/a |
| mercury | DATAC4 | -8.4 / -7.2 | -7.1 / -0.6 | -6.1 / -2.1 | -6.0 / -2.4 | -2.2 / n/a |
| mercury | MFSK100 | -10.4 / -9.4 | -8.1 / -4.3 | -7.6 / -4.7 | -7.7 / -4.3 | -7.5 / -5.2 |
| mercury | MFSK103 | -13.5 / -13.1 | -11.0 / -8.3 | -10.6 / -9.2 | -10.8 / -9.4 | -10.6 / -9.2 |
| xara | L1 | -15.5 / -14.3 | -14.1 / -8.9 | -14.1 / -11.6 | -14.1 / -12.1 | -13.6 / -11.7 |
| xara | L2 | -12.5 / -11.9 | -10.7 / -6.9 | -10.9 / -9.1 | -10.7 / -9.1 | -9.9 / -8.2 |
| xara | L3 | -10.5 / -10.1 | -8.5 / -3.6 | -8.2 / -6.0 | -7.7 / -6.2 | -6.8 / -5.0 |
| xara | L4 | -7.5 / -7.1 | -5.2 / -0.3 | -5.0 / -2.7 | -4.5 / -2.6 | -0.2 / n/a |
| xara | L5 | -5.4 / -4.5 | -3.6 / 1.6 | -3.2 / -0.7 | -2.7 / -1.0 | 1.1 / 3.7 |
| xara | L6 | -4.1 / -3.2 | -1.9 / 3.4 | -1.7 / 0.7 | -1.2 / 2.0 | 2.3 / 4.6 |
| xara | L7 | -2.4 / -1.8 | 0.0 / 5.1 | -0.0 / 1.9 | 0.0 / 2.0 | 6.1 / n/a |
| xara | L8 | -1.1 / -0.2 | 0.8 / 6.0 | 1.2 / 3.3 | 1.5 / 3.4 | 7.6 / 13.0 |
| xara | L9 | -0.2 / 0.7 | 1.9 / 8.0 | 2.1 / 4.5 | 2.4 / 4.0 | 9.4 / n/a |
| xara | L10 | 3.6 / 4.3 | 6.1 / 11.8 | 5.8 / 8.4 | 6.2 / 8.0 | 13.8 / n/a |
| xara | L11 | 5.5 / 5.9 | 8.8 / 14.6 | 8.6 / 11.2 | 9.3 / 11.4 | n/a / n/a |
| xara | L12 | 7.3 / 7.9 | 11.8 / 18.0 | 11.6 / 14.0 | 12.5 / 14.7 | n/a / n/a |
| xara | L13 | 9.5 / 9.9 | 12.6 / 18.6 | 12.3 / 14.9 | 13.0 / 15.4 | n/a / n/a |
| xara | L14 | 11.5 / 11.9 | 15.6 / 21.7 | 15.6 / 18.3 | 17.0 / 18.9 | n/a / n/a |
| xara | L15 | 12.5 / 12.9 | 17.1 / 22.5 | 17.4 / 19.6 | 17.9 / 20.8 | n/a / n/a |
| xara | L16 | 15.5 / 15.9 | 19.3 / 24.7 | 19.4 / 21.8 | 20.7 / 23.8 | n/a / n/a |

## Airtime model

- JS8 Slow: 9 B per 30 s slot = 2.4 bit/s at 100 %
- JS8 Normal: 9 B per 15 s slot = 4.8 bit/s at 100 %
- JS8 Fast: 9 B per 10 s slot = 7.2 bit/s at 100 %
- JS8 JS8 40: 9 B per 6 s slot = 12.0 bit/s at 100 %
- JS8 JS8 60: 9 B per 4 s slot = 18.0 bit/s at 100 %
- XARA L1: 18 B per 12.58 s cycle (2 frames + ACK) = 11 bit/s at 100 %
- XARA L2: 44 B per 11.72 s cycle (2 frames + ACK) = 30 bit/s at 100 %
- XARA L3: 94 B per 11.77 s cycle (2 frames + ACK) = 64 bit/s at 100 %
- XARA L4: 178 B per 10.46 s cycle (2 frames + ACK) = 136 bit/s at 100 %
- XARA L5: 264 B per 9.38 s cycle (2 frames + ACK) = 225 bit/s at 100 %
- XARA L6: 346 B per 9.14 s cycle (2 frames + ACK) = 303 bit/s at 100 %
- XARA L7: 512 B per 8.99 s cycle (2 frames + ACK) = 455 bit/s at 100 %
- XARA L8: 682 B per 8.93 s cycle (2 frames + ACK) = 611 bit/s at 100 %
- XARA L9: 850 B per 8.82 s cycle (2 frames + ACK) = 771 bit/s at 100 %
- XARA L10: 1792 B per 8.20 s cycle (2 frames + ACK) = 1749 bit/s at 100 %
- XARA L11: 2392 B per 8.20 s cycle (2 frames + ACK) = 2334 bit/s at 100 %
- XARA L12: 2872 B per 8.20 s cycle (2 frames + ACK) = 2803 bit/s at 100 %
- XARA L13: 3592 B per 8.20 s cycle (2 frames + ACK) = 3505 bit/s at 100 %
- XARA L14: 4312 B per 8.20 s cycle (2 frames + ACK) = 4208 bit/s at 100 %
- XARA L15: 5420 B per 8.46 s cycle (2 frames + ACK) = 5124 bit/s at 100 %
- XARA L16: 6508 B per 8.46 s cycle (2 frames + ACK) = 6153 bit/s at 100 %
- Mercury MFSK103: 92 B per 30.5 s round + poll = 24 bit/s at 100 %
- Mercury MFSK100: 184 B per 31.3 s round + poll = 47 bit/s at 100 %
- Mercury DATAC15: 144 B per 30.7 s round + poll = 37 bit/s at 100 %
- Mercury DATAC4: 240 B per 33.5 s round + poll = 57 bit/s at 100 %
- Mercury DATAC3: 840 B per 31.3 s round + poll = 215 bit/s at 100 %
- Mercury DATAC1: 3024 B per 33.4 s round + poll = 725 bit/s at 100 %
- Mercury DATAC17: 4704 B per 33.8 s round + poll = 1113 bit/s at 100 %

## Best-mode goodput envelope, bit/s (mode), channel awgn

| SNR | JS8 (all speeds) | XARA L1-4 (QDX tx) | Mercury MFSK (QDX tx) | XARA L1-16 (linear) | Mercury all rungs (linear) |
|---|---|---|---|---|---|
| -26 | – | – | – | – | – |
| -24 | – | – | – | – | – |
| -22 | 1 (Slow) | – | – | – | – |
| -20 | 3 (Normal) | – | – | – | – |
| -18 | 5 (Normal) | – | – | – | – |
| -16 | 6 (Fast) | 2 (L1) | – | 2 (L1) | – |
| -14 | 7 (Fast) | 11 (L1) | – | 11 (L1) | – |
| -12 | 10 (JS8 40) | 27 (L2) | 24 (MFSK103) | 27 (L2) | 24 (MFSK103) |
| -10 | 12 (JS8 40) | 61 (L3) | 38 (MFSK100) | 61 (L3) | 38 (MFSK100) |
| -8 | 12 (JS8 40) | 64 (L3) | 47 (MFSK100) | 64 (L3) | 47 (MFSK100) |
| -6 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 136 (L4) | 57 (DATAC4) |
| -4 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 225 (L5) | 57 (DATAC4) |
| -2 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 396 (L7) | 215 (DATAC3) |
| 0 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 611 (L8) | 215 (DATAC3) |
| 2 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 771 (L9) | 681 (DATAC1) |
| 4 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 1504 (L10) | 1057 (DATAC17) |
| 6 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 2288 (L11) | 1113 (DATAC17) |
| 8 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 2775 (L12) | 1113 (DATAC17) |
| 10 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 3435 (L13) | 1113 (DATAC17) |
| 12 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 4124 (L14) | 1113 (DATAC17) |
| 14 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 5124 (L15) | 1113 (DATAC17) |
| 16 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 18 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 20 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 22 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 24 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 26 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 28 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |
| 30 | 12 (JS8 40) | 136 (L4) | 47 (MFSK100) | 6153 (L16) | 1113 (DATAC17) |

## Best-mode goodput envelope, bit/s (mode), channel ccir-good

| SNR | JS8 (all speeds) | XARA L1-4 (QDX tx) | Mercury MFSK (QDX tx) | XARA L1-16 (linear) | Mercury all rungs (linear) |
|---|---|---|---|---|---|
| -26 | – | – | – | – | – |
| -24 | – | – | – | – | – |
| -22 | 1 (Normal) | – | – | – | – |
| -20 | 1 (Normal) | – | – | – | – |
| -18 | 3 (Normal) | 1 (L1) | – | 1 (L1) | – |
| -16 | 3 (Normal) | 2 (L1) | – | 2 (L1) | – |
| -14 | 4 (Fast) | 6 (L1) | 2 (MFSK103) | 6 (L1) | 3 (DATAC15) |
| -12 | 5 (Fast) | 10 (L2) | 7 (MFSK103) | 10 (L2) | 7 (MFSK103) |
| -10 | 7 (JS8 40) | 19 (L3) | 16 (MFSK103) | 19 (L3) | 16 (MFSK103) |
| -8 | 8 (JS8 40) | 35 (L3) | 24 (MFSK100) | 35 (L3) | 24 (MFSK100) |
| -6 | 9 (JS8 40) | 54 (L4) | 38 (MFSK100) | 54 (L4) | 38 (MFSK100) |
| -4 | 10 (JS8 40) | 94 (L4) | 43 (MFSK100) | 99 (L5) | 47 (DATAC3) |
| -2 | 10 (JS8 40) | 108 (L4) | 45 (MFSK100) | 148 (L6) | 101 (DATAC3) |
| 0 | 10 (JS8 40) | 126 (L4) | 46 (MFSK100) | 238 (L8) | 137 (DATAC3) |
| 2 | 10 (JS8 40) | 131 (L4) | 46 (MFSK100) | 393 (L9) | 203 (DATAC1) |
| 4 | 10 (JS8 40) | 135 (L4) | 46 (MFSK100) | 540 (L9) | 420 (DATAC1) |
| 6 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 857 (L10) | 580 (DATAC1) |
| 8 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1189 (L10) | 723 (DATAC17) |
| 10 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1424 (L11) | 912 (DATAC17) |
| 12 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1751 (L11) | 1035 (DATAC17) |
| 14 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2138 (L13) | 1079 (DATAC17) |
| 16 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2734 (L13) | 1079 (DATAC17) |
| 18 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2988 (L14) | 1079 (DATAC17) |
| 20 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 3843 (L15) | 1079 (DATAC17) |
| 22 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 4553 (L16) | 1090 (DATAC17) |
| 24 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 5168 (L16) | 1090 (DATAC17) |
| 26 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 5907 (L16) | 1090 (DATAC17) |
| 28 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6153 (L16) | 1090 (DATAC17) |
| 30 | 10 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6153 (L16) | 1090 (DATAC17) |

## Best-mode goodput envelope, bit/s (mode), channel ccir-moderate

| SNR | JS8 (all speeds) | XARA L1-4 (QDX tx) | Mercury MFSK (QDX tx) | XARA L1-16 (linear) | Mercury all rungs (linear) |
|---|---|---|---|---|---|
| -26 | – | – | – | – | – |
| -24 | – | – | – | – | – |
| -22 | – | – | – | – | – |
| -20 | 1 (Slow) | – | – | – | – |
| -18 | 2 (Normal) | – | – | – | – |
| -16 | 4 (Normal) | 1 (L1) | – | 1 (L1) | – |
| -14 | 5 (Fast) | 6 (L1) | – | 6 (L1) | – |
| -12 | 6 (Fast) | 10 (L1) | 3 (MFSK103) | 10 (L1) | 3 (MFSK103) |
| -10 | 7 (Fast) | 22 (L2) | 18 (MFSK103) | 22 (L2) | 18 (MFSK103) |
| -8 | 10 (JS8 40) | 35 (L3) | 23 (MFSK103) | 35 (L3) | 23 (MFSK103) |
| -6 | 11 (JS8 40) | 58 (L3) | 39 (MFSK100) | 58 (L3) | 39 (MFSK100) |
| -4 | 11 (JS8 40) | 99 (L4) | 43 (MFSK100) | 99 (L4) | 46 (DATAC4) |
| -2 | 11 (JS8 40) | 132 (L4) | 46 (MFSK100) | 164 (L5) | 54 (DATAC3) |
| 0 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 242 (L6) | 114 (DATAC3) |
| 2 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 414 (L7) | 163 (DATAC3) |
| 4 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 647 (L9) | 283 (DATAC1) |
| 6 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 944 (L10) | 551 (DATAC1) |
| 8 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1539 (L10) | 746 (DATAC17) |
| 10 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1751 (L11) | 1013 (DATAC17) |
| 12 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2218 (L11) | 1090 (DATAC17) |
| 14 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2699 (L13) | 1102 (DATAC17) |
| 16 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 3330 (L13) | 1102 (DATAC17) |
| 18 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 3703 (L14) | 1102 (DATAC17) |
| 20 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 4868 (L15) | 1102 (DATAC17) |
| 22 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 5660 (L16) | 1102 (DATAC17) |
| 24 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6091 (L16) | 1102 (DATAC17) |
| 26 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6153 (L16) | 1102 (DATAC17) |
| 28 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6153 (L16) | 1102 (DATAC17) |
| 30 | 11 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6153 (L16) | 1102 (DATAC17) |

## Best-mode goodput envelope, bit/s (mode), channel ccir-poor

| SNR | JS8 (all speeds) | XARA L1-4 (QDX tx) | Mercury MFSK (QDX tx) | XARA L1-16 (linear) | Mercury all rungs (linear) |
|---|---|---|---|---|---|
| -26 | – | – | – | – | – |
| -24 | – | – | – | – | – |
| -22 | – | – | – | – | – |
| -20 | 1 (Slow) | – | – | – | – |
| -18 | 1 (Normal) | – | – | – | – |
| -16 | 4 (Normal) | – | – | – | – |
| -14 | 5 (Fast) | 6 (L1) | – | 6 (L1) | – |
| -12 | 6 (Fast) | 11 (L1) | – | 11 (L1) | – |
| -10 | 7 (Fast) | 22 (L2) | 20 (MFSK103) | 22 (L2) | 20 (MFSK103) |
| -8 | 9 (JS8 40) | 29 (L2) | 24 (MFSK103) | 29 (L2) | 24 (MFSK103) |
| -6 | 11 (JS8 40) | 60 (L3) | 40 (MFSK100) | 60 (L3) | 40 (MFSK100) |
| -4 | 12 (JS8 40) | 88 (L4) | 43 (MFSK100) | 88 (L4) | 46 (DATAC4) |
| -2 | 12 (JS8 40) | 132 (L4) | 45 (MFSK100) | 160 (L5) | 53 (DATAC4) |
| 0 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 228 (L7) | 155 (DATAC3) |
| 2 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 410 (L7) | 198 (DATAC3) |
| 4 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 701 (L9) | 312 (DATAC1) |
| 6 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 787 (L10) | 652 (DATAC1) |
| 8 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1591 (L10) | 901 (DATAC17) |
| 10 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 1749 (L10) | 1079 (DATAC17) |
| 12 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2264 (L11) | 1079 (DATAC17) |
| 14 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 2524 (L13) | 1090 (DATAC17) |
| 16 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 3330 (L13) | 1090 (DATAC17) |
| 18 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 3470 (L13) | 1102 (DATAC17) |
| 20 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 4099 (L15) | 1102 (DATAC17) |
| 22 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 4817 (L15) | 1102 (DATAC17) |
| 24 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 5660 (L16) | 1102 (DATAC17) |
| 26 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 5907 (L16) | 1102 (DATAC17) |
| 28 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 5968 (L16) | 1102 (DATAC17) |
| 30 | 12 (JS8 40) | 136 (L4) | 46 (MFSK100) | 6030 (L16) | 1102 (DATAC17) |

## Best-mode goodput envelope, bit/s (mode), channel itur-mdv

| SNR | JS8 (all speeds) | XARA L1-4 (QDX tx) | Mercury MFSK (QDX tx) | XARA L1-16 (linear) | Mercury all rungs (linear) |
|---|---|---|---|---|---|
| -26 | – | – | – | – | – |
| -24 | – | – | – | – | – |
| -22 | – | – | – | – | – |
| -20 | 1 (Slow) | – | – | – | – |
| -18 | 1 (Slow) | – | – | – | – |
| -16 | 4 (Normal) | – | – | – | – |
| -14 | 5 (Fast) | 5 (L1) | – | 5 (L1) | – |
| -12 | 7 (Fast) | 10 (L1) | 1 (MFSK103) | 10 (L1) | 1 (MFSK103) |
| -10 | 7 (JS8 40) | 14 (L2) | 19 (MFSK103) | 14 (L2) | 19 (MFSK103) |
| -8 | 10 (JS8 40) | 28 (L2) | 23 (MFSK103) | 28 (L2) | 23 (MFSK103) |
| -6 | 11 (JS8 40) | 45 (L3) | 40 (MFSK100) | 45 (L3) | 40 (MFSK100) |
| -4 | 12 (JS8 40) | 63 (L3) | 43 (MFSK100) | 63 (L3) | 43 (MFSK100) |
| -2 | 12 (JS8 40) | 64 (L3) | 47 (MFSK100) | 64 (L3) | 47 (MFSK100) |
| 0 | 12 (JS8 40) | 73 (L4) | 47 (MFSK100) | 73 (L4) | 47 (MFSK100) |
| 2 | 12 (JS8 40) | 90 (L4) | 47 (MFSK100) | 155 (L5) | 47 (DATAC3) |
| 4 | 12 (JS8 40) | 103 (L4) | 47 (MFSK100) | 245 (L6) | 73 (DATAC3) |
| 6 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 303 (L6) | 86 (DATAC3) |
| 8 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 342 (L8) | 101 (DATAC1) |
| 10 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 452 (L8) | 167 (DATAC1) |
| 12 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 752 (L10) | 217 (DATAC1) |
| 14 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 892 (L10) | 203 (DATAC1) |
| 16 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1189 (L10) | 261 (DATAC1) |
| 18 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
| 20 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
| 22 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
| 24 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
| 26 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
| 28 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
| 30 | 12 (JS8 40) | 108 (L4) | 47 (MFSK100) | 1224 (L10) | 261 (DATAC1) |
