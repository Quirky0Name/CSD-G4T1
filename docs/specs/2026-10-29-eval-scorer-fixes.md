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
- **Files:** `backend/evals/score.py`, `backend/evals/all.py`
- **Seams:** the scorer command.
- **Decisions:**
  - The grid iterates over the same run list as scoring: `*__run*.json`,
    without the `.judge.json` files (`transient`).
- **Acceptance criteria:**
  - [x] Build a temp results folder with `<case>__run1.json` up to
        `<case>__run12.json` for one case. Expected: the scorer command
        prints 12 grid rows for that case.
  - [x] `uv run ruff check` passes.
  - [x] (added with the `all.py` spec change) `all.py`'s summary, on the
        same 12 runs plus a `.judge.json`, counts 12 runs for that case and
        lists `run12` in the noise floor.
- **Doc deltas:**
  - Spec change (user's call, 2026-10-29, code review): S1's Files also
    include `backend/evals/all.py`. Its `load_runs` globbed `*__run?.json`,
    so its summary and noise floor dropped runs 10 and up. Both scripts
    now take their runs from one `score.run_paths(folder)`
    (`*__run*.json`, without `.judge.json`). Needs no doc: the docs never
    said runs stop at 9.
  - Decision: the grid loops over `runs` itself (each run keeps its
    `path`), not a second glob, so the two lists can't drift apart again.
    Needs no doc: code shows it.
  - Decision: the 12-run check is a throwaway shell script, not a committed
    test, as evals have no test suite (DECISIONS.md, 2026-10-27). Needs no
    doc.
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
- **Files:** `backend/evals/score.py`, `backend/evals/all.py`
- **Seams:** the scorer command.
- **Decisions:**
  - ASSUMED (Q1): with no tags, `tag = None`, and the averages skip it.
- **Acceptance criteria:**
  - [x] Use a temp cases folder with one case where `"tags": {}`, plus a
        results folder with one run for it. Expected: the scorer command
        exits 0 and prints the tables, with `-` for that case's average
        tag score.
  - [x] `uv run ruff check` passes.
  - [x] (added with the `all.py` spec change) `all.py`'s summary, on a
        cases folder with the same tagless case (2 runs) and one tagged
        case (2 runs), doesn't crash and shows `-` as the tagless case's
        tag; the noise floor uses only the tagged case's scores.
- **Doc deltas:**
  - `docs/EVALUATION.md` ("Evals"): "A case may list no expected tags. Its
    tag score is then left out of the averages."
  - Decision: the grid's `tag` cell for such a run shows `-`, like the
    `text` cell does with no judge score. Needs no doc: covered by the
    delta above.
  - Decision: a category or the overall row with no tag score left
    (including one with only placeholder runs) shows `-`, not `0.00` as
    before, so "no score" isn't read as "all wrong". Doc: EVALUATION.md,
    with the delta above.
  - Decision: the diagnostics panel's average tag line shows
    `N/A (no tag scores)` when no tag score is left, like its text line
    does. (First worded "no case lists tags", which was wrong when only
    placeholder runs of tagged cases are left; reworded in code review.)
    Needs no doc: the panel says it.
  - Spec change (user's call, 2026-10-29): S2's Files also include
    `backend/evals/all.py`. Its summary summed `score_run`'s `tag`, so a
    tagless case still crashed `all.py` (`TypeError`). Its per-case tag,
    spread and per-run noise floor now skip `None`, and the per-case tag
    shows `-` when none is left. Doc: EVALUATION.md, with the first delta.
  - Decision: the temp cases folder is reached by a throwaway script that
    sets `score.ROOT` to a temp folder and calls `main()`, because the
    scorer always reads `evals/cases/`. No test is committed (no eval test
    suite, DECISIONS.md 2026-10-27). Needs no doc.
  - Decision (code review): a placeholder run of a tagless case also
    scores `tag = None`, not `0.0`, as Q1 says a case with no tags scores
    `None`. A placeholder of a tagged case still scores `0.0`. Doc:
    EVALUATION.md, with the first delta.
  - Decision (code review): `format_score` (`-` or 2 decimals) moved from
    `all.py` to `score.py`, which `all.py` already imports, and is used for
    the grid's tag and text cells and the OVERALL tag cell. Needs no doc:
    no behaviour change.
  - Decision (code review): one `score.mean(values)` (`None` when empty)
    replaces the six hand-written "average or `-`" steps in `score.py` and
    `all.py`, with `format_score` for display. The diagnostics panel's text
    line now uses the unrounded mean (e.g. `0.333`, was `0.330` from the
    2-decimal string). Needs no doc: display precision only.

## Human checks

none
