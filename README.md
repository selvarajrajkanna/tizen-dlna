# TizenDLNA

Ultra-lightweight, zero-dependency, all-native **Android 10–16 (`arm64-v8a`)** DLNA Media Server & Push Controller built specifically for **Samsung Tizen TVs**.

## Features

### Built-in File Manager (No Background Scanning)
- Browse internal storage directly with an Up button, Root shortcut, path breadcrumb, and Android 13–16 **Predictive Back** folder navigation.
- **Only folders, video, and audio files are selectable** (non-media files like `.srt`/`.txt`/`.jpg` are dimmed and non-selectable).
- Instant folder listing—zero background media indexing or MediaStore battery drain.

### 100% Samsung Tizen DLNA Compatibility
- **Automatic same-directory subtitle matching** (`.srt`, `.smi`, `.vtt`, `.ass`, `.ssa`, `.sub`, including `.en.srt` / `.eng.srt` language suffixes) with a pill badge on video rows.
- Sends Samsung's **`CaptionInfo.sec`** HTTP header along with **`<sec:CaptionInfo>`**, **`<sec:CaptionInfoEx sec:type="...">`**, and `text/srt` `<res>` inside DIDL-Lite XML.
- Auto-converts UTF-16 subtitles to UTF-8 so Samsung TVs never garble characters.
- Full HTTP/1.1 `Range` (`206 Partial Content`), `HEAD`, `transferMode.dlna.org`, and `contentFeatures.dlna.org` (`DLNA.ORG_OP=01`) for instant seeking.
- Supports both **Push Casting** (`AVTransport:1` + `RenderingControl:1` with Play/Pause, ±15s, Seek bar, Stop, and TV Volume) and **DLNA Media Server (DMS)** browsing from Samsung TV's Connected Devices.
- **Video & Audio formats:** `.mkv`, `.mp4`, `.m4v`, `.webm`, `.mov`, `.ts`, `.m2ts`, `.avi`, `.wmv`, `.mpg`, `.mpeg`, `.flv`, `.3gp` and `.mp3`, `.flac`, `.m4a`, `.aac`, `.ogg`, `.opus`, `.wav`, `.wma`.

### Share & Notification Controls
- **"Share to TizenDLNA" / "Open with TizenDLNA"** (`ACTION_SEND` & `ACTION_VIEW`) from any file manager, gallery, or download app—automatically opens the file's folder, picks up any matching subtitle, and casts to your TV.
- **Foreground notification** with live **Play/Pause** and **Stop Server** actions synced in real time with the app UI.
- Dedicated **Stop / Start Server** and **Quit** buttons (stops casting, shuts down the DLNA server & service cleanly, and closes the app).
- **Live Wi-Fi IP tracking** (`ConnectivityManager.NetworkCallback`) so reconnecting or changing Wi-Fi updates the advertised server address automatically.

### Material You & Performance
- **Zero third-party libraries** — pure Android SDK + Java 21 stdlib, R8 full-mode shrinking, tiny APK, instant startup.
- **Native Material You UI** using Android 14–16 `@android:color/system_*` dynamic wallpaper colors (Light & Dark DayNight) with a Material 3 palette fallback on Android 10–13, plus a custom **Adaptive + Material You Monochrome** launcher icon.
- **64-bit ARMv8/ARMv9 (`arm64-v8a`) + Vulkan 1.1+ baseline** (automatically uses Vulkan 1.3.128 Skia HWUI on Snapdragon 7+ Gen 3 / Adreno 732).
- **Zero-copy streaming** via `FileChannel.transferTo()` (Linux `sendfile64()`), 256 KB TCP send buffers, `TCP_NODELAY`, efficiency-core background threads (`THREAD_PRIORITY_BACKGROUND`), low-latency Wi-Fi lock, and partial wakelock for screen-off streaming.

## Requirements
- Android **10 to 16** (API 29–36), **64-bit (`arm64-v8a`)**, Vulkan 1.1+ GPU.
- Phone and Samsung TV on the same Wi-Fi network.
- **All Files Access** (Android 11–16) or **Storage permission** (Android 10) for browsing folders and reading same-directory subtitles.

## Building & CI Releases
- **GitHub Actions** (`.github/workflows/ci.yml`):
  - Builds the `arm64-v8a` release APK with JDK 21, Gradle 8.13, and Android API 36.
  - Signs with your persistent release keystore via GitHub Secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) for seamless in-place upgrades.
  - Auto-publishes a GitHub Release with `TizenDLNA-v<version>-arm64-v8a.apk`, `.sha256` checksum, and commit changelog, pruning older releases to keep the latest 5.
- **Local build** (Windows with `D:\code\android-sdk`):
  ```powershell
  .\scripts\build-local.ps1
  ```
