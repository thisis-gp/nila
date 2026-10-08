# NILA

NILA is an intent-first, agentic Android launcher. The user states an outcome; NILA answers directly, invokes trusted capabilities, generates temporary UI when choice helps, or opens a full app when the experience itself matters.

## Hackathon proof

This repository proves the interaction model. It is not an independent operating system yet.

- Chat-first Android home screen
- Direct answers for simple requests
- Native Android actions such as alarms
- Safe generated UI from a fixed component catalogue
- Explicit confirmation before consequential actions
- App redirects for movies, games and social media
- Small, user-controlled memory

## Run

Requirements: JDK 17, Android SDK 37, USB debugging and an attached Android device.

```powershell
.\scripts\build-install-test.ps1
```

Build without a device:

```powershell
.\scripts\build-install-test.ps1 -BuildOnly
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Team workflow

Create a branch, make the smallest working change, run the build script, and open a pull request. Gurupriyan reviews and merges. Read [AGENTS.md](AGENTS.md) before changing code.

