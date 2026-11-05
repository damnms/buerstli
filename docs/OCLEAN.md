# Oclean brushes: research notes

Status: an **experimental, read-only driver** (`OcleanDriver`), not yet tested with Bürstli. It reads
model, firmware and battery of every Oclean and the stored sessions of the X series; see
[What the driver does](#what-the-driver-does).

Sources, studied to understand the protocol (interoperability, ADR-6), not copied:

- the official app Oclean Care+ 4.0.8 (`com.yunding.noopsychebrushforeign`). Its BLE layer is a
  library `com.ocleanble.lib` whose public API kept its names; the protocol classes behind it are
  obfuscated, so class names below (`g.i`, `w.b`, …) refer to that app version only.
- [ha-oclean-integration](https://github.com/deniskie/ha-oclean-integration) (MIT), a Home Assistant
  integration built from older app versions and checked on real brushes by its users. Where it
  shows what real brushes send, that wins over the app's code; see
  [Real brushes](#real-brushes-ha-oclean-integration).

This file keeps what is known, what is only assumed, and what the first report should answer.

## Finding the brush

The app scans without a filter and keeps a device if its name (AD type `0x09`, lower-cased) matches
`oclean`, `crest`, `baiyao`, `w-yd`, `xtc`, `dialog` or `oral-b`. Which string function is used is
obfuscated; it is most likely *starts with*. `dialog` is a brush in its bootloader (see `SAFE_MODE`).
`crest` and `oral-b` suggest Oclean builds brushes for P&G brands; Bürstli's Oral-B driver must not
claim those, and vice versa. Open: what a real brush advertises as its name.

The manufacturer-specific AD (`0xFF`) holds the brush's MAC, byte-reversed, **without a company id**.
Android's `ScanRecord` still splits off the first two bytes as a "company id", so a driver must not
match on it.

The advertisement does **not** tell the model. The app connects first and reads Device Information:

| Characteristic | Use |
|---|---|
| `2A24` Model Number | ASCII, e.g. `OCLEANY3`, `OCLEANA1a`, `W1` |
| `2A27` Hardware Revision | if it starts with ASCII `HH` and has ≥ 6 bytes: bytes 2–3 are the model code (`0001` … `0014`), bytes 4–5 the OTA type |
| `2A26` / `2A28` | firmware / software revision, informational |
| `2A19` (`180F`) | battery, percent |

The model is the code from `2A27` if present and not `0000`, else the name from `2A24`
(`OCLEANY3D2` always by name). Both empty and the BLE name contains `dialog`: bootloader.

## Models and protocol handlers

Each model gets one handler class; some handlers have variants. `type` is the app's feature family.

| Handler | Variant | Models (`2A24` name or `2A27` code) |
|---|---|---|
| `g.i` | 1 | Y3, Y3S, Y3T, Y3M, Y3MT, Y3N, Y3MN, Y3MTN, Y3MD, Y3D, Y3D1, Y3D2, R3L |
| `g.i` | 0 | `0004`, `000B` |
| `g.i` | 2 | BAIYAO_W1 (`W1`): a brush, despite the name |
| `g.f` | 1 / 2 / 0 | A1, A1a–A1d / Y5 / `0002` |
| `g.e` | 1 / 0 | A1e, A1f / Y3P, Y3PD, `0001` |
| `g.a1`, `g.e1`, `g.z0`, `g.x0` | | X1 (One), Y2 (SE), X1+ and Y2+, K1 |
| `g.y0` | | C1: a different GATT service, see below |
| `g.g` | | `0003`, `000A` |
| `g.r` | | `0005`, `0006`, `000D`, `0011` (Wi-Fi) |
| `g.b0` / `g.k0` / `g.v0` / `g.w0` | | `0007` / `0008`, `0009`, `000E` / `000C`, `0010`, `0012`–`0014` / `000F` (Wi-Fi) |
| `g.b1` | | W1, W1a, W1b, W1d, BAIYAO_YD_01: water flossers (`isWashDevice`) |
| `g.d1` | | bootloader, battery only |

Retail names, from ha-oclean-integration's users: OCLEANY3M and OCLEANY3MH are the Oclean X,
OCLEANY3 the X Pro, OCLEANY3P the X Pro Elite, OCLEANY3PB the X Pro Digital, OCLEANX20 the X Pro 20,
OCLEANV1a the X Ultra, OCLEANV20 the X Ultra 20, OCLEANY5 the Z1. OCLEANY3MH, OCLEANY3PB, OCLEANX20,
OCLEANV1a and OCLEANV20 aren't in app 4.0.8 at all. Unknown: the names behind the codes `0001` … `0014`.

## GATT (handler `g.i`, variant 1)

No pairing, handshake or encryption on connect: the app reads Device Information, then subscribes.
The app's `AuthenticationCallback` belongs to its SDK key, the key-exchange errors to BluFi (Wi-Fi).

| UUID | Name in the app | Use |
|---|---|---|
| `8082caa8-41a6-4021-91c6-56f9b954cc18` | `OCLEAN_SERVICE` | service |
| `9d84b9a3-000c-49d8-9183-855b673fbb85` | `OCLEAN_WRITE_INFO` | commands |
| `5f78df94-798c-46f5-990a-855b673fbb86` | `OCLEAN_READ_INFO` | replies, notify |
| `5f78df94-798c-46f5-990a-855b673fbb89` | `OCLEAN_SEND_BRUSH_CMD` | request brushing records |
| `5f78df94-798c-46f5-990a-855b673fbb90` | `OCLEAN_RECEIVE_BRUSH` | brushing records, notify |
| `6c290d2e-1c03-aca1-ab48-a9b908bae79e` | `OCLEAN_CHANGE_INFO` | replaces `…fbb90` in variant 2 |

Other services in the app: `0000aaaa` with `bbb0`–`bbb3` (C1), `fef5` (Dialog SUOTA, firmware),
`7000/7001`, `ffff/ff01/ff02` (Espressif BluFi, Wi-Fi setup), `a6ed0401…` (unknown).

## Commands

A command is a 2-byte opcode, optionally followed by data. `03xx` reads, `02xx` changes the brush.
Opcode → public method of the app's library:

| Opcode | Characteristic | Library method | Reads or changes |
|---|---|---|---|
| `030201` | write info | `readDeviceInfo` | reads |
| `0303` | write info | `readBatteryLevel` | reads |
| `0307` | send brush cmd | `readRunningData` | reads |
| `030A` | write info | `readDeviceTime` | reads |
| `030B` | write info | `readHardwareInfo` | reads |
| `0215` | write info | `getTotalPoint` | reads (despite `02`) |
| `0202` | write info | `clearRunningDate` | **deletes** the brushing records |
| `020F` | write info | `clearHeadData` | **resets** brush-head usage |
| `0201` + date and time (GMT, decimal digits as bytes) | write info | `calibrationTime` | sets the clock |
| `0209`, `020D`, `0211`–`0217`, `0221`–`0223`, `0239`, `0240` | write info | settings | changes |

This table is for `g.i` variant 1. Variant 2 sets the clock with `020E` + Unix time (4 bytes BE)
and reads with `0308` / `0309` instead of `0307` / `030201`; other handlers differ more.

## Replies on `…fbb86`

Bytes 0–1 echo the opcode; the rest is the payload. Payloads that end in two ASCII bytes carry a
status there (meaning not yet traced). Long payloads (`0302`) start with `#` (`0x23`) and a length
byte that counts these two header bytes; the rest may continue in following notifications.

**`0303` status:** byte 0 status, byte 3 the battery in percent: ha-oclean-integration found it
matching `2A19` on an Oclean X (real reply `03 03 02 0e 4b 1d 00 00`: 29 %). Bytes 1-2 vary, meaning
unknown.

**`0302` device info** (variant 1, ≥ 32 bytes after the `#` header; integers big-endian):

| Bytes | Field |
|---|---|
| 0 | display theme |
| 4–7 | voice type |
| 8 | volume off if 0 |
| 9 | volume step: 46, 52, 58, 62 → 0–3 |
| 10 | calendar off if 0 |
| 11 | program number (`pNum`) |
| 12 | brush mode, 236 = off |
| 13 | splash prevention |
| 15 | brush head used, time |
| 16–21 | brush clock: year − 2000, month, day, hour, minute, second |
| 22 | over-pressure alert |
| 23 | area reminder |
| 24 | time zone index |
| 25–26 | brush head maximum time |
| 27–28 | brush head used, days |
| 29–30 | brush head used, times |
| 31 | display language |

## Brushing records on `…fbb90`

Request: `0307` to `…fbb89`. Reply header: `03 07`, ASCII `*B#`, record count (2 bytes BE), then
records of **42 bytes**, continuing over further notifications.

| Bytes | Field |
|---|---|
| 0–5 | start: year − 2000, month, day, hour, minute, second |
| 6 | program number |
| 7–8 | set time of the program, seconds |
| 9–10 | time actually brushed, seconds |
| 11–15 | pressure, five shares |
| 17 | time zone index |
| 18–29 | per-zone values (`gestureArray`; 8 or 12 zones depending on the model) |
| 30–32 | per-zone power, 2 bits each |
| 33 | score |
| 34 | points |

Both durations are seconds: ha-oclean-integration saw a whole buffer of 32 sessions on an Oclean X
where bytes 7-8 only take the program's length (120 or 150) and bytes 9-10 are at most that and
follow the score (an aborted run: 120 / 8, score 1). The time zone byte indexes a zone list in the
app; Bürstli reads the brush's clock in the phone's zone instead.

Asking for the records doesn't delete them: the app deletes them with `0202` after it has handled
them, so a read-only driver sees the same records again and tells them apart by their start time.

## Real brushes (ha-oclean-integration)

Its users' logs show:

- `0307` to `…fbb89` is answered on `…fbb90` with the `*B#` header, as in the app. With the default
  MTU a notification carries 20 bytes, so a 42-byte record spans several.
- Often the count is **0** and the newest session follows inline, cut after 13 bytes (start,
  program, both durations, no score). Real: `03 07 2a 42 23 00 00 1a 02 15 10 2c 1c 00 00 78 00 78 02 01`
  = 2026-02-21 16:44:28, 120 s set, 120 s brushed. App 4.0.8 would drop this (count 0 means no data
  to it); the brushes may run firmware the app reads elsewhere.
- Sometimes the count is 1 and still only those 13 bytes follow.
- The X Pro Elite (OCLEANY3P, issue #49) uses the same `*B#` transfer, but its records keep **no
  year**: byte 0 is 0, then month, day, time as usual. Real, polled 2026-02-25:
  `03 07 2a 42 23 00 01 00 08 1b 00 21 28 00 00 78 00 78 00 00` = count 1, 27 August 00:33:40 (its
  clock was off), 120 s. ha-oclean-integration takes the latest year that isn't in the future; a
  month of 0 means the clock was never set.
- The X Pro 20 (OCLEANX20, issue #37) sends its newest session inline four bytes later (`00 00 00 8d`
  first), cut after the set time: `… 1a 03 09 07 39 26 03 00 b4` = 2026-03-09 07:57:38, set 180 s.
  The time brushed isn't in it.
- The Z1 (OCLEANY5, issue #69) takes `0303` on `fbb85` and only `0307` on `fbb89`.
- `fbb89` can't be subscribed to, only written. The X series' firmware wants **all** query commands
  there (`0303` too), not on `fbb85` as app 4.0.8 sends them (its changelog, "TYPE1 command routing").
- An Oclean X (OCLEANY3M, firmware 1.0.0.20) returned all 32 stored sessions on every poll (issue
  #137), as full 42-byte records. One of them, a run of about 8 s:
  `1a 09 11 14 0d 32 00 00 78 00 08 64 … 01 00 ff…` = 2026-09-17 20:13:50, set 120 s, brushed 8 s,
  score 1 (byte 33).

Where it differs from the app:

- It sends `0202` on every poll and calls it a device-info query; in app 4.0.8 `0202` is the library's
  `clearRunningDate`. The app sends it only after `readRunningData` delivered sessions and it has
  stored them, and the brush answers with just "OK". But the 32-session buffer above was read with
  the integration, which sends `0202` (to `fbb89`) before every `0307`: sent that way, it deleted
  nothing on that brush. Whether it deletes when sent to `fbb85`, as the app does, is open; Bürstli
  doesn't send it either way.
- It sets the brush's clock (`0201`, `020E`) on every poll; Bürstli doesn't (ADR-6).
- Its `0302` layout differs from the one above (battery in byte 0, brush-head counters at 25–31),
  units unknown. Bürstli doesn't read `0302` yet.

## What the driver does

`OcleanDriver` takes every device whose name starts with "Oclean" (any case) or that advertises
`OCLEAN_SERVICE`. After connecting, for **every** Oclean, only standard characteristics: Model Number,
Hardware Revision, Firmware Revision, battery (`2A19`, subscribed if possible). The model shown is the
retail name if known, else the Model Number, or "Oclean" + the code from the Hardware Revision.

Only for the models of a known family (`OcleanCodec.family`) it also subscribes to `…fbb86` and
`…fbb90` and sends two read commands:

| Family | Models (2A24) | `0303` to | Records |
|---|---|---|---|
| X | the app's type-1 models (Y3, Y3S, Y3T, Y3M, Y3MT, Y3N, Y3MN, Y3MTN, Y3MD, Y3D, Y3D1, Y3D2, R3L), Y3MH, V1a | `fbb89` | with year |
| X Pro Elite | Y3P, Y3PD, Y3PB | `fbb89` | without year |
| Z1 | Y5 | `fbb85` | with year (assumed: no real record known) |

Left out: the X Pro 20 (its inline record has no time brushed) and the X Ultra 20 (no capture known).

1. `0303`, only if `2A19` gave no battery; battery from byte 3.
2. `0307` to `…fbb89`; the sessions from `…fbb90`. Full records with count × 42 bytes; count 0 with
   the short record inline; if the brush stops sending before the count is reached (1.5 s quiet),
   what came is read, the last record may be short. No answer within 8 s: no history.

The history shows every record newest first, with the time brushed (bytes 9–10, the set time if
empty), the set time and the start. Oclean doesn't number sessions; Bürstli counts them, the oldest
as 1. The score of the newest full record is shown as the last score.

Nothing else is sent: no `0202`, no clock, no setting.

## Open questions for the first report

1. What does the brush advertise as its name, and which `2A24` / `2A27` values does it have?
2. Does the battery come from `2A19`, and does `0303` agree?
3. Does `0307` bring the full records (count > 0) or only the newest inline? With which MTU?
4. Does `0202` to `fbb85` delete the sessions? With nRF Connect: `0307` to `fbb89` and note the
   count in the `*B#` header; `0202` to `fbb85`; `0307` again. (To `fbb89` it deletes nothing on an
   Oclean X, see above.)
5. Is the brush's clock in local time, and does it match what the app shows?
