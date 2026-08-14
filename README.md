# Habit Tracker

A personal Android habit tracker built around the ideas in James Clear's *Atomic Habits*.
Native Kotlin + Jetpack Compose, local-only (no account, no sync, no network).

## Design

The app is built around a visible chain of daily cells (a "mosaic") so progress is
obvious at the moment you decide whether to act.

**A solid cell always means "a good day"** — for every habit type. Habits differ in what
produces a good day, but never in how the mosaic reads.

### Habit types

| Type | Default state each day | You record |
| --- | --- | --- |
| Positive ("Cook healthy food") | not done | completions |
| Negative ("Don't buy McDonald's") | clean | slips |

The asymmetry is in the midnight rollover: an untouched positive habit becomes a **miss**,
an untouched negative habit becomes a **success**. Today's cell is provisional until the
day ends and is drawn with an outline.

### Cadence

Habits are daily or weekly. Days a habit isn't scheduled render as a faint dash, so a
weekly habit doesn't show six failures a week.

### Strictness

| Strictness | First miss | Second consecutive miss |
| --- | --- | --- |
| Standard (default) | amber, recoverable | breaks the chain |
| Strict | breaks the chain immediately | — |

Standard follows Clear's "never miss twice" rule: one gap is noise, two is the start of a
new habit. Strict is for habits where a single instance is itself the harm rather than a
data point in a trend; these lead with a days-since counter.

When a standard habit is one miss from breaking, the home screen shows a
"never miss twice" banner. That is the only nudge the app makes beyond the reminders you
set yourself.

## Build

Requires JDK 17 and the Android SDK. Neither is committed here.

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
