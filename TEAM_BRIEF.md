# NILA finale team brief

## What we are building

NILA is an intent-first Android launcher. The user asks for an outcome, NILA chooses an allowed capability, shows an interface when a decision helps, and asks before any consequential action.

The finale demonstration has two connected parts:

1. Use Swiggy's real MCP ecosystem to find and prepare food, grocery and dining actions.
2. Let the user ask NILA to create an expense-tracker skill, including its permission request, local data and reusable interface.

The launcher is Phase 1. A reusable NILA runtime and an independent operating system come later.

## Product rules

- Answer when the result is simple.
- Generate trusted UI when the user needs to compare, review or edit.
- Open the full app when immersion matters.
- Read and prepare automatically.
- Commit only after explicit confirmation.
- Never present fixture, simulated or inferred data as real.
- Never execute model-generated code.

## Locked technology

- Kotlin, Jetpack Compose and JDK 17.
- Gemma 4 E2B for conversation and skill planning.
- Fine-tuned FunctionGemma 270M for tool routing.
- EmbeddingGemma 2 Text 270M for memory retrieval.
- LiteRT-LM with GPU acceleration first.
- ObjectBox 5.4.2 for local structured and vector storage.
- Android on-device SpeechRecognizer and offline TextToSpeech.
- Lakshmi as NILA's voice identity.
- Swiggy Food, Instamart and Dineout MCP.
- Google Maps MCP.
- Android platform APIs for phone-owned actions.

## What a generated skill means

A skill is a validated manifest assembled from NILA's existing tools, data sources and Compose components. It can be installed, inspected, disabled and deleted. It cannot add executable code or request a permission that the APK does not declare.

The expense tracker is the first generated skill. It demonstrates that NILA can create a persistent personal capability without creating another app.

## Primary demo

1. Ask Lakshmi for chicken biryani for three under ₹800 before 8 PM.
2. Show the FunctionGemma tool decision.
3. Search Swiggy Food and render comparable cards.
4. Choose an item and prepare the cart.
5. Show the exact provider, address, items and price.
6. Stop for biometric confirmation.
7. Show the action in local history.
8. Search one Instamart item and one Dineout slot through the same capability system.
9. Ask NILA to build an expense tracker.
10. Review its permissions, create the skill and show the generated dashboard.

## Ownership

### Gurupriyan

- Own the runtime architecture and integration branch.
- Implement Read, Prepare and Commit enforcement.
- Implement confirmation and biometric flow.
- Coordinate Office Kit evidence and device checks.
- Review pull requests and run the final demonstration.

### Devananda

- Build the Compose launcher from the approved HTML direction.
- Build the trusted component catalogue.
- Build Swiggy search, comparison, cart and tracking surfaces.
- Build My Skills, permission review and expense-dashboard surfaces.
- Capture screenshots for every UI pull request.

### Habis

- Integrate and fine-tune FunctionGemma.
- Integrate Gemma 4 E2B and EmbeddingGemma 2 through LiteRT-LM.
- Build the Swiggy and Maps adapters.
- Build ObjectBox memory and retrieval.
- Build the skill manifest validator and expense extraction pipeline.
- Maintain multilingual routing and retrieval evaluations.

## Work order

Do not wait for every layer to be finished.

1. Gurupriyan creates the typed capability and response contracts.
2. Devananda builds against fixed fixture objects using those contracts.
3. Habis returns the same contracts from models and providers.
4. Gurupriyan connects the branches and enforces confirmation.

The fixture path stays available and is labelled clearly. It protects the presentation from network failure without being represented as a real result.

## Shared contracts to freeze first

- `NilaIntent`
- `ToolCall`
- `CapabilityDescriptor`
- `PermissionClass`
- `NormalizedResult`
- `TrustedComponent`
- `SkillManifest`
- `MemoryRecord`
- `ActionReceipt`

Keep these contracts small. Add fields only when a working flow needs them.

## Pull request rules

- Branch as `owner/short-topic`.
- Keep one problem per pull request.
- Start new behavior with one failing test.
- Include the exact build or test command and result.
- Include screenshots for visible changes.
- State whether data is real, fixture or simulated.
- Do not push directly to `main`.

## Scope cuts

- No custom ROM.
- No arbitrary app or APK generation.
- No real payment integration.
- No unofficial food, ride or messaging API.
- No general multi-agent framework.
- No cloud memory.
- No pitch polishing until the primary demo is stable.

## What to show judges

- The launcher running as Home on the iQOO 15.
- Local model inference with measured device latency.
- A real Swiggy search and visible transaction boundary.
- A skill created from a natural-language request.
- Semantic recall from local memory.
- Permission denial and safe recovery.
- Office Kit used throughout the build.

## Current risks

- Swiggy authentication or network access can fail on site.
- Offline Hindi and Malayalam speech packs may not be installed.
- Keeping all models resident may exceed the loan device's practical memory budget.
- SMS access may require additional device settings and is restricted for store distribution.

Use the fallback paths in `HACKATHON.md`; do not hide a failure or change the claim.
