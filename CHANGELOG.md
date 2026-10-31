# Changelog

All notable changes to Bürstli are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.11.0] - 2026-10-31

### Added
- Reports for nerds: “record until I stop”, chosen before the start, listens for up to 10 minutes,
  e.g. a whole brushing session. The screen stays on while a report is recorded.

### Fixed
- A report now ends as soon as the brush disconnects, instead of waiting out its time.
- Experimental brushes showed the report button twice.

## [0.10.0] - 2026-10-31

### Added
- Recognizes the Xiaomi T501.

### Changed
- Philips Sonicare: confirmed on a real DiamondClean 9000 – thanks to my awesome brother! Other
  Sonicare models stay experimental.

## [0.9.0] - 2026-10-30

### Added
- Recognizes Oclean and Xiaomi (T500, T700, T700i) brushes.
- "My brush isn't supported": pick any brush from the devices nearby and record a report for it
  (Playbrush, usmile, Ailoria, MOVA, Tesla, …). Reports now include service data, too.

### Fixed
- Privacy: reports could show part of the brush's Bluetooth address in raw bytes.

## [0.8.0] - 2026-10-30

### Added
- Laifen Wave Pro (LFTB02): experimental support that only listens – battery, brushing time,
  brushing or idle, pressure warning, and its Pro features as a display. Built on what the Home
  Assistant integration UrbanTechIO/Laifen confirmed on a real Wave Pro and on the frame format
  described by laifen-toolbox. Other brushes named LFTB02 may show nothing: please send a report.

## [0.7.0] - 2026-10-30

### Added
- Oral-B (iO, Genius, Smart, Pro, Triumph): experimental support that only listens to what the
  brush broadcasts, without connecting – status, mode, brushing time, pressure and mouth area.
  Oral-B brushes don't broadcast their battery level. Built on oralb-ble, the library behind Home
  Assistant's Oral-B integration, and tested against real recordings from its test suite.

## [0.6.3] - 2026-10-30

### Changed
- Updated tools and libraries (Kotlin 2.4, Gradle 9.8, Android Gradle Plugin 9.5, Compose
  2026.09). If anything behaves differently, please report it.
- Targets Android 17 and uses its new Bluetooth connection API there.

### Fixed
- Privacy: the "For nerds" log no longer shows the address part of brush names (e.g.
  "LFTB01-P-XXXX"), so it can be shared safely, too.

## [0.6.2] - 2026-10-29

### Fixed
- Privacy: brush reports could still contain the brush's Bluetooth address – Laifen brushes put
  it, byte-reversed, into their radio packets and partly into their name. It is now masked
  everywhere in the report. If you shared a report before, it may contain the address.
- Reports listed successful subscriptions as "no answer".

## [0.6.1] - 2026-10-29

### Added
- Install and stay up to date with Obtainium, straight from the GitHub releases (see README).

### Changed
- The fine print in the app now also says that Bürstli has nothing to do with Philips or Oral-B.

## [0.6.0] - 2026-10-29

### Added
- Philips Sonicare (classic models such as DiamondClean, Prestige, ExpertClean, Kids):
  experimental, read-only support for battery, status, mode, intensity, brushing time and brush
  head wear. Built on the protocol research of philips_sonicare_ble, not yet tried with Bürstli –
  if something looks off, use "Record a report for this brush".
- "Record a report for this brush" works for every connected brush, not only unknown ones.

## [0.5.0] - 2026-10-29

### Added
- Found a toothbrush Bürstli can't control yet (Philips Sonicare, Oral-B, other Laifen models)?
  Bürstli now says so and can record a report in about a minute – it only listens, nothing on the
  brush is changed – to send by messenger, e-mail or on GitHub. This is brand new and hasn't met a
  real Sonicare or Oral-B yet, so feedback is very welcome.
- "Use this mode" on every mode card: switch modes from the phone instead of pressing the
  button again and again.
- The README and the app explain the hidden fourth mode: the Laifen Wave only offers its
  strongest mode while high frequency is on, which as far as is known can't be switched on at
  the brush.

### Changed
- Saving a mode's settings also makes it the active mode – that's how the brush works – and the
  button now says so ("Save and use").
- Mode 4 can only be used or changed while high frequency is on, and turning high frequency off
  while in mode 4 switches to mode 3 first, as in the official app.

### Fixed
- Laifen LFTB01-SE and LFTB01-C are no longer treated as a Wave V1; according to laifen-toolbox
  they use a different protocol.

## [0.4.0] - 2026-10-29

### Added
- 30-second nudge on/off.
- High frequency on/off; mode 4 says when it isn't used.
- Start and stop brushing from the phone.
- Flight mode is shown as a switch (read-only: it can only be changed on the brush).

## [0.3.2] - 2026-10-29

First public release. Bürstli talks to your Laifen Wave (LFTB01) over Bluetooth – no account,
no internet permission, no data leaving your phone.

### Added
- Find and connect to a Laifen Wave (LFTB01) and show battery, active mode, flight mode and the
  settings of all four modes.
- Change strength, range and speed of every mode.
- Show and change the brushing time (1–5 minutes in half-minute steps).
- Every change is only reported as saved once the brush itself reports the new value.
- Unsaved changes survive the brush falling asleep.
- "For nerds: log" with timings of every Bluetooth step. Stays on the phone; copy it to report
  problems.
- German and English.

### Changed
- Connecting takes about 2 seconds: Bürstli asks the brush for its state instead of waiting up
  to 10 seconds for it.

### Fixed
- Saving no longer shows "ignored" or "connection lost" when it actually worked.

## [0.1.0] - 2026-10-29

### Added
- Internal preview: find and connect to a Laifen Wave (LFTB01) and show its status.
