# Working in this repository

Swahn Chat (formerly FamilyChat): a self-hosted, end-to-end encrypted messenger for a family.
`backend/` is the FastAPI server for a Raspberry Pi, `android/` the Kotlin/Compose app, `deployment/` the Docker
Compose stack and Pi scripts, `docs/` the documentation (API, SECURITY, ARCHITECTURE, setup guides, DRIFTHANDBOK,
ANVANDARGUIDE). Start with `HANDOVER.md`.

## Rules that do not bend

- No home-made cryptography: libsignal for messages, Tink/BouncyCastle for files and exports, WebRTC DTLS-SRTP for calls.
- No security claims the code does not back up; keep docs/SECURITY.md honest.
- Never commit secrets. Label insecure dev builds as such.
- Approving a new device on your own account is always required (the owner's decision; do not relax it without asking).
- Protocol constants kept from the FamilyChat era (for example `familychat/attachment/v1`, the `FCHX` export format)
  must not be renamed.
- The app's default language is Swedish.

## Who does what

Work is split by model; the subagents live in `.claude/agents/`.

| Work | Who | Model |
|---|---|---|
| Finding files and symbols, reading code, answering "where/how" | `Explore` | Haiku |
| Simple mechanical edits that are already fully specified (renames, strings, data values, the same small change in many places) | `mechanic` | Haiku |
| Regular coding: implementing a specified feature or fix, tests, planned refactors | `implementer` | Sonnet |
| Running a project's checks (tests, build, lint) and reporting only the verdict and failures | `verifier` | Haiku |
| Docs from a short summary: READMEs, notes, commit and PR bodies | `scribe` | Haiku |
| Shipping: commit, push, PR (merge only after the user approves) | `shipper` | Haiku |
| Architecture, trade-offs, hard bugs with an unknown cause, risk review | the main session, or `architect` when the main session runs a smaller model | Opus |

- Delegate search and reading to `Explore` instead of reading many files yourself, and pass it the
  question, not a file list to dump.
- Hand `implementer` a clear spec (what, where, how to verify); hand `mechanic` the exact change.
  Run independent pieces in parallel.
- Keep decisions here: choosing an approach, reviewing what subagents return, and anything that
  spans several systems. Check their results (the diff and the verifier's verdict) before committing.
- Hand `verifier` the part to check; hand `scribe` the facts. `shipper` merges a pull request
  only when told that the user approved that merge.
- Standing approval from the user: after each change, once the checks pass, merge its pull request
  into `main` (merge commit) without asking first.

## Checks

- Backend: `cd backend && ruff check . && ruff format --check . && .venv/bin/python -m pytest -q`
- Android: `cd android && ./gradlew build` (unit tests, detekt, lint), `./scripts/run-e2e.sh` (against a real
  backend), `./gradlew assembleDebugAndroidTest` and, separately, `./gradlew assembleQaAndroidTest -PtestBuildType=qa`.
  Instrumented tests need an emulator; CI runs them (`android.yml`, API 30 and 34, debug then R8-minified qa).
- Snapshot commits that are not meant to run CI carry `[skip ci]`; merge only a green, real head.
