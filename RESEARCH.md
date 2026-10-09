# NILA research and build evidence

Checked on 9 October 2026. This file records the evidence behind the finale build. `HACKATHON.md` contains the execution order.

## Finale rules

The Grand Finale runs for 48 hours in Bengaluru from 9 to 11 October 2026. The official schedule starts at 19:00 Friday and has scored checkpoints at 10:00 and 19:00 Saturday, then 09:00 Sunday. The final submission time is announced on site.

The published finale format assigns 25% to HackTracker device data and 75% to jury evaluation. City guidance explains the jury priorities: phone-first execution, native feel, on-device AI, real-world usefulness, craft and a clear defense of trade-offs. NILA must therefore run on the iQOO phone, use Office Kit visibly, and show a real user outcome rather than a collection of disconnected AI features.

Sources:

- Finale: https://iqoo.reskilll.com/
- Guide and scoring detail: https://iqoo-delhi.reskilll.com/guide
- Terms: https://iqoo-delhi.reskilll.com/terms

The terms say submission work must be original work created during the event, while pre-event idea drafting is allowed. This repository contains a pre-event scaffold and design prototype. Keep its history intact, disclose it to an organiser, and record the answer in the team channel. Do not rewrite history or imply that earlier code was written during the finale.

## Test device

The iQOO 15 uses Snapdragon 8 Elite Gen 5, Android 16, LPDDR5X memory and UFS 4.1 storage. The official page cites a 16 GB + 512 GB test configuration. The exact loan unit must be checked before choosing memory limits.

Source: https://www.iqoo.com/in/products/iqoo15

The build uses LiteRT-LM with GPU acceleration first. NPU support is an experiment because device-specific QNN artifacts can consume the event without improving the judged experience. CPU remains the fallback.

## Locked on-device model stack

### Gemma 4 E2B

Gemma 4 E2B is the conversational and planning model. It writes short answers, summaries and validated skill specifications. The published LiteRT-LM artifact is about 2.58 GB. Google reports a 0.3 second time to first token and about 52 decode tokens per second on a comparable flagship Android GPU. Those figures are reference measurements, not iQOO 15 results.

### FunctionGemma 270M

FunctionGemma is the tool router. It receives the allowed tool schemas and returns one typed function call or a direct-answer decision. Google provides an official Mobile Actions fine-tuning path that converts the result to `.litertlm` for Android. NILA fine-tunes it on English, Hindi and Malayalam requests for the fixed finale tool registry.

### EmbeddingGemma 2 Text 270M

EmbeddingGemma 2 Text creates local multilingual embeddings for semantic memory. Google lists the text-only artifact at about 165 MB. It is used for retrieval, not authorization or financial calculations.

### Runtime

All three models use LiteRT-LM where supported. Load models lazily and avoid keeping every model active. FunctionGemma can remain resident; the assistant and embedding model may be loaded on demand if memory pressure requires it.

Sources:

- LiteRT-LM and published model measurements: https://developers.google.com/edge/litert-lm/overview
- FunctionGemma Mobile Actions: https://ai.google.dev/gemma/docs/mobile-actions
- EmbeddingGemma: https://ai.google.dev/gemma/docs/embeddinggemma

## Speech and Lakshmi

Lakshmi is the name of NILA's voice. The reliable finale implementation uses Android's on-device `SpeechRecognizer` and offline `TextToSpeech` voices.

- Request locale packs for `en-IN`, `hi-IN` and `ml-IN`.
- Check on-device recognition support before creating the recognizer.
- Use partial results when supported.
- Select voices that do not require a network connection.
- Keep typed input and text-only output available when a locale pack is missing.

The installed offline voices may not have identical timbre across languages. Do not claim voice cloning or one physical speaker. Indic Parler-TTS supports English, Hindi and Malayalam, but its 0.9B model and unproven Android conversion are outside the critical path.

Sources:

- Android SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android recognition intents: https://developer.android.com/reference/android/speech/RecognizerIntent
- Indic Parler-TTS: https://huggingface.co/ai4bharat/indic-parler-tts

## Service integrations

### Swiggy

Use the official Swiggy MCP ecosystem:

- Food: restaurant discovery, menus, carts, coupons, ordering and tracking.
- Instamart: grocery discovery and carts.
- Dineout: restaurant discovery, availability and bookings.

Food is the full end-to-end demonstration. Instamart and Dineout prove that the same MCP client and UI vocabulary work across services. Do not handcraft a screen for every tool.

