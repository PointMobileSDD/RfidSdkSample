# Changelog

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
