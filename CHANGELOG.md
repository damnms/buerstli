# Changelog

All notable changes to Bürstli are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
