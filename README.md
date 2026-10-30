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


## Advanced Root / Risk page

The dashboard includes an expandable **ADVANCED ROOT / RISK PAGE**. It provides:

- Root diagnostics (`su -c id` and read-only system diagnostics).
- An experimental, reversible `renice` request for the currently running Free Fire / Free Fire MAX process (`renice -n -5 -p PID`).
- A restore action requesting normal nice priority (`renice -n 0 -p PID`).
- A thermal safety gate that blocks the priority request when Android reports SEVERE or worse thermal status.

The game must already be running, Magisk must grant root, and the kernel must permit the operation. The app records the PID it changed; if the game restarts, the old PID may no longer refer to the game, so do not assume restore succeeded unless the terminal confirms it. This operation is not an anti-cheat bypass, does not modify game files or memory, and does not guarantee higher FPS. Avoid CPU/GPU clock forcing, thermal limit disabling, game memory edits, and unknown root scripts.


## DOOMX 1.1.0 — Game-only root risk action

The Advanced Game-only Root / Risk Page now requires an explicit confirmation before applying a privileged action. After consent it displays a five-second terminal sequence, checks Magisk root access and Android thermal status, locates only the Free Fire / Free Fire MAX process, records that process's original nice value, requests `renice -5` for that PID, and verifies the resulting value. Restore checks that the recorded PID still belongs to the same Free Fire package before restoring the original nice value. Other apps and global CPU/GPU/thermal settings are not modified. Kernel/SELinux may deny the request; a successful request does not guarantee higher FPS.


## DOOMX 1.2.0 — Game-only risk controls

- The risk page now includes a read-only CPU/GPU frequency probe (`clocks`). It reports frequency nodes only when the device exposes them; GPU units and node names vary by vendor.
- The reversible `renice` action remains scoped to the currently running Free Fire / Free Fire MAX PID, with package verification before restore.
- The normal Optimize & Launch path requests Android Game Mode performance only when supported and skips the request when severe thermal status is detected.
- DOOMX intentionally does not write CPU/GPU frequency sysfs nodes, disable thermal protections, or force-stop background apps. CPU/GPU clocks and thermal limits are device-wide on most Android phones, so they cannot honestly be described as Free-Fire-only controls; changing them may overheat or destabilize the whole device.
- `clocks` is read-only and does not claim to optimize clocks.

## DOOMX 1.3.0 — game-only FPS text overlay

After the user grants Android's **Display over other apps** permission, the Optimize & Launch flow starts `GameFpsOverlayService` before opening Free Fire / Free Fire MAX. The service displays a small `DX ~NN FPS` text label at the top-right only while `dumpsys activity activities` indicates one of the two supported game packages is resumed. It hides the label when another app is foregrounded. The overlay is non-touchable and non-focusable so it does not intercept game controls.

FPS is an approximate SurfaceFlinger layer frame-timestamp estimate and may show `FPS --` when the device ROM does not expose the game's layer or latency data. It is not a guaranteed in-game engine FPS counter. Android may require the user to grant overlay permission in Settings. The persistent notification indicates that the monitoring service is active.
