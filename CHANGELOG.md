# Changelog

## 1.5.0

### Changed
- **Push is now its own artifact.** `lerix` stays the all-in-one artifact (nothing changes for apps that use it);
  `lerix-core` is everything but push, and `lerix-messaging` is push (Firebase Cloud Messaging). An app on
  `lerix-core` gets no Firebase, no `POST_NOTIFICATIONS` permission and no FCM service or receiver. JitPack builds
  all three from the same tag.
- `Lerix.notifications` is typed `LerixNotificationsApi` (same methods). Core finds `lerix-messaging` at runtime;
  without it, push calls throw `ERR_MESSAGING_NOT_INSTALLED`. `LerixPermissionStatus` and
  `LerixNotificationPayload` stay in `com.lerix.sdk.notifications`.

### Added
- **Rating (CSAT) surveys**: `Lerix.surveys.showRating(activity, key, force, custom)` (suspend and
  callback) shows the rating survey with that dashboard key when it is due. Stars, hearts, emoji,
  👍/👎 or numbers, each choice with its own value, label, emoji, colour and group; follow-up question
  and thanks per group. The row mirrors in RTL; every choice has a TalkBack description. Rating
  cadence: no first-showing spread, `frequencyDays` 0 means no gap; defaults 30 / 0 / 0 / 7 days.
- **Headless surveys**: `Lerix.surveys.dueNps()` / `dueRating(key)` return a `LerixSurveyPrompt`
  (`question`, `choices`, `appearance`, `groupOf`, `followUp`, `thanks`, `shown()`,
  `answer(scoreOrChoiceId, message?)`, `dismiss()`) that records exactly what the built-in sheet does.
- **NPS appearance from the dashboard**: button shape (rounded, circle, square, pill), accent
  (beats `Lerix.setAccentColor` and `colorPrimary`), colour by group (0–6 / 7–8 / 9–10) and the end
  labels, or no labels.
- `maybeShowNps` takes `custom`, stored with the response.

### Changed
- The NPS 0–10 scale always reads left to right, also in RTL (as on the other platforms).
- `GET /plugin/surveys/config` is read lossily: a bad entry is skipped, a bad field falls back to its
  default; unknown survey types are ignored.
- Survey responses are sent as full snapshots (shown time, custom data and answer in every upsert),
  and right after each answer, message or dismiss instead of when the sheet closes.

### Fixed
- The NPS end labels sat in the middle of the sheet for Arabic text on an LTR scale (found in the
  emulator E2E test); they now use absolute left/right sides.

## 1.4.0 (unreleased)

### Added
- **Release Control**: `Lerix.appControl` (`state` as a `StateFlow`, `currentState`,
  `addListener`/`removeListener`, `refresh()`, `openStore()`, `later()`, `skip()`), configured with
  `Lerix.initialize(..., appControl = LerixAppControlOptions(enabled, ui, usePlayInAppUpdate))`.
  Fetches `GET /plugin/app-config` at launch, on foreground, every `ttlSeconds` while it is 60 s or
  less, and on a silent `config_refresh` push, with ETag/304 and an offline cache.
- Built-in screens: a full-screen maintenance screen ("Try again", expected return time; Back sends
  the app to the background), a full-screen required-update screen (release notes, "Update" opens the
  store or, with `usePlayInAppUpdate` and a Play install, a Play IMMEDIATE update), and a soft update
  dialog (Update / Later / Skip this version). English and Arabic, with RTL.
- **SDK kill switch**: when Lerix switches the SDK off for a version, every SDK request stops except
  `app-config`. Queued items are kept and sent once the switch lifts.
- Events: `$update_prompt_shown`, `$update_prompt_action`, `$maintenance_shown`, `$config_applied`.

### Fixed
- Polling restarts when the app returns to the foreground within `ttlSeconds`, so a maintenance
  screen lifts by itself after Back/Home and reopen (found in the emulator E2E test).
- Silent pushes (`config_refresh`, unsend) are handled and never shown as a blank notification.
