# Changelog

## 1.1.0

- Uses the SDK `ex.dev.sdk:rfid:1.1.0`. On an MF52 running a system image built before
  November 4, 2026, `connectInternal()` returns `E3`; keep such devices on 1.0.1.
- The MF52 connection screen tells a device without a supported reader (`E3`) apart from a reader
  without a valid license (`E4`) and from other failures, such as a device that is charging (`E0`).
- The MF52 settings screen labels the link profiles SDK 1.1.0 publishes: 0, 3, 4, 5, 7 and 8. The
  MF52 numbers its profiles differently from 1.0.1.
- The RF88 Bluetooth and wire screens explain a failed connection only when they made the attempt.
  A failure left by the other screen no longer reads as their own.
- `sdk-reference.html` and the API docs cover SDK 1.1.0: RF88 pairing with `pair()`, the MF52's
  25.0 dBm ceiling, `E4` when licensed use is turned off, and an MF52 that turns itself off reported
  as `DISCONNECTED`.
- The README and `sdk-reference.html` note that the MF52 reader does not come up while the device
  is charging, including over the USB cable you debug with.

## 1.0.1

- Uses the SDK `ex.dev.sdk:rfid:1.0.1`. `stop()` now reaches an RF88 that has gone to sleep, as every
  other call does, and disconnecting an RF88 over the wire beeps even when it was asleep.

## 1.0.0

- First release against the unified RFID SDK `ex.dev.sdk.rfid` 1.0.0, which drives both the RF88 and
  the MF52 through one `RfidManager`.
- Screens for both readers: connection (Bluetooth, Wire, NFC Tap-to-Pair, built-in), inventory,
  inventory and read, tag operations, settings and reader information.
- Every screen carries the SDK log it produced, folded away at the bottom until it is wanted.
- A thin progress bar under each screen's header shows while an SDK call is running, from the moment
  it is sent until it returns.
- The inventory screens say when a start is refused and why. The inventory-and-read screen lists each
  tag whose read failed, with the reader's code.
- Gets the SDK `ex.dev.sdk:rfid:1.0.0` from the Point Mobile Maven repository instead of bundling the
  `.aar`; building needs an access token (see README).
