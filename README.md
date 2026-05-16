# Remote Camera / Human Guard

Android app + Python FastAPI server for human detection. The Android app sends selected images or live camera frames to the home server. The server runs YOLO/TFLite/HOG detection and returns OK/NG results with annotated images.

## Current App Behavior

- `이미지 판정`: choose a JPEG/PNG/WebP image from the device and upload it to `/inspect`.
- `실시간 시작`: stream camera frames to the server over WebSocket.
- Detection area: tap the preview to set polygon points; detections are NG only when the person foot point is inside the configured area.
- Claude inspection is app-only. Enter the Claude API key and prompt in the Android settings screen.
- On YOLO/HOG NG, the app shows the detected image first and asks whether to run Claude.

## Key Paths

| Path | Purpose |
|---|---|
| `home_server/server.py` | FastAPI server entrypoint |
| `human_guard/detection.py` | YOLO/TFLite/HOG detection |
| `mobile-app/` | Android app |
| `skills/remote-camera-operations/` | Codex operational memory for this repo |
| `.github/workflows/android-apk.yml` | GitHub Actions APK build/release workflow |

## Server

Run on this PC through WSL:

```powershell
wsl --cd "/mnt/c/Users/dlack/code/02 Human Detect Model" ./venv/bin/python -m home_server.server
```

Health check:

```powershell
curl.exe http://127.0.0.1:8000/health
```

For server auth, set `HUMAN_GUARD_API_KEY`. Do not set `ANTHROPIC_API_KEY` or `CLAUDE_API_KEY` on the server.

Detailed run notes live in `skills/remote-camera-operations/references/server-run.md`.

## Android Testing URLs

- Emulator to host server: `http://10.0.2.2:8000`
- Same Wi-Fi phone: `http://192.168.x.x:8000`
- Known Tailscale URL: `https://chamin.taile54870.ts.net`
- Cloudflare Tunnel: use the printed `https://xxxx.trycloudflare.com` URL

## APK Build / Release

Prefer GitHub Actions because the local Gradle wrapper jar may be missing.

- Workflow: `Android APK Build`
- Artifact: `app-debug`
- Tag release: push a `v*` tag

Detailed release notes live in `skills/remote-camera-operations/references/github-apk-build.md`.
