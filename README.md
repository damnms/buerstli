# Bürstli 🪥

[![Build](https://github.com/damnms/buerstli/actions/workflows/build.yml/badge.svg)](https://github.com/damnms/buerstli/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/damnms/buerstli)](https://github.com/damnms/buerstli/releases/latest)
[![License: GPL-3.0](https://img.shields.io/github/license/damnms/buerstli)](LICENSE)

**Open-source Android app for Bluetooth toothbrushes. No account. No internet permission. No nonsense.**

Works with brushes from **Laifen, Oclean, Oral-B, Philips Sonicare and Xiaomi** – the Laifen Wave is
tested on a real brush, the Sonicare DiamondClean 9000 confirmed, the others are experimental.
Any other brush can record a report, so it can be added.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Bürstli connected to a Laifen Wave: battery, start button, 30-second nudge, high frequency and brushing time" width="280">
</p>

The official app wants you to register with an e-mail address or phone number and confirm a
verification code. To adjust a toothbrush. Bürstli doesn't. It talks to your brush over
Bluetooth, and that's it — the app doesn't even have the permission to access the internet.

> *Bürstli* is Swiss German for "little brush". Rhymes with *Brötli*. Pronounce it however you like.

## Supported brushes

| Brand | Model | Bluetooth name | Status | Features |
|---|---|---|---|---|
| Laifen | Wave (LFTB01) | `LFTB01-…` except `-SE`, `-C` | ✅ tested on a real brush | battery, modes, edit and switch modes, brushing time, 30-second nudge, high frequency ([the hidden fourth mode](docs/LAIFEN.md#the-hidden-fourth-mode)), start/stop, flight mode, firmware version |
| Laifen | Wave Pro (LFTB02) | `LFTB02-S-…` | 🧪 experimental, listens only | battery, brushing time, brushing/idle, pressure warning, 30-second nudge, high frequency, flight mode, Pro features (shown) |
| Laifen | Wave SE (LFTB01-SE) | `LFTB01-SE…` | 🧪 experimental, listens only | battery, modes, brushing time, brushing/idle, 30-second nudge, high frequency, flight mode (shown) |
| Laifen | Kids TC51 (LFTB01-C) | `LFTB01-C…` | 🧪 experimental, listens only | battery, brushing time, brushing/idle, child lock, 30-second nudge, high frequency, flight mode, ramp-up, spin dry, pressure warning (shown) |
| Oclean | X series (X, X Pro, X Ultra, X Pro Elite, X Pro Digital, …), Z1 | `Oclean…` | 🧪 experimental, read-only; other Oclean models: model and battery only | model, firmware, battery, brushing history with time brushed and score ([notes](docs/OCLEAN.md)) |
| Oral-B | iO, Genius, Smart, Pro, Triumph | broadcasts manufacturer data | 🧪 experimental, listens, connects briefly for what it doesn't broadcast | status, mode, brushing time, pressure, mouth area, exact iO series, battery level, brushing history, brush head reminder ([notes](docs/ORAL-B.md)) |
| Philips | Sonicare (classic: DiamondClean, Prestige, ExpertClean, Kids, …) | `Philips Sonicare`, `Philips OHC` | 🧪 experimental; ✅ reading confirmed on a DiamondClean 9000 (HX960X) – thanks to my awesome brother! | battery, status, mode, intensity, brushing time, brush head wear; from the official app, untested: model number, firmware, mode names per model, brushing history, live pressure, switch mode and intensity ([notes](docs/SONICARE.md)) |
| Philips | Sonicare 7100 (HX742X) | `Philips Sonicare` | 🔍 recognized, not supported yet (different protocol, "Condor"); protocol [documented](docs/SONICARE.md#condor--app) – tester wanted! | |
| Xiaomi | Mi Smart Electric Toothbrush T500 | MiBeacon product id | 🧪 experimental, listens only; the brush must have been set up with Mi Home once | brushing/off, score of the last session, battery level, brush head wear ([notes](docs/XIAOMI.md)) |
| Xiaomi | T700, T700i, T501 | MiBeacon product id | 🧪 experimental, listens only; they broadcast encrypted, so Bürstli needs the brush's key ([how to get it](docs/XIAOMI.md#bindkey)) | brushing/off, score of the last session, and whatever else the brush broadcasts |
| Xiaomi | Pro models, kids, partner brands in Mi Home (21 more models) | MiBeacon product id | 🔍 recognized, not supported yet (most broadcast encrypted) ([notes](docs/XIAOMI.md)) | |
| Any other brush | Playbrush, usmile, Ailoria, MOVA, Tesla, … | – | ✋ pick it from the list of nearby devices | |

✅ **tested**: confirmed by an owner with Bürstli. 🧪 **experimental**: built from other people's
research verified on real brushes, or from what the official app does, not yet tried with Bürstli.
Experimental drivers only read – except Philips Sonicare, where Bürstli also switches mode and
intensity and sets the clock, as the official app does ([details](docs/SONICARE.md#writing)).
🔍 **recognized**: Bürstli knows the brush and records a report for it. ✋ **any other brush**: pick
it from the list of nearby devices to record a report.

## Install

[<img src="docs/badges/obtainium.png" alt="Get it on Obtainium" height="60">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/damnms/buerstli)

**Recommended: [Obtainium](https://github.com/ImranR98/Obtainium).** It installs Bürstli straight
from the GitHub releases and keeps it up to date – no store, no account. With Obtainium installed,
tap the badge above on your phone, or add `https://github.com/damnms/buerstli` by hand.

**Or by hand:** grab the APK from [Releases](../../releases) and open it on your phone. You'll have
to look for updates yourself. No Google Play Services needed either way.

Tips:
- The brush only shows up while it's awake. Pick it up before tapping *Find my brush*.
- It can only talk to one app at a time. Close the official app (or nRF Connect) first.
- After a disconnect it may take up to a minute until the brush advertises again.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | To find and talk to the brush (Android 12+) |
| `ACCESS_FINE_LOCATION` | Android 11 and older only — the OS requires it for Bluetooth scanning |
| `INTERNET` | **not requested** |

## Help wanted: testers 🙋

Got a brush marked 🔍 or 🧪? You can help without any technical knowledge: open Bürstli, tap
**Find my brush**, then **Let's go**. Bürstli doesn't know your brush at all? Tap **My brush isn't
supported** and pick it from the list of Bluetooth devices nearby. Bürstli listens to your brush for
about a minute – it only reads, nothing on the brush is changed – and gives you a report to send by
messenger, e-mail or as a [GitHub issue](../../issues/new?template=new-brush.yml).

What a report would settle, per brand:

- **Laifen Wave Pro, SE, Kids TC51:** does the screen fill, and does it match the official app?
  [What to check](docs/LAIFEN.md#to-check-on-a-real-brush)
- **Oclean:** model and battery for every Oclean, the brushing history for the X series and the Z1.
  Bürstli only asks for the data; it never sends the command the app deletes the history with.
  One report from an X or X Pro answers most of it. [What to check](docs/OCLEAN.md#to-check-on-a-real-brush)
- **Oral-B:** battery, brushing history and brush head reminder come from studying the official
  app. One report from an iO and one from an older Genius or Smart would settle most of it.
  [What to check](docs/ORAL-B.md#to-check-on-a-real-brush)
- **Philips Sonicare 7100 (HX742X):** it speaks a different protocol ("Condor"), [written
  down](docs/SONICARE.md#condor--app) from the official app. A report shows whether it answers the
  way the app expects – the first step to a driver.
- **Xiaomi:** the T500, T700, T700i and T501 have drivers that only listen. The T700 and T501
  broadcast encrypted; with the brush's key ([how to get it](docs/XIAOMI.md#bindkey)) Bürstli reads
  them – brush once, then copy the nerd log. A report from any other Xiaomi shows whether it can be
  read without Mi Home. [What to check](docs/XIAOMI.md#to-check-on-a-real-brush)

## For developers

```sh
./gradlew test lintDebug assembleDebug
```

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the code is split, and why.
- [CONTRIBUTING.md](CONTRIBUTING.md): adding a brush, the changelog, releasing
  (`scripts/release.sh`).
- [docs/](docs/README.md): the protocol notes per brand, with where every value comes from.

Releases are built by GitHub Actions when a tag `v*` is pushed. The workflow expects these
repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Credits

The protocol knowledge comes from earlier interoperability work, which deserves all the credit:

- **Laifen:** [UrbanTechIO/Laifen](https://github.com/UrbanTechIO/Laifen) (Home Assistant
  integration) and [marc-alexis-com/laifen-toolbox](https://github.com/marc-alexis-com/laifen-toolbox)
  (Linux CLI).
- **Oclean:** [deniskie/ha-oclean-integration](https://github.com/deniskie/ha-oclean-integration)
  (Home Assistant integration, MIT), whose tests and issues provide our real Oclean frames.
- **Oral-B:** [Bluetooth-Devices/oralb-ble](https://github.com/Bluetooth-Devices/oralb-ble) (the
  library behind Home Assistant's Oral-B integration, MIT), whose test suite provides the real
  advertisements our tests use.
- **Philips Sonicare:** [mtheli/philips_sonicare_ble](https://github.com/mtheli/philips_sonicare_ble)
  (Home Assistant integration, MIT).
- **Xiaomi:** [Bluetooth-Devices/xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble)
  (Apache-2.0) and [custom-components/ble_monitor](https://github.com/custom-components/ble_monitor)
  (MIT), whose tests provide our real Xiaomi frames.

For Laifen's newer brushes, Oclean, Oral-B, Philips Sonicare and Xiaomi, the official apps were also
studied to understand the protocol; the [protocol notes](docs/README.md) say what comes from where.
No code was copied from any of them, and none from an official app.

## How this was made

Bürstli was written with the help of an AI assistant (Claude). For the Laifen Wave, the protocol
was verified step by step on a real brush, and the parser is tested against packets captured from
that brush. The Philips Sonicare driver was confirmed on a real DiamondClean 9000. Everything else
is built on other people's research or on what the official app does (see Credits), and stays
experimental until an owner confirms it.

## Legal

This is an independent interoperability project. It is not affiliated with, endorsed by or
supported by Laifen, Philips, Procter & Gamble (Oral-B), Xiaomi or Oclean. "Laifen", "Laifen Wave",
"Philips", "Sonicare", "Oral-B", "Xiaomi", "Mijia" and "Oclean" are trademarks of their respective
owners and are used here only to describe compatibility. Tools by others that the docs point to
(e.g. for a Xiaomi key) are not part of Bürstli; Bürstli has no rights or duties regarding them. Use at your own risk – worst case, your toothbrush forgets its favourite
settings.

## License

[GPL-3.0](LICENSE). Fork it, improve it, share it — but keep it open. Nobody gets to turn this
into yet another closed app that wants your phone number.