Sources:

- Documentation: https://mcp.swiggy.com/builders/docs/start/what-is-swiggy-mcp/
- Authentication: https://mcp.swiggy.com/builders/docs/start/authenticate/
- Developer entry point: https://mcp.swiggy.com/builders/developers/

Zomato has an official MCP endpoint but its repository restricts third-party use without permission. Do not include it in the finale build unless written approval is received: https://github.com/Zomato/mcp-server-manifest

### Zepto

Zepto has an official MCP endpoint at `https://mcp.zepto.co.in/mcp` for quick-commerce product search, cart management, order placement and order history. Authentication is OAuth: the user enters an Indian mobile number and verifies by OTP in a browser redirect, no sandbox, real consumer orders only.

Zepto overlaps Swiggy Instamart's grocery surface, so it is not part of the locked finale stack. It is a documented Phase 2 option if the team wants a second quick-commerce provider to prove the capability registry and MCP client generalize across companies, not only across Swiggy's own three surfaces. Confirm third-party app embedding terms in Zepto's own repository before building against it, since the published setup examples target AI chat clients, not a native Android MCP client.

Correction confirmed 2026-10-09 by an actual registration attempt: dynamic client registration at `https://auth.zepto.co.in/register` only accepts `localhost` redirect URIs. Any production redirect, including an Android app's custom URI scheme, is rejected with `invalid_redirect_uri` and requires a written allowlist request filed as a GitHub issue on `zeptonow/mcp`, same approval gate as Zomato, just a different channel, response time unknown (multiple other apps have open allowlist requests pending). Zepto also expects the redirect URI to be a real HTTPS URL on a domain the requester controls, not a bare custom scheme. **Zepto is not a faster path than Swiggy** for this reason; both are blocked on an external response.

Sources:

- Repository: https://github.com/zeptonow/mcp

### Maps and device actions

Google Maps Grounding Lite MCP provides place search and routes: https://developers.google.com/maps/architecture/grounding-with-maps-mcp

Use Android platform APIs for alarms, app opening, speech, contacts, messages and device actions. MCP is for external services, not a replacement for Android permissions.

### Open-source MCP ecosystems

Nextcloud can expose files, calendars, tasks, contacts and Talk through its first-party MCP server: https://docs.nextcloud.com/server/stable/user_manual/en/ai_assistant.html

Home Assistant exposes selected devices and entities through its first-party MCP server: https://www.home-assistant.io/integrations/mcp_server/

Both are post-core integrations. Community bridges for rides, messaging, media or finance are not trusted finale dependencies.

## Generated skills

NILA does not generate or execute Android code. It creates declarative skills from a fixed capability registry and trusted Compose components.

A skill contains:

- Name, purpose and version.
- Inputs and triggers.
- Allowed tools and data sources.
- Required permissions.
- Typed workflow steps.
- Local data schema.
- Trusted UI component specification.
- Confirmation policy.
- Provenance and deletion behavior.

The skill compiler rejects unknown tools, undeclared permissions, invalid component data and commit actions without confirmation. Android permissions must already exist in the APK manifest before a skill can request them at runtime: https://developer.android.com/guide/topics/manifest/manifest-intro

The expense tracker is the finale example of a generated skill. It reads transaction-related SMS only after consent, extracts structured transactions locally, categorizes known merchants deterministically, uses Gemma for unknown categories, and generates a dashboard. Raw SMS is not added to semantic memory.

## Local memory

ObjectBox 5.4.2 is the local structured and vector database. It supports Android/Kotlin objects and HNSW nearest-neighbour search. EmbeddingGemma produces the vectors.

Source: https://github.com/objectbox/objectbox-docs/blob/main/on-device-vector-search.md

Memory is divided into:

- Working memory for the current request.
- Semantic memory for preferences and stable facts.
- Episodic memory for past actions and outcomes.
- Skill memory for generated skill definitions and versions.
- Structured private records such as expenses, carts and reminders.

Every durable memory stores its source, type, confidence, sensitivity, timestamps, expiry and owning skill. Users can inspect, correct and delete it. Permission state, confirmations and financial records are queried structurally. Vector similarity never grants authority.

## Constraints that shape the demo

- A launcher does not receive OEM-only privileges.
- SMS and call-log permissions are restricted and require clear disclosure. Google Play recognizes SMS-based money management as a possible exception, subject to review: https://support.google.com/googleplay/android-developer/answer/10208820
- MCP server scopes may be broader than NILA's policy. NILA applies its own Read, Prepare and Commit gate before every tool call.
- Generated UI is declarative. Never run model-generated Kotlin, JavaScript or downloaded executable code.
- Report measured device results only. Vendor and comparable-device numbers are references.
## Verification: 9 October 2026

