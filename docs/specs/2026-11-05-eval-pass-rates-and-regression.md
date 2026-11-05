# Evals: pass/fail thresholds, pass rates, run records, regression compare, judge checks, metamorphic cases

**Status:** approved
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

Source of the task: the course's "Software Testing" slides 25–30 (testing an
AI feature), applied to `backend/evals/` (EVALUATION.md "Evals").

**Commands** (from `backend/`):
- Full test suite: `uv run pytest` (the evals have none: they are scored by
  command, not by pytest, DECISIONS.md 2026-10-27)
- Single test file: none for the evals
- Run the scorer: `uv run --with rich --with fpdf2 python evals/score.py <results folder>`
- Build cases without Gemini: `uv run --with rich --with fpdf2 python evals/run.py --dry [case ...]`
- Typecheck: none
- Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | S1 |
| S3 | none |
| S4 | S2, S3 |
| S5 | none |
| S6 | S2 |

Run order: S1, S2, S3, S4, S5, S6.

External blockers: `docs/specs/2026-11-05-eval-realistic-pdfs.md` changes
the same files (`score.py`, `run.py`, `judge.py`, `cases/`). Run the two
specs one after the other, each on a branch from `main` after the other
has merged, not in parallel.

Git state: branch `feat/ai-eval-output` (already checked out, no commits
ahead of `main`). Uncommitted changes that must stay out of every commit:
- `backend/evals/cases/01_correction_effect_size.json` (editor reformat only,
  no content change)
