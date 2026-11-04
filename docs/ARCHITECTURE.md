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
| **Passive driver** | Listens to what the brush broadcasts (e.g. Oral-B). For a value the brush doesn't broadcast, its session asks for a short connection (`wantsPoll`), reads it in `poll` and lets go; how often the adapter tries is the adapter's business. Read-only by nature. |
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

**ADR-6: Experimental drivers only on verified research, and they never change the brush.** A
driver may be added without a Bürstli tester if its protocol was documented *and verified on real
brushes* by others (e.g. Sonicare by philips_sonicare_ble). It stays `EXPERIMENTAL` and read-only
until an owner confirms it with Bürstli: every capability is `Access.READ_ONLY`.

The official vendor app counts as verified research too: what it reads and sends is what real
brushes do every day. Bürstli studies it only to understand the protocol (interoperability) and
never copies its code. Where the vendor app and other research disagree, the vendor app wins.
Either way, the brand's protocol notes record where each value comes from and whether it was
seen on a real brush, so a report can confirm or correct it (e.g. [ORAL-B.md](ORAL-B.md), [SONICARE.md](SONICARE.md)).

Read-only means that nothing on the brush changes, not that nothing is sent. Some protocols can't
be read at all without messages (a handshake, subscribing, asking for values, acknowledging what
arrived). An experimental driver may send those, but only the messages the verified
implementation sends on real brushes for reading. Anything that changes the brush stays excluded:
settings, modes, resets, firmware, flight mode, and vendor-level binding that replaces the vendor
app's pairing (e.g. Xiaomi's). Standard Bluetooth pairing through Android, which the user
confirms, is not such a change. When in doubt, it counts as a change.

Exception, decided by the maintainer: the experimental Sonicare driver also switches the mode and
the intensity, so that a single test by an owner covers them. Guarded by the brush's own list of
modes and by reading back (ADR-2); see [SONICARE.md](SONICARE.md).

For everything else, Bürstli recognizes the brush and lets its owner record a report in the app –
also from an experimental driver, when something looks wrong.
