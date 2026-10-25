# NILA

NILA is an intent-first Android launcher. Say or type what you want; NILA can answer, use a trusted phone or service capability, show a native card when a choice needs review, or open the full app when that experience matters.

This repository is a working launcher prototype, not an independent operating system.

## What works today

- Home screen, app drawer, app search and chat.
- Typed and microphone requests with on-device Gemma inference when the model files are installed.
- Phone actions including alarms, timers, flashlight, camera, notes, calendar, calls, messages and media controls.
- Trusted native cards for food, groceries, routes, files, photos, health records and fitness.
- User-authored, one-step skills: exact phrases can invoke an existing NILA tool; their data is encrypted locally.
- User-authored workflows: exact phrases preview up to eight existing tools, then run supported read/prepare steps in order; each Commit pauses for confirmation and interactive provider steps hand off to the user.
- Private App Builder mini-apps: a described idea becomes a declarative recipe rendered by trusted Compose code; apps with transaction views are labelled unavailable and cannot be installed until real bank/UPI access exists.
- On-device model selection in Settings, gated by available memory; deterministic behavior remains when no model fits.
- Screen-control suggestions from a user-picked screenshot: on-device vision proposes visible controls only; NILA never taps automatically.
- Swiggy Food, Swiggy Instamart, Zomato and Zepto account connections and guarded commerce flows.
- OpenStreetMap place lookup and route estimates, with a maps-app handoff for navigation.
- My NILA facts, people, reminders and encrypted health records stored on the phone.
- Explicit confirmation before consequential actions.

Voice capture is processed by the on-device Gemma 4 E2B model. Transcription language coverage has not been validated across phones. Spoken replies use only voices advertised by the installed Android TTS engine; NILA stays silent if the selected reply language has no installed offline voice. Network-dependent voices are reported separately and are never selected.

FunctionGemma routes a fixed set of tools. Gemma 4 E2B provides conversation, transcription and report reading. Deterministic phone actions still work when a model is unavailable.

Generated code/agents, unattended cross-app control, semantic vector memory and NILA Orbit earnings remain future work. Workflows and mini-apps are declarative data, not generated code.

## Add an MCP server

Open **Settings → MCP servers**, enter a name and an HTTPS Streamable HTTP endpoint, then add a bearer token if the server requires one. NILA checks the endpoint before saving it. Endpoints and bearer tokens are kept in encrypted app storage and excluded from Android backup; tokens are not sent to the model.

NILA currently discovers only tools with flat scalar inputs (string, integer, number or boolean), up to 16 parameters per tool. Unsupported schemas are skipped. Every discovered tool is treated as consequential: the confirmation card shows the server, tool and supplied arguments, and nothing runs until you confirm. OAuth-only servers and other MCP transports are not supported by this manual setup.

Discovery follows paginated `tools/list`, `resources/list` and `prompts/list` responses, with bounds on pages and total entries. The client also supports `resources/read` and `prompts/get`; resources and prompt templates are not yet surfaced in chat. This implementation currently speaks the 2024-11-05 initialize/session form, not the breaking 2026-07-28 stateless revision (`server/discover`, per-request metadata, and `Mcp-Method`/`Mcp-Name` headers); [the current protocol defines those wire changes](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports). NILA does not implement server-to-client sampling, roots, or notifications. Custom endpoints currently support Streamable HTTP with manual bearer tokens; legacy HTTP+SSE and local stdio servers are not supported. Built-in Swiggy, Zomato and Zepto sign-in flows are provider-specific and do not make arbitrary OAuth MCP servers compatible.

Cross-app actions use Android's app launcher, deep links and system composer/chooser. NILA does not silently tap other apps or use Accessibility to automate arbitrary screens. Calls open the dialer, messages open a prefilled composer, and the person still completes the consequential action in the target app.

## Run

Requirements: JDK 17 and Android SDK 36. USB debugging and an authorized Android device are needed for install and launch checks.

```powershell
.\scripts\build-install-test.ps1
```

Build without a device:

```powershell
.\scripts\build-install-test.ps1 -BuildOnly
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The model files are not bundled in the APK:

- `gemma-4-E2B-it.litertlm` (assistant; `gemma3-1b-it-int4.litertlm` is an optional lower-memory choice)
- `nila-router-functiongemma-run8-q8-ekv4096.litertlm` (tool router)

NILA looks for them in its internal or app-specific external `models` directory. Without them, the app reports the missing model and keeps its deterministic fallback behavior. The repository does not distribute model weights.

## Privacy and safety

Facts and people are stored in Room over SQLCipher with a passphrase protected by Android Keystore. Health metadata and attachments use encrypted local storage. Android backup rules exclude this private data.

Provider and maps features need the network. Account tokens stay in provider clients and are not placed in model prompts. Live orders are consequential actions and require the app's visible confirmation flow.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the implemented boundaries and [HACKATHON.md](HACKATHON.md) for the current demo checklist and unimplemented ideas.

## Team workflow

Create a branch, make the smallest working change, run the build script, and open a pull request. Gurupriyan reviews and merges. Read [AGENTS.md](AGENTS.md) before changing code.

