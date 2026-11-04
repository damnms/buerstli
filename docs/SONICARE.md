# Philips Sonicare: protocol notes

Status: experimental (`SonicareClassicDriver`). Battery, status, mode, intensity, brushing time and
routine length were confirmed on a real brush (see "Seen on a real brush"); the model number, the
mode names per model, the brushing history and switching mode and intensity come from the official
app and were **not seen on a real brush yet**.

Two sources, and every value below says which one it comes from:

- **psb** – [mtheli/philips_sonicare_ble](https://github.com/mtheli/philips_sonicare_ble) (MIT), a
  Home Assistant integration, verified by its authors on several models. Bürstli's first driver
  was built on its [BLE_PROTOCOL.md](https://github.com/mtheli/philips_sonicare_ble/blob/main/docs/BLE_PROTOCOL.md).
- **app** – the official Philips Sonicare app (Android, version **10.21.1**), studied to understand
  the protocol. Sound because it is what the vendor's own app does, but not seen on a real brush
  unless the table says so. Where it disagrees with psb, the app wins (ADR-6).

No code was copied from either; this file describes the protocol in our own words.

## Seen on a real brush

A brush reported as "DiamondClean 9000", model number **HX960X**, firmware 1.5.3, with Bürstli
0.9.0 on 2026-10-31 (the values are in `SonicareTest.diamondClean9000`): handle state, battery,
brushing state, mode, intensity, brushing time and routine length as described below. Its head
has no chip, so brush head limit and usage were 0/0.

Open: the app files HX960X under the ExpertClean family, and its mode list for HX960X has no
White mode (see "Modes"). The mode value the brush sent was `1`; Bürstli 0.9.0 showed "White+",
the app's table says "Gum Care". Which mode was really set wasn't noted.

## Recognizing a Sonicare

- Advertised name `Philips Sonicare` (the app uses exactly this as the device name; psb also
  knows `Philips OHC`).
- Classic service `477ea600-a260-11e4-ae37-0002a5d50001` (psb, app).
- The 7100 series (HX742X) only offers the Condor service `e50ba3c0-af04-4564-92ad-fef019489de6`
  (psb); the app has a separate Condor stack for it (see "Condor"). Bürstli leaves those to the
  report flow.

### Model number – app

The app tells models apart by their model number, not by the name. Bürstli reads it from the
standard Device Information characteristic `2A24` (Model Number String). That this is where the
app gets it is our assumption: it reads Device Information through a generic handler.

| Model number | App's internal name | Notes |
|---|---|---|
| HX741X | cairo1 | 7100 series |
| HX742X, HX74XX | cairo2 | 7100 series |
| HX84XX / HX85XX / HX92XX | mykonos5m / mykonos6m / mykonos7m | |
| HX98XX, HX9XXX | mykonos8m | |
| HX9120 | tuscany | |
| HX960X, HX961X, HX97XX | viking | app: "ExpertClean" |
| HX962X | medium | app: "ExpertClean" |
| HX991X | osaka | |
| HX992X, HX993X, HX99XX | shanghai | |
| HX9996, HX999X | xian | |

Bürstli shows the model number as the brush sends it; the product names are in the app's
resources, which weren't studied. The app compares the number with its short forms above as they
are. In case a brush sends the full product number instead (e.g. `HX9611/62`), Bürstli takes the
most specific short form that fits (`HX961X`, not `HX9XXX`); that a brush does this is our guess.

## Characteristics (classic GATT)

Base `477ea600-a260-11e4-ae37-0002a5d5xxxx`; integers little-endian. "u8/u16/u32" are the formats
the app reads them with. Bürstli column: what it does with it.

| Service | Char. | Meaning | Format | Source | Bürstli |
|---|---|---|---|---|---|
| `0001` Sonicare | `4010` | handle state: 0 off, 1 standby, 2 run, 3 charge, 4 shutdown, 6 validate, 7 background | u8, notify | psb, app, **seen** | reads, subscribes |
| | `4020` | available routines | bytes | app | – |
| | `4022` | available routine ids (read); the app writes a routine id here to change the mode | bytes | app | reads; writes a listed id |
| | `4040` | cumulative motor runtime | ? | app | – |
| | `4050` | handle clock, seconds since 1970 (the app writes the phone's time) | 32 bit | app | – (never written) |
| `0002` Routine | `4070` | current session id | u16, notify | app | – |
| | `4080` | mode (routine number), meaning per model, see "Modes" | u8, notify | psb, app, **seen** | reads, subscribes |
| | `4082` | routine state: 0 inactive, 1 running, 2 paused, 3 completed, 4 terminated, 5 failed | u8 | psb, app, **seen** | reads, subscribes (2 = paused) |
| | `4090` | brushing time, seconds | u16, notify | psb, app, **seen** | reads, subscribes |
| | `4091` | routine length, seconds | u16, notify | psb, app, **seen** | reads, subscribes |
| | `40B0` | intensity: 0 low, 1 medium, 2 high; the app writes it to change it | u8, notify | psb, app, **seen** | reads, subscribes, writes |
| `0004` Storage | `40D0` | latest session id | u16 | app | reads (history) |
| | `40D2` | number of stored sessions | u16 | app | reads (history) |
| | `40D5` | data type to read, see "Brushing history" | u8, write | app | writes `00` |
| | `40E0` | session id to start at / the one being sent | u16, write, notify | app | writes the first id |
| | `4100` | session data | notify | app | subscribes (history) |
| | `4110` | control (write) and status (notify) | u8 | app | writes `01`, `02` |
| `0005` Sensor | `4120` | which sensors to stream, bit mask (see "Sensor stream") | u16, write | app | – |
| | `4130` | sensor stream | notify | app | – |
| `0006` Brush head | `4210` | NFC tag version | | app | – |
| | `4220` | factory mode | u8 | app | – |
| | `4230` | serial number of the head | text | app | – |
| | `4280` / `4290` | lifetime limit / usage | u16 | psb, app, **seen** (0/0, head without chip) | reads |
| | `42C0` | ring id | u16 | app | – |
| `0007` Diagnostics | `4310`–`4370` | error values, pressure sensor, proximity, invalid-charger count, debug info, external flash | | app | – |
| `0008` Extended | `4420` | handle settings | u32, write | app | – (never written) |
| `180F` Battery | `2A19` | level, percent | u8, notify | psb, **seen** | reads, subscribes |
| `180A` Device Information | `2A24` / `2A26` | model number / firmware | text | app (model number) | reads |

psb notes that the brush drops the connection after about 6 seconds unless something is
subscribed, so Bürstli subscribes first.

## Modes – app

The value in `4080` (live) and in byte 11 of a stored session is a **routine number**, and its
meaning depends on the model. For every model except xian, routine number *n* is entry *n + 1* of
the model's list below; entry 0, Touch-up, can't be reached that way. Numbers 0–7 only; anything
else the app shows as Clean.

| Model number | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| HX741X | Clean | Sensitive | White | | | | | |
| HX742X, HX74XX | Clean | Sensitive | White | Gum Care | | | | |
| HX84XX | Clean | Deep Clean | White | Gum Care | Sensitive | | | |
| HX85XX | Clean | Deep Clean | White | Gum Care | Sensitive | Max Care | | |
| HX92XX | Clean | Deep Clean | White | Gum Care | Sensitive | Max Care | Comfort | |
| HX98XX, HX9XXX | Clean | Deep Clean | White | Gum Care | Sensitive | Max Care | Comfort | Polish |
| HX9120, HX961X | Clean | White | Deep Clean | | | | | |
| HX960X | Clean | Gum Care | Deep Clean | | | | | |
| HX991X | Clean | White | Gum Care | Deep Clean | | | | |
| any other (HX962X, HX992X, HX993X, unknown) | Clean | White | Gum Care | Deep Clean | Tongue Care | Sensitive | Max Care | Comfort |

**xian** (HX9996, HX999X) uses a routine *id* instead, without the +1: 0 Clean, 1 White, 2 Gum
Care, 3 Tongue Care, 4 Deep Clean, 5 Sensitive, 6 Max Care, 7 Comfort, 8 Polish. In stored sessions
the app then takes byte 13 instead of byte 11.

psb's table (0 Clean, 1 White+, 2 Gum Health, 3 Deep Clean+, 4 Sensitive, 5 Tongue Care), which
Bürstli used up to 0.13.0 for every model, matches the "any other" list except that 4 and 5 are
swapped.

The names are the app's internal ones. What each brush prints on its handle (e.g. "White+",
"Gum Health", "Deep Clean+") is in the app's resources, which weren't studied; Bürstli keeps
showing White+, Gum Health and Deep Clean+ for White, Gum Care and Deep Clean.

## Brushing history (storage service) – app

The brush keeps its sessions. How the app fetches them, and what Bürstli does the same way right
after connecting:

1. Write the data type `00` (brushing sessions) to `40D5`.
2. Read the latest session id (`40D0`) and the number of stored sessions (`40D2`). The first
   session is latest − number + 1; Bürstli fetches at most the newest 30.
3. Write the first session id to `40E0` (u16, little-endian) and `01` ("all from here") to `4110`.
4. The brush sends the data as notifications on `4100`, the session id it is sending now on
   `40E0`, and status `00` on `4110` when a session is complete. Done when the latest session is
   complete.

Other values for `4110`: `00` a single session, `02` cancel (the app sends it when the brush stops
answering; so does Bürstli, after 8 seconds in total). The app has **no delete command** for
stored sessions; it remembers itself which ones it already has. Other data types for `40D5`:
1 pressure, 2 temperature, 3 motion, 4 brush id, 5 brush head replacement, 6 gyro compensation,
7 diagnostics, 8 session summary, 9 ML estimates, 10 mouth map summary.

A session is the first notification of that session; its length gives the version:

| Bytes | Meaning | Version |
|---|---|---|
| 0 | not read by the app (probably the data type) | all |
| 1–4 | start, seconds since 1970 by the brush's clock (u32) | all |
| 5–6 | session id | all |
| 7–8 | brushed seconds | all |
| 9–10 | set routine length, seconds | all |
| 11 | routine number (mode, see "Modes") | all |
| 12 | intensity: 0 low, 1 medium, 2 high | all |
| 13 | routine id (xian: the mode) | 2 (16 bytes), 3 (18 bytes) |
| 14 / 15 | how often the intensity adapted / was changed | 2, 3 |
| 16 | mouth map starting position | 3 |
| 17 | 1 regular session, 2 touch-up | 3 |

Version 1 is 13 bytes. The app sets the brush's clock from the phone (`4050`), so Bürstli reads
the start as UTC and shows no date before 2015, assuming the clock was never set.

## Sensor stream (live pressure) – app

Not used by Bürstli yet. The app switches the stream on by writing a bit mask (u16, little-endian)
to `4120` and gets the data as notifications on `4130` (`CBTSensorServiceHandler`,
`tuscany/ble/SensorDataParser.java`). Bits, as far as the packet types show: 1 pressure, 4 motion,
64 ML estimates. The app writes 64 if the model supports ML estimates, otherwise 5 (pressure and
motion); without a choice the handler writes 7.

A packet starts with its type in bytes 0–1 (u16); bytes 2–3 aren't read by the app, the data
starts at byte 4:

| Type | Content |
|---|---|
| 1 pressure | 7 bytes: bytes 4–5 pressure value (u16), byte 6 state. Shorter: byte 4 state. State 2 = too much pressure, anything else normal. |
| 4 motion | bytes 4–15: gyro X, Y, Z, then acceleration X, Y, Z, each a signed 16-bit value; at least 16 bytes |
| 64 ML estimate | bytes 4–7 a u32, then the estimate; at least 12 bytes |

What the pressure value means (unit, scale) wasn't traced. Bürstli could show "too much pressure"
live from type 1 alone; that would need writing `01 00` to `4120`, which Bürstli doesn't do yet.

## Changing mode and intensity – app

The app's settings for a classic Sonicare (`handle_controls/settings`): the mode is switched by
writing its **routine id** (not the routine number) to `4022`, one byte; the intensity by writing
0, 1 or 2 to `40B0`. The routine ids are the xian list above: 0 Clean, 1 White, 2 Gum Care,
3 Tongue Care, 4 Deep Clean, 5 Sensitive, 6 Max Care, 7 Comfort, 8 Polish. The app's mode screen
knows Clean 0, White 1, Gum Health 2, Deep Clean 4 and Sensitive 5. Reading `4022` gives the ids the
brush offers; the app checks a mode against that list.

Which models the app offers these settings for wasn't traced. What Bürstli does:

- It shows only the modes listed in `4022`; if `4022` can't be read, no mode can be switched.
- Switching: write the id to `4022`, then read `4080` back (up to 6 times, 0.3 s apart) until it
  shows that mode, translated with the model's table above. Only then is it confirmed (ADR-2);
  otherwise "not applied".
- Intensity belongs to the active mode. Saving it for another mode switches to that mode first.
  Confirmed the same way, by reading `40B0` back.

For an HX960X, `4022` should then list 0, 2 and 4 (Clean, Gum Care, Deep Clean), if the app's table
for it is right; the report shows it.

## Writing

**What Bürstli writes to a Sonicare, and nothing else:** `4022` (a routine id the brush listed),
`40B0` (0–2), and for the history `40D5` (`00`), `40E0` (a session id) and `4110` (`01`, `02`). The
codec builds nothing else, it rejects ids and levels out of range, and a test checks every write. It
never writes the routine state (`4082`, which starts and stops), the clock (`4050`), the settings
(`4420`) or anything in the sensor, brush head or diagnostics services.

Mode and intensity are an exception to ADR-6: the driver is experimental, but it changes them. The
maintainer decided that, so that one test by an owner covers everything; the guards are the
brush's own list and reading back.

## Condor – app

The 7100 series (HX741X, HX742X) only offers the Condor service
`e50ba3c0-af04-4564-92ad-fef019489de6` (psb); which other models use it wasn't checked, the app
treats xian together with the 7100 series in places. Bürstli has no Condor driver yet; it
recognizes those brushes and offers a report. Everything below is read from the app 10.21.1,
nothing was seen on a real brush.

Condor has three layers: a byte stream over seven characteristics (reliable, with sequence numbers
and acks), Condor messages in that stream, and ports with JSON properties in the messages. **No
encryption on any of them**: the code has no cipher, AES, HMAC or signature (the one hit,
`core/util/c.java`, turns bytes into Base64 for JSON). There is a `SecurityPort` with `key` and
`nextKey`, but nothing in the toothbrush code uses it; probably it belongs to Philips' Wi-Fi
devices on the same stack (Wi-Fi, cloud pairing and backend ports are there too). Whether a 7100
asks for a key anyway only the brush can tell.

### Byte stream

Philips' generic "byte streaming" service (`com/philips/connectivity/bluetooth/services/handlers/bytestreaming/`),
here on the Condor service; the same code also runs on `a651fff1-4074-4131-bce9-56d4261bc7b1`
with `a6510001`–`a6510005` for other Philips devices. The names are the app's; "Rx" and "Tx" are
from the brush's point of view.

| Char. | Name | App does |
|---|---|---|
| `e50b0001-af04-4564-92ad-fef019489de6` | Rx | writes its packets (without response) |
| `e50b0002-…` | Rx ack | subscribes: the brush acknowledges the app's packets |
| `e50b0003-…` | Tx | subscribes: the brush's packets |
| `e50b0004-…` | Tx ack | writes its acks for the brush's packets (without response) |
| `e50b0005-…` | protocol configuration | reads (v3 only) |
| `e50b0006-…` | server configuration | subscribes: the brush's answers while setting up v4 |
| `e50b0007-…` | client configuration | writes while setting up v4 (without response) |

**Packet:** one header byte, then the payload. Bit 7 the channel (0 or 1), bit 6 "start", bits
0–5 a sequence number (0–63, then 0 again). Channel 0 carries the Condor messages, channel 1 binary
data (`protocol/v4/ByteStreamingProtocolV4.java`).

**Opening a channel:** the app sends a start packet (start bit set, sequence 0, no payload) and
waits for the brush to acknowledge it with the start bit set on Rx ack. After that the start bit
is only ever 0 (`internal/ByteStreamingChannel.java`).

**Sending:** the app's data packets count on from 1 (the start packet was 0). It cuts the data
into packets of the agreed size (header byte included) and only sends as long as the brush's
receive buffer (from the configuration) has room; every ack frees the bytes of the packets up to
it. An ack can cover several packets. While waiting for an ack the app runs a 10-second timer;
what happens when it runs out wasn't read.

**Receiving:** a packet with the expected sequence number is accepted (the counter starts at 0,
so the app expects 0 first) and acknowledged 10 ms later; one ack covers everything received by then. A packet with the
wrong number is dropped and the last good one acknowledged again. **An ack** is a packet without
payload: the channel bit and the sequence number of the last packet received, start bit 0 – one
byte, on Tx ack.

**Setting up** (`internal/protocol/ByteStreamingProtocolFactory.java`): which of the two versions
the app uses on a Condor brush is decided in a method jadx couldn't decompile
(`ByteStreamingServiceHandler.x1`). Since the Condor service has both configuration
characteristics, probably v4 after negotiating:

1. Negotiate: write `03 04` (the versions the app supports) to the client configuration; the brush
   answers with one byte on the server configuration, the version it picked (the app waits 5 s).
2. v4: write `FF FF FF FF` to the client configuration; the brush answers on the server
   configuration with up to 6 bytes, 3 × u16: maximum packet size, receive buffer of channel 0, of
   channel 1 (`protocol/v4/b.java`). The packet size used is the smaller of that and a value from
   the Bluetooth connection (probably what the MTU allows).
3. v3 instead: read the protocol configuration, 3 bytes: version (must be 3), incoming and
   outgoing buffer size in packets, u8 each (`gr/c.java`).

Then channel 0 (and with v4 channel 1) is opened as above.

### Condor messages

A message may span several packets; the app collects the bytes of channel 0 until a message is
complete (`condor/bluetooth/communication/f.java`). Framing (`e.java`): `FE FF`, one byte message
type, two bytes payload length (big-endian, Java's default), then the payload.

| Type | Message | | Type | Message |
|---|---|---|---|---|
| 1 | InitializeRequest | | 2 | InitializeResponse |
| 3 | PutPropsRequest | | 4 | GetPropsRequest |
| 5 | SubscribeRequest | | 6 | UnsubscribeRequest |
| 7 | GenericResponse | | 8 / 9 | ChangeIndication request / response |
| 10 | GetProdsRequest | | 11 | GetPortsRequest |
| 12 / 13 | AddProps / DelProps | | 15 | RawRequest |

**Requests** (`kr/a.java`), UTF-8 strings each ending in a zero byte. The product id is `1` for
every toothbrush port (`getProductId()` in each port).

- GetProps: `1` `00` port `00`, e.g. `31 00 53 6F 6E 69 63 61 72 65 00` for `Sonicare`.
- PutProps, Subscribe, Unsubscribe: `1` `00` port `00` JSON `00`; byte values inside the JSON as
  Base64. A PutProps on a binary port sends the raw bytes instead of the JSON.
- Subscribe carries `{"ttl": …, "subscriber": …}`, Unsubscribe `{"subscriber": …}`
  (`BluetoothCommunicationStrategy`).

**Responses** (GenericResponse, InitializeResponse; `g.java`): one byte status, the rest are the
values (for GetProps the properties as JSON). Status codes: 0 no error, 1 not understood, 2 out of
memory, 3 no such port, 4 not implemented, 5 version not supported, 6 no such property, 7 no such
operation, 8 no such product, 9 property already exists, 10 no such method, 11 wrong parameters,
12 invalid parameter, 13 not subscribed, 14 protocol violation.

**Change indications** (type 8, the brush reports a change on a subscribed port; `d.java`): a port
path ending in a zero byte, then the values. The app answers with type 9 and payload `00 00`. The
exact form of the port path wasn't seen.

**Initialize:** nothing in the Condor packages sends an InitializeRequest; the app only accepts an
InitializeResponse like a generic response. Whether a brush expects one first is open.

### Ports

The toothbrush ports (`com/philips/cdp/ohc/condor/services/`, product id 1) are the classic
services again, as JSON properties (all integers unless noted):

| Port | Properties |
|---|---|
| `Sonicare` | `HandleState`, `HandleTime`, `RoutineIDs` (list), `UserMode` (list), `UserModePos`, `PhysModes` |
| `RoutineStatus` | `RoutineState`, `Mode` (the routine number), `Intensity`, `Length`, `Duration`, `SessionID`, `BrushingType`, `CoverageThreshold`, `OverpressureThreshold`, `UnderpressureThreshold`, `ScrubbingThreshold` |
| `Battery` | `BatteryPercent`, `BatteryMV` |
| `BrushHead` | `LifetimeLimit`, `LifetimeUsage`, `RemainingBrushings`, `SerialNumber`, `NfcTagVersion`, `RingId`, `FactoryMode`, `BHRR` |
| `SessionStorage` | `ID`, `LatestID`, `Count`, `Type`, `Control`; the data on the binary port `SessionStorage.b` |
| `SensorData` | `Control`, `Sensors`, `Types`; the data on the binary port `SensorData.b` |
| `Diagnostics` | `PErrors`, `VErrors`, `ExternalFlash`, `InvalidCharger`, `PressureSensor` |
| `Extended` | `FeatureCtrl`, `FeedbackMode` |
| `Languages` | `ID`, `Supported` |

The names match the classic characteristics, so the values presumably mean the same: `HandleState`
like `4010`, `RoutineState` like `4082`, `Mode` like `4080` and so on; not checked value by value.
`UserMode`/`UserModePos` are called "SenseIQ mode" in the app.

### A read-only driver would

Set up the byte stream (v4 as above), open channel 0, subscribe to `Sonicare`, `RoutineStatus`,
`Battery` and `BrushHead`, then GetProps each once and follow the change indications. Open before
that: whether the brush wants an Initialize first, the form of the port path in a change
indication, and how a real 7100 answers the setup. All of it is best seen in a log from a real
brush; no further files from the app are needed. Probably doable without an account, but larger
than Oral-B's driver, and it needs someone with a 7100 to test.

## To check on a real brush

- Model number: does `2A24` give the short forms above (HX960X, HX991X, …) or the full product
  number? Is the DiamondClean 9000
  really an HX960X, or an HX991X (whose list matches its four modes)?
- Modes: set every mode on the brush once and note what Bürstli shows next to the value in the
  log. Does the app's table for the model fit?
- History: does the brush have the storage service? Do `40D0`/`40D2` and the sessions arrive as
  described, are times and durations right, and are the sessions still there afterwards in the
  official app?
- Does `2A26` hold the firmware version as the official app shows it?
- Mode and intensity: which ids does `4022` list? Does switching a mode and changing the
  intensity work, and does the handle show the same afterwards? Does it work while brushing?

## Looking things up again

So nobody has to start from scratch: the source was the official app **10.21.1** (XAPK from
APKPure, package `com.philips.cdp.ohc.tuscany`, base APK `com.philips.cdp.ohc.tuscany.apk`).
Plain Java/Kotlin, no Flutter. `jadx --no-res` decompiles it; Bluetooth is in `classes4.dex` and
`classes5.dex`, and the useful parts have readable names:

- `com/philips/cdp/ohc/cbt/services/` – one handler per classic service with its UUIDs
  (`CBTSonicareServiceHandler`, `CBTRoutineServiceHandler`, `CBTBrushHeadServiceHandler`,
  `CBTSensorServiceHandler`, `CBTDeviceDiagnosticServicesHandler`, `CBTExtendedServiceHandler`,
  `storage/CBTStorageService`). The number in `readIntCharacteristicIO(…, 17|18|20|36, …)` is the
  Android format: 17 u8, 18 u16, 20 u32, 36 s32.
- `com/philips/cdp/ohc/connectivity/services/` – `HandleState`, `RoutineState`,
  `storage/SessionDataType`, `storage/data_extraction/SessionDataExtractor` (how the history is
  fetched).
- `com/philips/cdp/ohc/utility/datamodel/model/device/Device.java` – model numbers and families.
- `com/philips/cdp/ohc/tuscany/handle_controls/settings/` – changing mode (`ModeChange`, the ids in
  `d.java`) and intensity (`Intensity`, levels in `c.java`).
- `com/philips/cdp/ohc/tuscany/data_extraction/routineSession/b.java` – layout of a stored
  session; `RoutineIdToBrushMode.java` – the xian modes; `views/mouthmap/BrushMode$Mode.java` – the
  default mode list.
- Obfuscated, outside those packages: `dp/d1.java` (`h`: routine number → mode, `g`: xian) and
  `dp/e0.java` (`b(device)`: the default list without a device, otherwise the private `e(device)` with the list per model number).
- Condor: `com/philips/connectivity/condor/` (framing in `bluetooth/communication/e.java`, message
  stream in `f.java`, responses in `g.java`, change indications in `d.java`, message types and
  status codes next to them, requests in `requests/`), the byte stream in
  `com/philips/connectivity/bluetooth/services/handlers/bytestreaming/` (characteristics in
  `ByteStreamingServiceHandler`, packets and acks in `internal/ByteStreamingChannel`, setup in
  `internal/protocol/`), and
  `com/philips/cdp/ohc/condor/services/` (one port per service, properties in
  `*PortProperties.java`). Obfuscated: `kr/a.java` (request payloads), `gr/` (v3 configuration),
  `ar/` (only a UUID type and interfaces).
- Byte order: the app reads integers with `qp/a.java`, little-endian (`a` as an int, `b`/`c` as a
  signed 16-bit value). Stored sessions and sensor packets use it.

## Legal

As far as we know, studying a program to make another program work with the same device is allowed for
interoperability (EU Software Directive, Art. 6; Swiss Copyright Act, Art. 21). That's what
happened here, and nothing else: no code, images or texts from the app are in Bürstli.
