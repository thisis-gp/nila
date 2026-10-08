# Contributing

1. Read `AGENTS.md` and the issue assigned to you.
2. Branch from `main` using `feat/`, `fix/` or `chore/`.
3. Add one focused test before changing non-trivial behavior.
4. Run `scripts/build-install-test.ps1 -BuildOnly`.
5. Push the branch and open a pull request. Do not push directly to `main`.
6. Gurupriyan reviews and merges after CI and the relevant device check pass.

Commit examples:

```text
feat: add trusted comparison cards
fix: reject invalid alarm minutes
test: cover app redirect fallback
docs: clarify simulated ordering flow
```

Pull requests must include the actual build/test output and a screenshot or short recording for visible changes.

