# Android Virtual Webcam & Live Stream Video Player — Feasibility Analysis & System Architecture

> **Role**: Senior Android Engineer & System Architect  
> **Project**: WebcamMax-Inspired Pre-Recorded Video Source & Live Stream Studio for Android  
> **Target SDK**: Android 15 (API 35), Min SDK: Android 12 (API 31)  
> **Tech Stack**: Kotlin, Jetpack Compose (Material 3 Dark + Arabic RTL / English), Android Media3 ExoPlayer, Foreground Service (`mediaPlayback`), Hardware `MediaCodec` (H.264/AVC), MJPEG/HTTP Live Server, RTMP Publisher, and Clean Broadcast Stage (`MediaProjection` + `ALLOW_CAPTURE_BY_ALL`).

---

## 1. Executive Feasibility Analysis: Can an Android App Act as a Virtual Webcam?

Unlike desktop operating systems (Windows DirectShow / Media Foundation virtual camera filters, macOS CoreMediaIO DAL plugins, or Linux `v4l2loopback` kernel modules), **unrooted Android does not provide a universal public SDK API that allows a normal third-party app to replace the physical camera feed inside arbitrary third-party apps.**

### Why Direct System-Wide Camera Replacement Is Blocked on Unrooted Android

1. **Camera HAL & SELinux Isolation (`cameraserver`)**:
   - When an app such as TikTok, Instagram, or Zoom opens a camera on Android, it calls `CameraManager.openCamera(cameraId, ...)` in the `android.hardware.camera2` API (or Jetpack `CameraX`, which wraps `Camera2`).
   - `CameraManager` communicates via Binder IPC with the privileged system service `cameraserver`, which enumerates hardware camera devices exclusively from vendor HAL processes (`ICameraProvider` AIDL/HIDL running in the `hal_camera_default` SELinux domain).
   - An ordinary user-installed APK runs in the `untrusted_app` SELinux domain and cannot register a custom `ICameraProvider` or inject frames into `cameraserver`.

2. **Android 14+ `VirtualCamera` (`android.companion.virtual.camera.VirtualCamera`)**:
   - Android 14/15 introduced `VirtualDeviceManager` and `VirtualCamera` for companion-device streaming (such as Chromebook / Windows cross-device streaming).
   - Creating a `VirtualDevice` and registering a `VirtualCamera` requires `android.permission.CREATE_VIRTUAL_DEVICE` and `android.permission.ADD_ALWAYS_UNLOCKED_VIRTUAL_DEVICE`, which are **`signature|privileged|role`** permissions granted only to system-signed OEM packages. Furthermore, virtual cameras created under `VirtualDeviceManager` are scoped to virtual displays rather than replacing camera ID `0` (back) or `1` (front) on the primary display.

3. **Root / Xposed / LSPosed Hooking (`VCam`)**:
   - On a **rooted** phone with Magisk/KernelSU and LSPosed, an Xposed module can hook `CameraManager`, `CameraDevice.StateCallback`, and `SurfaceTexture` inside the target app's process (`com.zhiliaoapp.musically`) and decode an MP4 file onto the target app's preview/recording `Surface`.
   - **Limitation**: Requires unlocking the bootloader, rooting the device, installing Zygisk + LSPosed, and enabling the hook per target app. This violates our core requirement of working on normal, unrooted Android devices without requiring root for the standard workflow.

---

## 2. Evaluation of the 4 Candidate Approaches

