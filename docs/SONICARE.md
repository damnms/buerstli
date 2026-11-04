# Philips Sonicare: protocol notes

Status: experimental, read-only (`SonicareClassicDriver`). Battery, status, mode, intensity, brushing
time and routine length were confirmed on a real brush (see "Seen on a real brush"); the model
number, the mode names per model and the brushing history come from the official app and were
**not seen on a real brush yet**.

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
| | `4022` | available routine ids; the app also writes a routine id here to change the mode | bytes | app | – (never written) |
| | `4040` | cumulative motor runtime | ? | app | – |
| | `4050` | handle clock, seconds since 1970 (the app writes the phone's time) | 32 bit | app | – (never written) |
| `0002` Routine | `4070` | current session id | u16, notify | app | – |
| | `4080` | mode (routine number), meaning per model, see "Modes" | u8, notify | psb, app, **seen** | reads, subscribes |
| | `4082` | routine state: 0 inactive, 1 running, 2 paused, 3 completed, 4 terminated, 5 failed | u8 | psb, app, **seen** | reads, subscribes (2 = paused) |
| | `4090` | brushing time, seconds | u16, notify | psb, app, **seen** | reads, subscribes |
| | `4091` | routine length, seconds | u16, notify | psb, app, **seen** | reads, subscribes |
| | `40B0` | intensity: 0 low, 1 medium, 2 high | u8, notify | psb, app, **seen** | reads, subscribes |
| `0004` Storage | `40D0` | latest session id | u16 | app | reads (history) |
| | `40D2` | number of stored sessions | u16 | app | reads (history) |
| | `40D5` | data type to read, see "Brushing history" | u8, write | app | writes `00` |
| | `40E0` | session id to start at / the one being sent | u16, write, notify | app | writes the first id |
| | `4100` | session data | notify | app | subscribes (history) |
| | `4110` | control (write) and status (notify) | u8 | app | writes `01`, `02` |
| `0005` Sensor | `4120`, `4130` | motion and pressure stream, switched on by writing the sensor types | | app | – |
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

## Writing

**Bürstli only writes to `40D5` (`00`), `40E0` (a session id) and `4110` (`01`, `02`)**, which is
what the app sends to read the history, and the codec builds nothing else; a test checks every
write. It never writes the mode (`4022`), intensity (`40B0`), routine state (`4082`), clock
(`4050`), settings (`4420`) or anything in the sensor, brush head or diagnostics services.

## Condor

The 7100 series (HX741X, HX742X) uses the Condor protocol (psb; in the app
`com.philips.connectivity.condor`, service `e50ba3c0-af04-4564-92ad-fef019489de6`). Which other
models use it wasn't checked; the app treats xian together with the 7100 series in places. Not
studied yet; Bürstli recognizes Condor-only brushes and offers a report.

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
- `com/philips/cdp/ohc/tuscany/data_extraction/routineSession/b.java` – layout of a stored
  session; `RoutineIdToBrushMode.java` – the xian modes; `views/mouthmap/BrushMode$Mode.java` – the
  default mode list.
- Obfuscated, outside those packages: `dp/d1.java` (`h`: routine number → mode, `g`: xian) and
  `dp/e0.java` (`e`: mode list per model number).
- Condor: `com/philips/connectivity/condor/` and `com/philips/cdp/ohc/condor/`.

## Legal

As far as we know, studying a program to make another program work with the same device is allowed for
interoperability (EU Software Directive, Art. 6; Swiss Copyright Act, Art. 21). That's what
happened here, and nothing else: no code, images or texts from the app are in Bürstli.
