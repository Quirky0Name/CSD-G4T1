# Evals: run on Claude Code or the Claude API, log the prompts, and use a smaller case set

**Status:** approved
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

Source of the task: the user wants to simulate eval runs on their Claude
subscription through Claude Code, without Gemini or Anthropic API calls, with
a smaller input set, and with the prompts that produced a run on record. The
user also wants a Claude API client (user, 2026-11-07: "add a claude api client
also"), so paid runs are possible once they want them.

**Commands** (from `backend/`; on Windows, run `uv` as `py -m uv`, docs: SETUP.md):
- Full test suite: `uv run pytest` (the evals have none: they are scored by
  command, not by pytest, DECISIONS.md 2026-10-27)
- Single test file: none for the evals
- Build cases without a model: `uv run --with rich --with fpdf2 python evals/run.py --dry [case ...]`
- Run the scorer: `uv run --with rich --with fpdf2 python evals/score.py <results folder>`
- Typecheck: none
- Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | S1 |
| S3 | S1 |
| S4 | none |
| S5 | S1, S2 |

Run order: S1, S2, S3, S4, S5.

External blockers: none.

Git state: on `feat/claim-passages-backend`, 9 commits ahead of `main`, working
tree clean apart from this spec. Branch: `feat/eval-claude` (user, 2026-11-07), stacked on
`feat/claim-passages-backend`, because `run.py`, `judge.py` and `score.py` on that
branch have the claims work. Its PR merges after that branch's.

## Suggested splits

No split: S2 and S3 build on S1's `run.py` changes, and S4 changes
`docs/EVALUATION.md` in the same section as the other three.

## Out of scope

- Changing production's LLM: `impact/llm.py`, `impact/assess.py` and the
  services stay on Gemini, and the services never import `evals/`. Doc delta
  on S1.
- Agents or subagents acting as the tested model. They could read a case's
  `expected` and `why`. `transient`.
- Swapping prompts by flag or file (user, 2026-11-07). `transient`.
- Logging rendered prompts per call: they hold case text and PDFs, and the
  saved run and its case file already identify them. Doc delta on S3.
- A git worktree: nothing in the run needs one, because the Claude calls run
  from an empty temp folder. `transient`.
- An API key or direct API call on the `claude-code` provider. Doc delta on S1.
- The Message Batches API: step 2 depends on step 1's answer, and runs are
  sequential. Doc delta on S5.
- Any run against the API: Human checks. `transient`.
- Recording a baseline or running the full set: Human checks. `transient`.

## Interfaces

- **`evals/claude_llm.py`** (S1, used by S2), implements
  `research_evaluation.impact.llm.Llm`:
  ```python
  class ClaudeLimitReached(RuntimeError): ...
  class NotOnSubscription(RuntimeError): ...

  def preflight(command: tuple[str, ...] = ("claude",)) -> None: ...

  @dataclass(frozen=True)
  class ClaudeLlm:
      model_name: str
      command: tuple[str, ...] = ("claude",)
      timeout_seconds: float = 300

      @property
      def model(self) -> str: ...
      @property
      def models(self) -> tuple[str, ...]: ...  # (model_name,), as run_case records
      async def generate[T: BaseModel](
          self, *, system: str, parts: list[Part], schema: type[T]
      ) -> Answer[T]: ...
  ```
  Doc delta on S1.
- **`--provider gemini|claude-code|claude-api`** on `run.py` and `judge.py` (S1,
  S2, S5), passed on by `all.py` (S2). S1 and S2 add `claude-code`; S5 adds
  `claude-api`. `--model <name>` and `--effort <level>` override the tested model and its
  effort on `run.py`.
  `JUDGE_MODEL` overrides `haiku` when the judge's provider is `claude-code`.
  Doc delta on S1 and S2.
- **`--resume`** on `run.py` and `all.py` (S1, S2): skips a run whose
  `<case>__run<N>.json` exists in the folder and isn't a placeholder. Doc delta
  on S1 and S2.
- **`meta.json`, per run** (S1, S3): adds `"provider": "gemini" | "claude-code" | "claude-api"` and
  `"prompt_sha256": "<hex>"`. Doc delta on S1 and S3.
- **Rubric contract** (S2, written by case files, read by `judge.py`):
  `expected.facts` in a case:
  ```json
  {"must_include": ["<one fact, checkable from the answer alone>"],
   "must_not_include": ["<one fact, checkable from the answer alone>"]}
  ```
  Both keys are required, no other key is allowed, each item is a non-empty string,
  and the two lists together hold at least one statement. Doc delta on S2.
- **Judge fact-grading contract** (S2): the judge gets two JSON documents and returns
  one. Input, the answer:
  ```json
  {"change_summary": "...", "evaluation": "... or null", "recommendation": "... or null"}
  ```
  Input, the statements: the rubric's two lists, `must_include` first, numbered from
  1, without saying which list each came from:
  ```json
  {"statements": [{"n": 1, "text": "..."}]}
  ```
  Output, the existing `Verdicts`:
  ```json
  {"items": [{"n": 1, "evidence": "<words of the answer, or empty>", "present": true}]}
  ```
  Doc delta on S2.
- **`<results folder>/prompts.json`** (S3, read by `compare.py`):
  ```json
  {"<sha256>": {"prompt_version": 5, "system": "...", "change_task": "...",
                "impact_task": "...", "actions_task": "...",
                "judge_system": "...", "judge_passage_system": "..."}}
  ```
  Doc delta on S3.

## Subtasks

### S1: `run.py` can generate through Claude Code on the subscription

- **Goal:** `run.py --provider claude-code` sends each step's real prompt and PDFs
  through `claude -p`, on the subscription login, with no tools, no project or
  user context and no access to the case's expected answer, and saves results
  the scorer reads unchanged. Hitting the usage limit stops the run, and
  `--resume` continues it.
- **Blocked by:** none
- **Files:** `backend/evals/claude_llm.py` (new), `backend/evals/run.py`,
  `docs/EVALUATION.md`, `docs/DECISIONS.md`
- **Seams:** `run.py --provider claude-code`, with `ClaudeLlm.command` pointing at a
  fake CLI script in the throwaway checks; `claude_llm.preflight`;
  `ClaudeLlm.generate`; the scorer command.
- **Decisions:**
  - `claude -p` under the user's subscription, no API calls (user, 2026-11-07).
  - The tested model is Sonnet at effort medium: "Sonnet medium with haiku medium
    as judge" (user, 2026-11-07), so `--model sonnet --effort medium` by default.
  - `--provider` defaults to `gemini`, so existing commands keep using Gemini.
  - `assess` runs unchanged with `ClaudeLlm` as its `Llm`, so the model only
    gets what `change_prompt`, `impact_prompt` and `actions_prompt` build, and
    never a case file (code: `impact/llm.py`, `impact/assess.py`).
  - The command is, with the working directory a new empty temp folder per
    call, removed afterwards:
    `claude -p --verbose --input-format stream-json --output-format stream-json --model <model> --effort <effort> --system-prompt <system> --tools "" --strict-mcp-config --disable-slash-commands --no-session-persistence --setting-sources= --json-schema <schema>`.
    Probed on 2026-11-07 (Claude Code 2.1.319): with these flags a one-page PDF
    was read with no file tool, the only tool was `StructuredOutput`,
    `apiKeySource` was `none`, the answer was in the `result` event's
    `structured_output`, and the model reported no style, memory or persona
    instructions. `transient`.
  - `--bare` is not used: its help says Anthropic auth is strictly
    `ANTHROPIC_API_KEY` or `apiKeyHelper`, and OAuth is never read. `--setting-sources=`
    keeps user hooks such as this machine's caveman hook out of the answers.
  - The prompt goes on stdin as one stream-json user message. A `str` part is a
    text block; a `Pdf` part is a `document` block with a base64
    `application/pdf` source, in the parts' order.
  - The schema is `schema.model_json_schema()`. Its longest form is about 2,400
    characters, so it fits on the command line. `transient`.
  - The answer is validated with `schema.model_validate(structured_output)`.
    `Answer.model` is `model_name`; `Answer.model_version` is the `system`
    `init` event's `model`.
  - The subprocess gets the environment without `ANTHROPIC_API_KEY` and
    `ANTHROPIC_AUTH_TOKEN`, and `backend/.env` is not read when the provider is
    `claude-code`. A `system` `init` event whose `apiKeySource` isn't `none` raises
    `NotOnSubscription`, and the run stops.
  - `preflight` runs once before the first case: `claude auth status` must exit
    0 with `"authMethod": "claude.ai"`, otherwise `NotOnSubscription` and exit
    non-zero, with no call made.
  - Hitting the limit: a `rate_limit_event` with `rate_limit_info.status`
    `"rejected"`, or a `result` with `is_error` true whose text contains
    "limit" (case-insensitive), raises `ClaudeLimitReached`. `assess` doesn't
    catch it, so no file is saved for that run. `run.py` prints the
    `--into <folder> --resume` command and the reset time when known, then
    exits non-zero.
  - Every other failure (non-zero exit, no `result` event, `is_error`, no
    `structured_output`, a schema mismatch, the 300 s timeout) raises
    `AllModelsFailed` with one `ModelFailure` whose cause holds no response
    text, so the run is a placeholder, as in production.
  - `--resume` re-runs placeholders, so a limit that was missed and saved as a
    placeholder is recovered by resuming.
  - "no fall back for evaluation. just mark which failed to be rerun" (user,
    2026-11-07). No provider falls back to another model in an eval: `claude-code` and
    `claude-api` have none, and the `gemini` provider's `FallbackLlm` gets only
    `GEMINI_MODEL`, ignoring `GEMINI_FALLBACK_MODELS`. Production's fallback doesn't
    change.
  - A failed run stays marked as it is now: a saved placeholder with
    `assessment.placeholder` true, `failed_step` and `failures`, which `score.py`
    shows as `error`. At its end `run.py` lists each failed run with its cause and
    prints the `--into <folder> --resume` command that re-runs them, on every provider.
  - Temperature and seed stay null in `meta.json`:
    the CLI has no flag for them (code: `run.py`).
  - After each call `run.py` prints the call's token counts and, when given,
    the 5-hour and 7-day `utilization` from the `rate_limit_event`.
  - `run_case`'s `llm` type becomes a protocol with `models`, or `FallbackLlm |
    ClaudeLlm`; apart from using only `GEMINI_MODEL`, a Gemini run's behaviour and
    output don't change.
  - The evals run on teammates' laptops too (user, 2026-11-07), on Windows and macOS:
    `uv` is the one way to run them (docs: SETUP.md, `uv.lock`), and `.venv` isn't
    used directly. The CLI is found with `shutil.which("claude")`, so an npm install's
    `claude.cmd` works too; if it isn't found, the run exits non-zero and says to
    install and log in to Claude Code. Each person uses their own subscription or key.
    Temp folders come from `tempfile`, and subprocess pipes are UTF-8.
  - The evals stay dev only, and no new dependency is added (docs: DECISIONS.md
    2026-10-27).
- **Acceptance criteria:**
  - [ ] Throwaway script, fake CLI that records its argv, cwd, env and stdin and
        prints an `init`, a `rate_limit_event` and a `result` event: a full
        `run_case` for one case with `paper_doc`, one with `paper_file`, one
        with no draft saves `<case>__run1.json`, and `score.py` on the folder
        exits 0.
  - [ ] Same script: each argv has every flag above, the cwd is empty and outside
        the repo, the env has no `ANTHROPIC_API_KEY`, and no stdin or argv
        contains the case's `expected` values or `why`.
  - [ ] Same script: a `Pdf` part reaches stdin as a `document` block whose
        decoded bytes equal the file's sha256 in `evals/real/SOURCES.md`, for
        case `28_real_retraction`.
  - [ ] Same script: `apiKeySource` `ANTHROPIC_API_KEY` stops the run with
        `NotOnSubscription`, and `preflight` with `authMethod` other than
        `claude.ai` exits non-zero before any call.
  - [ ] Same script: a `rejected` `rate_limit_event` on a case's second call saves
        no file for that run, exits non-zero and prints a `--resume` command;
        re-running with `--into <folder> --resume` makes calls only for runs
        without a saved non-placeholder file.
  - [ ] Same script: non-zero exit, a timeout, and a `structured_output` that
        fails the schema each save a placeholder run with
        `assessment.failures[0].cause` containing none of the fake answer's text.
  - [ ] Same script: with no `claude` on `PATH`, the run exits non-zero and names what
        to install; with a fake `claude.cmd` on `PATH` on Windows, a call succeeds.
  - [ ] `run.py --dry` builds every case without calling the CLI.
  - [ ] A run with no `--provider` builds `FallbackLlm` with only `GEMINI_MODEL`, with
        `GEMINI_FALLBACK_MODELS` set, shown by a throwaway script with the Gemini client
        faked.
  - [ ] Same script as the failures above: `run.py` ends by listing the failed runs and
        the `--resume` command, and `--resume` calls the model only for them.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": `--provider`, `--model`, `--resume`; `claude_llm.py`
    in the scripts table and in "Where the code lives"; that a Claude run uses
    the subscription login, makes no API calls, and has no fallback model; what
    happens at the usage limit; that PDFs go as document blocks with no tools;
    that the working directory is empty and no user or project settings load;
    `provider` in `meta.json`.
  - EVALUATION.md "Evals": Claude results measure Claude, not production's
    Gemini, so `compare.py` across providers shows the model change and is not a
    regression check.
  - EVALUATION.md "Evals": what someone needs to run it: `uv` (SETUP.md), Claude Code
    logged in to their own account for `claude-code`, their own key for `claude-api`;
    that no eval falls back to another model, so `GEMINI_FALLBACK_MODELS` isn't used;
    how failed runs are listed and re-run with `--resume`.
  - DECISIONS.md, dated entry: the evals can generate through Claude Code on a
    subscription (why: run without API spend); production stays on Gemini; an
    API key is never used for it; the model goes through the same `assess` and
    never reads a case file, because a case holds its expected answer.

### S2: The judge and `all.py` can use Claude Code too

- **Goal:** `all.py --provider claude-code` runs generation and grading through
  Claude Code with no Gemini or API call, and a stopped run can be resumed.
- **Blocked by:** S1
- **Files:** `backend/evals/judge.py`, `backend/evals/all.py`, `docs/EVALUATION.md`,
  `docs/DECISIONS.md`
- **Seams:** `judge.py --provider claude-code` with a fake CLI; `all.py`; the scorer
  command.
- **Decisions:**
  - The judge also runs through Claude Code when the provider is `claude-code`
    (user, 2026-11-07: no API calls).
  - The judge is Haiku at effort medium: "Sonnet medium with haiku medium as
    judge" (user, 2026-11-07), so `--model haiku --effort medium`, unless
    `JUDGE_MODEL` is set. `judge.py` exits
    with a usage error when it would be the same model name as the run's
    `assessment.model`, so a model doesn't grade its own answers (docs:
    DECISIONS.md 2026-10-27).
  - The judge's fact grading gets only the ANSWER and the numbered statements, on
    every provider. The CASE MATERIAL block leaves `judge.prompt`, `document_text`
    goes, and `SYSTEM` says to use only the ANSWER (user, 2026-11-07). Whether an
    answer is true of the sources is checked by the hand-written facts, the tags
    and the quote check, not by the judge.
  - "add the contract for the rubric. should be just the two json for the judge to
    check" (user, 2026-11-07): the fact grading's prompt is the answer JSON and the
    statements JSON from Interfaces, and nothing else.
  - The judge isn't told which statements are `must_include` or `must_not_include`:
    it reports `present`, and the code decides pass or fail (docs: DECISIONS.md
    2026-10-27, "The judge reports, the code decides").
  - `judge.Rubric`, a pydantic model with `extra="forbid"`, checks the rubric
    contract. `judge.py` validates every case's `expected.facts` before its first
    call, and on a violation exits non-zero naming the case and makes no call.
  - `answer()` stays for the passage prompt and `judge_check.py`.
  - `PASSAGE_SYSTEM`, the passage prompt and the grading code don't change. No temperature is set, because the CLI has no such flag; the Gemini
    judge keeps temperature 0.
  - `judge.py --provider claude-code` needs no `JUDGE_API_KEY` or `GEMINI_API_KEY`,
    and calls `preflight`.
  - `ClaudeLimitReached` during grading stops `judge.py` non-zero. Runs already
    graded are kept, and running it again grades only the rest (code: `judge.py`
    skips runs already judged).
  - A judge call that fails for any other reason leaves that run ungraded and moves
    on, so `score.py` shows it as `unjudged`. At its end `judge.py` lists those runs
    with their causes, and running it again grades only them (user, 2026-11-07:
    "just mark which failed to be rerun"). It exits 0, so `all.py` still writes its
    summary.
  - `all.py` passes `--provider`, `--model`, `--effort` and `--resume` on to its steps. When
    `run.py` stops, its message gives the `--folder <folder> --resume` command,
    because `--skip-run` would leave the remaining runs unmade.
- **Acceptance criteria:**
  - [ ] Throwaway script, fake CLI: `judge.py --provider claude-code <folder>` with no
        Gemini key in the environment writes `<run>.judge.json` with `items`,
        `passages` and `statements`, and `score.py` shows a text score and verdict.
  - [ ] Same script: a judge whose model equals the run's exits with a usage
        error before any call.
  - [ ] Same script: `ClaudeLimitReached` on the second run exits non-zero and
        keeps the first run's grade; a second call grades only the second run.
  - [ ] Same script: `all.py --provider claude-code --folder <folder> --resume` with
        stdin `y` passes the provider to `run.py` and `judge.py`, and writes
        `summary.txt`.
  - [ ] Throwaway script: `judge.prompt` for `28_real_retraction` holds the answer
        JSON and the statements JSON, whose values equal the run's three free-text
        fields and the rubric's statements in order; it holds no sentence of the
        paper, notice or draft, and neither `must_include` nor `must_not_include`.
  - [ ] Throwaway script: every case in `evals/cases/` passes `judge.Rubric`.
  - [ ] Same script: `judge.py` on a temp copy of a case with a missing key, an extra
        key, an empty string, and no statements at all exits non-zero each time,
        naming the case, with the fake client never called.
  - [ ] Same script: a fake verdict for each statement of a case gives the same
        `pass` values as before this change, for `present` true and false.
  - [ ] Same script: a schema failure on one run grades the others, lists that run,
        exits 0, and a second call makes a call only for it.
  - [ ] A `judge.py` call with no `--provider` still builds a Gemini client
        (throwaway script with the client faked).
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals", "A case": the rubric contract and the judge's fact-grading
    contract from Interfaces, and that a statement states one fact checkable from the
    answer alone.
  - EVALUATION.md "Evals", `judge.py` row: the fact grading reads only the answer
    and the statements; remove "the fact grading's prompt is unchanged, so text
    scores stay comparable with `PROMPT_VERSION` 4 baselines", because text scores
    from before this change aren't comparable.
  - `judge.py` module docstring: replace "only the case's own texts, the model's
    three free-text fields and the facts".
  - DECISIONS.md, dated entry amending 2026-10-27: the judge compares the answer
    with the statements and doesn't read the sources; the statements are the
    reference, and faithfulness to the sources is checked in code by the quote check.
  - EVALUATION.md "Evals": the judge's `--provider`, `JUDGE_MODEL` with Claude
    defaulting to `haiku`, that it may not equal the tested model, that it has no
    temperature 0, and `all.py --provider claude-code` and `--resume`, with the
    command.
  - DECISIONS.md: add to S1's entry that with Claude the judge has its own model
    but shares the subscription's limits, unlike a separate Gemini key.

### S3: Each results folder records the prompts it ran with

- **Goal:** Any results folder shows the exact prompt texts behind its runs,
  including after `prompts.py` has changed, and `compare.py` shows when two
  folders ran on different texts.
- **Blocked by:** S1
- **Files:** `backend/evals/run.py`, `backend/evals/compare.py`,
  `docs/EVALUATION.md`
- **Seams:** `run.py`; `<folder>/prompts.json`; `<folder>/meta.json`; the
  `compare.py` command.
- **Decisions:**
  - "just make the old prompts output in logs for tracking" (user, 2026-11-07), for
    every provider.
  - `prompts.json` as in Interfaces. It holds the templates: `SYSTEM`,
    `CHANGE_TASK`, `IMPACT_TASK`, `ACTIONS_TASK`, and the judge's `SYSTEM` and
    `PASSAGE_SYSTEM`. Rendered prompts aren't logged.
  - `prompt_sha256` is the sha256 of the entry's six texts as canonical JSON,
    without `prompt_version`. A wording change without a `PROMPT_VERSION` bump
    therefore shows up.
  - `run.py` adds the entry when its hash isn't in the folder's file yet, and
    keeps existing ones, so `--into` after a prompt change holds both.
  - `compare.py` prints `prompt_sha256` and `provider` as header lines when they
    differ between folders, like `prompt_version` and `models`, and not as an
    error. A folder made before these fields is compared without them.
  - `prompts.py`'s texts don't change, and `PROMPT_VERSION` stays 5.
- **Acceptance criteria:**
  - [ ] Throwaway script, fake CLI: a run writes `prompts.json` whose texts equal
        `prompts.py`'s and `judge.py`'s, and the run's `prompt_sha256` in
        `meta.json` is a key in it.
  - [ ] Same script: a second `--into` run with `CHANGE_TASK` patched adds a second
        entry, keeps the first, and only the new run's `prompt_sha256` differs.
  - [ ] Same script: `compare.py` on two folders with different `prompt_sha256`
        and `provider` prints both as header lines and exits 0; with neither field
        in one folder's `meta.json` it exits 0.
  - [ ] `score.run_paths` doesn't pick up `prompts.json` as a run (same script).
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": `prompts.json`, `prompt_sha256`, and the `compare.py`
    header lines; that rendered prompts aren't logged, since the run's case file
    and the templates rebuild them.

### S4: The input set is 24 cases

- **Goal:** The eval set no longer has cases 06, 17, 22, 23, 26, 30 and 31, nor the
  real PDFs only they use, and every doc and case agrees with what is left.
- **Blocked by:** none
- **Files:** deleted from `backend/evals/cases/`: `06_overclaiming_draft.json`,
  `17_concern_lifted.json`, `22_retraction_key_evidence_reordered.json`,
  `23_same_authors_different_paper_reworded.json`,
  `26_correction_department_of_error.json`,
  `30_real_correction_figures_switched.json`, `31_real_new_version.json`; deleted
  from `backend/evals/real/`: `pone.0309025.pdf` and `.txt`,
  `f1000research.187739.1.pdf` and `.txt`, `f1000research.187739.2.pdf` and `.txt`;
  changed: `backend/evals/real/SOURCES.md`,
  `backend/evals/cases/25_retraction_reversed_by_addendum.json`,
  `backend/evals/cases/10_draft_does_not_cite.json`, `docs/EVALUATION.md`,
  `docs/ROADMAP.md`
- **Seams:** `run.py --dry`.
- **Decisions:**
  - Keep the 2 smallest real papers (user, 2026-11-07): cases 28 and 29 stay, 30 and
    31 go. By paper, 29 is 8 pages and 28 is 17.
  - Cases 06, 17, 22, 23 and 26 go, and 19 stays (user, 2026-11-07), leaving 24
    cases. The no-notice partition keeps 3 cases, above the earlier maximum of 2 per
    partition (user, 2026-11-07: "nvm add back").
  - With 31 gone, no case has a `new_version` alert or tests a real two-PDF
    comparison; case 11 still has a `new_version` document. Doc delta below.
  - The seven files are deleted, and there is no list or flag: "stick to the 7" (user,
    2026-11-07). Git keeps them, and no results folder exists locally, so no baseline
    depends on them.
  - In `SOURCES.md`, the deleted papers and the notice of `pone.0309025` move from
    their tables to "Dropped", each with "removed to reduce the eval input,
    2026-11-07", keeping its DOI and licence.
  - In case 25's `expected.why`, "like a lifted concern (case 17)" becomes "like a
    lifted concern".
  - In case 10's `must_not_include`, "invents a citation of the retracted paper in
    the draft" becomes "says the draft cites the retracted paper", because the judge
    no longer sees the draft (S2) (user, 2026-11-07). Nothing else in a remaining case
    changes.
  - Partitions are not recorded in the case files.
- **Acceptance criteria:**
  - [ ] `run.py --dry` builds 24 cases, and none named 06, 17, 22, 23, 26, 30 or 31.
  - [ ] `grep -rnE "06_overclaiming|17_concern_lifted|22_retraction|23_same_authors|26_correction|30_real|31_real|\(case 17\)|cases 21, 22, 23|invents a citation" backend/evals --include=*.py --include=*.json --include=*.md docs/EVALUATION.md`
        finds nothing.
  - [ ] Throwaway script: 13 of the 24 cases have `"tier": "hard"`, and every
        `variant_of` names an existing case.
  - [ ] Throwaway script: every `paper_file` and `pdf_file` in a case exists in
        `evals/real/`, and every PDF there is named by a case.
  - [ ] Throwaway script: cases 25 and 10 differ from `git show HEAD:` only in the
        two strings above.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals", "A case": "24 of them, 13 marked `tier: hard`" (it says 23
    and 11 now); "case 21 varies 01"; that the real cases are 28 and 29; that
    `new_version` alerts have no case.
  - ROADMAP.md: under Evals, add a case for a `new_version` alert.

### S5: `run.py` and `judge.py` can call the Claude API directly

- **Goal:** `--provider claude-api` runs generation and grading through the
  official Anthropic Python SDK with an API key, with results the scorer reads
  unchanged, and stops instead of saving placeholders on failures that would
  repeat for every run.
- **Blocked by:** S1, S2
- **Files:** `backend/evals/claude_llm.py`, `backend/evals/run.py`,
  `backend/evals/judge.py`, `backend/evals/all.py`, `docs/EVALUATION.md`,
  `docs/DECISIONS.md`
- **Seams:** `ClaudeApiLlm.generate` with a fake client; `run.py --provider
  claude-api`; `judge.py --provider claude-api`; the scorer command.
- **Decisions:**
  - "add a claude api client also" (user, 2026-11-07).
  - `ClaudeApiLlm` implements `Llm`, in `evals/claude_llm.py`, with the same
    `model` and `models` properties as `ClaudeLlm`. It takes an injected
    `anthropic.AsyncAnthropic`, and `anthropic` is imported only when this
    provider is chosen, so the other providers don't need it.
  - `anthropic` is not added to `pyproject.toml`: commands add `--with anthropic`,
    like `fpdf2` and `rich` (docs: DECISIONS.md 2026-10-27, dev only). The SDK's
    1.x line uses `httpx2`, and the project's `httpx` isn't passed to it.
  - The key comes from `ANTHROPIC_API_KEY` in the environment, or from
    `backend/.env` for this provider only.
  - "Sonnet medium with haiku medium as judge" (user, 2026-11-07): the tested model
    defaults to `claude-sonnet-5-5` at effort `medium`, overridable by `--model` and
    `--effort`, and the judge to `claude-haiku-5-5` at effort `medium`, overridable
    by `JUDGE_MODEL`. The rule that a judge may not be the tested model applies (S2).
  - The request is `messages.parse` with `output_format=schema`, `system`,
    `max_tokens=16000`, one user message, `output_config={"effort": "medium"}`, and no
    `temperature`, `top_p`, `top_k` or `thinking`: both models run adaptive thinking
    when `thinking` is omitted, and reject non-default sampling values. Effort is
    always sent, because Sonnet 5.5's default is `high`.
  - Parts keep their order. A `str` is a text block, and a `Pdf` is a `document`
    block with a base64 `application/pdf` source and no newlines. Parts aren't
    reordered to put PDFs first, because each PDF's label comes before it
    (code: `impact/prompts.py`).
  - No server-side fallback: no `fallbacks` parameter and no fallback beta (user,
    2026-11-07: "no fall back for evaluation"). A refusal marks the run as failed for
    `--resume`. `Answer.model_version` is `response.model`.
  - `cache_control={"type": "ephemeral"}` is set at the top level, so runs 2 and 3
    of a case can read run 1's step 1 prefix.
  - Failures, most specific first:
    - `RateLimitError`, after the SDK's default 2 retries: `ClaudeLimitReached`.
      No file is saved, and `--resume` continues.
    - `AuthenticationError`, `PermissionDeniedError`, `NotFoundError` and
      `BadRequestError`: propagate and stop the run, because they would repeat.
    - `APIStatusError` at 500 or above, `APIConnectionError` (which covers
      timeouts), a `refusal` or `max_tokens` stop reason, no `parsed_output`, and
      `pydantic.ValidationError`: `AllModelsFailed` with one `ModelFailure` holding
      no response text, so the run is a placeholder, as in production.
  - The client timeout is 300 s, the same as `ClaudeLlm`.
  - After each call `run.py` prints `response.usage`: input, cache write, cache read
    and output tokens, and `_request_id` when a call fails.
  - `all.py`'s existing "Do you want to execute" prompt stays, and with this
    provider it also says that the run is billed to the API key.
- **Acceptance criteria:**
  - [ ] Throwaway script, fake client recording its kwargs: a full `run_case` of
        `28_real_retraction` saves a run, and `score.py` on the
        folder exits 0.
  - [ ] Same script: the kwargs have the model, `system`, `output_format`,
        `max_tokens`, effort and `cache_control` above, and no `fallbacks` or
        `temperature`; the first `document` block decodes to the sha256 in
        `SOURCES.md` and follows its label; nothing sent contains the case's
        `expected` values or `why`.
  - [ ] Same script: each failure class above gives the outcome listed: no file and
        a `--resume` command, a stopped run, or a placeholder whose cause holds none
        of the fake answer's text.
  - [ ] Same script: `judge.py --provider claude-api` with a fake client writes
        `items`, `passages` and `statements`, and `score.py` shows a text score.
  - [ ] `run.py --dry` and a `--provider gemini` run work in an environment without
        `anthropic` installed (a throwaway script blocks the import).
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": `--provider claude-api`, `--with anthropic`,
    `ANTHROPIC_API_KEY`, the default models and effort (Sonnet and Haiku, both
    medium), `--effort`, what stops a run and what makes a placeholder, that it
    bills the key, and that batches aren't used because steps depend on each other.
  - EVALUATION.md "Where the code lives": `ClaudeApiLlm`.
  - DECISIONS.md: add to S1's entry that the API provider exists for paid runs, and
    that production stays on Gemini.

## Human checks

- [ ] (S1) On a teammate's laptop, after `git pull`, with their own Claude Code login:
      run the first check below. Expected: a saved run that isn't a placeholder.
- [ ] (S1) From `backend/`, with `ANTHROPIC_API_KEY` unset:
      `uv run --with fpdf2 --with rich python evals/run.py 03_harmless_affiliation --provider claude-code --runs 1`.
      Expected: a saved run that isn't a placeholder, `apiKeySource` none, and the
      printed token counts and utilization.
- [ ] (S1) The same with `28_real_retraction`, the largest input left (a 17-page
      PDF). Expected: no placeholder. Read the printed tokens and utilization
      change to estimate the full run's cost before starting it.
- [ ] (S2) `uv run --with fpdf2 --with rich python evals/all.py 03_harmless_affiliation --provider claude-code --runs 1`.
      Expected: a verdict and `summary.txt`, with no Gemini key set.
- [ ] (S2) Decide `--runs` from the check above. On the Pro plan with overage
      disabled, 24 cases × 3 runs is up to 216 generation and 144 judge calls.
      5-hour and 7-day utilization were 0.33 and 0.68 on 2026-11-07. Expected: a run
      count that fits the limits, or a plan to resume over several windows.
- [ ] (S2) `judge_check.py sample <folder> --n 20`, fill in `human_present`, run
      `judge_check.py human`. Expected: an agreement rate showing the judge is
      reliable without the case material; if it's low, restore the material or reword
      the statements it gets wrong.
- [ ] (S2) `judge_check.py agree` on one folder graded twice with `--label b`.
      Expected: an agreement rate to quote, since the Claude judge has no
      temperature 0.
- [ ] (S3) Open a folder's `prompts.json` after a real run. Expected: readable
      prompt texts that match `prompts.py` at that commit.
- [ ] (S5) Only when you choose to spend API credit: from `backend/`, with
      `ANTHROPIC_API_KEY` set,
      `uv run --with fpdf2 --with rich --with anthropic python evals/run.py 03_harmless_affiliation --provider claude-api --runs 1`.
      Expected: a saved run that isn't a placeholder, and the printed usage.
- [ ] (S5) The same with `28_real_retraction`, twice into one folder
      with `--into`, within 5 minutes. Expected: the second run's step 1 shows cache
      read tokens, and the usage gives a per-run cost to multiply before a full run.
- [ ] (S4) Read case 25's `why`. Expected: it reads naturally without the
      reference to case 17.
