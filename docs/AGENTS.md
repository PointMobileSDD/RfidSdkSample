# RfidSdkSample — guide for coding agents

This app demonstrates the Point Mobile RFID SDK (`ex.dev.sdk.rfid`) against both readers it drives:
the **RF88** that attaches to a device, and the **MF52** reader built into a device.

Read this before changing anything here. Several of the rules below look like oversights and are not.

## What this app is for

Nobody reads this app end to end. Integrators open the one screen that does what they need and
**copy it into their own app.** Every rule here follows from that.

## The duplication is deliberate — do not remove it

Screens repeat each other. `Mf52AccessActivity` and `Rf88AccessActivity` are near-identical; every
screen carries its own executor, its own listener wiring, its own log panel and its own window-inset
code.

**This is the point.** A screen that delegates to a shared base class or helper cannot be copied on
its own: the person who lifts one file gets a file that does not compile. Factoring the repetition
out would make the app tidier and useless.

If you are about to write "extract common", "reduce duplication" or "introduce a base activity" —
stop. That is the one change this repository does not want.

## One screen, one file

- A screen is a single Activity plus a single layout. No fragments.
- Helper classes that belong to one screen (a list adapter, a row holder) are **inner classes of
  that screen**, not separate files.
- Layouts are not shared. Two screens with the same header both spell the header out. The one
  exception is `include_log_sheet.xml`, the log panel every screen ends with.
- A screen may not import another screen except to start it.

## Every screen is one column

The root is a vertical `LinearLayout` holding three rows: `header`, `content` with
`layout_weight="1"`, and the included log panel. Nothing floats over anything, so nothing can be
hidden behind anything. The panel folds by animating its height to zero, and `content` takes the room
back as it goes.

One thing to watch: **a scroll view works out how far it can scroll from its child's height alone
and counts no margin**, so space around a scrolling card belongs in the scroll view's padding. A card
with `layout_margin` can never bring its last row into view.

## What this app does not use

`ViewModel`, `LiveData`, data binding, view binding, dependency injection, repositories, use cases,
a shared base `Activity`, or a wrapper around the SDK.

Instead: `findViewById`, one `ExecutorService` per screen, `runOnUiThread`. Plain Java 11. Adding
any of the above is a breaking change to this app's purpose, not an improvement.

## Threading

Three rules, repeated in every screen because every screen has to hold them on its own:

1. **Synchronous SDK methods block** until the reader answers — hundreds of milliseconds to seconds.
   Call them on the screen's executor, never on the main thread.
2. **Listener callbacks arrive on an SDK thread**, never the main thread. Hand anything that touches
   a `View` to `runOnUiThread`.
3. **Do not call a synchronous SDK method from inside a listener callback.** Callbacks are delivered
   one at a time; blocking one delays every other.

Tag reports during inventory arrive fast. The inventory screens fold them into a map on the callback
thread and redraw on a timer instead of posting once per tag.

## Let the SDK decide

When an argument might be wrong, **send it and report the code that comes back.** Do not pre-check
what the SDK already checks.

A guard in the app drifts from the SDK, is usually stricter than it, and hides the failure path the
sample exists to demonstrate. The exception is input the SDK cannot see — an empty field, or a format
the screen itself defines.

## Show failures on screen

Every screen has a log panel. Failures go in it, in red, with the result code and a sentence saying
what usually causes it. A code alone tells an integrator nothing; `00` in particular has several
causes that look nothing alike.

Do not swallow a failure into logcat. Logcat is for the SDK's own debug output, which this app turns
on with `RfidManager.setDebugMode(BuildConfig.DEBUG)`.

## Comments

English only, and generous. Above each SDK call: what it does, what it returns, what to watch for.
At the top of each screen: what it demonstrates, which reader answers it, and **what to copy** —
the list of fields and methods someone lifting that screen needs to take with them.

Do not leave commented-out code.

## The two readers are not interchangeable

They share one API and differ in what they answer. The RF88 has triggers, a battery, a buzzer and Q;
the built-in reader has none of those and has a license instead. Values differ too — search mode
takes three values on the RF88 and two on the built-in reader, and link profile numbering is
unrelated between them.

So: **value lists belong to the reader's own screen and are never shared.** `isSupported(key)` says
whether a reader has a setting; it cannot say which values that setting takes.

Which reader answers what is stated per method in the SDK reference — see below.

## Where the SDK is documented

`sdk-reference.html` in the repository root is the full SDK reference: every method, which readers answer
it, the constants, the result codes and the reader-owned value tables. It is written from the SDK
source and is the authority. This app's comments explain *this app*; the reference explains the SDK.

The `.aar` carries no sources, so the reference is the only way to read the SDK's contract.

`getting-started.html` is the installation guide for every Point Mobile SDK — access token, Maven
repository and dependency lines — and is maintained on its own. The RFID-specific setup notes stay in
`sdk-reference.html`.

## Build

Java 11, `minSdk` 26. The SDK is `ex.dev.sdk:rfid:1.0.1` from Point Mobile's private Maven
repository. `settings.gradle.kts` declares the repository and reads the access token from
`local.properties` (`pointmobileMavenToken`), then from the Gradle property of the same name, and
stops the sync with a message when neither is set. Never write the token into a tracked file. The
SDK's POM brings in the Kotlin standard library.

Do not raise `minSdk` to suit one reader. The RF88 is used with older devices than the built-in
reader ships on.