| # | Integration Approach | Works on Unrooted Phone? | Requires PC? | Works with TikTok LIVE? | Technical Verdict |
|---|---|---|---|---|---|
| **1** | **Camera2 / CameraX API** | Yes (in-app only) | No | No (cannot inject into TikTok's process) | Camera2/CameraX are *consumer* APIs for reading physical sensors, not virtual camera *providers* for other apps. |
| **2** | **System Virtual Camera / CameraProvider** | **No** (Requires Root + LSPosed, or OEM `signature` key) | No | Only with Root + LSPosed (`VCam`) OR via `ACTION_VIDEO_CAPTURE` intent (not used by TikTok LIVE) | Cannot replace `Camera2` system-wide without root. Standard `ACTION_VIDEO_CAPTURE` intent only works for apps requesting external video capture. |
| **3** | **Direct Phone-Only Streaming & Screen-Cast Stage (`RTMP` + `Clean Broadcast Stage` + `AudioPlaybackCapture`)** | **Yes (100% Phone-Only)** | **No** | **Yes** — via TikTok LIVE "Mobile Gaming / Screen Share" mode OR TikTok RTMP Stream Key | **Best Phone-Only Architecture.** Zero root, zero PC required. |
| **4** | **Desktop / Network Virtual Webcam Bridge (`MJPEG over HTTP` + Web Player / OBS / USB `adb forward`)** | **Yes** | Yes (when feeding Desktop OBS / TikTok LIVE Studio) | **Yes** — via TikTok LIVE Studio / OBS Virtual Camera on PC, or any MJPEG/HTTP receiver | **Best External / Studio Architecture.** Streams real-time composited frames over Wi-Fi or USB. |

---

## 3. Specific Feasibility Assessment for TikTok LIVE

TikTok LIVE offers **three distinct broadcasting modes**. Here is the exact engineering truth for each mode:

### Mode 1: TikTok LIVE "Device Camera" (الكاميرا)
- **How TikTok implements it**: Calls `CameraManager.openCamera()` directly inside TikTok's own Activity to read the physical front/back CMOS sensor.
- **Can an unrooted Android app replace this feed directly?** **No.** Without root + LSPosed hooking TikTok's process, Android's `cameraserver` only serves physical camera HALs to TikTok.
- **Workaround if Camera Mode is mandatory**: Either (a) use a rooted phone with an LSPosed virtual camera hook, or (b) stream from the phone to a PC running OBS Virtual Camera + TikTok LIVE Studio.

### Mode 2: TikTok LIVE "Mobile Gaming / Screen Share" (بث ألعاب الهاتف / مشاركة الشاشة) — **WORKS 100% PHONE-ONLY (NO PC, NO ROOT)**
- **How TikTok implements it**: TikTok LIVE uses Android's `MediaProjection` API to capture the phone's screen (`VirtualDisplay`) and Android 10+ `AudioPlaybackCapture` (`AudioRecord` with `AudioPlaybackCaptureConfiguration`) to capture internal application audio directly from the digital audio mixer.
- **How our app integrates with TikTok LIVE Mobile Gaming mode**:
  1. **Digital Internal Audio Capture (`C.ALLOW_CAPTURE_BY_ALL`)**: Our Media3 `ExoPlayer` instance explicitly configures `AudioAttributes` with `setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)`. This instructs Android's audio policy service to allow TikTok LIVE's `MediaProjection` audio capture to record the crystal-clear stereo audio track directly from our video player without microphone noise.
  2. **Clean Broadcast Stage Mode (وضع البث النظيف)**: With one tap, the user enters a chromeless, distraction-free fullscreen stage (9:16 portrait or 16:9 landscape) that renders **only** the pre-recorded video with the active Aspect Ratio (`Fit`, `Fill`, `Original`) and `Mirror` transform. All buttons, scrub bars, and status badges are hidden so TikTok LIVE viewers see a pure video feed.
  3. **Zero-Interruption Source Switching**: While Clean Broadcast Stage is active, the user can switch tracks, pause/play, or stop the output using:
     - **Android Foreground Notification Controls** (`Prev`, `Play/Pause`, `Next`, `Stop Output`),
     - **Android Picture-in-Picture (PiP) Mode**, or
     - **The Built-in Web Remote Controller** (`http://<phone-ip>:8080/player`) from another device.

### Mode 3: TikTok LIVE Studio / Custom RTMP Stream Key (بث RTMP / استوديو البث) — **WORKS VIA BUILT-IN RTMP & MJPEG SERVER**
- **Direct RTMP Push (Phone-Only)**: For TikTok accounts with RTMP cast permissions (or YouTube Live, Twitch, Facebook Live, Kick, Restream), our app includes a real **RTMP Live Publisher (`RtmpOutputSink`)** that encodes the composited video frames using hardware `MediaCodec` (`video/avc` H.264) and pushes FLV media packets directly to `rtmp://...` over TCP.
- **Wi-Fi / USB Virtual Webcam Server (`MjpegHttpOutputSink`)**: For users running **TikTok LIVE Studio** or **OBS Studio** on a PC, turning the app's **ON/OFF** switch **ON** starts a real-time HTTP/MJPEG stream (`http://<phone-ip>:8080/stream.mjpeg` and `/player`). OBS Studio or TikTok LIVE Studio adds this URL as a *Browser Source* or *Media Source*, turning the phone into a wireless (or USB `adb forward tcp:8080 tcp:8080`) WebcamMax controller.

---

## 4. Best Practical System Architecture

To satisfy all requirements cleanly without faking any capability, we implement a **Modular Multi-Sink Architecture** (`com.facefusion.mobile.webcam`):

```
┌────────────────────────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI (Arabic RTL & English)                │
│  ┌──────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐  │
│  │ Video Preview Canvas │ │ Prominent ON / OFF  │ │  Playlist Manager   │  │
│  │ (Fit/Fill/Original + │ │ Live Output Switch  │ │ (SAF Picker, Thumb, │  │
│  │  Mirror + CleanStage)│ │ + Real-Time Status  │ │  Duration, Reorder) │  │
│  └──────────┬───────────┘ └──────────┬──────────┘ └──────────┬──────────┘  │
└─────────────┼────────────────────────┼───────────────────────┼─────────────┘
              │                        │                       │
              ▼                        ▼                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                  WebcamStudioController (State & Lifecycle)                │
│  • Media3 ExoPlayer (MP4, WebM, MKV, MOV; ALLOW_CAPTURE_BY_ALL audio)      │
│  • Repeat Mode (OFF / Loop Single / Loop Playlist) & Volume (0..100%)      │
│  • TextureView GPU Frame Extractor + FrameCompositor (Fit/Fill/Orig/Mirror)│
└──────────────────────────────────────┬─────────────────────────────────────┘
                                       │ Composited ARGB/Bitmap Frames (15-30 FPS)
                                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│            VirtualCamService (Android Foreground Service)                  │
│            Runs ONLY when Output Toggle is ON (Battery Safe)               │
└──────────────┬───────────────────────┼───────────────────────┬─────────────┘
               │                       │                       │
               ▼                       ▼                       ▼
┌─────────────────────────┐ ┌─────────────────────┐ ┌────────────────────────┐
│  MjpegHttpOutputSink    │ │ CleanStageOutputSink│ │  RtmpLiveOutputSink    │
│  • /stream.mjpeg        │ │ • TikTok LIVE Screen│ │  • TCP RTMP Handshake  │
│  • /player (HTML5/OBS)  │ │   Share Ready       │ │  • AMF0 connect/publish│
│  • /frame.jpg           │ │ • Chromeless 9:16   │ │  • Hardware MediaCodec │
│  • /status & /control   │ │ • Android PiP Mode  │ │    H.264/AVC FLV Muxer │
└─────────────────────────┘ └─────────────────────┘ └────────────────────────┘
```

### Why the `ON / OFF` Toggle Is Genuinely Functional (Never Cosmetic)
- **When `OFF`**:
  - `ExoPlayer` can still preview videos locally in the app if the user presses Play, **or** sit idle with zero background CPU usage.
  - `VirtualCamService` foreground service is **stopped**.
  - `FrameCompositor` frame extraction loop is **stopped** (0 FPS extracted, saving battery).
  - `MjpegHttpOutputSink` / `RtmpLiveOutputSink` sockets are **closed**.
- **When `ON`**:
  - `VirtualCamService` starts as a compliant Android Foreground Service with persistent notification and quick-action transport controls.
  - The selected `VideoOutputSink` starts (`MjpegHttpOutputSink` binds port `8080` and serves live MJPEG + Web Studio; `RtmpLiveOutputSink` performs the TCP RTMP handshake + H.264 `MediaCodec` encoding; `CleanStageOutputSink` arms the broadcast stage + wake lock).
  - A dedicated frame pump captures the live rendered video from `ExoPlayer`'s `TextureView`, applies the exact **Aspect Ratio (`Fit`, `Fill`, `Original`)** and **Mirror (`scaleX = -1`)** transforms into an output `Bitmap`, and pushes each frame to the active sink at the configured frame rate.
  - If playback is **STOPPED**, the compositor continues emitting a clean "STANDBY / OFF-AIR" black frame to keep downstream streaming clients alive without freezing or crashing their decoders.

---

## 5. Minimal Proof of Concept (Testable Verification)

Our minimal proof of concept is embedded directly into the application and verified at three levels:

1. **Automated Unit & Integration Tests (`WebcamStudioTest.kt`)**:
   - Verifies playlist CRUD operations (add, remove, reorder up/down, JSON serialization/deserialization).
   - Verifies `FrameCompositor` math and pixel transformations for `Fit`, `Fill`, and `Original` aspect ratios plus `Mirror` horizontal flipping.
   - Verifies `MjpegHttpOutputSink` real socket server lifecycle: starts when switched `ON`, serves HTTP `200 OK` on `/status`, `/frame.jpg`, and `multipart/x-mixed-replace` on `/stream.mjpeg` with real JPEG frames, and refuses connections once switched `OFF`.
   - Verifies `RtmpLiveOutputSink` RTMP handshake packet construction (`C0`/`C1`), AMF0 command encoding (`connect`, `createStream`, `publish`), and FLV AVC tag framing.

2. **On-Device / Browser Loopback Verification (Zero PC Required)**:
   - Turn the **ON/OFF** switch **ON** in the app (with `MJPEG / HTTP Server` selected).
   - Tap **"Open Live Stream in Browser / فتح البث التجريبي"** inside the app (or open `http://127.0.0.1:8080/player` in Chrome on the same Android phone, or `http://<phone-ip>:8080/player` on any other device).
   - Observe the pre-recorded video streaming live in the browser receiver, and verify that Play, Pause, Stop, Playlist Switching, Aspect Ratio (`Fit`/`Fill`/`Original`), Mirror Mode, and turning the `ON/OFF` switch `OFF` immediately take effect on the live output stream.

---

## 6. GitHub-Based Development & APK Delivery Plan

- **Phase 1**: Implement `WebcamPlaylist` (Storage Access Framework multi-video picker, persistent URI permissions, background thumbnail/duration/resolution extraction, add/remove/reorder/save playlist) + `WebcamController` (Media3 ExoPlayer, Play/Pause/Stop, Loop modes, Volume, Aspect Ratio, Mirror, ON/OFF state machine).
- **Phase 2 & 3**: Implement `VideoOutputEngine` with `MjpegHttpOutputSink` (`/stream.mjpeg`, `/player`, `/frame.jpg`, `/status`, `/control`), `RtmpLiveOutputSink` (H.264 `MediaCodec` + RTMP/FLV publisher), `CleanStageOutputSink` (TikTok LIVE chromeless stage + Android PiP), `VirtualCamService` foreground service, and `ACTION_VIDEO_CAPTURE` intent support.
- **Phase 4**: Build the responsive Jetpack Compose UI (`WebcamScreen.kt`) with dark theme, Arabic RTL + English technical labels, portrait & landscape adaptive layouts, settings modal, and comprehensive lifecycle/rotation handling + JUnit test suite.
- **Phase 5**: Configure `.github/workflows/build-apk.yml` to run unit tests, compile the debug APK with Media3 ExoPlayer, upload the workflow artifact, and automatically publish a downloadable **GitHub Release** (`gh release`) containing the signed debug APK.
