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

The current response modes are direct text, trusted native cards and forms, Android actions, live provider flows, and redirects into immersive apps. NILA does not execute model-generated Kotlin, JavaScript or shell code.

## Capabilities and permission boundary

Each registered capability has a stable ID, typed arguments and one permission class:

- Read: search, inspect, calculate and retrieve automatically.
- Prepare: create alarms, timers, notes, calendar drafts, carts and other reversible state.
- Commit: require a visible confirmation before an order, deletion or other consequential action.

Unknown tools and invalid arguments are rejected. Numbers proposed by a model are checked against the user's words for sensitive commands. OAuth tokens stay inside provider clients rather than model prompts.

Settings supports user-authored one-step skills: an exact phrase, an existing capability ID and typed arguments. The skill data is encrypted in app-private preferences. At runtime NILA revalidates every call through the same capability registry, and Commit actions still produce the normal confirmation card. Skills cannot add permissions or contain executable code.

The registry is currently compiled into the app. FunctionGemma receives only the tool set used by its training data because adding unseen schemas reduced routing accuracy during device testing.

## Models

- `gemma-4-E2B-it.litertlm`: conversation, tool fallback, speech transcription and health-report reading.
- `functiongemma-mobile-actions_q8_ekv1024.litertlm`: the small tool router.
- LiteRT-LM 0.17.1: Android inference runtime.

The files are not bundled in the APK. NILA looks for them in its internal or app-specific external `models` directory. Both assistant and router are loaded in the background and remain resident while the launcher screen owns `NilaBrain`; deterministic behavior remains available if a model is missing.

The assistant tries GPU for text inference. Audio and vision use CPU backends. The current model names and loading policy are fixed in code; selectable models, single-model low-memory loading and completed Pixel 4a calibration are future work.

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

- Multi-step generated skills, generated native apps and the expense-tracker skill.
- Dynamic MCP discovery, installation, versioned UI-control mappings and arbitrary cross-app computer use.
- EmbeddingGemma/ObjectBox semantic memory.
- Dineout, ONDC and Google Maps MCP integrations.
- Progressive task stages, elapsed time, cancellation and background continuation.
- NILA Orbit sponsorships, impression accounting, earnings and payouts.
- An independent NILA operating system.

Any future generated UI must remain a validated tree of trusted native components. Any capability that can pay, order, send, publish, book, delete or share must remain behind the same confirmation boundary.
