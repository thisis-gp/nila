# NILA architecture

```text
Voice or text
    -> intent and constraint extraction
    -> permission policy
    -> native tools / MCP providers / app redirect
    -> normalized result
    -> direct answer or trusted generated UI
    -> user confirmation for commit actions
    -> execution, monitoring and report
```

## Response modes

1. **Answer:** calculations, questions and summaries.
2. **Act:** safe native operations such as preparing an alarm.
3. **Generate:** cards, forms, maps or timelines from a trusted component catalogue.
4. **Redirect:** launch movies, games, social media or another immersive experience.

## Evolution

- Phase 1: Android launcher proving the interaction model.
- Phase 2: NILA Runtime with capability registry, memory, permissions and action history.
- Phase 3: independent NILA OS with system-level integration.

The demo router is deliberately deterministic. Replace it behind the same typed responses when the model/tool runtime is ready.

