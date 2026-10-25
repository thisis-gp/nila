# NILA architecture

## Current runtime

```text
Voice or text
    -> voice capture and on-device Gemma transcription, when spoken
    -> deterministic fast paths for exact phone actions
    -> FunctionGemma tool routing or Gemma conversation/tool fallback
    -> capability lookup, argument checks and permission class
    -> Android action, MCP provider, local store or app redirect
    -> trusted Compose response
    -> explicit confirmation before Commit actions
```

`MainActivity.kt` coordinates the launcher and renders responses. `NilaEngine.kt` owns deterministic routing, the fixed capability registry and confirmation tokens. `NilaModels.kt` loads the two LiteRT-LM models and passes proposed calls through the same registry checks. Model output never bypasses business rules or confirmation.

The runtime selects an installed assistant only when Android reports enough available memory. Gemma 4 E2B is the current general-assistant catalog entry and supports text, audio and vision. Jimi Gemma 3 1B v8 is excluded: it is a task-specific fine-tune, and a Pixel chat smoke test produced no usable reply. FunctionGemma is a tool router, not a conversation model; its proposals still pass deterministic checks and permissions in NilaEngine.

The current response modes are direct text, trusted native cards and forms, Android actions, live provider flows, and redirects into immersive apps. NILA does not execute model-generated Kotlin, JavaScript or shell code.

## Capabilities and permission boundary

Each registered capability has a stable ID, typed arguments and one permission class:

- Read: search, inspect, calculate and retrieve automatically.
- Prepare: create alarms, timers, notes, calendar drafts, carts and other reversible state.
- Commit: require a visible confirmation before an order, deletion or other consequential action.

Unknown tools and invalid arguments are rejected. Numbers proposed by a model are checked against the user's words for sensitive commands. OAuth tokens stay inside provider clients rather than model prompts.

Settings supports private one-step skills and declarative workflows. A workflow has an exact phrase and up to eight ordered, typed calls to existing capabilities; its encrypted app-private data contains no executable code. Dependencies must point to earlier steps and cycles are rejected. NILA previews the full sequence before starting, revalidates every call at runtime, and asks again before each Commit. Supported local read/prepare actions run in order; provider and app flows hand off to the user and stop later steps. Workflows use built-in capabilities only: dynamically discovered MCP tools confirm and run through the interactive chat flow, which a workflow run cannot host.

The App Builder turns a described idea into a mini-app from a declarative recipe interpreted by trusted Compose code; it never executes model-generated code. Recipes and installed apps live encrypted on the phone, apps preview before install with the data they may touch, and any recipe needing transaction history is labelled unavailable and cannot be installed, because no real bank/UPI transaction source exists yet.

The built-in capability registry is compiled into the app, and Settings can add private MCP servers whose flat-schema tools join the registry at runtime as Commit-class capabilities. FunctionGemma receives every built-in capability schema plus the currently registered MCP tool schemas; built-ins are covered by its fine-tune data, MCP tools are prompt-only with no per-server training guarantee, and every proposed call is still validated through the same registry checks before anything runs.

## Models

- `gemma-4-E2B-it.litertlm`: conversation, tool fallback, speech transcription, report reading and screen-control suggestions.
- `gemma3-1b-it-int4.litertlm`: text-only assistant choice for lower-memory phones.
- `nila-router-functiongemma-run8-q8-ekv4096.litertlm`: the small tool router (4096-token KV cache: the 17-tool prompt needs about 2536).
- LiteRT-LM 0.17.1: Android inference runtime.

The files are not bundled in the APK. NILA looks for them in its internal or app-specific external `models` directory. An installed assistant loads only when Android reports enough available memory (Gemma 4 E2B needs 4 GiB; Gemma 3 1B needs 1.25 GiB). Neither assistant model is validated on the Pixel 4a yet. FunctionGemma remains on demand on memory-tight devices and is not loaded alongside an assistant. When generation fails, NILA records the error category and duration without logging the prompt, shows that it used a fallback, and keeps the deterministic path available.

The assistant tries GPU for text inference. Audio and vision use CPU backends. The installed assistant can be chosen in Settings among models that fit memory; Pixel 4a calibration of load times, latency and thermals is still unverified.

`training/functiongemma/` contains dataset generation, tests and a Kaggle training/export script. It exports only when the tuned held-out tool-name accuracy beats the base model. A successful exported model and device benchmark are release evidence, not repository source.

## Voice

NILA records 16 kHz mono audio directly with `AudioRecord`, stops after detected silence and asks Gemma 4 E2B to transcribe it on-device. English, Hindi and Malayalam share the same typed-request path. Lakshmi speaks supported replies through an installed offline Android TextToSpeech voice and stays silent when only a network voice is available.

The end-of-speech threshold is fixed and still needs Pixel 4a calibration. Downloaded voice packs, noisy-room behavior, multilingual accuracy and thermal/latency results must be verified on the device.

## Local memory

People and facts live in Room over SQLCipher. A random database passphrase is wrapped with an Android Keystore key. My NILA supports facts, relations, optional contact links, correction, deletion and a biometric/device-credential screen lock.

Health metadata uses encrypted preferences; report photos and PDFs use encrypted app-private files. Fitness entries and reminders are local. Backup rules exclude memory, health records and attachments from cloud backup and device transfer.

Retrieval is currently exact and rule-based. Embeddings, vector search, provenance/confidence fields, export and portable encrypted backup are future work. If the Keystore key can no longer decrypt the database, the current recovery path starts a fresh database; it does not recover the old contents.

## Network integrations

- Swiggy Food: OAuth/MCP address, search, menu, cart, coupon, payment option and confirmed order flow.
- Swiggy Instamart: search, cart comparison and confirmed checkout when the provider exposes a supported payment method.
- Zomato: OAuth/MCP dish and cart-price comparison with Swiggy.
- Zepto: OAuth/MCP product search, cart review and confirmed order flow.
- Maps: coarse device location, Nominatim place lookup and OSRM driving distance/ETA, with an Open Maps handoff for turn-by-turn navigation.
- Web search: Tavily when the user supplies a key in Settings.

Provider responses are parsed defensively, but their remote schemas and authentication can change. Public Nominatim and OSRM endpoints are demo fallbacks, not production infrastructure. Automated tests must stop before a real order; live checkout requires a separate, explicit user confirmation.

## Future architecture

The following ideas are not implemented on `main`:

- Generated code or background agents of any kind, and the expense-tracker skill.
- Versioned UI-control mappings and arbitrary cross-app computer use (screen guidance today only suggests controls from a user-picked screenshot).
- EmbeddingGemma/ObjectBox semantic memory.
- Dineout, ONDC and Google Maps MCP integrations.
- Task elapsed time and background continuation beyond the current progressive waiting messages and cancellation.
- NILA Orbit sponsorships, impression accounting, earnings and payouts.
- An independent NILA operating system.

Any future generated UI must remain a validated tree of trusted native components. Any capability that can pay, order, send, publish, book, delete or share must remain behind the same confirmation boundary.
