# Bürstli 🪥

**Open-source Android app for the Laifen Wave toothbrush. No account. No internet permission. No nonsense.**

The official app wants you to register with an e-mail address or phone number and confirm a
verification code. To adjust a toothbrush. Bürstli doesn't. It talks to your brush over
Bluetooth, and that's it — the app doesn't even have the permission to access the internet.

> *Bürstli* is Swiss German for "little brush". Rhymes with *Brötli*. Pronounce it however you like.

## Status

| | |
|---|---|
| ✅ Find and connect to the brush | |
| ✅ Live status: battery, active mode, settings of all 4 modes, flight mode | |
| 🚧 Changing mode settings | next |
| 🚧 Brushing duration, 30 s reminder, high frequency, flight mode | later |

**Supported:** Laifen Wave **V1** (model `LFTB01`, Bluetooth name `LFTB01-…`), tested on an `LFTB01-P`.
The newer Wave (`LFTB02`) and Wave Pro speak a different protocol and are not supported yet.

## Install

Grab the APK from [Releases](../../releases) and sideload it. No Google Play Services needed.

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

## Build

```sh
./gradlew testDebugUnitTest assembleDebug
```

Releases are built by GitHub Actions when a tag `v*` is pushed. The workflow expects these
repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Protocol (LFTB01)

GATT service `0xFF01`, one characteristic `0xFF02` (read, write, notify).

Status packet: `AA 0A 02 [len] [payload] [xor]`, payload = active mode index, then
4 × (strength, range, speed), flight mode flag, battery %.
Write mode settings: `AA 04 01 04 [mode] [strength] [range] [speed] [xor]`.
The checksum is the XOR of all preceding bytes. Details in
[`BrushProtocol.kt`](app/src/main/java/io/github/damnms/buerstli/BrushProtocol.kt),
with real captured packets in the unit tests.

## Credits

The protocol knowledge comes from earlier interoperability work, which deserves all the credit:
[UrbanTechIO/Laifen](https://github.com/UrbanTechIO/Laifen) (Home Assistant integration) and
[marc-alexis-com/laifen-toolbox](https://github.com/marc-alexis-com/laifen-toolbox) (Linux CLI).
No code was copied from either, and none from the official app.

## How this was made

Bürstli was written with the help of an AI assistant (Claude). The protocol was verified step by
step on a real brush, and the parser is tested against packets captured from that brush.

## Legal

This is an independent interoperability project. It is not affiliated with, endorsed by or
supported by Laifen. "Laifen" and "Laifen Wave" are trademarks of their owner and are used here
only to describe compatibility. Use at your own risk — worst case, your toothbrush forgets its
favourite settings.

## License

[GPL-3.0](LICENSE). Fork it, improve it, share it — but keep it open. Nobody gets to turn this
into yet another closed app that wants your phone number.
