# Lerix (native Android / Kotlin)

Native Android SDK for apps with no Flutter involved — feature parity with
the `lerix` Flutter plugin and the native iOS `Lerix` package:
app/device registration, error/crash reporting, and push notifications
(Firebase Cloud Messaging).

## Install

Published via [JitPack](https://jitpack.io/#lerix/lerix-android), built directly
from this repo's tagged releases — no separate publishing step required
beyond tagging a release on GitHub.

Add the JitPack repository in `settings.gradle.kts`:

```kotlin settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency in `app/build.gradle.kts`:

```kotlin app/build.gradle.kts
dependencies {
    implementation("com.github.lerix.lerix-android:lerix:1.0.0")
}
```

Replace `1.0.0` with the Git tag (or commit hash) you want to pin to — see
the [releases page](https://github.com/lerix/lerix-android/releases) for
available versions.

Push notifications need Firebase configured — see **Requirements** below.
Everything else (registration, error reporting, crash capture) works with
zero Firebase setup.

## Setup

### 1. Initialize

Call `Lerix.initialize()` once, from your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lerix.initialize(
            context = this,
            apiKey = "YOUR_PROJECT_API_KEY",
            projectId = "YOUR_PROJECT_ID",
            debugMode = true,
        )
    }
}
```

Register it in your manifest:

```xml
<application android:name=".MyApp" ...>
```

### 2. Push notifications

No manifest changes needed — the SDK bundles its own
`FirebaseMessagingService` and tap-receiver via manifest merging.

```kotlin
lifecycleScope.launch {
    val granted = Lerix.notifications.requestPermissions()
}

Lerix.notifications.setOnNotificationReceived { payload ->
    // Fired while the app is in the foreground.
}

Lerix.notifications.setOnNotificationTapped { payload ->
    // Fired when the user taps the notification — including one that
    // launched the app from a fully closed state.
}
```

On Android 13+, request the `POST_NOTIFICATIONS` runtime permission from an
Activity first (`Lerix.notifications.requestNotificationPermissionLauncher`),
then call `requestPermissions()` once the user responds.

### 3. Report errors

```kotlin
Lerix.throwError(
    "Something went wrong",
    stack = Thread.currentThread().stackTrace.map { it.toString() },
    type = BugType.RUNTIME_ERROR,
    severity = BugSeverity.HIGH,
)
```

Uncaught exceptions on any thread are reported automatically —
`Lerix.initialize()` installs a crash handler by default (pass
`enableCrashReporting = false` to opt out). A crash can't do async network
I/O, so it's persisted to disk and reported on the *next* launch, tagged
`type: crash, severity: critical`.

## Device identifiers

| Method | Returns | Use it for |
|---|---|---|
| `getRegisteredTokenId()` | Lerix's own id for this registered device | The value for the dashboard's/REST API's device-targeting field |
| `getDeviceToken()` | The raw FCM registration token | Diagnostics, or calling FCM yourself |
| `getDeviceId()` | `Settings.Secure.ANDROID_ID` — a local system identifier | Local diagnostics only; **not** what the dashboard's send flow expects |

## Requirements

- Android 7.0+ (API 24)
- Kotlin 1.9+, AGP 8.5+
- For push notifications, **no `google-services.json` and no Google
  Services Gradle plugin are required** — matching the Flutter plugin's
  approach exactly. The SDK lazily creates a secondary, *named* `FirebaseApp`
  (`LERIX_FCM_APP`, see `LerixFirebase.kt`) using Lerix's own shared
  Firebase project (`atelerix-44685`), with this Lerix project's real
  Sender ID fetched dynamically from the backend
  (`GET /plugin/notifications/sender-id`) layered in via `setGcmSenderId()`
  for delivery routing — the `projectId`/`applicationId`/`apiKey` are a
  fixed, hardcoded triple that must be used verbatim (they're a real,
  registered Firebase app; a synthesized `applicationId` — even one that
  looks more internally consistent — isn't registered and gets rejected by
  Firebase Installations with a `403 PERMISSION_DENIED`/`FIS_AUTH_ERROR`,
  confirmed via real side-by-side device testing against the Flutter
  plugin).
  For the backend to actually *send* to devices, upload your Firebase
  project's own **service account key** (Firebase Console → Project
  Settings → Service Accounts → Generate new private key) to the
  **Lerix dashboard**, under this project's **Notifications →
  Settings** — this is unrelated to the client-side setup above and is
  the only Firebase-related step a developer needs to take.

## Notes

- Device/user identity is stored via `EncryptedSharedPreferences`
  (AndroidX Security), the Android counterpart to the iOS SDK's Keychain
  wrapper and the Flutter SDK's `flutter_secure_storage`.
- Backend routes and payload shapes are identical to the Flutter/iOS/Web
  SDKs — bugs reported from a native Android app show up in the dashboard
  exactly like ones from any other platform.
- Custom notification sound: pass the raw resource filename **without**
  extension via the `sound` data field (e.g. `notif` for
  `res/raw/notif.mp3`) — a notification channel is created per distinct
  sound value the first time it's used (Android channels are immutable
  after creation).
