# TV App Manager

Lightweight (no dependencies, ~100 KB) Android TV app to remove unwanted apps, with a safety
layer that refuses to touch anything the TV needs.

## Safety model
| Group | What it is | What the app lets you do |
|---|---|---|
| Removable (green) | Apps you or an app store installed | Uninstall (2-step confirm) |
| System · caution (amber) | Pre-installed, non-core apps (bloat) | Disable via App info, or get a reversible ADB command |
| Protected (red) | Launcher, Settings, keyboard, HDMI/tuner inputs, WebView, Play services/Store, installer, accessibility services, platform-signed / privileged / persistent apps, SoC vendor stacks (MediaTek, Amlogic, Realtek...) | Nothing. Remove button is not offered |

Checks run against the live device (what is the current launcher, keyboard, TV input, WebView...)
and again immediately before any uninstall. See `SafetyGuard.kt`.

## Important limit
A normal Android app cannot silently delete system apps; Android does not allow it. For
pre-installed apps the app shows the exact `adb shell pm uninstall -k --user 0 <pkg>` command
(removes for the current user only, the system image is untouched) plus the restore command:
`adb shell cmd package install-existing <pkg>`. A factory reset also restores everything.

## Build
Requires JDK 17 and the Android SDK (platform 34).
    gradle wrapper        # once, to generate ./gradlew
    ./gradlew assembleRelease
    # APK: app/build/outputs/apk/release/app-release.apk
(Or open the folder in Android Studio and press Run.)

## Install on the TV
Enable Developer options > USB/Network debugging, then:
    adb connect <TV-IP>:5555
    adb install app/build/outputs/apk/release/app-release.apk
Or copy the APK to a USB stick and open it with a file manager app.

The release build is signed with the debug key so it installs directly; use your own keystore
if you plan to distribute it.
