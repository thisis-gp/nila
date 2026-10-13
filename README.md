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

Discovery follows paginated `tools/list`, `resources/list` and `prompts/list` responses, with bounds on pages and total entries. The client also supports `resources/read` and `prompts/get`; resources and prompt templates are not yet surfaced in chat. This implementation currently speaks the 2024-11-05 initialize/session form, not the breaking 2026-07-28 stateless revision (`server/discover`, per-request metadata, and `Mcp-Method`/`Mcp-Name` headers); [the current protocol defines those wire changes](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports). NILA does not implement server-to-client sampling, roots, or notifications. Custom endpoints currently support Streamable HTTP with manual bearer tokens; legacy HTTP+SSE and local stdio servers are not supported. Built-in Swiggy, Zomato and Zepto sign-in flows are provider-specific and do not make arbitrary OAuth MCP servers compatible.

Cross-app actions use Android's app launcher, deep links and system composer/chooser. NILA does not silently tap other apps or use Accessibility to automate arbitrary screens. Calls open the dialer, messages open a prefilled composer, and the person still completes the consequential action in the target app.

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

