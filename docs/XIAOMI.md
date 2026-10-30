# Xiaomi brushes: research notes

Status: Bürstli **recognizes** Xiaomi brushes and offers the "help out" report. There is **no
driver** and Bürstli sends **no command** to them. Nobody has tested anything below with
Bürstli yet. This file keeps what is known, what is only assumed, and what the first report
should answer.

## What is known (and where from)

- Every Xiaomi device sends MiBeacon service data under `0xFE95`; bytes 2-3 are the product
  id, little-endian. Toothbrush ids from [ble_monitor](https://custom-components.github.io/ble_monitor/by_brand):
  `0x0489` (M1S-T500), `0x0806` (T700), `0x1790` (T700i).
- **T500:** not in ble_monitor's list of encrypted devices. It broadcasts battery, brush-head
  wear, a brushing score and a session counter. ble_monitor notes that it has to be set up and
  paired with Mi Home once, otherwise it only sends an empty payload.
- **T700:** encrypted. Reading it needs the device's bindkey, which normally comes from the
  Xiaomi cloud account.
- **T501** (product id `0x3B94`): a parser log in
  [xiaomi-ble #356](https://github.com/Bluetooth-Devices/xiaomi-ble/issues/356) shows
  "Registered and bound, Standard certification, Encryption" and object ids the parser does not
  know.
- Brushing itself needs no app or account. Holding the power button for 12 seconds resets the
  T500 to factory settings (Xiaomi FAQ).

## The idea: bind locally, without the cloud

Mi Home binds a device with Xiaomi's "standard auth". The protocol is documented from BLE
captures of the pairing by [dnandha/miauth](https://github.com/dnandha/miauth), building on
[atc1441](https://github.com/atc1441/ATC_MiThermometer). atc1441's
[activation page](https://atc1441.github.io/Temp_universal_mi_activate.html) binds devices
without Xiaomi's servers:

- the handshake runs over service `0xFE95`, characteristics `0x0010` and `0x0019` (ECDH);
- the device id that Mi Home would get from the cloud is made up locally;
- token and bindkey are derived locally from the key exchange.

If Bürstli bound a brush itself, it would **know the bindkey**, so encrypted models (T700, T501)
could be read too, not only the T500. Side effect: the brush is no longer bound to Mi Home.
Owners must know this before they start.

## Not verified

- whether the T500 (and T700) use standard auth, or a stricter variant;
- whether a brush bound this way actually sends data;
- whether binding needs a button press on the brush (Xiaomi scooters ask for one);
- whether Android's crypto offers AES-CCM, or Bürstli needs its own implementation.

## Rules

Binding means writing to the brush, so **ADR-6 applies**: nothing is implemented until an
owner tests it with Bürstli. Bürstli implements from the protocol descriptions only and copies
no code, neither from Mi Home nor from other projects.

## What the first report should answer

The current report already records everything needed, without sending a command:

1. **Frame control** (bytes 0-1 of the `0xFE95` service data, not masked): is the brush bound,
   and with which auth mode? Decode the bits with the
   [xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble) parser as reference, not from
   memory.
2. **GATT dump:** does the brush offer service `0xFE95` with `0x0010` and `0x0019`?

Note: during the notification phase the report subscribes to every characteristic that offers
notifications, `0x0010` included. That is a write to the standard descriptor (CCCD), not a
command to the brush; Mi Home subscribes the same way before binding.