These checks confirm or correct the stack above. Decisions that follow from them are in `DECISIONS.md` under "Integration decisions".

| Item | Finding | Impact |
|---|---|---|
| Swiggy MCP auth | OAuth 2.1 with PKCE and dynamic client registration. Dev mode allows only `http://localhost` redirects. Custom schemes for Android apps must be registered by emailing builders@swiggy.in. Access tokens last 5 days with no refresh tokens. Authorization codes last 120 seconds. | Register `nila://oauth`; use an in-app loopback redirect until then; re-login before the demo. |
| ObjectBox 5.4.2 | Latest stable Java/Android release (6 May 2026) with HNSW vector search. `6.0.0-beta2` is newer but pre-release. | Confirmed; stay on 5.4.2. |
| EmbeddingGemma 2 | Released 6 October 2026. The LiteRT-LM community repo has an Android-ready 165 MB build and an SM8850 (iQOO 15) build. Google lists 334 MB RAM on Android CPU (S26 Ultra, second load). 768-dimension vectors, Matryoshka down to 128. | Use it; measure RAM on the iQOO 15. |
| FunctionGemma Mobile Actions | Official Colab fine-tunes with Hugging Face TRL and exports `.litertlm`. The Mobile Actions dataset (about 9.65k rows) and the published evaluation are English-only. No official evidence for or against Hindi or Malayalam. | English first; our own Hindi and Malayalam data with held-out evaluation. |
| Android on-device speech | `createOnDeviceSpeechRecognizer` and `isOnDeviceRecognitionAvailable` exist from API 31. `ml-IN` is a supported locale, but an on-device recognizer does not guarantee Malayalam is installed, and `EXTRA_PREFER_OFFLINE` may be ignored. | Test on the iQOO 15 with networking off; keep typed input. |
| Maps Grounding Lite | Endpoint `https://mapstools.googleapis.com/mcp`. Tools: search places, compute routes (driving or walking), weather and place resolution. API key or OAuth; a no-cost demo key exists. No turn-by-turn navigation or live traffic. | Use for places and routes; keep OSM as a free fallback. |
| OpenStreetMap (live test) | Nominatim and Photon returned real Bengaluru biryani restaurants; OSRM returned Indiranagar to Koramangala as 6.0 km, about 8 minutes. All four public Overpass servers failed. OSM has no prices, menus or ordering. | Optional fallback for places and routes only. |
| Other providers | Zomato's MCP blocks third-party apps. Razorpay and Paytm MCPs are merchant-side payment links. No usable official MCP for Uber, Rapido, Ola, Zepto, Blinkit or ONDC. Meta's WhatsApp Business Tools MCP only sets up business accounts. | Swiggy and Maps are the only external MCPs in the build. |
| APK size | Gemma 4 E2B LiteRT-LM is about 2.58 GB, above the 2 GB APK limit. | Side-load or download models; do not bundle. |

Sources:

- Swiggy authentication: https://mcp.swiggy.com/builders/docs/start/authenticate/
- Swiggy Builders Club: https://mcp.swiggy.com/builders
- ObjectBox vector search: https://docs.objectbox.io/on-device-vector-search
- EmbeddingGemma 2 LiteRT-LM: https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm
- Mobile Actions dataset: https://huggingface.co/datasets/google/mobile-actions
- FunctionGemma model card: https://ai.google.dev/gemma/docs/functiongemma/model_card
- Maps Grounding Lite overview: https://developers.google.com/maps/ai/grounding-lite
- ObjectBox releases: https://github.com/objectbox/objectbox-java/releases
- Android RecognizerIntent: https://developer.android.com/reference/android/speech/RecognizerIntent
- FunctionGemma Mobile Actions: https://ai.google.dev/gemma/docs/mobile-actions
- Android SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Maps Grounding Lite MCP: https://developers.google.com/maps/architecture/grounding-with-maps-mcp
- OSM MCP server (no API key): https://glama.ai/mcp/servers/ni-c/osm-mcp
- Zomato MCP terms: https://github.com/Zomato/mcp-server-manifest
- Razorpay MCP: https://hub.docker.com/r/mcp/razorpay
- Paytm MCP: https://paytmpayments.com/docs/mcp-server-overview
