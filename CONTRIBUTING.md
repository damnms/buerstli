# Contributing

## Adding a toothbrush

You need the brush. Drivers are only marked `VERIFIED` after testing on real hardware.

1. **Capture.** Install nRF Connect, connect to the brush, write down the service and
   characteristic UUIDs, read/notify values, and what changes when you press buttons.
   If the official app is involved, an HCI snoop log (Android developer options) shows
   what it sends. Never copy code from a vendor app; describe the protocol in your own words.
2. **Test first.** Create `drivers/<brand>` (copy `drivers/laifen/build.gradle.kts`) and
   put your captured packets into a test, like `drivers/laifen/.../Packets.kt`.
   Write tests for decoding, recognizing the device name, and every command.
3. **Implement** a `Codec`, a `ToothbrushDriver` and a `BrushSession` until the tests pass.
   Start as `SupportLevel.EXPERIMENTAL`.
4. **Register** the driver in `app/.../Drivers.kt` and add the module to `app/build.gradle.kts`.
5. **Try it** on the brush. If everything works, switch to `VERIFIED` and update the
   support table in the README.

If your brush needs a concept the domain doesn't have yet (a new parameter, a new
command), add it to `:domain` with tests, and explain why in the pull request.

## Ground rules

- `./gradlew test lintDebug assembleDebug` must pass.
- No `INTERNET` permission, no analytics, no accounts. Ever.
- Commit messages: short imperative summary ("Add Oral-B iO driver").
