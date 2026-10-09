# NILA architecture

## Runtime flow

```text
Voice or text
    -> Android on-device speech recognition when needed
    -> deterministic fast paths for calculator and exact device commands
    -> FunctionGemma typed intent and tool call
    -> permission policy
    -> Android capability / MCP provider / generated skill / app redirect
    -> boundary validation and normalized result
    -> answer or trusted Compose component tree
    -> explicit confirmation for Commit actions
    -> execution, action history and memory update
```

Gemma 4 E2B handles conversation, summaries and skill planning. FunctionGemma owns tool selection. Business rules and permissions stay outside both models.

## Response modes

1. Answer: calculations, questions and summaries.
2. Act: Android or MCP operations allowed by the policy.
3. Generate: trusted cards, forms, maps, timelines and saved skills.
4. Redirect: open movies, games, social media or another immersive experience.

## Capability registry

Every capability has a stable ID, typed input and output, owner, permission class, confirmation level and availability check.

Finale capability groups:

- Android: alarms, app opening, speech, contacts, messages and permission requests.
- Swiggy: Food, Instamart and Dineout MCP tools.
- Maps: place and route tools.
- Memory: exact lookup, semantic retrieval, store, correct and forget.
- Skills: create, validate, enable, run, disable and delete.

The model never receives credentials. OAuth tokens stay at the transport boundary. External responses are validated before they reach UI or memory.

## Generated skill runtime

NILA skills are data interpreted by trusted code. They cannot contain Kotlin, JavaScript, shell commands, URLs to executable code or unregistered tool names.

```text
SkillManifest
├── id, name, purpose and version
├── triggers and typed inputs
├── capability IDs
├── required Android permissions
├── workflow steps
├── local record schemas
├── trusted component tree
├── Read / Prepare / Commit policy
└── provenance, state and deletion rules
```

Creation flow:

1. Gemma 4 E2B drafts a skill manifest.
2. The validator rejects unknown fields, tools, permissions and components.
3. NILA shows the skill's data access and actions.
4. The user approves installation and grants Android permissions separately.
5. NILA stores the versioned manifest and executes it through registered capabilities.

Android permissions must be declared in the APK. A generated skill can request only that reviewed subset. Missing capabilities produce an explanation, not generated code.

## Expense tracker skill

The expense tracker demonstrates the skill runtime. It is not compiled into NILA as a special app.

```text
Financial SMS
    -> deterministic transaction detector
    -> amount / direction / merchant / account suffix / timestamp
    -> merchant normalization and known-category rules
    -> Gemma category fallback when confidence is low
    -> structured local transaction
    -> generated dashboard and correction form
```

Raw SMS is processed locally and is not embedded. The skill stores only fields needed for the user's expense history. Corrections update the merchant rule for later transactions.

## Memory system

ObjectBox 5.4.2 stores structured objects and HNSW vectors. EmbeddingGemma 2 Text 270M creates embeddings locally.

Memory types:

- Working: current conversation state; short-lived.
- Semantic: explicit preferences and stable facts.
- Episodic: requests, actions and outcomes.
- Skill: manifests, versions and user corrections.
- Structured private records: transactions, carts, reminders and approvals.

Durable records include source, owner, confidence, sensitivity, creation time, last use, expiry and deletion state. Semantic retrieval filters by memory type and owning skill before ranking by vector distance, confidence and recency.

Exact data uses exact queries. Vector similarity may suggest context but cannot authorize an action, calculate money or override current user input.

## Model runtime

- Gemma 4 E2B: assistant and skill planner.
- FunctionGemma 270M: tool router, fine-tuned for NILA.
- EmbeddingGemma 2 Text 270M: semantic retrieval.
- LiteRT-LM: Android inference with GPU first, NPU only after a measured device win.

Models are loaded lazily. The runtime records load time, first-token time, decode rate, peak memory and failures on the actual iQOO 15. A deterministic path remains available when the assistant model is unavailable.

## Voice

Lakshmi is the voice identity. Android's on-device SpeechRecognizer provides input and offline TextToSpeech voices provide output for English, Hindi and Malayalam. The settings screen exposes downloaded languages, offline availability and a text-only fallback.

## Permission policy

- Read: automatic search, inspection, calculation and retrieval.
- Prepare: automatic drafts, plans, carts, skill creation and generated UI.
- Commit: explicit confirmation before pay, order, book, send, publish, delete or share.

Biometrics confirm the user, but the confirmation screen supplies informed consent. It must show provider, account, destination, data shared and final price before a consequential tool runs.

## Evolution

- Phase 1: Android launcher proving intent-first interaction, skills and permissions.
- Phase 2: NILA Runtime with a wider capability registry, portable skills and richer memory controls.
- Phase 3: independent NILA OS with system-level integration.

The finale proves Phase 1. It does not claim OEM privileges or a complete operating system.
