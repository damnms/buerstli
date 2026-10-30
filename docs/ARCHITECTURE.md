# Architecture

Bürstli wants to support as many toothbrushes as possible. Every brand speaks its own
Bluetooth dialect, so the code is split so that **adding a brush means adding a driver**,
and nothing else has to change.

## Layers (ports & adapters)

```
┌───────────────────────────── :app (Android) ─────────────────────────────┐
│  UI (Compose)  →  BrushViewModel  →  BleConnector / AndroidBrushLink     │
│                                        (adapter: Android Bluetooth)      │
└───────────────┬──────────────────────────────────────┬───────────────────┘
                │ uses                                  │ implements BrushLink
┌───────────────▼───────── :domain (pure Kotlin) ───────▼───────────────────┐
│  BrushModel · Capabilities · ModeSpec · ModeSettings · BrushState         │
│  BrushCommand · CommandResult · Validation                                │
│  Ports: ToothbrushDriver · BrushSession · BrushLink · DriverRegistry      │
└───────────────▲───────────────────────────────────────────────────────────┘
                │ implements ToothbrushDriver
┌───────────────┴──────── :drivers:<brand> (pure Kotlin) ───────────────────┐
│  Codec (bytes ⇄ domain)  ·  Driver (recognize, capabilities)  ·  Session  │
└───────────────────────────────────────────────────────────────────────────┘
```

Rules, enforced by Gradle module boundaries:

- `:domain` knows nothing about Android, Bluetooth APIs or any brand.
- Drivers depend only on `:domain`. They are plain Kotlin and fully testable on the JVM.
- `:app` wires everything together (`Drivers.kt` is the composition root) and contains
  the only Android-specific code.

## Ubiquitous language

| Term | Meaning |
|---|---|
| **Brush** | A physical toothbrush. |
| **Model** | A brush type with one protocol, e.g. Laifen Wave (LFTB01). |
| **Driver** | Code that recognizes a model and speaks its protocol. One per protocol. |
| **Session** | A live conversation with one brush: over a connection, or by listening to its advertisements (passive drivers). |
| **Passive driver** | Never connects; decodes what the brush broadcasts (e.g. Oral-B). Read-only by nature. |
| **Mode** | A brushing program on the brush (Mode 1, 2, …). |
| **Parameter** | A knob of a mode: strength, range, speed, … |
| **Capabilities** | What a model can do; the UI is generated from them. |
| **Support level** | `VERIFIED` = tested on real hardware, otherwise `EXPERIMENTAL`. |
| **Known brush** | A brush Bürstli recognizes but has no driver for. |
| **Capture report** | What Bürstli recorded from a known brush, read-only, for building its driver. |

## Decisions

**ADR-1: Pure-Kotlin domain and drivers.** Protocol bugs are the most likely bugs and the
hardest to reproduce. Keeping drivers free of Android lets us test them in milliseconds
against packets captured from real brushes.

**ADR-2: Verify writes by reading back.** A command only counts as `Confirmed` when the brush
reports the new state itself. "Probably worked" is not good enough for something people put
in their mouth twice a day.

**ADR-3: Capabilities drive the UI.** The UI never asks "is this a Laifen?". It renders
whatever modes and parameters the driver declares, so a new brand needs no UI changes
unless it brings a genuinely new concept.

**ADR-4: No DI framework, no database.** A handful of objects wired by hand in `Drivers.kt`
and `BrushViewModel` is easier to read than Hilt. Settings live on the brush, so there is
nothing to persist.

**ADR-5: The driver interface follows reality, not speculation.** It is shaped by the one
protocol we know. When the second brand arrives and doesn't fit, the interface changes then
— not before.

**ADR-6: Experimental drivers only on verified research, and read-only.** A driver may be added
without a Bürstli tester if its protocol was documented *and verified on real brushes* by others
(e.g. Sonicare by philips_sonicare_ble). It stays `EXPERIMENTAL` and read-only until an owner
confirms it with Bürstli. For everything else, Bürstli recognizes the brush and lets its owner
record a report in the app – also from an experimental driver, when something looks wrong.
