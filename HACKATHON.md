# NILA demo status

This file records what the current `main` branch can demonstrate and what still needs proof. Planned work is listed separately so it is not mistaken for shipped behavior.

## Demonstrable now

### Launcher and phone actions

- Android home screen, app drawer, dock, wallpaper and app launching.
- Typed chat and microphone input through the same request path.
- Calculator, alarms, timers, flashlight, camera, notes, calendar, calls, messages, media controls and Android settings redirects.
- Trusted Compose result cards and visible confirmation gates for consequential actions.
- Animated NILA mascot with listening, thinking, talking, success and error states.

### On-device intelligence

- FunctionGemma 270M routes a fixed set of trained tools.
- Gemma 4 E2B handles conversation, speech transcription and health-report reading when its model file is installed.
- Deterministic routing remains available when a model is missing.
- English, Hindi and Malayalam replies can use installed offline Android TTS voices.

The repository contains FunctionGemma dataset generation and a Kaggle fine-tune/export script. A tuned model is accepted only when its held-out tool-name accuracy beats the base model. The repository does not contain a completed calibration report for the Pixel 4a.

### Personal data

- My NILA stores people, relations and facts in an encrypted Room/SQLCipher database.
- Users can add, edit, forget and clear facts, and optionally link a person to a contact.
- My NILA can be protected by the phone's biometric or device credential.
- Health reports and attachments are encrypted locally; follow-up reminders and fitness entries are supported.
- Memory and health data are excluded from Android cloud backup and device transfer.

### Network services

- Swiggy Food supports signed-in search, menus, carts, coupons, payment options and a confirmed order path.
- Zomato can compare matching dishes and quote the same cart when connected.
- Zepto and Swiggy Instamart can be searched side by side and have guarded cart/order flows.
- OpenStreetMap Nominatim and OSRM provide place lookup and driving distance/ETA; turn-by-turn navigation opens a maps app.
- Tavily web search is available when the user adds a key in Settings.

Provider results are live only after the relevant account is connected. The disconnected food fallback is labelled as an offline fixture. Public OSM services and remote MCP schemas are demo dependencies, not production guarantees.

## Safe demo sequence

1. Build and install NILA, then select it as the home app.
2. Confirm the assistant and router model status from the empty chat screen.
3. Try a typed calculator or phone action, then the same kind of request by voice.
4. Save a harmless preference, inspect it in My NILA, edit it and delete it.
5. Ask for a route and open the result in the installed maps app.
6. Connect one commerce provider in Settings and complete search, selection, cart and review.
7. Compare the same item or cart on a second connected provider.
8. Stop at the final review unless the user explicitly confirms a real order with provider, address, items, total and payment method visible.

Use real device observations for every performance or offline claim. Never describe a fixture, failed authentication or missing model as live.

## Validation still required

- Copy both expected `.litertlm` files to the Pixel 4a and measure load time, response latency, memory pressure, thermals and failure recovery.
- Calibrate microphone end-of-speech behavior in quiet and noisy rooms.
- Verify English, Hindi and Malayalam transcription and offline TTS in airplane mode.
- Exercise My NILA across process death, reboot, screen lock, report import and reminder delivery.
- Test Maps with permission denied, location disabled, no network and ambiguous destinations.
- Test each provider after fresh sign-in and retain sanitized response fixtures for parser regression tests.
- Compare final basket totals, not only headline item prices.
- Run repeated chat, voice and provider operations long enough to expose memory or thermal failures.

## Future work, not current behavior

- A selectable low-memory assistant model and one-large-model-at-a-time loading.
- Dynamic MCP discovery and installation.
- Declarative generated apps/skills, My Skills and an expense tracker.
- Semantic/vector memory, provenance, confidence and encrypted export.
- Versioned screenshot-to-action mappings for controlled cross-app computer use.
- Dineout, ONDC and a production maps service.
- Progressive waiting stages, elapsed time, cancellation and background continuation.
- NILA Orbit advertising, verified impressions, earnings and payouts.
- A custom Android ROM or independent operating system.

## Definition of done for a release candidate

- `.\scripts\build-install-test.ps1 -BuildOnly` passes from a clean checkout.
- The APK installs and launches on the target phone.
- Typed and voice requests degrade safely when either model is missing.
- Personal data remains encrypted and excluded from backup.
- Real, fixture and unavailable states are visibly distinct.
- Permission denial, missing network and expired authentication do not crash the app.
- No order, payment, send, publish, booking, deletion or share happens without explicit confirmation.
- Claims in `README.md` and `ARCHITECTURE.md` match the tested build.
