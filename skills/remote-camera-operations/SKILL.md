---
name: remote-camera-operations
description: Operate and evolve this Remote Camera / Human Guard repository. Use when Codex needs to run or explain the home_server FastAPI server, check health, connect Tailscale or Cloudflare Tunnel, build or release the Android APK through GitHub Actions, download artifacts, inspect build status, change Android image/live inspection behavior, configure app-only Claude inspection, clean repo docs, or document repeatable release/build/server workflows for this repo.
---

# Remote Camera Operations

Use this skill for repo-specific operations, not general Android or FastAPI guidance.

## Workflow

1. Identify the requested operation: server run, APK build, artifact download, release/tag, Android app behavior change, Claude inspection change, doc cleanup, or troubleshooting.
2. Load only the relevant reference:
   - Server operations: `references/server-run.md`
   - GitHub APK build: `references/github-apk-build.md`
   - Android app workflows and current product decisions: `references/android-app-workflow.md`
3. Preserve local-only files: do not commit `.vs/`, `venv/`, `server_uploads/`, `server_results/`, `server_history.csv`, `alarm_history.csv`, `alarms/`, or local YOLO model weights unless the user explicitly asks.
4. Before pushing to GitHub, run a focused status check and stage only the intended files.
5. After a GitHub Actions build, report the run URL, conclusion, commit SHA, and artifact path or download location.

## Repo Facts

- GitHub repo: `paperbox9898/Remote-Camera`
- Android workflow: `.github/workflows/android-apk.yml`
- Workflow name: `Android APK Build`
- Main branch build trigger: push to `main`
- APK artifact name: `app-debug`
- Server entrypoint: `python -m home_server.server`
- Server default URL: `http://localhost:8000`
- Server health endpoint: `/health`
- Android app path: `mobile-app/`
- Claude inspection is app-only. The server must not require `ANTHROPIC_API_KEY` or `CLAUDE_API_KEY`; the Android app stores the Claude key and prompt in settings.
- Manual single-image inspection should use Android image upload/selection, not camera capture. Live inspection can still use the device camera.
- When YOLO/HOG returns NG, show the first detected image and ask whether to run Claude before calling the Claude API.

## Validation

- For server code edits, run `python -m py_compile home_server/server.py human_guard/detection.py`.
- For Android behavior edits, add or update focused JVM tests under `mobile-app/app/src/test/java/com/remotecamera/app/` when possible.
- For GitHub APK builds, prefer GitHub Actions because this repo may not have `mobile-app/gradle/wrapper/gradle-wrapper.jar` locally.
- If local Gradle fails with `gradle-wrapper.jar not found`, use `gh run`/GitHub Actions instead of trying to repair the wrapper unless the user asks.
