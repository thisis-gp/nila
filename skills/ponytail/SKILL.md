---
name: ponytail
description: Build NILA with the smallest correct change and no speculative abstractions or dependencies.
---

# Ponytail, lazy senior developer mode

Before writing code, understand the task and its real call path, then stop at the first rung that works:

1. Does this need to exist?
2. Does it already exist in the repo?
3. Does the standard library do it?
4. Does Android provide it natively?
5. Does an installed dependency solve it?
6. Can it be one line?
7. Only then, write the minimum code that works.

Fix root causes in the shared path, not symptoms in individual callers. Prefer deletion over addition, boring code over clever code and the fewest files possible.

Do not simplify away input validation, security, accessibility, error handling that prevents data loss, hardware calibration or explicit requirements. Non-trivial logic leaves one smallest runnable check behind.

Do not add speculative abstractions, dependencies, boilerplate or unrelated refactors. Mark an intentional shortcut with a `ponytail:` comment that names its limit and when to replace it.
