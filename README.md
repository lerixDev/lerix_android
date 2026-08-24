# Lerix (native Android / Kotlin)

Native Android SDK for apps with no Flutter involved — feature parity with
the `atelerix` Flutter plugin and the native iOS `Lerix` package:
app/device registration, error/crash reporting, and push notifications
(Firebase Cloud Messaging).

## Install

Add the `:lerix` module to your project (published artifact coming later —
for now, include it as a local/composite build):

```kotlin settings.gradle.kts
include(":lerix")
project(":lerix").projectDir = File("../lerix_android/lerix")
```

```kotlin app/build.gradle.kts
dependencies {
    implementation(project(":lerix"))
}
```

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
| `getRegisteredTokenId()` | Atelerix's own id for this registered device | The value for the dashboard's/REST API's device-targeting field |
| `getDeviceToken()` | The raw FCM registration token | Diagnostics, or calling FCM yourself |
| `getDeviceId()` | `Settings.Secure.ANDROID_ID` — a local system identifier | Local diagnostics only; **not** what the dashboard's send flow expects |

## Requirements

- Android 7.0+ (API 24)
- Kotlin 1.9+, AGP 8.5+
- For push notifications, **the same Firebase project** needs two separate
  artifacts uploaded to two separate places — they're different files for
  different halves of the pipeline, and both are required:
  1. **`google-services.json`** (Firebase Console → Project Settings →
     General → your Android app) — applied to your **app module** (via the
     Google Services Gradle plugin, already wired into this repo's example
     module) so the client can actually register with your project and mint
     a token.
  2. **A service account key** (Firebase Console → Project Settings →
     Service Accounts → Generate new private key) — uploaded to the
     **Atelerix dashboard**, under this project's **Notifications →
     Settings**, so the backend can authenticate as your Firebase project
     to send.
  A token minted under a different Firebase project than the one whose
  service account is on file fails with FCM's `SenderId mismatch` — there's
  no shared/bundled Atelerix Firebase project that can stand in for this;
  each Atelerix project must use its own.

  This was tried two ways and confirmed not to work, so don't re-attempt
  either without new information: (1) a hardcoded shared Firebase project
  baked into the SDK — real-device tested, fails with `SenderId mismatch`
  since the backend always sends via the per-project service account, never
  a shared one; (2) fetching this project's real Sender ID from the backend
  (`GET /plugin/notifications/sender-id`) and constructing `FirebaseOptions`
  with it plus a placeholder API key — real-device tested, fails with
  `FIS_AUTH_ERROR` because modern `firebase-messaging` always goes through
  Firebase Installations Service, which requires a genuinely valid,
  project-authorized API key to create an installation at all. The backend
  only stores the service account (server-side admin credentials with no
  API key field), not a client API key, so there's nothing valid to fetch.
  A real `google-services.json` is the only way to get a real one.

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
