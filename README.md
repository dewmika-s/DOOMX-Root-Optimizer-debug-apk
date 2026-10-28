# DOOMX ROOT OPTIMIZER

Android Kotlin starter project for a terminal-style gaming diagnostics app targeting Free Fire / Free Fire MAX.

## Build APK

This workspace does not include the Android SDK or Gradle installation, so the APK is not precompiled here. The included GitHub Actions workflow builds it and publishes `app-debug.apk` as a downloadable workflow artifact.

1. Create a GitHub repository and upload these project files.
2. Open **Actions** → **Build DOOMX Root Optimizer APK** → **Run workflow**.
3. Open the completed run and download **DOOMX-Root-Optimizer-debug-apk** under Artifacts.
4. Transfer `app-debug.apk` to your Android device and install it. You may need to allow installs from that source.

Alternatively, open the folder in Android Studio (JDK 17, Android SDK Platform 35) and run `assembleDebug`.

## Current functionality

- Live battery level, charging state, battery temperature, Android thermal status, RAM estimate, CPU load estimate, and filesystem stats.
- Root check through `su -c id`; Magisk may ask for permission.
- Terminal commands: `help`, `status`, `root`, `rootdiag`, `battery`, `thermal`, `memory`, `storage`, `cpu`, `fps`, `profile`, `optimize balanced|performance|battery`, `restore`, `clearcache`, `clearlog`.
- FPS probe attempts to find a Free Fire SurfaceFlinger layer and estimate frame rate from valid timestamps. FPS may report N/A if Android/OEM permissions or layer data do not permit a reliable reading.
- Profiles are advisory app-side presets, not fake kernel/CPU tweaks. `restore` returns the app profile to Balanced; it does not revert arbitrary system modifications because the app does not make them.
- Cache cleanup only targets DOOMX's own app cache, not Free Fire or system files.

## Safety and limitations

- Read-only diagnostics use normal shell access. Root check uses Magisk's `su` prompt when available.
- No arbitrary shell command entry is exposed. The terminal command parser is allowlisted.
- Android does not provide a universal third-party API for exact game FPS, CPU/GPU die temperature, or all thermal sensors. The app explicitly reports N/A when it cannot read a metric. Battery temperature is not CPU/GPU temperature.
- No governor changes, thermal-throttle bypass, system-file deletion, or undocumented `build.prop` tweaks are performed.
- This is a debug APK build workflow. For public distribution, create and securely manage a release signing key and build a signed release APK.
