# Research summary

Research checked on 8 October 2026.

## Evidence supporting NILA

- Android AppFunctions lets applications expose capabilities as locally discoverable tools and describes the model as Android MCP: https://developer.android.com/ai/appfunctions
- Android's intelligence system combines AppFunctions, remote MCP and A2UI: https://developer.android.com/ai/intelligence-system
- Google's A2UI sends declarative UI data that clients render with trusted native components instead of executing generated code: https://developers.googleblog.com/introducing-a2ui-an-open-project-for-agent-driven-interfaces/
- Apple App Intents exposes application actions to Siri, Spotlight and system experiences: https://developer.apple.com/documentation/appintents
- Android supports third-party home applications through `ROLE_HOME`: https://developer.android.com/reference/android/app/role/RoleManager
- Gartner forecast a 25% reduction in mobile-app usage by 2027 because assistants will replace some app functions: https://www.gartner.com/en/newsroom/press-releases/2025-01-15-gartner-predicts-mobile-app-usage-will-decrease-25-percent-due-to-ai-assistants-by-2027

## Constraints

- Cross-application AppFunctions access remains experimental and limited to authorized callers.
- Messages, calls, contacts and notifications require explicit Android permissions or default roles.
- MCP does not replace application signing, sandboxing, permissions, installation or updates.
- Arbitrary generated code is unsafe; use declarative UI over a fixed component catalogue.
- A launcher can prove the UX but cannot provide every privilege of an OEM system image.

## Competitive lesson

RabbitOS validates chat-first agents and generated interfaces, but its later card UI also shows why pure conversation is insufficient. NILA differentiates through smartphone-first integration, local personal context, neutral provider comparison, safe generated UI, app fallback and explicit consent.

