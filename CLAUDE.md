# Stack

Native Kotlin + Jetpack Compose, targeting Android only. This is the default
stack for Android projects here; prefer it over React Native / Expo unless iOS
is genuinely required.

- **UI** - Compose + Material3, Navigation Compose. `androidx.glance` for the
  home-screen widget, which renders to RemoteViews and shares nothing with the
  app's own Compose UI except the domain layer.
- **Persistence** - Room via KSP. Schemas are committed under `app/schemas` and
  migrations are verified by instrumentation tests that replay them, not
  assumed.
- **Reminders** - AlarmManager, rescheduled on boot.
- **Network** - none. The app declares no `INTERNET` permission at all and must
  keep it that way; history leaves the device only through Android's own backup
  to the user's Google account.
- **Static analysis** - ktlint + detekt, plus Android lint with
  `warningsAsErrors`. `./gradlew staticAnalysis` runs what CI runs.

Release signing reads `keystore.properties`, which is gitignored and absent on
CI and on a fresh clone. That is deliberate: an unsigned release artifact is
obviously unusable, whereas one silently signed with the debug key looks fine
and then can never be updated in place. See `docs/INSTALLING.md`.

## Why native rather than Expo

The sibling `show_tracker` is React Native + Expo; the split was accidental
rather than designed. Native is the right call for this app specifically: the
Glance widget would have required dropping to native code regardless, and Room
gives versioned, tested migrations where AsyncStorage would give none.
