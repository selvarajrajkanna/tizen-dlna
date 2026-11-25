# TizenDLNA

Ultra-lightweight, zero-dependency, all-native Android 16 (API 36, `arm64-v8a`) DLNA Media Server & Push Controller built specifically for **Samsung Tizen TVs** and **Snapdragon 7+ Gen 3** (Vulkan 1.3.128).

## Key Features
- **Zero third-party libraries** — pure Android 16 SDK + Java 21 stdlib (no Compose, no AndroidX, no Kotlin runtime, no external HTTP/UPnP jars).
- **100% Samsung Tizen DLNA compatibility**:
  - Automatic same-directory subtitle matching (`.srt`, `.smi`, `.vtt`, `.ass`, `.ssa`, `.sub`, including `.en.srt` / `.eng.srt` suffixes).
  - Sends Samsung's `CaptionInfo.sec` HTTP header + `<sec:CaptionInfo>` + `<sec:CaptionInfoEx sec:type="...">` + `text/srt` `<res>` inside DIDL-Lite XML.
  - Auto-converts UTF-16 subtitles to UTF-8 so Tizen renders them cleanly.
  - Full HTTP/1.1 `Range` (`206 Partial Content`), `HEAD`, `transferMode.dlna.org`, and `contentFeatures.dlna.org` (`DLNA.ORG_OP=01`).
  - Works both as a **Push Controller** (tap video on phone → plays on TV) and as a **DLNA Media Server (DMS)** browsable from Samsung TV's Connected Devices.
- **Snapdragon 7+ Gen 3 / Android 16 / Vulkan 1.3.128 optimizations**:
  - Strictly 64-bit `arm64-v8a` target (`minSdk = 36`, `targetSdk = 36`).
  - Declares Vulkan 1.3.128 (`0x00403080`) hardware acceleration for Android 16's Skia Vulkan HWUI pipeline.
  - Zero-copy streaming via `FileChannel.transferTo()` (Linux `sendfile64()` syscall) with 256 KB TCP send buffers and `TCP_NODELAY`.
  - Background threads pinned to `THREAD_PRIORITY_BACKGROUND` (Cortex-A520 efficiency cores), with Foreground Service + Low-Latency Wi-Fi Lock + Partial WakeLock for screen-off streaming.

## Building & Releases
- **GitHub Actions CI** (`.github/workflows/ci.yml`): builds the release APK on every push/PR, and automatically attaches `TizenDLNA-arm64-v8a-android16.apk` + SHA-256 checksum to GitHub Releases on `v*` tags (or manual `workflow_dispatch`).
- **Local build** (using `D:\code\android-sdk`):
  ```powershell
  .\scripts\build-local.ps1
  ```