- `frontend/csd-frontend-vite/package-lock.json`
- `docs/specs/2026-11-04-in-app-report-notification.md` (another task's spec)
- `docs/specs/2026-11-05-eval-realistic-pdfs.md` (the other spec)

## Suggested splits

No split: all subtasks change `backend/evals/score.py` (S5 through
`score.run_paths`, S6 through the variant table) or build on S1's verdicts,
and all add to EVALUATION.md "Evals".

## Out of scope

- Realistic case PDFs (rich layout, Unicode font, longer cases, new
  scenarios): `docs/specs/2026-11-05-eval-realistic-pdfs.md` (user,
  2026-11-05). `transient`.
- Cases from real PDFs, and cases for investigation (user, 2026-11-05).
  ROADMAP.md already lists them (`transient`).
- A quality/actionability rubric for the recommendation text (user,
  2026-11-05). `transient`.
- Changing production generation settings (temperature, seed) in
  `impact/llm.py` (user, 2026-11-05). Doc delta on S3.
- Recording the git commit of a run (user: "can remove"). `transient`.
- Running the evals against Gemini, recording the baseline and filling in
  the hand-check CSV: Human checks, not subtasks (`transient`).
- Making the evals a pytest suite (DECISIONS.md 2026-10-27 rejects it).
  `transient`.

## Interfaces

- **`evals/thresholds.json`** (S1, read by S2, S4, S6):
  ```json
  {"tag": 0.8, "text": 0.8, "max_hard_fails": 0, "max_invented": 0,
   "max_misattributed": 0, "case_pass_rate": 0.67}
  ```
  Doc delta on S1 (EVALUATION.md).
- **`score.verdict(r: dict, thresholds: dict) -> dict`** (S1, used by S2,
  S4, S6): `r` is `score_run`'s result. Returns
  `{"result": "pass" | "fail" | "unjudged" | "error", "failures": list[str]}`,
  failures from: `hard_fail`, `wrong_tag` (tag below threshold),
  `missed_fact` (a `must_include` failed), `wrong_fact` (a
  `must_not_include` failed), `invented_quote`, `misattributed_quote`.
  `failures` is filled for `fail`; `unjudged` still lists the structured
  failures found so far; `error` has none. `transient` (code shows it).
- **`score.pass_rate(runs: list[dict], thresholds: dict) -> dict`** (S2, used
  by S4, S6): `{"passed": int, "counted": int, "rate": float | None,
  "unjudged": int, "error": int}`, `rate` None when `counted` is 0.
  `transient`.
- **`<results folder>/meta.json`** (S3, read by S4):
  ```json
  {"runs": {"<case>__run<N>": {"started_at": "<ISO UTC>",
            "prompt_version": 4, "models": ["gemini-flash-latest", "..."],
            "generation": {"temperature": null, "seed": null},
            "case_sha256": "<hex of the case file bytes>"}}}
  ```
  `--into` adds entries; an entry for a run that is re-run is replaced.
  Doc delta on S3 (EVALUATION.md).
- **Judge file names** (S5): the judge writes `<run>.judge.json` by default
  and `<run>.judge-<label>.json` with `--label <label>`; every file whose
  name contains `.judge` is a grade, not a run. Doc delta on S5.

## Subtasks

### S1: Each run gets a pass/fail verdict against fixed thresholds

- **Goal:** Every scored run has a verdict (`pass`, `fail`, `unjudged`,
  `error`) and, when it fails, the failure types, decided by thresholds
  written in a file before the run.
- **Blocked by:** none
- **Files:** `backend/evals/thresholds.json` (new), `backend/evals/score.py`,
  `docs/EVALUATION.md`
- **Seams:** the scorer command; `score.verdict`.
- **Decisions:**
  - Thresholds live in `evals/thresholds.json`, values as in Interfaces:
    a run passes with `tag >= 0.8`, `text >= 0.8`, zero `hard_fail_if`
    hits, zero invented quotes and zero misattributed quotes (user,
    2026-11-05).
  - Unknown-source quotes never fail a run, as the prompt has no label for
    an alert's line yet (user, 2026-11-05).
  - A tagless case (`tag` None) skips the tag threshold (docs:
    EVALUATION.md, "A case may list no expected tags"). A judged run whose
    case lists no facts (`text` None with a `.judge.json` present) skips
    the text threshold the same way (code: `score.py`, `score_run`).
  - A PLACEHOLDER run is `error` (usually quota, EVALUATION.md); a run
    without a `.judge.json` is `unjudged` (user, 2026-11-05).
  - The grid gets a `verdict` column (`pass` green, `fail` red with its
    failure types, `unjudged`/`error` dim).
- **Acceptance criteria:**
  - [x] Throwaway script on a temp results folder with five runs of one
        case built to hit each outcome (all met; one hard fail; tag 0.5; a
        `must_not_include` present; placeholder) plus one run with no
        `.judge.json`: `score.verdict` returns `pass`, `fail [hard_fail]`,
        `fail [wrong_tag]`, `fail [wrong_fact]`, `error`, `unjudged`.
  - [x] Same folder through the scorer command: exits 0 and the grid shows
        each run's verdict.
  - [x] Changing `tag` in `thresholds.json` to 0.5 turns the tag-0.5 run
        into `pass` (same script).
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals", "The scores": the verdict, its four values, the
    failure types, and `thresholds.json` with its values; the thresholds
    are set before a run and changed only on purpose, never to make a run
    pass.
  - (decision) `missed_fact` and `wrong_fact` are reported only when the
    text score is below the `text` threshold: they name which kinds of
    statement the judge failed. One failed statement in a case with five
    or more statements leaves text at 0.8 or above, and the run can still
    pass. Why: the spec's pass rule is `text >= 0.8`, not "every fact met".
  - (decision) A hard fail, invented or misattributed quote counts against
    the threshold as "more than `max_*`" (`len(hard) > max_hard_fails`).
    Why: `max_*` = 0 means none allowed. No doc beyond the thresholds
    themselves.
  - (decision) `score_run` also returns `judged` (a `.judge.json` exists)
    and `failed_kinds` (the kind of each failed statement), which
    `verdict` reads. No doc needed (code shows it).
  - (decision) The grid's `verdict` column sits after `text` and lists the
    failure types after `fail`, comma-separated. No doc needed beyond the
    column itself.

### S2: Pass rates and failure types are reported per case, category and overall

- **Goal:** The scorer and `all.py`'s summary report a pass rate (`k/N`,
  counted runs only) per case, per category and overall, whether each case
  meets `case_pass_rate`, and how often each failure type occurred.
- **Blocked by:** S1
- **Files:** `backend/evals/score.py`, `backend/evals/all.py`,
  `docs/EVALUATION.md`
