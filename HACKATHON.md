# 48-hour finale build plan

## Goal

Win with one coherent demonstration: NILA understands an outcome on the phone, uses a real service, generates the interface needed for the decision, remembers relevant context, and creates a reusable personal skill without executing generated code.

## Official schedule

- Friday 17:00: check-in and device distribution.
- Friday 19:00: build clock starts.
- Saturday 10:00: scored checkpoint 1.
- Saturday 19:00: scored checkpoint 2.
- Sunday 09:00: scored checkpoint 3.
- Sunday: final submission and jury presentation at the announced time.
- Sunday 18:00: results.

The finale page assigns 25% to HackTracker device data and 75% to jury evaluation. Keep the iQOO phone connected through Office Kit during real work, use shared clipboard and file transfer, and preserve device evidence. Do not manufacture activity.

## Build lock

### Core demonstration

1. Ask by voice or text: "Find chicken biryani for three under ₹800, arriving before 8 PM."
2. FunctionGemma emits a typed Swiggy search call.
3. NILA renders comparable trusted cards from real Food MCP results.
4. The user chooses an option and reviews the exact cart, address, price and provider.
5. NILA stops at a visible biometric confirmation before the commit action.
6. NILA records the approved or cancelled action locally.

### Ecosystem proof

- Instamart: search for one grocery item and prepare a cart.
- Dineout: find a restaurant and prepare a time slot.
- Google Maps: show the selected place or route.
- Android: calculate, set an alarm and open an immersive app.

### Self-building phone proof

The user says, "Build me an expense tracker." NILA generates a declarative skill, explains the SMS permission, asks the user to enable it, extracts structured transactions locally, and renders a reusable expense dashboard. The skill appears in My Skills and can be disabled or deleted.

## Locked stack

- Gemma 4 E2B: conversation, summaries and skill specifications.
- FunctionGemma 270M: fine-tuned tool routing.
- EmbeddingGemma 2 Text 270M: semantic memory.
- LiteRT-LM: local model runtime, GPU first.
- ObjectBox 5.4.2: structured data and vector search.
- Android SpeechRecognizer and TextToSpeech: offline voice path.
- Lakshmi: NILA's voice identity.
- Swiggy Food, Instamart and Dineout MCP.
- Google Maps MCP.
- Jetpack Compose trusted component catalogue.

## Permission policy

- Read: search, inspect, calculate, retrieve and compare automatically.
- Prepare: draft, plan, generate a skill, build a cart and create UI automatically.
- Commit: pay, place an order, book, send, publish, delete or share only after explicit confirmation.

A generated skill may use only manifest-declared permissions and registered capabilities. Vector retrieval never bypasses this policy.

## Checkpoint plan

### Checkpoint 1: working phone core

Target: Saturday 10:00.

- Pair Office Kit and establish the event branch.
- Build, install and select NILA as the home launcher.
- Load FunctionGemma on the iQOO phone.
- Convert English, Hindi and Malayalam requests into typed tool calls.
- Connect Swiggy authentication and list Food tools.
- Render one real search response in trusted cards.
- Keep a deterministic fixture fallback with a visible "offline fixture" label.

Checkpoint evidence: phone demo, commit hash, model latency, screenshot and one passing routing test.

### Checkpoint 2: complete transaction boundary

Target: Saturday 19:00.

- Complete Food search, item selection, cart preparation and confirmation.
- Add one Instamart search and one Dineout search.
- Add Google Maps place or route output.
- Add action history and visible Read, Prepare and Commit states.
- Add Android alarm and app redirect.
- Load Gemma 4 E2B for answers and summaries.

Checkpoint evidence: one uninterrupted food flow, clear real/simulated labels, actual device timings and failure recovery.

### Checkpoint 3: memory and generated skill

Target: Sunday 09:00.

- Add ObjectBox memory records and EmbeddingGemma retrieval.
- Generate and validate a skill manifest.
- Implement the expense tracker skill with synthetic fixtures first.
- Request SMS access only with a clear explanation and user action.
- Add My Skills, inspect, disable and delete.
- Test English, Hindi and Malayalam voice requests.

Checkpoint evidence: create the skill live, retrieve a relevant memory, show permission denial behavior and delete the skill's data.

### Final block

- Fix only demo-breaking issues.
- Measure cold start, tool-routing latency, first-token latency and peak memory.
- Test without network and with expired provider authentication.
- Test permission denial and cancellation.
- Record a backup demo video outside the repository.
- Update the README with only verified claims.
- Prepare the final pitch after the working demo is stable.

## Team ownership

- Gurupriyan: integration, architecture, permissions, confirmations, review, Office Kit evidence and final demo.
- Devananda: Compose launcher, trusted component catalogue, Swiggy result surfaces, My Skills and visual polish.
- Habis: FunctionGemma, Gemma runtime, MCP adapters, ObjectBox memory, EmbeddingGemma and evaluation.

Each owner works on `owner/short-topic`, opens a focused pull request and includes the exact command result. Do not modify another owner's active files without coordinating first.

## Definition of done

- A clean clone builds through `scripts/build-install-test.ps1`.
- The APK installs and runs as the iQOO 15 home launcher.
- The primary demo works from voice request to prepared Swiggy action and confirmation.
- A generated expense-tracker skill can be created, used and deleted.
- All model inference and personal memory used in the demo stay on-device.
- Real, fixture and simulated data are visibly distinguished.
- Denied permissions, missing network and failed authentication degrade safely.
- No pay, order, send, book, delete or share action runs without confirmation.

## Scope cuts

- No custom Android ROM.
- No arbitrary code or APK generation.
- No real payment integration.
- No unofficial provider API.
- No multi-agent framework.
- No community MCP in the critical path.
- No attempt to perfect every screen before the main demo works.

## Judge answers

**Is this only a launcher?**

The finale build is a launcher because it is the fastest way to prove the interaction model on a real phone. The capability registry, skills, memory and permission policy are the reusable runtime for a future OS.

**How is this different from Gemini or Siri?**

NILA treats an intent as the primary interface. It can create a persistent skill and its temporary UI from trusted building blocks, connect to service tools, and keep personal memory under one visible permission policy.

**Does the model generate applications?**

It generates validated skill manifests and component trees. It cannot execute generated code or call tools outside NILA's registry.

**What runs locally?**

Tool routing, assistant inference, embeddings, memory, skill validation and supported speech paths. Swiggy and Maps calls leave the device because they are network services.

**Can it work on cheaper phones?**

The architecture is tiered. FunctionGemma and deterministic tools provide the minimum experience. Larger assistant and embedding models are downloaded capabilities for devices that can support them.

**Why a vector database?**

Structured storage answers exact questions. Vector search finds relevant preferences and past context when the user's wording changes. Permissions and financial records remain structured.
