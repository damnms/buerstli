# Oral-B: protocol notes

Status: experimental (`OralBPassiveDriver`): listens, and connects only briefly to read the battery,
the brushing history and the brush head reminder.
Nobody has tried it with Bürstli yet.

Two sources, and every value below says which one it comes from:

- **oralb-ble** – [Bluetooth-Devices/oralb-ble](https://github.com/Bluetooth-Devices/oralb-ble)
  (MIT), the library behind Home Assistant's Oral-B integration. Verified on many real brushes;
  its test suite provides the real advertisements in our tests.
- **app** – the official Oral-B app (Android, version 10.8.1), studied to understand the protocol.
  Sound because it is what the vendor's own app does, but **not seen on a real brush yet**. Where
  it disagrees with oralb-ble, the app wins (ADR-6), and the table says so.

No code was copied from either; this file describes the protocol in our own words.

## Advertisements

Manufacturer data, company id `0x00DC` (Procter & Gamble). Byte positions after the company id:

| Byte | Meaning | Source |
|---|---|---|
| 0 | protocol version (1–9) | oralb-ble, app |
| 1 | model id, see below | oralb-ble, app |
| 2 | software version | app |
| 3 | state, see below | oralb-ble, app |
| 4 | bit 7 high pressure, bit 3 power button, bit 2 mode button | oralb-ble, app |
| 5–6 | brushing time: minutes, seconds | oralb-ble, app |
| 7 | mode, see below | oralb-ble, app |
| 8 | bits 0–2 sector (0 none, 1–6, 7 = last), bits 3–5 a "smiley" (not used) | oralb-ble, app |
| 9 | up to protocol 7: sector timer; **from protocol 8: iO series** | oralb-ble; series: app |
| 10 | number of sectors (only in the 11-byte form) | oralb-ble, app |

### Model ids (byte 1)

| Id | Model | Source |
|---|---|---|
| 0–2 | Triumph D36 | oralb-ble, app |
| 32–34 | Genius D701 | oralb-ble, app |
| 39–41 | Smart D700 | oralb-ble, app |
| 48–50, 54 | iO | oralb-ble, app |
| 52 / 53 | iO Series 4 / iO Series 5 | oralb-ble, app |
| 64–70 | Smart D21 | oralb-ble, app |
| 80–87 | Pro D601 | oralb-ble, app |
| 112–114 | Genius X D706 | oralb-ble, app |
| 117–119 | Genius X D706 | oralb-ble only |
| 165 / 166 | iO ("HCB Galaxy iO5" / "HCB ePlatform") | app |

The app also knows 63, 127 and 255 as "experimental" prototypes; Bürstli ignores them.

### iO series (byte 9, protocol 8 and later) – app

1 iO 9 · 2 iO 8 · 3 iO 7 · 4 iO 6 · 5 iO 5 · 6 iO 4 · 16 iO 10. Bürstli only uses it for iO
models (or unknown ones). The real oralb-ble advertisement `08-36-…-01-00` thereby reads as iO 9.

### State (byte 3)

| Value | Meaning | Bürstli shows | Source |
|---|---|---|---|
| 1, 2 | initializing, idle | standby | oralb-ble, app |
| 3 | brushing | brushing | oralb-ble, app |
| 4 | charging | charging | oralb-ble, app |
| 5, 6, 8, 10 | setup, menu, about to start, post-brushing statistics | standby | oralb-ble, app |
| 7 | on the charger, charging not allowed | nothing | app |
| 9 | **paused** | paused | app; oralb-ble says "off" |
| 115, 116 | sleeping, transport mode | off | oralb-ble, app |
| 113, 114, 117 | factory tests | nothing | app |

### Mode (byte 7)

The **protocol version** decides the numbering, not the model (app). oralb-ble picks it by model,
so an iO it doesn't know by id got the old names.

- Protocol 1–5: 0 off · 1 Daily Clean · 2 Sensitive · 3 Massage · 4 Whitening · 5 Deep Clean ·
  6 Tongue Clean · 7 **Pro Clean** (app; oralb-ble says "turbo").
- Protocol 6 and later: 0 Daily Clean · 1 Sensitive · 2 Gum Care · 3 Whitening · 4 Intense ·
  5 Super Sensitive · 6 Tongue Clean · 7 off · 8 settings · 11 Smart Adapt · 12 **Gentle White**
  (app). The app calls them internally clean, soft, massage, polish, turbo, soft plus, tongue;
  the names above are the ones on the brush, as oralb-ble uses them.

## Battery level (short connection)

Service and characteristics use the base `A0F0xxxx-5047-4D53-8208-4F72616C2D42`. The battery
is in characteristic `FF05` (read). oralb-ble reads it over a short active connection; a user
reports for iO10/iO Sense that no pairing or bonding is needed.

Layout of `FF05` by protocol version (app):

- 1–3: one byte, the level in percent;
- 4–7: four bytes, byte 0 & `0x7F` = level, bytes 1–2 seconds left (big-endian);
- 8 and later: at least three bytes, byte 0 & `0x7F` = level, bytes 1–2 seconds left
  (little-endian), optional voltage, current and temperature after that.

Reading byte 1 instead of byte 0 gives values like 189 %, as an ESPHome example in the Home
Assistant forum shows.

What Bürstli does: once it heard the brush idle or charging and has no battery level yet, it
stops listening, connects, reads `FF05` and disconnects; then it listens again. Not while
brushing or paused: a user in the Arduino forum reports that the connection drops when brushing starts. At most
two tries per listening, a minute apart. The brush doesn't advertise while connected, so live
values pause for those few seconds.

## Brushing history (same short connection) – app

To read session *n* (0 = newest), the app writes `02 n` ("read data") to `FF21` and then reads
`FF29`. Reading deletes nothing; the app itself remembers which sessions it already has.
Bürstli reads sessions 0, 1, 2, … and stops at session id 0, at the app's "no data" filler (every
byte `0x44`), or after 30 (newer brushes keep 30). If a later read fails, it keeps what it got.

