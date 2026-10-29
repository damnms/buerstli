# Changelog

## 0.3.0
- Show and change the brushing time (1–5 minutes in half-minute steps).
- Found out that the Wave V1 does report its brushing time in the status packet.

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
