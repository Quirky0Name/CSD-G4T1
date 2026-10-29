# Updating: share the token check with Research Evaluation

**Status:** draft
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

**Commands** (from `backend/`):
- Full test suite: `uv run pytest`
- Single test file: `uv run pytest <path>`
- Typecheck: none
- Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |

Run order: S1.

External blockers: none.

Git state: on `main`, working tree clean. Branch: `refactor/updating-user-token`.

## Suggested splits

No split: one subtask.

## Out of scope

none

## Subtasks

### S1: One bearer-token decode in common/ (prefactor)

Fixes this review finding: `updating/auth.py` `require_user` copies the
bearer parsing, the `jwt.decode` options, the error mapping and
`jwt_key` from `research_evaluation/auth.py` (`transient`).

This changes no behaviour.

- **Goal:** Updating's `require_user` and Research Evaluation's
  `require_service_token` both use one decode function in
  `backend/src/common/`.
- **Blocked by:** none
- **Files:**
  - new `backend/src/common/bearer_token.py`
  - `backend/src/updating/auth.py`
  - `backend/src/research_evaluation/auth.py`
  - new `backend/tests/common/test_bearer_token.py`
- **Seams:**
  - `POST /run-poll` (Updating)
  - `POST /evaluate/changes` (Research Evaluation)
  - the new common function
- **Decisions:**
  - The status codes and messages stay exactly as now
    (code: `backend/src/updating/auth.py`,
    `backend/src/research_evaluation/auth.py`) (`transient`):
    - 401 "Missing bearer token"
    - 401 "Invalid token"
    - 403 "User token required"
    - 403 "Service token required"
- **Acceptance criteria:**
  - [ ] The existing tests pass unchanged: `uv run pytest`.
  - [ ] `tests/common/test_bearer_token.py` covers a missing header, a bad
        token, an expired token, a user token and a service token.
  - [ ] `uv run ruff check` passes.
  - [ ] `grep -rn "jwt.decode" backend/src` shows exactly one call site.
- **Doc deltas:** none.

## Human checks

none
