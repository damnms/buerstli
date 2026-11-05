# Laifen: protocol notes

Status:

| Model | Bluetooth name | Driver | Status |
|---|---|---|---|
| Wave (LFTB01) | `LFTB01-…` except `-SE`, `-C` | `LaifenWaveV1Driver` | **tested** on a real LFTB01-P: reads and changes everything listed in the README |
| Wave Pro (LFTB02) | `LFTB02-S-…` | `LaifenWaveProDriver` | experimental, connects and listens, writes nothing |
| Wave SE (LFTB01-SE) | `LFTB01-SE…` | `LaifenWaveSeDriver` | experimental, connects and listens, writes nothing |
| Kids TC51 (LFTB01-C) | `LFTB01-C…` | `LaifenTc51Driver` | experimental, connects and listens, writes nothing; its charging base (`TC51BB…`) is left alone |

Sources, studied to understand the protocol, no code copied:
[laifen-toolbox](https://github.com/marc-alexis-com/laifen-toolbox) (`docs/PROTOCOL.md`, including
an appendix read from the vendor app), [UrbanTechIO/Laifen](https://github.com/UrbanTechIO/Laifen)
(Home Assistant integration; it saw the Wave Pro's features change on a real brush), and the
official app, for the layouts of the newer brushes. The Wave's packets were captured from a real
brush; they are in the driver's tests (`drivers/laifen/.../Packets.kt`).

## Wave (LFTB01)

GATT service `0xFF01`, one characteristic `0xFF02` (read, write, notify). Every packet starts with
`AA` and ends with the XOR of all bytes before it.

| Packet | Meaning |
|---|---|
| `AA 0A 02 [len] [payload] [xor]` | status. Payload: active mode index, then 4 × (strength, range, speed), flight mode, battery %, one unknown byte, brushing time in seconds (16 bit, big-endian) |
| `AA 04 01 04 [mode] [strength] [range] [speed] [xor]` | set a mode's values |
| `AA 05 01 02 [seconds hi] [seconds lo] [xor]` | set the brushing time |
| `AA 07 01 01 [01/00] [xor]` | flight mode on/off |
| `AA 01 00 00 AB` | ask for the firmware; the brush also sends it on its own right after connecting, e.g. `AA 01 02 09 02 00 08 59 04 04 03 00 03 F3` = `V2.0.8.Y44.303` (LFTB01-P) |
| `AA 09 00 00 A3` | ask for the history (not used, see below) |

Details in
[`LaifenWaveV1Codec.kt`](../drivers/laifen/src/main/kotlin/io/github/damnms/buerstli/drivers/laifen/LaifenWaveV1Codec.kt).

### The hidden fourth mode

The Wave has a fourth mode, **high frequency**, with the strongest vibration (strength 11–20
instead of 1–10). It is not part of the button's mode cycle until high frequency is switched on,
and as far as is known (laifen-toolbox's protocol notes, reviews of the official app) that is only
possible from an app – the button alone never gets you there.

In Bürstli: turn on **High frequency**, then either press the button twice to cycle
Mode 1 → 2 → 3 → 4, or tap **Use this mode** on the mode 4 card. Turning high frequency off while
mode 4 is active switches to mode 3 first, like the official app does.

### Brushing history: not shown

The Wave remembers its brushing sessions – mode, settings, how long you brushed and how long was
set – and hands them to an app that asks. Bürstli could show them, but the brush **deletes every
entry once it has handed it out**, and keeps no date. So Bürstli would have to store the history
on the phone (it stores nothing of it today), and every entry it fetched would be gone for the
official app.

How it works: `AA 09 00 00 A3` asks; the brush sends one `AA 09 02 [len] …` notification per entry
and deletes it. With nothing stored it answers `AA 09 02 01 00 A0` (seen on a real LFTB01-P).
Short sessions aren't stored at all. Entry layout as the official app reads it: ?, number, mode
(`FF` = empty), strength, range, speed, then the brushed seconds (length 8) or set seconds,
brushed seconds and two unknown bytes (length 12+).

Worth it for you? Give the [brushing history issue](https://github.com/damnms/buerstli/issues?q=is%3Aissue+%22brushing+history%22)
a 👍. Enough thumbs, and it gets built.

## Newer brushes: the long frame (Wave Pro, SE, TC51)

They wrap everything in a longer frame (appendix of laifen-toolbox's notes, read from the vendor
app):

    start | function << 14 | command (16 bit BE) | counter | length (16 bit BE) | payload | xor

Function 0 is write, 1 read, 2 report, 3 answer. Commands from the app start with `AA`, frames from
the brush with `5A`. The status is command `0x0103`; the Home Assistant integration's Wave Pro
packets (`5A C1 03 …`, `5A 81 03 …`) are its answer and report. Bürstli only listens to these
reports. A boolean is on only for exactly `01`.

Status payloads, as the official app reads them:

| Byte | Wave Pro (LFTB02-S) | Wave SE (LFTB01-SE) | Kids TC51 (LFTB01-C) |
|---|---|---|---|
| 0 | active mode | active mode | active mode |
| 1 | flight mode | 1–12: 4 × (strength, range, speed) | flight mode |
| 2 | battery % | | battery % |
| 3 | 30-second reminder | | (low-frequency value, not read) |
| 4–11 | 4 × brushing time, s (16 bit BE) | | 4 × brushing time, s (16 bit BE) |
| 12 | high frequency | | (low frequency, not read) |
| 13 | 0 idle, 1 brushing, 2 just stopped | flight mode | 0 idle, 1 brushing, 2 just stopped |
| 14 | | battery % | (charging, not read) |
| 15 | Pro feature | 30-second reminder | child lock |
| 16–23 | 16: Pro feature | 4 × brushing time, s (16 bit BE) | (brush head uses left at 18–19, last times at 20–23, not read) |
| 24 | Pro feature | high frequency | power ramp-up |
| 25 | Pro feature | motor running | spin dry |
| 26 | Pro feature | (emergency power, charging, constant power at 26–28, not read) | pressure warning |
| 30, 32 | Pro features | | |
| 36 | | | 30-second reminder |
| 41 | | | high frequency |

The Wave Pro's features (bytes 15, 16, 24–26, 30, 32) are named as UrbanTechIO/Laifen saw them
change on a real brush; the app calls 16 "pressure sensor on" and 30 "sleep protection". Up to byte
15 the SE's status has the order of the Wave's. The TC51's status is a relative of the Wave Pro's;
the app reads its bytes from 29 on only if they are there. The Pro and the TC51 send only which
mode is active, not the modes' values; the SE sends them too.

## To check on a real brush

1. **Wave Pro:** does the screen fill as soon as it is connected, without Bürstli asking? Other
   brushes named `LFTB02-S` may send other payloads and show nothing.
2. **Wave SE:** does it send its status on its own at all? If not, the screen stays empty – and a
   report shows what it sends instead.
3. **Kids TC51:** do battery, brushing time, child lock and the switches match what the official
   app shows?
4. All three: does the status change when you switch something in the official app?
