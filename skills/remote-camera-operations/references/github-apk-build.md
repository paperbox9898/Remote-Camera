# GitHub APK Build Reference

Use this when the user asks to build the Android APK through GitHub, check a build, download artifacts, or prepare a release.

## Build Path

The repo builds APKs through GitHub Actions:

```text
.github/workflows/android-apk.yml
```

Workflow name:

```text
Android APK Build
```

The workflow runs on:

- `workflow_dispatch`
- push to `main`
- tags matching `v*`

It uses `gradle assembleDebug` in `mobile-app/` and uploads artifact `app-debug`.

## Commit And Push For Build

Check state:

```bash
git status --short --branch
git diff --stat
```

Stage only intended source/docs files. Avoid local artifacts and model weights:

```bash
git add home_server/server.py mobile-app/app/src/main/java/com/remotecamera/app/MainActivity.kt skills/remote-camera-operations
git commit -m "Update image inspection and operations guide"
git push origin HEAD:main
```

Adjust staged files and commit message to the actual work.

## Watch Build

List recent runs:

```bash
gh run list --repo paperbox9898/Remote-Camera --workflow "Android APK Build" --limit 5
```

Watch a run:

```bash
gh run watch <run-id> --repo paperbox9898/Remote-Camera --exit-status
```

View run summary:

```bash
gh run view <run-id> --repo paperbox9898/Remote-Camera --json status,conclusion,url,headSha,displayTitle
```

Report `status`, `conclusion`, `url`, and `headSha` to the user.

## Download APK

Download artifact:

```bash
gh run download <run-id> \
  --repo paperbox9898/Remote-Camera \
  --name app-debug \
  --dir /tmp/remote-camera-apk
```

Copy it into a local ignored artifact folder if the user wants easy access:

```bash
mkdir -p build-artifacts
cp /tmp/remote-camera-apk/app-debug.apk build-artifacts/app-debug.apk
ls -lh build-artifacts/app-debug.apk
```

Do not commit `build-artifacts/app-debug.apk` unless the user explicitly asks.

## Release Build

Use this when the user asks for "release", "GitHub Release", "릴리즈", or wants the APK to appear outside the Actions artifact page.

Recommended flow:

1. Commit and push the intended changes to `main`.
2. Choose the next tag by checking existing tags.
3. Push the tag. The workflow creates a GitHub Release and attaches `app-debug.apk`.

Commands:

```bash
git fetch --tags
git tag --list "v*" --sort=-v:refname | head
git tag v0.x.x
git push origin v0.x.x
```

If the current branch is not `main` but the commit was already pushed to main with `git push origin HEAD:main`, tag the exact commit that was pushed:

```bash
git tag v0.x.x <commit-sha>
git push origin v0.x.x
```

Watch the tag build the same way as a normal APK build. When it succeeds, report both:

- Actions run URL
- Release URL: `https://github.com/paperbox9898/Remote-Camera/releases/tag/v0.x.x`

Do not create or move a release tag without confirming the version number when a version is ambiguous.

Minimal tag example:

```bash
git tag v0.x.x
git push origin v0.x.x
```

The workflow creates a GitHub Release only on tag builds.

## Known Local Build Issue

Local command:

```bash
./gradlew :app:assembleDebug
```

may fail in this checkout with:

```text
gradle-wrapper.jar not found.
```

Use GitHub Actions for APK generation unless the user asks to repair the Gradle wrapper.

## GitHub Actions Warnings

Cache save/restore warnings or Node.js deprecation annotations can appear even when the APK build succeeds. Treat the build as successful if the job conclusion is `success` and `Upload APK Artifact` completed.
