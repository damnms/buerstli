# Xiaomi: protocol notes

Status: Bürstli **recognizes** every toothbrush in Xiaomi Home and offers the "help out" report.
The **T500**, **T700/T700i** and **T501** have experimental drivers that only **listen** to their
broadcasts; the T700 and T501 broadcast encrypted and need the brush's [bindkey](#bindkey), which
the user enters. Bürstli sends **no command** to any Xiaomi brush. Nobody has tested anything below with
Bürstli yet. This file keeps what is known, what is only assumed, and what the first report
should answer.

## Drivers: what they read

All Xiaomi drivers are passive (`XiaomiPassiveDriver`): no connection. Frame layout and object ids as documented by
[xiaomi-ble](https://github.com/Bluetooth-Devices/xiaomi-ble) (Home Assistant's built-in Xiaomi
parser) and [ble_monitor](https://github.com/custom-components/ble_monitor), studied, not copied.

**Frame** (service data `0xFE95`): frame control (2 bytes, LE), product id (2), frame counter (1),
then the MAC (6, if bit 4), a capability byte (if bit 5; one more I/O byte if the capability has
`0x20`), then objects (if bit 6): id (2, LE), length (1), data. Bit 3 means encrypted; bit 7 is
mesh. Bürstli skips mesh frames and versions below 2, as xiaomi-ble does. Encrypted frames are
read with the user's key, see [Encrypted brushes](#encrypted-brushes-t700-t501).

| Object | Data | In Bürstli | Seen on a real T500 |
|---|---|---|---|
| `0x0010` toothbrush | `00` + counter: brushing started; `01` + score: brushing ended | Brushing / off, last score | start, counter 3 (xiaomi-ble and ble_monitor tests); the end with score only on other brushes |
| `0x3003` brushing | `00`/`01` start/end, timestamp (4, not used), score | same | T700 (encrypted), not the T500 |
| `0x100A` battery | percent | battery | per ble_monitor, no frame in the tests |
| `0x1013` consumable | percent **left** (Xiaomi's object definition) | brush head wear = 100 − left | per ble_monitor, no frame in the tests |

The counter at the start of brushing is not understood, so Bürstli doesn't show it. The score has no
known maximum; the reference frames have 83 and 99. That `0x1013` counts what is left, not what
is worn, is from Xiaomi's object definition and should be checked against Mi Home's display.

Per ble_monitor, an un-set-up T500 sends only an empty frame, so Bürstli shows nothing for it until
the brush was set up with Mi Home once. The real T500 frame has frame control `0x3071`: not bound,
old auth mode, not encrypted.

## Encrypted brushes (T700, T501)

Drivers only for models with a real frame known: `XiaomiT700Driver` (`0x0806`, `0x1790`; a real T700
frame and its key in xiaomi-ble's tests) and `XiaomiT501Driver` (`0x3B94`; a real advertisement in
xiaomi-ble#356, encrypted, MiBeacon V5). The Pro models and the others stay recognized only: nobody
has published a frame from them.

**Decryption** (MiBeacon V4/V5, as xiaomi-ble does it, implemented from that description): AES-CCM
with the 16-byte bindkey, 4-byte tag, associated data `0x11`. Nonce (12 bytes): the device address
(little-endian; from the frame if it carries it, else the Bluetooth address), product id and frame
counter (bytes 2-4), then the 3-byte extended counter that sits just before the tag. The ciphertext
runs from where the objects would start to the extended counter; decrypted, it holds the usual
objects. Neither the JVM nor Android ships AES-CCM, so Bürstli builds it on plain AES (RFC 3610).
Checked against the real T700 frame: it decrypts to `0x3003`, end of brushing, score 83.

Older encrypted frames (V2/V3) use another scheme; Bürstli skips them. A frame that fails to
decrypt counts as a wrong key only if the key never worked before, or twice in a row (as
xiaomi-ble does): one damaged frame isn't a wrong key.

This is reading only (ADR-6): the key decrypts what the brush broadcasts anyway; nothing is written,
nothing is bound.

## Bindkey

The bindkey is made when the brush is set up with Xiaomi Home, and Xiaomi keeps it in the cloud.
Bürstli can't get it itself (that would mean binding, see below), so the owner enters it once per
brush. It changes when the brush is set up again.

Ways to get it, both outside Bürstli and both need the owner's Xiaomi account. They are other
people's projects, not Bürstli's: Bürstli has nothing to do with them, takes no responsibility for
them and has no rights or duties regarding them. Whoever uses them does so under their terms.

- [Xiaomi Cloud Tokens Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor):
  lists the devices of the account; for Bluetooth devices it shows the "BLE KEY", which is the bindkey.
- Home Assistant's Xiaomi BLE integration can fetch it from the Xiaomi cloud when it finds the brush.

Bürstli stores the key per Bluetooth address in the app's private storage (no backup), never logs it
and never sends it anywhere. When it doesn't fit, Bürstli says so and asks again.

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

## Mi Home's device catalog

Mi Home (Xiaomi Home 11.9.622, Android) ships a device catalog per server region in
`assets/device_config/plugin_config_<region>` (JSON, `result.devices.list`). Its `pd_id` is the
MiBeacon product id: it matches all four ids known before (T500, T700, T700i, T501). Bürstli
recognizes every toothbrush in it.

What a brush can do is not in the APK: Mi Home downloads a React Native plugin per model at
runtime. The APK ships only three plugins, none of them a toothbrush.

| Model | pd_id | Regions | bt_bind_style | Name in the catalog |
|---|---|---|---|---|
| `soocare.toothbrush.m1` | `0x01A1` | cn, de, ru, sg | 1 | Mijia Sonic Electric Toothbrush |
| `soocare.toothbrush.m1s` | `0x0489` | cn, de, ru, sg, us | 1 | Mijia T500 |
| `k0918.toothbrush.t700i` | `0x1790` | de, ru, sg, us | 2 | Xiaomi Electric Toothbrush T700 |
| `soocare.toothbrush.2501` | `0x3B94` | de, ru, sg, us | 2 | Xiaomi Smart Electric Toothbrush T501 |
| `xiaomi.toothbrush.24peu` | `0x7B27` | de, sg | 2 | Mijia Smart Servo-oscillation Electric Toothbrush Pro |
| `xiaomi.toothbrush.24pgl` | `0x7B2A` | de, ru, sg | 2 | Mijia Smart Servo-oscillation Electric Toothbrush Pro |
| `xiaomi.toothbrush.p02gl` | `0x8E95` | de, ru, sg, us | 2 | Mijia Smart Multi-oscillation Electric Toothbrush Pro |
| `soocare.toothbrush.mc1` | `0x0440` | cn, sg | 1 | Mitu kids sonic |
| `k0918.toothbrush.t700` | `0x0806` | cn | 2 | Mijia T700 |
| `soocare.toothbrush.t501` | `0x10C5` | cn | 2 | Mijia T501 |
| `soocare.toothbrush.1501` | `0x1FF3` | cn | 2 | Mijia T501 |
| `xiaomi.toothbrush.p001` | `0x4DD8` | cn | 2 | Mijia multi-directional |
| `xiaomi.toothbrush.24p` | `0x6800` | cn | 2 | Mijia servo Pro |
| `xiaomi.toothbrush.p002` | `0x6F5B` | cn | 2 | Mijia multi-directional Pro |
| `xiaomi.toothbrush.m04` | `0x759B` | cn | 2 | Mijia kids Pro |
| `xiaomi.toothbrush.24pkr` | `0x7B29` | sg | 2 | Mijia Smart Servo-oscillation Electric Toothbrush Pro |
| `soocare.toothbrush.x3` | `0x00CE` | cn | 1 | Soocas sonic |
| `k0918.toothbrush.kid01` | `0x05A1` | cn | 0 | Dr.Bei kids (`pid` 8, all others 6) |
| `nancii.toothbrush.t600` | `0x1767` | cn | 2 | partner brand T600 |
| `eizi.toothbrush.sy01` | `0x2A24` | cn | 2 | EIZI Magic 01 |
| `xmlong.toothbrush.r02` | `0x3097` | cn | 2 | partner brand R02 |
| `xmlong.toothbrush.r05` | `0x30C6` | cn | 2 | partner brand R06 |
| `jrj.toothbrush.j3m800` | `0x3228` | cn | 2 | imoya |
| `xjyd.toothbrush.k1` | `0x33E2` | cn | 2 | partner brand K1-MI |
| `hlznkj.toothbrush.se40a` | `0x342F` | cn | 2 | partner brand, portable |

Regions are Mi Home's servers: `de` is `de-en` (Europe). The Indian catalog (`i2-in`) has no
toothbrush. `zhij.toothbrush.bv1` (`0x094A`) is in the toothbrush category but is a sterilizing
cup; Bürstli ignores it. Chinese names are translated loosely.

Other catalog fields, the same for every toothbrush: `bt_is_secure` 0, `bind_confirm` 0,
`bt_gateway` 0. `bt_is_secure` is 0 for the encrypted T700 too, so it does **not** mean
"encrypted"; it probably means Xiaomi's "secure auth" with a security chip (not verified).
`bt_match` (0, 1, 2) is not understood.

## What Mi Home does (from the app, verified in its code)

Studied in Mi Home 11.9.622 with jadx. Class names are jadx's, obfuscated and only valid for
that version; they are here so the place can be found again, not as a source of code.

- **`bt_bind_style` 1 = strong bind, 2 = weak bind.** The login (`_m_j/v41`) asks
  `wz6.OooOooo(model)` and logs `loginForStrongBind` for 1, `loginForWeakBind` for 2; other
  values are rejected ("impossible here"). The real implementation (`_m_j/pq6`) returns the value
  that Mi Home elsewhere passes on as `"bt_bind_style"`. Gap: that `pq6` is the instance used at
  runtime is assumed, it is the only real implementation found.
- **Bind style is not encryption.** The T500 (strong) broadcasts in clear, the T700 (weak)
  encrypted (ble_monitor). So the earlier guess "style 1 readable, style 2 encrypted" is wrong as a
  rule.
- **The brush stores its binding itself, also under weak bind.** For a weak-bind brush that is not
  marked bound yet, Mi Home (`_m_j/v21`, `_m_j/ww0`) reads characteristic **`0x0013`** of service
  `0xFE95`: the device id (did), encrypted with the token the app holds. Decrypted, a bound
  brush's did starts with `blt.`; all `0xFF` means unbound.
- **Characteristic `0x0014`** of `0xFE95` holds the **beacon key**, encrypted with the same token.
  Mi Home reads and decrypts it. So the key for encrypted MiBeacons comes from the brush, not
  from the cloud, once one has the token.
- If the brush has no did yet, Mi Home writes one (padded to 20 bytes, encrypted with the
  token) to `0x0013` ("write SN to Device"). Where that did comes from is not visible there.
- **The crypto is native**: `BLECipher` loads `libblecipher.so` (`nativeEncrypt`, and
  `nativeMixA`/`nativeMixB`, which mix 8 bytes from a MAC address and a 2-byte number). The
  token exchange (`0x0010`/`0x0019`) is not in Java either. Bürstli does not study the native
  code; the public protocol descriptions below cover it.

What this means for Bürstli: reading an **encrypted** brush needs the beacon key, the key needs
the token, and the token comes only from binding. Binding is the step ADR-6 rules out.

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

- whether binding works without Xiaomi's servers for toothbrushes (atc1441 says yes for its
  devices; Mi Home's token exchange is native and was not studied);
- which brushes broadcast encrypted: known only for T500 (clear), T700 and T501 (encrypted);
- whether the T500 (and T700) use standard auth, or a stricter variant (`bt_is_secure` 0
  suggests standard auth);
- whether a brush bound this way actually sends data;
- whether binding needs a button press on the brush (Xiaomi scooters ask for one);
- whether Android's crypto offers AES-CCM, or Bürstli needs its own implementation.

## Rules

Binding changes the brush (new keys, no longer bound to Mi Home), so under **ADR-6** it is not
allowed for an experimental driver: nothing is implemented until an owner tests it with Bürstli. Bürstli implements from the protocol descriptions only and copies
no code, neither from Mi Home nor from other projects.

## To check on a real brush

From a T500: does Bürstli show brushing, the score after brushing, battery and brush head, and do
they match Mi Home? Is the brush head wear the right way round?

From a T700, T700i or T501 with its key: does the key work, and what does Bürstli show? The T501
especially: nobody knows yet which objects it sends. A report can't tell, it records the frames
encrypted. The **nerd log** can: with a working key, Bürstli writes every decrypted frame into it
(`decrypted: object 0x… = …`, unknown objects marked), once per frame. Brush, then copy the log.

From any Xiaomi brush, the current report already records everything needed, without sending a
command:

1. **Frame control** (bytes 0-1 of the `0xFE95` service data, not masked): is the brush bound,
   and with which auth mode? The report writes it out next to each frame (`MiBeacon V5, product
   0x3B94, encrypted, bound, standard auth`), from the bits as xiaomi-ble documents them; objects
   of frames in clear are listed too.
2. **GATT dump:** does the brush offer service `0xFE95` with `0x0010`, `0x0013`, `0x0014` and
   `0x0019`?

Note: during the notification phase the report subscribes to every characteristic that offers
notifications, `0x0010` included. That is a write to the standard descriptor (CCCD), not a
command to the brush; Mi Home subscribes the same way before binding.