- **Seams:** the scorer command; `all.py --skip-run --folder <folder>`
  (summary only, the judge step skips runs already judged); `score.pass_rate`.
- **Decisions:**
  - `unjudged` and `error` runs aren't counted in a rate and are shown next
    to it (user, 2026-11-05).
  - A case meets its target when at least `case_pass_rate` = 0.67 of its
    counted runs pass (2 of 3). There is no overall target; the overall
    pass rate is reported only (user, 2026-11-05).
  - A pass rate with no counted runs shows `-`.
- **Acceptance criteria:**
  - [x] Throwaway script, temp folder: case A with 3 runs (2 pass, 1
        fail), case B with 3 runs (1 pass, 1 fail, 1 error). The scorer
        prints A `2/3` meets target, B `1/2` misses target, overall `3/5`,
        and failure-type counts that match the runs.
  - [x] `all.py`'s summary on the same folder (all runs already judged)
        has a `Pass` column with the same per-case values and an overall
        pass rate line.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": pass rate per case, category and overall,
    counted runs only, `unjudged`/`error` shown apart; the case target;
    failure-type counts; a few runs per case illustrate reliability but
    don't estimate it, so raise `--runs` for a final comparison.
  - (decision) A case's rate is rounded to two places before it is
    compared with `case_pass_rate`, so 2 of 3 (0.667) meets 0.67. Why: the
    spec says 0.67 means 2 of 3, and an exact comparison would miss it.
    Doc: the case target in EVALUATION.md.
  - (decision) Failure-type counts are over failed runs only (an
    unjudged run's structured failures aren't counted). Why: they explain
    the pass rate, which counts only pass and fail. Doc: EVALUATION.md,
    with the counts.
  - (decision) The scorer prints a new "Pass Rates by Case" table (Runs,
    Pass, Target `meets`/`misses`/`-`, Unjudged, Error), adds Pass,
    Unjudged and Error columns to the category table and its OVERALL row,
    an overall pass rate line in the diagnostics panel, and a one-line
    failure-type count. `all.py`'s summary adds Pass, Target, Unj and Err
    columns per case, then a pass rate per category, an "Overall pass
    rate:" line and the failure-type counts. Doc: EVALUATION.md (what each
    output shows).

### S3: Each run records what produced it

- **Goal:** A results folder records, per run, the prompt version, the
  model list, the generation parameters, the case file's hash and the
  start time, so a regression can be told from noise or from a changed
  case.
- **Blocked by:** none
- **Files:** `backend/evals/run.py`, `docs/EVALUATION.md`
- **Seams:** `run.py` (with the Gemini client faked in the throwaway
  script); `<folder>/meta.json`.
- **Decisions:**
  - `meta.json` shape as in Interfaces; `--into` adds to it, a re-run
    replaces its entry (user, 2026-11-05).
  - Record only: `generation` is `{"temperature": null, "seed": null}`
    because `assess` sets neither; `impact/llm.py` is not changed (user,
    2026-11-05).
  - No git commit is recorded (user: "can remove").
- **Acceptance criteria:**
  - [x] Throwaway script runs `run.py`'s `run_case` path for 2 cases × 2
        runs with `FallbackLlm` faked: `meta.json` has 4 entries, each with
        the case file's sha256 and `prompt_version` 4.
  - [x] A second call with `--into` and run 2 of one case replaces that
        entry and keeps the other 3.
  - [x] `meta.json` is not picked up as a run by `score.run_paths`.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": `meta.json` and its fields; temperature and seed
    are the model's defaults in production and in the evals.
  - (decision) `run_case` writes the run's `meta.json` entry right after
    the run's file, one run at a time, so a run stopped part-way (quota)
    still has entries for the runs it saved. `started_at` is taken before
    the run starts. Doc: EVALUATION.md, with `meta.json`.
  - (decision) `models` is the list the run's `FallbackLlm` was given
    (`GEMINI_MODEL` then `GEMINI_FALLBACK_MODELS`), not the model that
    answered; that one is already in the run's own
    `assessment.answered_by`. Doc: EVALUATION.md, with the fields.
  - (decision) `case_sha256` hashes the case file's bytes as read from
    disk, so a reformat with no content change also counts as a changed
    case. Doc: EVALUATION.md, with the fields.