Byte positions in `FF29` (little-endian; time in seconds since 2000-01-01 by the brush's clock):

| | protocol 1–5 (16 bytes) | protocol 6–7 (21) / 8–9 (23) |
|---|---|---|
| start time | 0–3 | 0–3 |
| session id (low 13 bits) | 14–15 | 4–5 |
| set brushing time (low 13 bits; top 3: sectors) | 12–13 | 6–7 |
| brushing time | 4–5 | 8–9 |
| mode (numbering by protocol, as above) | 7 | 19 |
| high pressure, tenths of a second | 8–9 (×10 up to protocol 3) | 10–11 |
| battery at the end | 11 | 20 |

The app also reads low pressure, pressure events, average and maximum pressure and, from
protocol 8, tongue time; Bürstli doesn't show those. The brush's clock is set by the official app;
before 2015 Bürstli assumes it never was and shows no date.

## Brush head reminder (same short connection) – app

The app subscribes to `FF2D` and gets the value as a notification; Bürstli does the same, waits
up to 3 seconds and tries once per listening (not every brush has it).

| Byte | Meaning |
|---|---|
| 0 | state. Up to protocol 6: `00`/`01` on (counted by the brush / by the app), `FF` off. From protocol 7: `FF` off, `FE` interval, `00` on, `01` reset, `02` snoozed, other values a stage |
| 1–2 | days left |
| 3–4 | brushing seconds left |
| 5–8 | from protocol 7 (9 bytes in total; 5 before): days and seconds since the reminder came up |

Bürstli shows days and brushing time left unless the reminder is off; "interval" it treats as off
too, because the app doesn't say what the numbers mean then.

## Writing

**`02 n` to `FF21` is the only thing Bürstli ever writes to an Oral-B.** `FF21` also takes commands
that change or reset the brush (in the app, `34` is a factory reset), so the codec builds only this
one message and refuses any other index; a test checks every write. A user also reports that
writing to `FF06` resets the brush (the app calls it the button state); Bürstli never touches it.

## Reference: what else the app does (not used by Bürstli)

Read from the official app for later; nothing here was seen on a real brush. Under ADR-6 anything
that changes the brush waits for an owner who tries it.

**Characteristics** (`A0F0xxxx-…`): `FF04` state · `FF05` battery · `FF06` buttons (0 none,
1 power, 2 mode) · `FF07` mode · `FF08` brushing time · `FF09` sector · `FF0A` smiley · `FF0B`
pressure · `FF0D` motion sensor · `FF0E` dashboard data stream · `FF10` access control · `FF21`
commands · `FF22` clock · `FF23` time zone · `FF24` brushing timer · `FF25` mode list · `FF26`
sector timers · `FF27` tongue time · `FF28` pressure settings · `FF29` selected data · `FF2B`
ring colour · `FF2C` dashboard settings · `FF2D` brush head reminder · `FF2E` post-brushing
statistics · `FF81`–`FF85` firmware update.

**Commands to `FF21`** (hex):

- Reading (no change): `02 n` session *n*, `02 FA` / `02 FB` service data A / B, `01 n` a
  parameter, `04 n` calibration, `05 n` metadata (`01` device id, `02` business unit, `03` BLE
  profile, `04` device type, `05` PCBA, `06`–`08` software versions, `09` git hash, `0A` GCAS,
  `10` brush settings, `FF` iO type), `12 n` device colour. Sessions, service data and metadata
  are then read from `FF29`; where the others answer, the app code doesn't make clear.
  `31 n` keeps the connection open for *n* more seconds.
- Changing a setting is two writes: first announce it on `FF21` – the code alone from protocol 6,
  `37` + code before – then write the value to its own characteristic. Codes: `26` clock
  (`FF22`, `FF23`), `28` brushing timer (`FF24`), `29` modes (`FF25`), `2A` sector timers
  (`FF26`), `2B` tongue time (`FF27`), `2C` pressure settings (`FF28`), `2F` ring colour
  (`FF2B`), `30` dashboard (`FF2C`), `31` brush head reminder (`FF2D`).
- Direct: `03 n` clear a notification, `33 50`/`33 51` smart guide off/on, `47` flight mode,
  `50 n` first day of the week.
- **Destructive:** `32 52` resets the settings, `34 52` resets the brush to factory settings,
  `F0 n` are debug commands (among them erasing the session data).
- Brush commands are `10` + command (e.g. switch mode, enable or disable motion data or the
  pressure sensor, light effects, motor ramping).

## Legal

As far as we know, studying a program to make another program work with the same device is allowed for
interoperability (EU Software Directive, Art. 6; Swiss Copyright Act, Art. 21). That's what
happened here, and nothing else: no code, images or texts from the app are in Bürstli.
