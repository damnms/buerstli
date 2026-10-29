# Changelog

## 0.3.2
- Connecting is much faster: Bürstli now asks the brush for its state instead of waiting up
  to 10 s for it to send it on its own. (Plain Bluetooth reads only return filler.)
- Fix: saving the brushing time showed "connection lost" although it worked. The brush only
  acknowledges this command, so Bürstli now asks for the state to confirm it.
- Fix: a timeout while confirming was misreported as "connection lost".

## 0.3.1
- "For nerds: log" with timings of scanning, connecting and every Bluetooth operation.
  Stays on the phone, copy it to report problems.

## 0.3.0
- Show and change the brushing time (1–5 minutes in half-minute steps).
- Read the brushing time from the status packet (field documented by laifen-toolbox).

## 0.2.1
- Fix: saving showed "the brush ignored that" although it worked. The brush applies a change
  with a short delay; Bürstli now waits up to 3 seconds for it to report the new values.

## 0.2.0
- Change strength, range and speed of every mode, confirmed by reading the brush back.
- Unsaved changes survive the brush falling asleep.
- New architecture for supporting more toothbrushes: pure-Kotlin domain and drivers.
- Lint and Dependabot in CI.

## 0.1.0
- Find and connect to a Laifen Wave (LFTB01), show battery, modes and flight mode.