### S4: Two results folders can be compared as a regression check

- **Goal:** `compare.py <baseline> <new>` says, after a prompt, model or
  input change, whether the pass rate and mean scores moved by more than
  the noise floor, and which cases got better or worse.
- **Blocked by:** S2, S3
- **Files:** `backend/evals/compare.py` (new), `docs/EVALUATION.md`,
  `docs/DECISIONS.md`
- **Seams:** the `compare.py` command.
- **Decisions** (user, 2026-11-05):
  - For the cases in both folders, it reports the change in overall pass
    rate, mean tag and mean text, and each case's pass rate before and
    after (better, worse, same). Cases in only one folder are listed as
    "only in baseline" / "only in new" and left out of the totals.
  - A change is "within noise" when its size is at most the larger of the
    two folders' noise floors (all.py's spread of per-run overall tag
    score). With one run per case there is no noise floor, and each change
    is labelled "no noise floor (1 run)".
  - A case whose `case_sha256` differs between the folders is listed as
    "case changed" and left out of the totals.
  - A different `prompt_version` or model list is printed as a header
    line, not an error.
  - A folder without `meta.json` (made before S3) is compared without the
    case-hash check, and the output says so.
- **Acceptance criteria:**
  - [x] Throwaway script, two temp folders with the same 2 cases × 3 runs,
        the new one with one case going from 3/3 to 1/3 passing: output
        lists that case as worse, the other as same, and an overall pass
        rate change of -2/6.
  - [x] Same, with a per-run spread larger than the change in mean tag:
        that change is labelled "within noise".
  - [x] One case's `case_sha256` differs between the folders: it is listed
        as "case changed" and left out of the totals.
  - [x] A folder without `meta.json`: exits 0 and prints that case hashes
        weren't checked.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": the regression workflow (record a baseline per
    `PROMPT_VERSION`, re-run after every prompt, model or input change,
    compare with `compare.py`, keep a change only when it beats the noise
    floor) and the command.
  - DECISIONS.md: a dated entry, "Evals decide pass or fail by thresholds
    fixed in a file; a change is judged against the noise floor", with the
    reasons (slides 29–30: fix the threshold before you run; one green run
    proves nothing).
  - (decision) The noise-floor test applies to all three changes: the
    overall pass rate (as a fraction) as well as mean tag and mean text.
    Each is "within noise", "beyond noise" or "no noise floor (1 run)".
    Why: the goal asks whether the pass rate and mean scores moved by more
    than the noise floor. Doc: EVALUATION.md, the regression workflow.
  - (decision) When only one folder has a noise floor (the other has one
    run per case), that one is used; "no noise floor (1 run)" only when
    neither has one. Each folder's floor is taken over all its runs, as
    `all.py` does, so it matches `summary.txt`. Doc: EVALUATION.md, with
    the command, including the limit: a case that regresses in only some
    runs widens the new folder's spread too, so a regression in a single
    case can come out "within noise"; read the per-case lines as well as
    the totals.
  - (decision) Mean tag and mean text are `score.py`'s overall means
    (PLACEHOLDER runs and missing scores left out). The pass-rate change
    is printed as `±k/N` when both sides count the same number of runs,
    and as a percentage otherwise. Doc: EVALUATION.md, with the output.
  - (decision) A case with no counted run on one side is "not
    comparable" instead of better/worse/same. Doc: EVALUATION.md, with the
    output.
  - (decision) A case whose runs have no `meta.json` entry in one folder
    (runs added with `--into` to a folder made before `meta.json`) is not
    flagged as "case changed". Why: no hash to compare isn't a change. Doc:
    EVALUATION.md, with the case-hash check.
  - (decision) `compare.py` reuses `all.load_runs` for the runs and repeats
    `all.py`'s noise-floor formula in its own `noise_floor`, since S4 may
    not change `all.py`. No doc needed (code shows it).
  - (decision, code review) Superseding the line above: the noise floor
    (`score.noise_floor`, with `score.tag_by_run`), the overall mean
    (`score.overall_mean`) and the failure-type line (`score.failure_line`)
    now live once in `score.py`, used by `score.py`, `all.py` and
    `compare.py`; output is unchanged. No doc needed (code shows it).

