# Habit Tracker

[![CI](https://github.com/tomas-feith/habit_tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/tomas-feith/habit_tracker/actions/workflows/ci.yml)

A personal Android habit tracker built around the ideas in James Clear's *Atomic Habits*.
Native Kotlin + Jetpack Compose. No account, no server, and no `INTERNET` permission — the
app itself never talks to anything. Your history is backed up by Android to your own Google
account; see [Your data](#your-data) for exactly what that means and how to turn it off.

The app is built around a visible chain of cells (a "mosaic") so progress is obvious at the
moment you decide whether to act. Habit tracking works, per Clear, because it makes an
action immediately satisfying when its real payoff is months away.

## The one rule everything else follows

**A solid cell always means "a good day."** For every habit type, without exception.

Habits differ in what *produces* a good day, never in how the mosaic reads. If solid meant
"you did it" for one habit and "you failed" for another, the mosaic would stop being
glanceable, which is the only reason it exists.

## The habit model

Three independent axes. Any combination is valid.

### Polarity — what you log

| Polarity | Example | You log | An untouched period becomes |
| --- | --- | --- | --- |
| Positive | "Cook healthy food" | completions | a **miss** |
| Negative | "Don't buy McDonald's" | slips | a **success** |

That rollover asymmetry is the whole mechanic. A negative habit is clean by default and
you only ever record the slips.

### Cadence — what a cell means

| Cadence | Example | One cell is |
| --- | --- | --- |
| Daily | "Read 20 pages" | one day |
| Specific days | "Gym on Mon/Wed/Fri" | one day (off-days render as faint dashes) |
| N times per week | "Workout 3x/week" | **one week** |

For times-per-week habits no individual day can fail, only the week can fall short, so the
strip is drawn in weeks and streaks are counted in weeks. The home row shows live progress
(`2 of 3 this week`). A target of 1 ("Try a new recipe weekly") shows a plain check instead
of a one-pip row.

A weekly target reads as a **floor** for a positive habit ("at least 3x") and an
**allowance** for a negative one ("at most 2x"), which is where "Eat out at most twice a
week" comes from for free.

### Strictness — what a miss costs

| Strictness | First miss | Second consecutive miss |
| --- | --- | --- |
| Standard (default) | amber, chain intact | breaks the chain |
| Strict | breaks the chain immediately | — |

Standard implements Clear's **"never miss twice"**: one gap is noise, two is the start of a
new habit. A pure don't-break-the-chain counter is brutally fragile — a 40-day streak wiped
by one sick day is exactly when people quit — so an isolated miss stays recoverable.

Strict is for habits where the single instance *is* the harm rather than a data point in a
trend. Those lead with a days-since counter, since that number is the whole point.

### Two numbers, deliberately

- **`currentStreak`** — consecutive good periods. Resets on any miss. The honest number,
  and the headline for strict habits.
- **`chainLength`** — periods back to where the chain actually *broke*, surviving isolated
  misses. The headline for standard habits.

## Cell states

| State | Drawn as | Meaning |
| --- | --- | --- |
| `DONE` | solid | a good period |
| `MISSED_ONCE` | hollow outline | one miss, chain intact — deliberately quiet |
| `BROKEN` | solid warning colour | second consecutive miss, or any miss when strict |
| `NOT_SCHEDULED` | faint dash | carries no judgement |
| `PENDING` | thin outline | the current period, still open |

A period settles **early** once its outcome is determined: completing a positive habit
turns today solid immediately (that is the reward), and a slip marks a negative habit
immediately. A period is `PENDING` only while genuinely open.

## Architecture

```
domain/     Pure Kotlin. No Android, no database, no clock.
            Model.kt         Habit, Entry, Cell, HabitStats
            HabitEvaluator   entries -> evaluated Timeline
            Timeline         the evaluated history; slice it, never re-evaluate

data/       Room. Entities, DAO, repository, domain <-> entity mapping.

ui/         Compose. One ViewModel per screen, unidirectional state.
            components/      MosaicStrip, YearMosaic, QuotaPips
            home/            the check-off screen
            detail/          year heatmap and stats
            edit/            create and edit habits

notify/     AlarmManager reminders and their boot restore.
```

**`HabitEvaluator.timeline()` evaluates a habit's entire history, and every view is a slice
of that one result.** This is load-bearing, not an optimisation: the never-miss-twice rule
is order-dependent, so evaluating a visible window directly would restart the miss counter
at the window's left edge and paint a broken chain as merely amber. Slice with
`Timeline.since()` or `Timeline.between()`; never re-evaluate a sub-range.

Because the domain layer takes `today` as a parameter and never reads the clock, every
rollover rule is testable without mocking time.

## Your data

Everything lives in one SQLite database on the device. The app has no `INTERNET`
permission, so it cannot send your habits anywhere itself.

It does opt into **Android's backup**, which is a platform feature rather than something
the app runs: the system copies the database to your Google account, and restores it when
you set up a new phone. This is deliberate — the app is the only copy of your history, and
without it a lost phone loses everything.

The trade-off is real and worth stating plainly: a habit tracker can hold private material,
and backup means an encrypted copy sits in Google Drive rather than only on your device.
Exactly what is included is spelled out in
[`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml) (Android 12+)
and [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml) (Android 11 and below) —
the habit database and nothing else.

To make the app genuinely device-only, set `android:allowBackup="false"` in
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml). Uninstalling then destroys your
history permanently.

## Database migrations

The database holds your whole history, and Android's backup restores that same database
rather than rebuilding it from anywhere — so a bad migration corrupts the backup too.
Destructive fallback is therefore deliberately never enabled: a missing migration must fail
loudly in testing rather than quietly wipe months of tracking on a real device.

To change the schema:

1. Edit the entities and bump `HabitDatabase.VERSION`.
2. Add a `Migration(n, n + 1)` to `HabitDatabase.MIGRATIONS` with the SQL.
3. `MigrationTest` fails until the committed schema JSONs and the SQL agree.

The exported schemas under `app/schemas` are committed precisely so the migration test has
something to diff against. Do not delete them.

## Building

Requires JDK 17 and the Android SDK. Neither is committed. The SDK is located via
`ANDROID_HOME`; there is no `local.properties` (Android Lint objects to it, and it is
machine-specific anyway).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`minSdk` 26, `compileSdk`/`targetSdk` 37. AGP 9 applies the Kotlin Android plugin itself,
so the build script must **not** apply `org.jetbrains.kotlin.android` — doing so fails.

### Checks

```bash
./gradlew ktlintFormat            # auto-fix formatting
./gradlew ktlintCheck             # formatting
./gradlew detekt                  # static analysis
./gradlew testDebugUnitTest       # domain unit tests
./gradlew lintDebug               # Android Lint, warnings are errors
./gradlew connectedDebugAndroidTest   # migration tests, needs a device
```

CI runs all of these on every push and PR to `main` and uploads the reports and debug APK.
The instrumentation job boots an API 35 emulator and is gated to `main`, so PR feedback
stays fast.

Enable the pre-commit hook (ktlint + detekt on Kotlin changes) once per clone:

```bash
git config core.hooksPath .githooks
```

## Known limitations

- **Reminders are inexact alarms.** A nudge is worth nothing extra at exactly 07:00 versus
  07:12, and this avoids the exact-alarm permission and is much kinder to the battery.
- **No UI tests.** The domain layer is thoroughly unit-tested and migrations are covered on
  device, but the Compose screens are verified by eye rather than automatically.
- **No manual export.** Android's backup covers device loss and phone-to-phone transfer,
  but there is no way to get your history out as a file you own.
