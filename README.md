# RfidSdkSample

A sample app for the Point Mobile RFID SDK (`ex.dev.sdk.rfid`). It drives both readers the SDK
supports — the **RF88** that attaches to a device over Bluetooth, Wire or NFC Tap-to-Pair, and the
**MF52** built into a device — through one `RfidManager`.

Nobody has to read the whole app. Open the screen that does what you need and copy that one file.

## What is inside

| Screen | Reader | What it demonstrates |
|---|---|---|
| Bluetooth | RF88 | Discovery, runtime permissions, `connect(address)` |
| Wire | RF88 | `connect()` over the cradle, with no address |
| NFC Tap-to-Pair | RF88 | Reading the address off the reader's NFC mark, then connecting |
| Inventory | RF88 · MF52 | `inventory()` / `stop()`, tag reports, the RF88 triggers |
| Inventory and read | RF88 | `inventoryAndRead()` — a memory block from every tag in the same pass |
| Tag Operations | RF88 · MF52 | `read` · `write` · `lock` · `kill`, the Select mask, result codes |
| Settings | RF88 · MF52 | Every `getX` / `setX` pair, staged and applied together |
| RFID Information | RF88 | Battery, firmware and the identity the reader reports |
| Connection and License | MF52 | `connectInternal()` and the license state |

Every screen carries a log panel at the bottom. It records what was sent and what came back, so a
question about behaviour can be asked with one screenshot.

## Requirements

| Item | Value |
|---|---|
| Android Studio | a release that supports Android Gradle Plugin 9.4 |
| Gradle | 9.6 |
| JDK | 25 (the Gradle toolchain downloads it) |
| compileSdk · targetSdk | 37 |
| minSdk | 26 |
| Language | Java 11 |

## Add the SDK

The SDK comes from Point Mobile's private Maven repository, so Gradle needs the access token you get
from the Point Mobile portal. Set it up in three steps. This project already has steps 2 and 3, so
building it needs only step 1.

### Step 1: Put the token in `local.properties`

Open `local.properties` in the project's root folder — the file where Android Studio keeps `sdk.dir` —
and add the token from the portal.

```properties
# Add the access token below the existing sdk.dir entry.
pointmobileMavenToken=<your token>
```

The file stays on your machine; `.gitignore` keeps it out of commits. On a build server, set the
environment variable `ORG_GRADLE_PROJECT_pointmobileMavenToken` instead.

### Step 2: Declare the repository in `settings.gradle.kts` or `settings.gradle`

Add the token lookup and the `maven { … }` block to `dependencyResolutionManagement`; with Kotlin DSL,
also add the import at the top of the file. The lookup reads the token from `local.properties` first,
then from the Gradle property of the same name. If neither is set, Gradle sync stops and says what is
missing.

Kotlin DSL (`settings.gradle.kts`):

```kotlin
// Add this import at the top of the file.
import java.util.Properties

// Keep the existing pluginManagement { … } and plugins { … } blocks above this code.

// Read the token from local.properties, falling back to the Gradle property of the same name.
// On a build server, set ORG_GRADLE_PROJECT_pointmobileMavenToken to provide this property.
val localProperties = Properties().apply {
    val file = settingsDir.resolve("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val pointmobileMavenToken: String =
    (localProperties.getProperty("pointmobileMavenToken")
        ?: providers.gradleProperty("pointmobileMavenToken").orNull)
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Add pointmobileMavenToken=<your token> to local.properties.")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Add the Point Mobile Maven repository using the access token configured above.
        maven {
            url = uri("https://raw.githubusercontent.com/PointMobileSDD/maven/master")
            credentials(HttpHeaderCredentials::class.java) {
                name = "Authorization"
                value = "token $pointmobileMavenToken"
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
    }
}
```

Groovy (`settings.gradle`):

