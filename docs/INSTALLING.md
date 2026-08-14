# Installing and updating on your own phone

This app is not on the Play Store and is not meant to be. It is side-loaded: you build the
APK yourself and install it over USB. That works indefinitely and costs nothing, but it
puts one responsibility on you that Play would otherwise handle - keeping the signing key.
Read [The signing key](#the-signing-key) before you start logging real history.

Commands are given for PowerShell on Windows. On macOS or Linux use `./gradlew` and forward
slashes; nothing else differs.

## One-time phone setup

1. Settings > About phone > tap **Build number** seven times.
2. Back out to Settings > System > **Developer options** > enable **USB debugging**.
3. Connect the phone to the computer. Use a cable that carries data; charge-only cables are
   the usual reason a phone never appears.
4. The phone shows **Allow USB debugging?**. Tick "Always allow from this computer" and
   accept. If no prompt appears, open the USB notification in the shade and set the mode to
   **File transfer**, which usually triggers it.

Confirm the computer can see it:

```powershell
adb devices
```

The phone should be listed as `device`. `unauthorized` means step 4 has not been accepted
yet. An empty list means the cable, the port, or USB debugging.

`adb` lives in `platform-tools` inside the Android SDK. If it is not on your `PATH`, call it
by full path or add that directory to `PATH`.

## First install

From the repository root:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat installDebug
```

That builds and installs in one step. To build and install separately - useful when the
phone is not attached at build time:

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The debug APK is large (around 19 MB) because it is unminified and carries debug
information. That is expected and does not affect how the app runs.

Without a cable, copy `app-debug.apk` to the phone by any means and tap it in the Files app.
Android will ask you to allow "install unknown apps" for whichever app you opened it from,
and will warn that the source is unverified. Both are normal for anything not from Play.

## Updating

Rebuild and install again. The same command does it:

```powershell
.\gradlew.bat installDebug
```

This is an in-place upgrade, not a fresh install: **your habits and history are preserved.**
No uninstall, no re-adding habits.

An update succeeds when both of these match the installed app:

- the `applicationId` (`com.chainhabits.app`), and
- the **signing key**.

Nothing else matters. `versionCode` does not need bumping for `adb install -r` or
`installDebug`; Android only refuses to move *backwards* to a lower `versionCode`.

If either the id or the key differs, Android treats the APK as a different app and refuses
the update. The only way in from there is to uninstall, and **uninstalling deletes the
database**. This is why the key matters more than anything else in this document.

## The signing key

Android identifies an app by `applicationId` **plus signing certificate**. An update must be
signed by the same key as the install it replaces - forever, with no override, no recovery
path, and no support channel. Lose the key and the installed app can never be updated again;
you can only uninstall and start over with empty history.

By default a debug build is signed with the automatically generated debug keystore:

```
~/.android/debug.keystore          (Windows: %USERPROFILE%\.android\debug.keystore)
alias: androiddebugkey   store password: android   key password: android
```

Modern Android Studio generates these valid for 30 years, so expiry is not the risk. Check
yours if you want to be sure:

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -alias androiddebugkey
```

The risk is **loss**. That file sits outside this repository, outside version control, and
outside most backup setups. Reinstalling Windows, wiping the user profile, moving to a new
machine, or letting the tooling regenerate it all end the same way: no more updates.

Pick one of the following before you accumulate history worth keeping.

### Option 1 - back up the debug keystore

Copy `%USERPROFILE%\.android\debug.keystore` somewhere durable: a password manager, an
encrypted drive, a private cloud folder. **Not this repository** - it is gitignored
precisely because the repo is public, and that rule has no negated exceptions so a stray
keystore cannot be re-included by accident.

That is the whole task. Everything above keeps working, and restoring the file onto a new
machine restores your ability to update.

### Option 2 - use a real release keystore

Generate a dedicated release key, wire it into `signingConfigs`, and install a release build
instead. This gives a smaller and faster app, a key that is a deliberate artifact rather
than an accident of tooling, and it is the prerequisite for any tag-triggered release
workflow later.

The cost is one uninstall and reinstall, because the signing key changes. **That cost is
zero today and grows with every day of history you log**, so if you are going to do it, do
it before you start using the app in earnest.

Whichever option you choose, the keystore file and its passwords must never be committed.
`.gitignore` already covers `*.jks`, `*.keystore`, `keystore.properties`, `signing.properties`
and `secrets.properties`.

## Verifying an install worked

```powershell
adb shell pidof com.chainhabits.app     # non-empty means it is running
adb logcat -d -b crash -t 200           # empty means it did not crash
```

To watch a crash as it happens, clear the buffer first with `adb logcat -c`, reproduce, then
read it back.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `adb devices` lists nothing | Charge-only cable, USB debugging off, or a flaky port. Try another cable first. |
| Device shows as `unauthorized` | The debugging prompt has not been accepted. Unplug, replug, watch the phone screen. |
| Prompt never appears | Set the USB mode to **File transfer** from the notification shade. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app is signed with a different key. See [The signing key](#the-signing-key). Uninstalling fixes it but destroys the database. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The APK has a lower `versionCode` than what is installed. Bump `versionCode` in `app/build.gradle.kts`. |
| `INSTALL_FAILED_USER_RESTRICTED` | Some OEM skins (notably Xiaomi and Samsung) require "Install via USB" to be enabled in Developer options. |
| App installs but instantly closes | Check `adb logcat -b crash`. A `minSdk` mismatch is not possible here (26), so it is a real crash worth reporting. |

## What happens to your data

The database is separate from the APK. It survives updates and is removed only by
uninstalling or by clearing app storage. Android's backup also copies it to your Google
account; see [Your data](../README.md#your-data) for exactly what is included and how to
turn it off.

Backup is a genuine safety net for a lost phone, but it is not instant and not a substitute
for keeping the signing key. Restoring a backup onto an app you can no longer update leaves
you exactly where you started.