### S5: The judge can be checked for consistency and against a person

- **Goal:** The judge can grade a folder a second time into separate files,
  and a script reports how often two gradings agree, writes a random sample
  of verdicts for a person to mark, and reports how often the person agrees
  with the judge.
- **Blocked by:** none
- **Files:** `backend/evals/judge.py`, `backend/evals/judge_check.py` (new),
  `backend/evals/score.py` (`run_paths` only), `docs/EVALUATION.md`
- **Seams:** `judge.py --label <label>` (Gemini faked in the throwaway
  script); `judge_check.py agree <folder> <labelA> <labelB>`,
  `judge_check.py sample <folder> [--n 20] [--seed N]`,
  `judge_check.py human <folder> <csv>`.
- **Decisions:**
  - Both checks are built: grading twice and comparing, and a hand-marked
    sample (user, 2026-11-05).
  - File names as in Interfaces; `run_paths` and `judge.py`'s own glob skip
    every name containing `.judge`. `score.py` reads only
    `<run>.judge.json`. In `agree`, the label `default` means
    `<run>.judge.json`.
  - Agreement is per statement (the judge's `present`), over the statements
    graded in both (user, 2026-11-05).
  - The sample CSV has columns `run, n, fact, kind, answer_excerpt,
    judge_present, human_present` (`human_present` blank to fill with
    `true`/`false`). Sample size 20 by default, `--seed` for a repeatable
    sample (user, 2026-11-05).
- **Acceptance criteria:**
  - [x] Throwaway script: `judge.py --label b` with a fake client writes
        `<run>.judge-b.json` and leaves `<run>.judge.json` untouched; the
        scorer's runs and text scores are unchanged.
  - [x] `judge_check.py agree` on two hand-made gradings differing on 1 of
        10 statements prints 9/10 (90%) and lists the differing statement.
  - [x] `judge_check.py sample --n 5 --seed 1` twice writes the same 5
        rows. `judge_check.py human` on that CSV with 4 rows filled (3
        matching the judge, 1 not) and 1 blank prints 3/4 (75%) and names
        the blank row as unmarked.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": how to check the judge (grade twice and compare;
    sample and mark by hand), and that a judge with low agreement makes the
    text score itself flaky (slide 28).
  - (decision) `judge.py` parses its arguments with argparse (`[folder]
    [--redo] [--label <label>]`); the old "every argument not starting with
    `--` is the folder" parsing would have taken the label for the folder.
    `--redo` with `--label` regrades only that label's files. Doc:
    EVALUATION.md's `judge.py` row.
  - (decision) `sample` writes to `<folder>/judge_sample.csv` unless
    `--out <csv>` is given. It samples statements from the default grading
    (`<run>.judge.json`, the one the text score uses), leaves out
    statements the judge skipped (`present` null), and writes the rows
    sorted by run and statement number. Without `--seed` the sample
    differs each time. Doc: EVALUATION.md, with the commands.
  - (decision) `answer_excerpt` is the run's three free-text fields as the
    judge saw them (`judge.answer`, also used by the judge's prompt), not
    the judge's own evidence quote, so the person marks the answer without
    seeing the judge's reasoning. Doc: EVALUATION.md, with the CSV.
  - (decision) `human` compares each marked row with the judge's `present`
    in the folder's current `<run>.judge.json` (what the text score
    reads), falling back to the CSV's `judge_present` when that grade is
    gone. A `human_present` that isn't `true`/`false` (case-insensitive)
    is listed as unmarked with its value. Disagreements are listed too.
    Doc: EVALUATION.md, with the commands.
  - (decision) `agree` lists runs graded under only one of the two labels
    as a count, left out of the agreement. No doc needed beyond the
    command.
  - (decision, code review) `judge.py --label default` exits with a usage
    error. Why: `judge_check.py` reads the label `default` as
    `<run>.judge.json`, so a `<run>.judge-default.json` would never be
    read. Doc: EVALUATION.md's `judge.py` row.

