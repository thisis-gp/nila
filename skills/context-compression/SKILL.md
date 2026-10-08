---
name: context-compression
description: Preserve enough state for another Claude Code or Codex session to resume active NILA work without rereading the repository.
---

# Context compression

Use when the context window is nearly full, a session is ending mid-task, or work is handed to another agent.

Post the checkpoint on the active GitHub issue or pull request. Do not commit agent transcripts or temporary checkpoint files.

Use this exact structure:

```text
Goal:
Done:
Files changed:
Commands and actual results:
Open blocker:
Next smallest action:
```

Keep it factual and under 200 words. Include file paths and failing error text only when it is needed to resume. Never include credentials, private user data or hidden chain-of-thought.

