---
name: git-workflow
description: Create focused NILA branches, commits and pull requests that Gurupriyan can review quickly.
---

# Git workflow

1. Sync `main` and create `feat/<name>`, `fix/<name>` or `chore/<name>`.
2. Keep the change inside the assigned task and avoid unrelated refactors.
3. Run the smallest relevant test, then the build check before push.
4. Commit one purpose at a time using Conventional Commits.
5. Push the branch and open a PR using `.github/PULL_REQUEST_TEMPLATE.md`.
6. Do not merge your own PR. Gurupriyan reviews and merges.

Allowed commit prefixes: `feat`, `fix`, `test`, `docs`, `chore`, `refactor`.

Never commit APKs, local SDK paths, secrets, personal data, generated build directories, agent transcripts or unverified claims.