```groovy
// Keep the existing pluginManagement { … } and plugins { … } blocks above this code.

// Read the token from local.properties, falling back to the Gradle property of the same name.
// On a build server, set ORG_GRADLE_PROJECT_pointmobileMavenToken to provide this property.
def localProperties = new Properties()
def localFile = new File(rootDir, 'local.properties')
if (localFile.isFile()) localFile.withInputStream { localProperties.load(it) }
def pointmobileMavenToken = (localProperties.getProperty('pointmobileMavenToken')
        ?: providers.gradleProperty('pointmobileMavenToken').getOrNull())?.trim()
if (!pointmobileMavenToken) {
    throw new GradleException('Add pointmobileMavenToken=<your token> to local.properties.')
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Add the Point Mobile Maven repository using the access token configured above.
        maven {
            url = 'https://raw.githubusercontent.com/PointMobileSDD/maven/master'
            credentials(HttpHeaderCredentials) {
                name = 'Authorization'
                value = "token $pointmobileMavenToken"
            }
            authentication {
                header(HttpHeaderAuthentication)
            }
        }
    }
}
```

Older projects declare repositories in the root `build.gradle` under `allprojects { repositories { … } }`
instead of in the settings file. There, put the Groovy lines in the root `build.gradle`: the token lookup
at the top, and the `maven { … }` block inside `allprojects { repositories { … } }`.

### Step 3: Add the dependency in `app/build.gradle.kts` or `app/build.gradle`

Kotlin DSL (`app/build.gradle.kts`):

```kotlin
dependencies {
    // Add this line.
    implementation("ex.dev.sdk:rfid:1.2.0")
}
```

Groovy (`app/build.gradle`):

```groovy
dependencies {
    // Add this line.
    implementation 'ex.dev.sdk:rfid:1.2.0'
}
```

On the next sync, Gradle downloads the AAR together with the Kotlin standard library it needs.

Keep the token out of files you commit. Written into `settings.gradle.kts` or any other file in the
repository, it lets anyone who can read that file download the SDK.

## Build and run

1. Put your access token in `local.properties` (step 1 of [Add the SDK](#add-the-sdk)).
2. Open this folder in Android Studio and let Gradle sync.
3. Build and install on a device with an RF88 attached, or on one with the MF52 built in.
4. Pick the reader on the first screen and connect before opening the rest.

The MF52 reader does not come up while the device is charging, and a USB cable to your computer
charges it. After installing, unplug the cable and then connect the MF52. With the cable in,
`connectInternal()` returns `E0`.

Only one reader can hold the link at a time. Connecting one while the other is up is refused, and the
returned code says so.

## Copy a screen, not the app

Each screen is one Activity and one layout, and carries everything it needs: its `RfidManager`
reference, its executor, its listener registration and its clean-up. The screens repeat each other on
purpose — a screen that shared a base class could not be lifted out on its own.

The comment block at the top of each Activity lists the SDK calls it demonstrates and what to take
with it.

Three rules hold in every screen, and they are the ones to carry across:

1. Synchronous SDK methods block until the reader answers. Call them off the main thread.
2. Listener callbacks arrive on an SDK thread. Hand anything that touches a View to the main thread.
3. Do not call a synchronous SDK method from inside a listener callback.

## Where the SDK is documented

[sdk-reference.html](docs/sdk-reference.html) documents every SDK method, supported readers, constants,
result codes and reader-specific value tables. Open it in a browser. All SDK documentation is in
English. The `.aar` ships without sources, so this reference describes the SDK contract.

[getting-started.html](docs/getting-started.html) is the installation guide shared by every Point Mobile SDK: the access token,
the Maven repository and the dependency line. It can be shared on its own.

`docs/AGENTS.md` describes how this repository is put together, for anyone — or anything — changing
it.

## Support

Before reporting a problem, check the log panel on the screen where it happened and note the result
code. With that, your Android Studio and JDK versions, and the device model, contact your Point
Mobile integration support representative.
