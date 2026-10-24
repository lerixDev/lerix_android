# Lerix (native Android / Kotlin)

Native Android SDK for apps with no Flutter involved — feature parity with
the `lerix` Flutter plugin and the native iOS `Lerix` package:
app/device registration, error/crash reporting, push notifications
(Firebase Cloud Messaging), in-app feedback with screenshots, NPS surveys and
product analytics.

## Install

Published via [JitPack](https://jitpack.io/#lerixDev/lerix_android), built directly
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
    implementation("com.github.lerixDev.lerix_android:lerix:1.5.0")
}
```

`lerix` is the all-in-one artifact. To leave push out (no Firebase, no
`POST_NOTIFICATIONS`, no FCM service in your manifest), depend on the parts
instead:

| Artifact | Contents |
|---|---|
| `lerix` | `lerix-core` + `lerix-messaging` |
| `lerix-core` | Crash reporting, users, analytics, feedback, surveys, release control |
| `lerix-messaging` | Push notifications (FCM). Brings `lerix-core` with it |

```kotlin
implementation("com.github.lerixDev.lerix_android:lerix-core:1.5.0")
```

`Lerix.notifications` finds `lerix-messaging` on its own when it's there; with
only `lerix-core`, its calls throw `ERR_MESSAGING_NOT_INSTALLED`.

Replace `1.5.0` with the Git tag (or commit hash) you want to pin to — see
the [releases page](https://github.com/lerixDev/lerix_android/releases) for
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

### 4. Filtering errors

Drop noisy errors or scrub data before it leaves the device. Pass these to
`Lerix.initialize()`:

```kotlin
Lerix.initialize(
    context = this,
    apiKey = "YOUR_PROJECT_API_KEY",
    projectId = "YOUR_PROJECT_ID",
    // Dropped when the message contains any of these (case-insensitive).
    ignoreErrors = listOf("Read timed out", "Software caused connection abort"),
    // Dropped when the message matches any of these regexes (containsMatchIn).
    ignoreErrorPatterns = listOf(Regex("^java\\.net\\.\\w+Exception")),
    // Runs after the ignore lists. Return the event (optionally modified) to
    // send it, or null to drop it.
    beforeSend = { event ->
        if (event.metadata?.get("debugOnly") == true) null
        else event.copy(message = event.message.replace(Regex("token=\\S+"), "token=[redacted]"))
    },
)
```

`LerixErrorEvent` has `message`, `stack`, `type`, `severity` and `metadata`.
Filtering applies to every error the SDK sends: `Lerix.throwError` and
uncaught crashes (checked when the crash is sent on the next launch). The
ignore lists run first, then `beforeSend`, which is called on a background
thread. If `beforeSend` throws, the original event is sent and the exception is
swallowed (logged in `debugMode`). Empty or whitespace-only `ignoreErrors`
entries are skipped. The SDK ships no built-in ignore rules;
default noise filtering happens on the server.

### 5. Link installs to your users

Call `setUser` after login with your own user id, and `clearUser` on logout.
Your backend can then send to all of a user's devices with `externalUserIds`.

```kotlin
lifecycleScope.launch {
    // After login
    runCatching { Lerix.setUser(user.id, identityHash = hashFromYourServer) }

    // On logout
    runCatching { Lerix.clearUser() }
}
```

Both are `suspend` functions that throw if the backend rejects the call, so
catch the error. The id is stored in encrypted preferences and re-sent
automatically if the install is registered again; called before `initialize`
finishes registering, it is queued. `identityHash` is only needed when the
project requires identity verification: it is the hex HMAC-SHA256 of the user
id keyed with the project's identity secret. Compute it on your server and
pass it to the app; never ship the identity secret in the app.

### 6. Analytics

Zero-config once `initialize` runs from `Application.onCreate()`:
`$first_open`, `$session_start` / `$session_end` (a new session after 30 min
in the background), `$app_update` and a `$screen_view` per resumed Activity
(its class name). Custom events:

```kotlin
Lerix.track("checkout_started", mapOf("items" to 3, "coupon" to "EID"))
Lerix.screen("Checkout")            // manual screen view (Compose, custom navigation)
Lerix.analytics.flush()             // send now
Lerix.analytics.setEnabled(false)   // opt-out: stops recording, clears the queue (persisted)
```

Names match `^[A-Za-z][A-Za-z0-9 _.:-]{1,63}$` (`$` names are reserved); at most
25 props with string (≤ 1024 chars), number, boolean or null values. Invalid
events and props are dropped locally and logged in `debugMode`; `track` never
throws. Events are stored on disk (`noBackupFilesDir/lerix/outbox`), batched
(first send 5 s after start, then every 30 s, at 50 pending and on background)
and kept for at most 7 days / 1 MB offline. A process started in the background
(an FCM data message, a service) is not counted as an app start or a session. Error reports carry the current
`sessionId`, which powers crash-free sessions.

```kotlin
Lerix.initialize(
    context = this, apiKey = "…", projectId = "…",
    environment = "staging",   // default: dev for debuggable builds/emulators, else prod
    analytics = LerixAnalyticsOptions(
        enabled = true, autoSessions = true, autoScreens = true,
        trackFragments = false,   // true: also a $screen_view per resumed AndroidX Fragment
    ),
)
```

### 7. In-app feedback

```kotlin
Lerix.feedback.show(activity) { result ->
    if (result.submitted) Log.d("App", "feedback ${result.clientId}")
}

