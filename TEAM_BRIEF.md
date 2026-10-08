# NILA — Hackathon Team Brief

## One-line idea

NILA is an intent-first launcher today and an independent mobile operating system tomorrow: people say what they need, services compete behind the scenes, and the right temporary interface appears only when a decision or confirmation is required.

## The problem

Smartphones force users to translate simple intentions into app workflows. To order dinner, send a document, book a ride, or message someone, people must decide which app to open, navigate different interfaces, repeat preferences, and manually compare options.

The phone understands taps but not outcomes. The user is the integration layer.

## Product thesis

Apps do not disappear technically; they become services. NILA replaces permanent app navigation with:

1. **Natural intent** — speak or type the outcome.
2. **Service orchestration** — discover and compare eligible providers.
3. **Generated interface** — show cards, maps, media, or controls only when useful.
4. **Human confirmation** — require approval before messages, purchases, payments, or sensitive sharing.

Core line: **Conversation when you ask. Interface when you choose.**

## Example demo

User: “Chicken biryani under ₹250, delivered within 30 minutes.”

NILA extracts constraints, searches a mock or ONDC-compatible catalogue, and generates comparable cards showing item, final price, rating, and ETA. The user selects one option and confirms payment with a fingerprint. No food-delivery interface is opened.

Second micro-demo: select Rahul and say, “Tell Rahul I’ll reach by 6:30.” NILA clearly changes from Assistant to Person mode, prepares the message, and waits for Send confirmation.

## What makes it different

- Not a chatbot: it generates visual decision surfaces.
- Not a super-app: providers stay independent and compete through open protocols.
- Not permanently an Android layer: the launcher is Phase 1 only.
- Not a fully autonomous agent: NILA searches automatically but asks before consequential actions.
- Not another app store: the core unit is an intent, not an installed application.

## Roadmap

### Phase 1 — NILA Launcher

Hackathon prototype on Android. Conversational home, intent parser, generated result cards, people/model modes, and explicit confirmation.

### Phase 2 — NILA Runtime

An open service registry, personal context layer, permissions, action history, and provider adapters independent of fixed app screens.

### Phase 3 — NILA OS

A native operating system designed around intent. This is not an Android skin or permanent launcher.

## MVP architecture

```text
Voice / text input
        ↓
On-device intent parser
        ↓
Structured intent + constraints
        ↓
Service router / provider adapters
        ↓
Normalized results
        ↓
Generated UI cards
        ↓
Permission policy
        ↓
User confirmation → transaction
```

For the hackathon, use a mock food catalogue or ONDC sandbox/reference integration. Do not claim production ONDC onboarding unless it is actually completed.

## Safety model

- **Read:** search, filter, compare — automatic.
- **Prepare:** draft messages, plans, carts — user reviews.
- **Commit:** purchase, pay, send, or share data — strong confirmation.

## PPT structure — 10 slides

1. **Hook:** “Your intention is simple. Your phone makes it complicated.”
2. **Problem:** the multi-app journey for one biryani order.
3. **Insight:** users should express outcomes, not operate workflows.
4. **Solution:** NILA and the line “The phone after apps.”
5. **Demo flow:** intention → comparison → confirmation → tracking.
6. **Interaction model:** Talk / Show / Act.
7. **Privacy:** on-device understanding and three permission levels.
8. **Architecture:** intent parser, service router, generated UI, action policy.
9. **Roadmap:** Launcher → Runtime → independent OS.
10. **Close:** “Don’t open an app. Say what you need.”

## Suggested team split

- **Product/story:** problem validation, pitch script, competitor positioning.
- **Android:** launcher shell, conversation UI, people/model switch, generated cards.
- **AI/orchestration:** structured intent extraction, provider selection, model fallback.
- **Service integration:** mock/ONDC catalogue, normalization, ordering state machine.
- **Design/demo:** brand, PPT, screen recording, website, demo choreography.

## Claims to avoid

- “There will be zero UI.” Use **no permanent app interfaces**.
- “Apps no longer exist.” Say they become headless services.
- “Everything runs on-device” unless every model and query actually does.
- “Integrated with every app/provider.” Name only implemented integrations.
- “Fully autonomous.” Emphasize partial autonomy and confirmation.
- “We built a complete OS.” The hackathon deliverable is the launcher proof; the independent OS is the roadmap.

## Visual direction

Near-black graphite, electric cyan, ultraviolet, and restrained amber. Use optical glass, fluid service cards, a living intelligence core, and motion that shows many apps collapsing into one intent. Avoid generic robots, glowing brains, and cluttered cyberpunk visuals.

## 60-second pitch

“To order one meal, we open an app, search, filter, compare, check delivery, choose payment, and confirm. Our intention was only one sentence. NILA is an intent-first launcher that lets people say the outcome they want. It searches services, generates the right temporary interface, and asks before anything consequential happens. We begin as an Android launcher to prove the interaction model. Then we build the service runtime and, ultimately, an independent operating system designed around intentions instead of apps. NILA is the phone after apps.”

## Immediate deliverables

- Working launcher interaction prototype
- One reliable end-to-end food-ordering demo
- 10-slide pitch deck
- 60-second demo video backup
- Architecture diagram and privacy explanation
- Marketing website
