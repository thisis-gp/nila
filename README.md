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

## Add an MCP server

Open **Settings → MCP servers**, enter a name and an HTTPS Streamable HTTP endpoint, then add a bearer token if the server requires one. NILA checks the endpoint before saving it. Endpoints and bearer tokens are kept in encrypted app storage and excluded from Android backup; tokens are not sent to the model.

NILA currently discovers only tools with flat scalar inputs (string, integer, number or boolean), up to 16 parameters per tool. Unsupported schemas are skipped. Every discovered tool is treated as consequential: the confirmation card shows the server, tool and supplied arguments, and nothing runs until you confirm. OAuth-only servers and other MCP transports are not supported by this manual setup.

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