// Options override the project's remote config (labels, email, screenshot modes):
Lerix.feedback.show(
    activity,
    LerixFeedbackOptions(
        email = LerixFieldMode.REQUIRED,
        prefillEmail = user.email,
        custom = mapOf("plan" to "pro"),
    ),
)

Lerix.feedback.setUserEmail(user.email)                 // prefill for every flow
Lerix.feedback.enableShakeToReport(application)         // opt-in
Lerix.markConfidential(cardNumberView)                  // black box in screenshots
```

The current screen is captured before the UI appears (PixelCopy on API 26+,
so maps and video are included; `View.draw` before that). The flow is message →
labels → screenshots (tap one to draw on it with 4 colours, 3 pen sizes,
undo/redo; "Add screenshot" lets the user go back into the app and capture
another screen) → email → review with "Show details" → sent. Screens with
`FLAG_SECURE` are never captured. Feedback is written to the outbox before it
is sent, so it survives offline use and app restarts; screenshots are uploaded
first and checkpointed. Labels, email/screenshot modes and limits come from the
dashboard (`GET /plugin/feedback/config`, cached 24 h).

Headless, for your own UI:

```kotlin
lifecycleScope.launch {
    val result = Lerix.feedback.submit(
        message = "Checkout button does nothing",
        labels = listOf("bug"),
        email = "user@example.com",
        screenshots = listOf(pngBytes),   // png / jpeg / webp, ≤ 5 MB each
    )
}
```

The SDK declares an internal, non-exported `LerixFeedbackActivity` through
manifest merging; nothing to add to your manifest.

### 8. NPS and rating surveys

```kotlin
lifecycleScope.launch {
    val shown = Lerix.surveys.maybeShowNps(activity)   // or force = true while testing
}
// Without coroutines:
Lerix.surveys.maybeShowNps(activity) { shown -> }
```

The question, follow-ups, thanks text and rules (minimum app starts, initial
delay, sample rate, frequency, dismiss cooldown) come from the dashboard. The
eligibility check runs on the device and is identical in every Lerix SDK; in
`debugMode` the log says why a survey was skipped and when it is next due.
Picking a score counts as an answer; closing the sheet before that (×, Back or
a tap outside) counts as a dismiss (shorter cooldown than an answer).

The sheet's look (button shape, accent colour, colour by score group, the end
labels) is set per survey in the dashboard too. A survey accent wins over
`Lerix.setAccentColor` and your theme's `colorPrimary`. The 0–10 scale always
reads left to right, also in Arabic.

#### Rating surveys (CSAT)

```kotlin
// After a moment you care about, e.g. a finished checkout.
lifecycleScope.launch {
    Lerix.surveys.showRating(activity, "checkout")
    Lerix.surveys.showRating(activity, "checkout", force = true, custom = mapOf("orderId" to id)) // a "Rate us" button
}
// Without coroutines:
Lerix.surveys.showRating(activity, "checkout") { shown -> }
```

A rating survey is created in the dashboard with a key, a question and 2–10
choices: stars, hearts, emoji/faces, 👍/👎 or numbers, each with its own value,
label, colour and group (negative / neutral / positive). It is only shown when
you call `showRating(key)`, and still at most once per `frequencyDays` per
install (0 = no limit) and not again for `dismissCooldownDays` after a dismiss.
The row follows the layout direction (mirrored in Arabic when your app declares
`android:supportsRtl="true"`). Every choice has a TalkBack description.

#### Your own UI (headless)

```kotlin
lifecycleScope.launch {
    val prompt = Lerix.surveys.dueRating("checkout") ?: return@launch // or dueNps()
    prompt.shown()                                   // when it is on screen
    // prompt.question, prompt.choices (id, value, label, emoji, color, group), prompt.appearance
    prompt.answer(choice.id)                         // NPS: prompt.answer(9)
    prompt.answer(choice.id, "Fast and easy")        // add the follow-up text
    // or prompt.dismiss()
}
```

`prompt.groupOf(answer)`, `prompt.followUp(group)` and `prompt.thanks(group)`
give the texts for the next steps. `answer` returns false when the answer
doesn't fit the survey. The prompt records exactly what the built-in sheet
would, so results and cadence are the same.

### 9. Breadcrumbs

The last 50 breadcrumbs (screen changes plus your own) are attached to
feedback:

```kotlin
Lerix.addBreadcrumb("Added item to cart", category = "ui", data = mapOf("sku" to "A1"))
```

### 10. Release control (updates, forced updates, maintenance)

Rules are set in the dashboard (Release control). The SDK reads them from `GET /plugin/app-config` at startup, on
foreground (when `ttlSeconds` has passed), every `ttlSeconds` while in the foreground if it is 60 s or less, and on a
silent `config_refresh` push. The last response is cached, so a required update or a maintenance window also applies
offline. Maintenance lifts at its `endsAt` without network.

Built-in UI is on by default: a full-screen maintenance screen ("Try again"), a full-screen required-update screen
("Update"), and an update dialog ("Update", "Later", "Skip this version"). Back on the full-screen screens sends the
app to the background. Strings are in English and Arabic.

```kotlin
Lerix.initialize(
    context = this, apiKey = "...", projectId = "...",
    appControl = LerixAppControlOptions(
        enabled = true,
        ui = true,                  // false: draw your own screens from Lerix.appControl.state
        usePlayInAppUpdate = false, // true: required updates use a Play IMMEDIATE in-app update
    ),
)

// Custom UI
lifecycleScope.launch {
    Lerix.appControl.state.collect { state ->
        // state.maintenance, state.update (state, title, message, storeUrl…), state.isBlocking
    }
}
Lerix.appControl.openStore(activity) // "Update"
Lerix.appControl.later()             // hides it for remindAfterHours
Lerix.appControl.skip()              // hides an "available" update until latestVersion changes
val fresh = Lerix.appControl.refresh() // suspend; forces a fetch
```

`usePlayInAppUpdate` needs `implementation("com.google.android.play:app-update:2.1.0")` in your app and an install
from Google Play. Without either, "Update" opens the store link.

### UI: language, dark mode, colours

The feedback and survey UI ship in English and Arabic and follow the system
light/dark mode. Layouts use start/end, so they mirror in Arabic when your app
declares `android:supportsRtl="true"`. The accent colour is your theme's
`colorPrimary`; override it with `Lerix.setAccentColor(0xFF6E56CF.toInt())`
or per flow with `LerixFeedbackOptions(accentColor = …)`. Any `lerix_*` string
can be overridden by defining the same name in your app's resources.

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
  Firebase project (`lerix-b7899`), with this Lerix project's real
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
