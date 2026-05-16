# Server Run Reference

Use this when the user asks to run, restart, document, or troubleshoot the home computer server.

## Local Server

### Fast Path On This PC

This checkout works most reliably through the WSL virtualenv at `venv/bin/python`.
From PowerShell, run:

```powershell
wsl --cd "/mnt/c/Users/dlack/code/02 Human Detect Model" ./venv/bin/python -m home_server.server
```

Claude inspection is app-only. Do not set `ANTHROPIC_API_KEY` or `CLAUDE_API_KEY` for the server; enter the Claude key in the Android app and turn on `Claude 자동 검사` there.

If server API auth should be enabled too:

```powershell
$env:HUMAN_GUARD_API_KEY="your-server-password"
wsl --cd "/mnt/c/Users/dlack/code/02 Human Detect Model" ./venv/bin/python -m home_server.server
```

Quick health check:

```powershell
curl.exe http://127.0.0.1:8000/health
```

Check whether port 8000 is already occupied:

```powershell
Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue
```

If it is already running, do not start a second server. Use `/health` to verify it.

From the repository root:

```bash
cd "/mnt/c/Users/dlack/code/02 Human Detect Model"
source venv/bin/activate
export HUMAN_GUARD_API_KEY="여기에_긴_비밀번호"
python -m home_server.server
```

PowerShell equivalent:

```powershell
cd "C:\Users\dlack\code\02 Human Detect Model"
.\venv\Scripts\Activate.ps1
$env:HUMAN_GUARD_API_KEY="여기에_긴_비밀번호"
python -m home_server.server
```

Expected server address:

```text
http://localhost:8000
```

Stop the foreground server with `Ctrl+C`.

## Health Check

Without API key:

```bash
curl http://localhost:8000/health
```

With API key:

```bash
curl -H "X-API-Key: 여기에_긴_비밀번호" http://localhost:8000/health
```

Expected response shape:

```json
{"ok":true,"detector":"yolo11s-ultralytics-cuda"}
```

Detector names vary by available model/runtime:

| Detector | Meaning |
|---|---|
| `yolo11s-ultralytics-cuda` | `yolo_model/yolo11s.pt` with GPU |
| `yolo11s-ultralytics-cpu` | `yolo_model/yolo11s.pt` with CPU |
| `yolov8n-tflite` | `yolo_model/yolov8n_float32.tflite` |
| `hog-fallback` | OpenCV HOG fallback after YOLO load failure |

## Dashboard

Open:

```text
http://localhost:8000
```

If API key is set:

```text
http://localhost:8000?key=여기에_긴_비밀번호
```

## Image Test

```bash
curl -X POST http://localhost:8000/inspect \
  -H "X-API-Key: 여기에_긴_비밀번호" \
  -F "image=@test.jpg" \
  -F "confidence=0.6"
```

With polygon:

```bash
curl -X POST http://localhost:8000/inspect \
  -H "X-API-Key: 여기에_긴_비밀번호" \
  -F "image=@test.jpg" \
  -F "confidence=0.6" \
  -F 'polygon=[[100,200],[500,200],[500,600],[100,600]]'
```

## Phone Access

Same Wi-Fi:

```text
http://192.168.x.x:8000
```

Tailscale Serve:

```powershell
tailscale serve --bg 8000
```

Known Tailscale app URL:

```text
https://chamin.taile54870.ts.net
```

Cloudflare Tunnel:

```bash
cloudflared tunnel --url http://localhost:8000
```

Use the printed `https://xxxx.trycloudflare.com` URL in the Android app.

## Sandbox Caveat

When Codex is running with network sandboxing, a server launched by Codex may show `Uvicorn running on http://0.0.0.0:8000` but not be reachable from the user's real Windows/WSL localhost. In that case, tell the user to run the server command in their own terminal.

## Generated Files

| Path | Contents |
|---|---|
| `server_uploads/` | Uploaded source images |
| `server_results/` | Result images with detection boxes |
| `server_history.csv` | Inspection history |

Do not commit generated files unless explicitly requested.
