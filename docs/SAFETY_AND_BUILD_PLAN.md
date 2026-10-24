# Face MMA — Safety and Build Plan

## Purpose
Face MMA is a personal Android face-editing app. Users must only process images/video they own or have permission to use.

## Required in-app warning (first launch)
**Use responsibly**
- Use only your own media or media you have explicit permission to edit.
- Do not create, request, share, or publish intimate or sexual imagery of a real person without that person's explicit consent.
- Do not use edited media to impersonate, harass, defraud, threaten, or mislead others.
- Follow local law and the terms/licenses of the app and its AI models.
- You are responsible for your use of the app. This notice does not remove any legal obligations or make prohibited use acceptable.

Buttons: **I Agree and Continue** / **Exit**. Store the acknowledgement locally and provide a Safety & Privacy page in Settings.

## Processing and privacy notice
**On-device processing:** Media may be processed locally on this device. Model files may be downloaded separately. Explain any network use accurately; do not claim all processing is offline unless verified. Do not upload user media unless the app clearly discloses it and the user explicitly chooses that action.

## NSFW setting behavior
Do not implement an OFF switch that bypasses all content checks. If the current checker falsely flags ordinary content, add a clearly labeled **Report a false positive / Retry check** path and log only non-sensitive diagnostic data. A check error or missing model must never be treated as permission to process. Any threshold adjustment must be evidence-based, tested, and must not disable checks for videos or alternate processing paths.

## Build strategy (Android-only workflow)
1. Use GitHub Actions to build from `work/android` with the project's Gradle wrapper and a pinned JDK/Android SDK.
2. Verify required native dependencies before compiling. The project documentation states QNN/NDK/CMake inputs are not all committed; a workflow must not pretend these are present.
3. Use only trusted, license-compatible native binaries that match the Kotlin/native source versions. Do not fetch arbitrary binaries from unknown sources.
4. Run unit/build checks and publish the APK as a GitHub Actions artifact only after a successful build.
5. Label debug builds clearly. Never include signing keys, passwords, or private model credentials in the repository.
6. Test installation and basic image/video flows on a real Android device before calling the build complete.

## License reminder
Review the upstream app and each model's license before redistribution or public release. Different model weights may have different commercial-use restrictions.
