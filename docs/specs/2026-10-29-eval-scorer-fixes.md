# Evals: scorer grid shows every run, cases without tags don't crash

**Status:** approved
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

**Commands** (from `backend/`):
- Full test suite: none for the evals. They are scored by command, not by
  pytest (DECISIONS.md, the 2026-10-27 evals entry).
- Single test file: none
- Run the scorer: `uv run --with rich --with fpdf2 python evals/score.py <results folder>`
- Typecheck: none
- Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | none |

Run order: S1, S2.

External blockers: none (Q1 resolved: assumption confirmed by user).

Git state: on `main`, working tree clean. Branch: `fix/eval-scorer`.

## Open questions

1. **RESOLVED (assumption confirmed): score for a case with no tags.** A case with no expected tags
   scores `tag = None`. The category and overall tag averages skip `None`,
   and show `-` when no score is left. Affects S2.

## Suggested splits

No split. S1 and S2 both change `backend/evals/score.py`.

## Out of scope

none

## Subtasks

### S1: The grid uses the same glob as scoring

Fixes this review finding: the grid globs `*__run?.json` but scoring globs
`*__run*.json`, so runs 10 and up are scored but missing from the grid
(`transient`).

- **Goal:** Every run that is scored also has a row in the grid.
- **Blocked by:** none
- **Files:** `backend/evals/score.py`
- **Seams:** the scorer command.
- **Decisions:**
  - The grid iterates over the same run list as scoring: `*__run*.json`,
    without the `.judge.json` files (`transient`).
- **Acceptance criteria:**
  - [ ] Build a temp results folder with `<case>__run1.json` up to
        `<case>__run12.json` for one case. Expected: the scorer command
        prints 12 grid rows for that case.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - Spec change (user's call, 2026-10-29): `uv run ruff check` already
    failed at the base commit (10 errors in `evals/all.py`, `judge.py`,
    `run.py`, `score.py`). They are fixed in a separate commit before S1,
    so the ruff criterion can pass. No behaviour change: `check=False` is
    `subprocess.run`'s default, and the removed `facts` line in `judge.py`
    was unused. Needs no doc: lint hygiene only.

### S2: A case with no expected tags doesn't crash the scorer

Fixes this review finding: `score_run` divides by `len(detail)` with no
guard, so `"tags": {}` raises `ZeroDivisionError` (`transient`).

- **Goal:** A case with no expected tags is scored without a crash.
- **Blocked by:** none
- **Files:** `backend/evals/score.py`
- **Seams:** the scorer command.
- **Decisions:**
  - ASSUMED (Q1): with no tags, `tag = None`, and the averages skip it.
- **Acceptance criteria:**
  - [ ] Use a temp cases folder with one case where `"tags": {}`, plus a
        results folder with one run for it. Expected: the scorer command
        exits 0 and prints the tables, with `-` for that case's average
        tag score.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:** `docs/EVALUATION.md` ("Evals"): "A case may list no
  expected tags. Its tag score is then left out of the averages."

## Human checks

none
