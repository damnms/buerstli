# Oral-B: protocol notes

Status: experimental, listens only (`OralBPassiveDriver`). Nobody has tried it with Bürstli yet.

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

## Not built yet: battery level (needs a short connection)

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

**Bürstli never writes to an Oral-B characteristic.** A user reports that writing to `FF06`
resets the brush to factory settings.

## Legal

As far as we know, studying a program to make another program work with the same device is allowed for
interoperability (EU Software Directive, Art. 6; Swiss Copyright Act, Art. 21). That's what
happened here, and nothing else: no code, images or texts from the app are in Bürstli.