### S6: Metamorphic variants of three cases, with a consistency report

- **Goal:** Three cases have a reworded variant with the same expected
  answer, and the scorer reports, per original and its variants, whether
  they pass at the same rate and give the same tag values.
- **Blocked by:** S2
- **Files:** `backend/evals/cases/` (3 new files), `backend/evals/score.py`,
  `docs/EVALUATION.md`
- **Seams:** `run.py --dry`; the scorer command.
- **Decisions:**
  - Variants (user, 2026-11-05):
    `21_correction_effect_size_reworded.json` (case 01, notice reworded),
    `22_retraction_key_evidence_reordered.json` (case 02, draft sections
    reordered and the use of the paper reworded),
    `23_same_authors_different_paper_reworded.json` (case 14, draft
    reworded), each with `"variant_of": "<original stem>"`. Their names
    contain no `__`, which splits case from run in result file names.
  - A variant keeps its original's `expected`, `category` and input format
    as it is when S6 runs; only texts change, and every fact and tag stays
    true of the new texts.
  - The scorer's "Metamorphic groups" table: per original, each member's
    pass rate and, per tag, whether all members' most common value is the
    same.
- **Acceptance criteria:**
  - [x] `run.py --dry 21_correction_effect_size_reworded
        22_retraction_key_evidence_reordered
        23_same_authors_different_paper_reworded` builds all three.
  - [x] Each variant's `expected` equals its original's (throwaway script
        compares the JSON).
  - [x] Throwaway script, temp folder with runs of 01 and 21 where 21's
        `impact.impact_level` differs: the scorer's group table flags that
        tag as inconsistent for group 01.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": the case count (the number of files in
    `evals/cases/`), `variant_of`, what a metamorphic variant is (same
    facts, other wording, same expected answer) and the group table.
  - (decision) Each variant is its original's committed file with one
    text swapped (21: `notice.text`; 22 and 23: `draft_text`) and a
    `"variant_of"` line added before `category`; everything else,
    including `tier`, `why`, the paper, alerts and DOIs, is identical. 22
    puts Methods before the Introduction, and the paper is still used in
    the Introduction, so "the draft's Introduction relies on the retracted
    paper" stays true. Doc: EVALUATION.md, with `variant_of`.
  - (decision) The "Metamorphic Groups" table appears only when at least
    two members of a group have runs in the folder. Per member it shows
    the pass rate and each tag's most common value across its non-
    PLACEHOLDER runs (as the grid writes it). Each group then gets a
    `(group)` row: pass rates `same`/`differ`, and `inconsistent: <tags>`
    or `all tags consistent`. A member with only PLACEHOLDER runs shows
    `-` for its values, which makes every tag inconsistent. When a member
    has no counted run (pass rate `-`), the pass-rate cell is `-`, not
    `same`/`differ`. Values tied for most common are all shown, sorted
    and joined with ` | `, so the flag doesn't depend on run order. A
    `variant_of` that names no case file is skipped. Doc: EVALUATION.md,
    the group table.

## Human checks

- [ ] (S1) Read `evals/thresholds.json` and agree the values before the
      baseline run. Expected: the team accepts them as the eval's
      acceptance criteria.
- [ ] (S4) Record the baseline for `PROMPT_VERSION` 4: from `backend/`,
      `uv run --with fpdf2 --with rich python evals/all.py --runs 3`
      (split across days or keys for quota: every case × 3 runs × up to 3
      calls), then keep the folder name. Expected: a `summary.txt` with
      pass rates and a noise floor, and a `meta.json`.
- [ ] (S5) `judge_check.py sample <baseline folder> --n 20`, fill in
      `human_present`, run `judge_check.py human`. Expected: an agreement
      rate to quote; if it's low, the judge prompt or model needs work
      before text scores are trusted.
- [ ] (S6) Read the three variant cases. Expected: each says the same
      facts as its original in other words, and its `expected` still fits.
