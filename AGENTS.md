# NILA contributor instructions

## Product truth

NILA is a personal, agentic, intent-first phone experience. It can answer, remember, plan, use tools, generate temporary UI and redirect to immersive apps. The launcher is Phase 1; Runtime and independent OS are later phases.

Interaction rule: **answer when simple; generate UI when choice helps; open the full experience when immersion matters; act only with consent.**

Permission rule:

- Read: search, inspect, calculate and compare automatically.
- Prepare: draft, plan, build carts and generate UI automatically.
- Commit: pay, send, publish, book, delete or share only after explicit confirmation.

Never claim a mock provider, simulated order, local model or unimplemented integration is real.

## Current architecture

- `app/src/main/java/com/nila/launcher/NilaEngine.kt`: deterministic intent routing used by the demo.
- `app/src/main/java/com/nila/launcher/MainActivity.kt`: launcher, trusted UI renderer and Android action execution.
- `app/src/test/`: smallest runnable checks for routing logic.
- `scripts/build-install-test.ps1`: build, install, launch and smoke check.

Use one orchestrator and a small tool registry. Do not add a multi-agent framework until a measured failure requires one. Generated UI must select trusted native components; never execute model-generated code.

## 48-hour scope

Prioritize one reliable end-to-end demo: understand a food request, compare providers, generate cards, prepare the action and ask for confirmation. Calculator, alarm, memory and app opening are supporting proof points.

Out of scope: real payments, production ONDC onboarding, unrestricted cross-app control, a custom Android ROM and speculative infrastructure.

## Ownership

- Gurupriyan: architecture, integration, permissions, review and final demo.
- Devananda: Compose launcher screens, generated UI catalogue and visual polish.
- Habis: intent/tool routing, provider adapters, ranking, memory and evaluation.

Coordinate through issues and pull requests. Do not modify another owner's active files without saying so in the PR.

Working notes and agent checkpoints belong in the git-ignored `vault/`; durable product decisions belong in the root documents.

## Engineering rules

- Kotlin, Jetpack Compose, JDK 17 and strict null safety.
- Reuse existing code before adding dependencies or abstractions.
- One function, one purpose. Extract only after three repetitions.
- Validate all external/model data at its boundary.
- Consequential actions require a visible confirmation state.
- New behavior starts with one failing test; bug fixes reproduce the bug first.
- Before declaring done, run `.\scripts\build-install-test.ps1 -BuildOnly` and report the actual result.

## Token discipline

- Work the Ponytail way in `skills/ponytail/SKILL.md`: understand the flow, then stop at the first solution already provided by the repo, standard library, Android platform or an installed dependency.
- Read only the files named by the task plus their direct callers. Use `rg`; do not dump build output, generated files or files already read in the session.
- Keep one work item per session. Run `/compact` when context is about half full and use `skills/context-compression/SKILL.md` for handoffs.
- Do not use subagents or broad web research unless the task requires it.
- Keep agent replies and PR descriptions short; paste failures, not full successful build logs.

## Git workflow

- Branches: `owner/short-topic` (for example, `deva/generated-cards`).
- Commits and PR titles: one line, `area: what changed` (for example, `launcher: render generated cards`).
- Keep commits single-purpose. Do not commit generated APKs, local SDK paths, secrets or agent transcripts.
- PR description: problem, smallest solution, test output, screenshots for UI changes, known limitation.
- Devananda and Habis open PRs; Gurupriyan reviews and merges.

## Context compression

Before ending a long agent session, leave a concise comment in the active issue or PR containing: goal, completed work, exact files changed, commands and results, unresolved blocker, and next smallest action. Do not add session logs or research scratchpads to the repository.
