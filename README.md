# Runlet

Little scripts that get things done.

## Android automation scripts · 0.1.0

A native, offline Android app for writing and manually running small automation scripts. Built for the OnePlus timer request. Supports Android 11 and newer; targets Android API 36. No Tasker installation is required. This app uses its own small language, not Tasker XML, JavaScript, Python or a shell.

## Install on your phone

1. Open `Runlet-0.1.0.apk` on your OnePlus. If Android prompts, allow this specific browser/file manager to install unknown apps, then install. You can turn that allowance off afterward.
2. Open **Runlet**, select **Quick test**, and tap **Run**. Grant notification permission, then tap Run again.
3. The test takes seven seconds. Verify the gentle beeps, vibration, automatic transitions and completion notification.
4. Run it again with the screen locked. Expand its notification to test **Pause**, **Resume**, and **Stop / Reset**. The countdown in the notification is rendered by Android's chronometer.
5. In phone Settings → Apps → Runlet, allow background activity and unrestricted battery use if those settings are offered. ColorOS labels vary. Check notification volume and Do Not Disturb if sound is absent.
6. Select **French press** for the real 4:50 routine. Begin brewing when you tap Run. The first stage alerts at entry, each following stage alerts on entry, and completion alerts once.

The APK is a locally debug-signed development build, not a Play Store release. It contains no network permission and has no analytics, accounts or advertising. The source is included for inspection. Android may show its normal sideload/Play Protect prompts; keep Play Protect enabled.

## What is included

- Script editor with automatic local saving, validation and line-number errors
- Multiple saved scripts; create/delete; import/export `.routine` text files
- Foreground runner with elapsed-realtime deadlines, screen-off CPU wake lock, and one active routine
- Start, Pause, Resume, Reset; replacing a run asks before discarding it
- Persistent progress notification with native countdown and controls
- Stage and completion chimes and vibration, no speech
- Activity log
- Launcher shortcut button: opens that saved script, then tap Run
- French press preset: Bloom 30s → Steep 210s → Press 20s → Pour 30s
- Seven-second verification routine
- Morning yoga draft, intentionally empty. Actual poses and timings from https://youtu.be/dAqQqmaI9vY have NOT been extracted.

## Script reference

Write one command per line. Blank lines and lines beginning with `#` are ignored. Durations are positive integers with one unit: `200ms`, `30s`, `5m`, `1h`. Quoted labels can contain spaces but cannot contain embedded quotes or newlines (160 characters maximum).

```text
routine "French press"
alerts both

stage "Bloom" 30s
stage "Steep" 210s
stage "Press" 20s
stage "Pour" 30s
```

```text
routine "Repeat example"
alerts both
notify "Routine starting"
repeat 3 {
  stage "Work" 1m
  stage "Rest" 20s
}
log "Finished the intervals"
```

| Command | Effect |
| --- | --- |
| `routine "Name"` | Display name; metadata outside repeat blocks |
| `alerts both` | Automatic stage and completion alerts: `both`, `sound`, `vibration`, `none`; metadata outside repeat blocks |
| `stage "Label" 30s` | Alert on entry according to `alerts`, then wait before advancing |
| `wait 2s` | Delay without an automatic stage alert |
| `chime` | Play a short notification-stream beep |
| `vibrate 200ms` | Vibrate; maximum 5 seconds |
| `notify "Message"` | Post a notification (later script messages replace the earlier one) |
| `volume 35` | Change media volume to 35% of the phone's supported volume range |
| `log "Message"` | Append to Activity |
| `repeat 3 {` ... `}` | Repeat the enclosed commands; closing brace on its own line |

Explicit `chime` and `vibrate` commands run regardless of the `alerts` setting. The alert setting controls only automatic stage/completion alerts. Completion posts a notification and follows the same alert setting. Chimes use notification volume, while `volume` changes media volume. A volume change remains in effect after the script stops; Reset does not reverse actions already performed.

Limits: 100,000 source characters, 2,000 actions after repeat expansion, repeat counts 1–100, eight nested repeat blocks, four hours total stage/wait time. Zero-duration stages and empty scripts are rejected. Edits and imports do not change a currently running script. Imported scripts are saved as drafts and are never automatically executed.

## Background behavior and limitations

The user starts the service while the app is visible. An Android `specialUse` foreground service displays progress, and a bounded partial wake lock keeps the CPU running during active timed work. Deadlines use `SystemClock.elapsedRealtime()`. Pause removes the scheduled callback and releases the wake lock; Resume sets a new deadline from the saved remainder. The notification uses the platform countdown chronometer, avoiding once-per-second notification posts. One main-looper callback chain owns run state, and a new run cancels the prior chain.

This is designed for locked-screen routines, but it has NOT been tested on a physical Android device, OnePlus 15 or ColorOS 17. Android/vendor process management can interrupt execution. Force-stop, reboot or process termination ends the active/paused run; there is no automatic recovery or reboot restart. Scripts remain saved. Logs record completed actions, not a guarantee that the entire run completed.

There are no schedule/event triggers, arbitrary JavaScript/Python, cross-app taps, shell commands, HTTP requests, sensor triggers, Tasker import, or Wi-Fi/Bluetooth toggles in this version. Those are future extensions, not existing features. A simple command-dispatch language provides the first working automation surface. General Android automation requires choosing additional APIs, permissions and sometimes Accessibility, ADB or root.

A wake lock consumes additional battery while running. It has a timeout capped at the script limit plus one minute and is released on pause, completion, reset and service destruction. Sound/vibration can be affected by device settings, Do Not Disturb and notification permissions. Do not rely on this development build for critical alerts.

## Build source

Open this folder in Android Studio. Install Android SDK Platform 36 and accept SDK licenses when prompted. The project uses Android Gradle Plugin 8.13.0, compatible Gradle 8.13, and JDK 17. There is no Gradle wrapper bundled. Use a local Gradle 8.13 installation or let Android Studio configure it, then run:

```sh
gradle :app:assembleDebug
```

An additional `build-sdk.sh` builds without Gradle using a JDK and an existing Android SDK. It does not download dependencies. Set `ANDROID_HOME` to the SDK and run `bash build-sdk.sh`. It requires SDK platform 36 and build-tools 36.0.0. It creates a debug key in `.local/` on the first run. Keep that key for compatible future updates; don't share a release key. The packaged development APK's signing key is not included in the source archive, so rebuilding on a different key requires uninstalling the old build first (export scripts before uninstalling).

## Validation performed

- 21 JVM parser assertions passed: coffee total/order size, nested repeat expansion, quick-test duration, malformed syntax, negative/zero/overflow durations, total-duration cap, action-count cap, vibration/volume bounds, and unsupported commands.
- App and resource compilation completed against Android API 36.
- APK alignment and signing verification completed.
- No emulator or physical-device execution was performed. The Android UI, foreground service, screen-off behavior, device audio, vibration and shortcuts still need on-device verification.

## Android design references

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/reference/android/os/SystemClock
- https://developer.android.com/reference/android/app/Notification.Builder
