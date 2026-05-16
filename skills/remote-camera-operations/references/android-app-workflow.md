# Android App Workflow Reference

Use this when changing the Android app UI, image inspection flow, Claude inspection flow, settings, or release behavior.

## Current Product Decisions

- Server-side YOLO/HOG is the first-pass detector.
- Claude inspection is app-only. Do not add Anthropic API key requirements to the Python server.
- The user enters the Claude API key only in the Android app settings.
- The app should not call Claude automatically on every detection. On NG, show the detected image first, then ask `Claude로 검사할까요?`.
- The app has a setting to enable/disable the Claude prompt and a setting for the custom Claude prompt text.
- Manual single-image inspection uses image upload/selection from the device, not camera capture.
- Live inspection still uses the camera preview and WebSocket stream.

## Important Android Files

| File | Purpose |
|---|---|
| `mobile-app/app/src/main/java/com/remotecamera/app/MainActivity.kt` | Main live/image inspection UI and server calls |
| `mobile-app/app/src/main/java/com/remotecamera/app/SettingsActivity.kt` | Server URL, server API key, Claude API key, Claude prompt settings |
| `mobile-app/app/src/main/java/com/remotecamera/app/AppSettings.kt` | SharedPreferences keys and defaults |
| `mobile-app/app/src/main/java/com/remotecamera/app/ClaudeInspector.kt` | Android-side Anthropic Messages API request and response parsing |
| `mobile-app/app/src/main/java/com/remotecamera/app/ClaudeInspectionPolicy.kt` | Rules for offering/running Claude |
| `mobile-app/app/src/main/java/com/remotecamera/app/ImageInspectionUpload.kt` | Supported MIME types for uploaded image inspection |
| `mobile-app/app/src/main/res/layout/activity_main.xml` | Main UI layout |
| `mobile-app/app/src/main/res/layout/activity_settings.xml` | Settings UI layout |
| `mobile-app/app/src/test/java/com/remotecamera/app/` | JVM tests for app-side behavior |

## Image Inspection Flow

1. `이미지 판정` opens `Intent.ACTION_OPEN_DOCUMENT`.
2. Allow only `image/jpeg`, `image/png`, and `image/webp`.
3. Read the selected `Uri` through `contentResolver`.
4. POST multipart field `image` to `${serverUrl}/inspect`.
5. Include `X-API-Key` only when the server API key setting is non-empty.
6. Show the selected image immediately, then replace it with `result_image` if the server returns one.
7. If `alarm == true` or `status == "NG"`, vibrate, show NG, and offer Claude if the policy allows it.

## Claude Flow

1. Keep server response `claude_inspection` absent for app-only mode.
2. `ClaudeInspectionPolicy` decides whether to offer a prompt.
3. `offerClaudeInspection()` displays the first detected image and asks the user.
4. Only the positive dialog action calls `ClaudeInspector.inspect()`.
5. Pass the actual selected image MIME type to Claude for uploaded PNG/WebP images; live frames are JPEG.
6. Include `customPrompt` from settings in the Claude context.

## Server Connection Notes

- Android emulator can reach a Windows/host server through `http://10.0.2.2:8000`.
- Physical devices should use same-Wi-Fi IP, Tailscale Serve URL, or Cloudflare Tunnel URL.
- Known Tailscale URL used in this repo: `https://chamin.taile54870.ts.net`.

## Testing Notes

- Add small pure Kotlin tests for policy or helper behavior before production changes.
- Local Gradle may fail because `mobile-app/gradle/wrapper/gradle-wrapper.jar` is missing.
- When local Gradle is unavailable, still run:
  - XML parse checks for changed layouts and manifest.
  - `git diff --check` for changed files.
  - GitHub Actions APK build for final release verification.

## Release Notes

- Use GitHub Actions for APK builds and releases unless the Gradle wrapper is restored locally.
- Stage only intended files. Exclude Android Studio `.idea/`, `.vs/`, generated server outputs, build artifacts, and local model weights unless explicitly requested.
