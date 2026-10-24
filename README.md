# NILA

NILA is an intent-first Android launcher. Say or type what you want; NILA can answer, use a trusted phone or service capability, show a native card when a choice needs review, or open the full app when that experience matters.

This repository is a working launcher prototype, not an independent operating system.

## What works today

- Home screen, app drawer, app search and chat.
- Typed and microphone requests with on-device Gemma inference when the model files are installed.
- Phone actions including alarms, timers, flashlight, camera, notes, calendar, calls, messages and media controls.
- Trusted native cards for food, groceries, routes, files, photos, health records and fitness.
- User-authored, one-step skills: exact phrases can invoke an existing NILA tool; their data is encrypted locally.
- Swiggy Food, Swiggy Instamart, Zomato and Zepto account connections and guarded commerce flows.
- OpenStreetMap place lookup and route estimates, with a maps-app handoff for navigation.
- My NILA facts, people, reminders and encrypted health records stored on the phone.
- Explicit confirmation before consequential actions.

Voice capture is processed by the on-device Gemma 4 E2B model. Transcription language coverage has not been validated across phones. Spoken replies use only voices advertised by the installed Android TTS engine; NILA stays silent if the selected reply language has no installed offline voice. Network-dependent voices are reported separately and are never selected.

FunctionGemma routes a fixed set of tools. Gemma 4 E2B provides conversation, transcription and report reading. Deterministic phone actions still work when a model is unavailable.

Generated multi-step skills/apps, dynamic MCP installation, semantic vector memory, cross-app screenshot guidance, selectable models and NILA Orbit earnings are planned work; they are not part of the current `main` build.

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

The two model files are not bundled in the APK:

- `gemma-4-E2B-it.litertlm`
- `functiongemma-mobile-actions_q8_ekv1024.litertlm`

NILA looks for them in its internal or app-specific external `models` directory. Without them, the app reports the missing model and keeps its deterministic fallback behavior. The repository does not distribute model weights.

## Privacy and safety

Facts and people are stored in Room over SQLCipher with a passphrase protected by Android Keystore. Health metadata and attachments use encrypted local storage. Android backup rules exclude this private data.

Provider and maps features need the network. Account tokens stay in provider clients and are not placed in model prompts. Live orders are consequential actions and require the app's visible confirmation flow.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the implemented boundaries and [HACKATHON.md](HACKATHON.md) for the current demo checklist and unimplemented ideas.

## Team workflow

Create a branch, make the smallest working change, run the build script, and open a pull request. Gurupriyan reviews and merges. Read [AGENTS.md](AGENTS.md) before changing code.

