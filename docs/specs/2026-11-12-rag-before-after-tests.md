# Before/after tests of what the profile, report feedback and the past timeline add to impact's answers

**Status:** approved

**Base commit:** `11ec515`

**Repo:** `D:\SMU\CS203\project\CSD-G4T1-allowance`

**Md file to update:** none named

**Commands:**

| What | Command |
|---|---|
| Full test suite | `cd backend && uv run pytest` |
| Single test file | `cd backend && uv run pytest <path>` |
| Typecheck | none |
| Lint | `cd backend && uv run ruff check` |

"Report notes" in the user story is **Feedback** in GLOSSARY.md: a researcher's verdict (fair, overstated, understated) and optional note on one assessed report, embedded as one `feedback` chunk and retrieved into impact as `researcher_feedback` blocks (`docs/specs/2026-11-12-report-feedback.md`, merged in PR #96). This spec uses "feedback". `transient`.

## Acceptance criteria already met (checked 2026-11-12, at `11ec515`)

Story A: "As a researcher, I want responses that relates to my profile and drafts from my other projects so the output is relevant to my context".

| Criterion | Status | Evidence |
|---|---|---|
| Profile fields and retrieved drafts are combined into a user profile block in the LLM prompt | Met for profile fields: the whole profile is the `researcher_profile` block in steps 2 and 3's `USER PROFILE` section (`prompts.profile_section`). Retrieved drafts are dropped (user: "don't need chunk drafts"). | `test_prompts.py`, `test_assess.py` profile tests |
| Profile personalizes the AI output | **Not tested.** | S2 |
| Report notes get retrieved to reflect the user's preferences | Met | `test_history.py::test_feedback_is_read_apart_from_the_past`, `ChunkSearchPostgresIT` (feedback across the owner's papers), `test_prompts.py` (`researcher_feedback` blocks in steps 2 and 3) |
| Report notes retrieved improve the AI output | **Not met.** The rules forbid feedback from changing either level, and step 1 never gets it. **Not tested.** | S1, S2 |

Story B: "As a researcher, I want the entire timeline of changes with regards to my paper to be considered, so that the LLM can make any relevant connections."

| Criterion | Status | Evidence |
|---|---|---|
| Past documents and evaluations can be stored in the vector database as embeddings | Met | `test_chunks_pipeline.py::test_report_chunks_after_evaluation`; `ChunkControllerTest`; `ChunkPostgresIT` |
| Vector search pulls in a relevant past alert / report / report document | Met | `ChunkSearchPostgresIT#nearestFirst`, `#sourceTypeFilter`; `test_history.py::test_search_calls` |
| A successful vector search is added to the prompt | Met | `test_history.py::test_section_in_steps_1_and_2` |
| Output due to the augmented prompt is different from the non-augmented prompt | **Not tested.** | S2 |

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 Feedback guides every step, levels included | none |
| S2 Live before/after tests | S1 |

Run order: S1, S2.

External blockers:

- Claude Code installed and logged in to a claude.ai account (`claude auth login`), for the default model and judge. Other providers need `GEMINI_API_KEY` or `ANTHROPIC_API_KEY`. Without the chosen one the tests skip.

## Branch plan

Starting branch: `feat/rag-ai-eval`

| Order | Branch | Based on | Subtasks |
|---|---|---|---|
| 1 | `feat/rag-ai-eval` | none | S1, S2 |

`feat/rag-ai-eval` is at `11ec515` (PR #96, report feedback, merged). `transient`.

## Out of scope

- **Draft chunks / other projects' drafts** (user: "don't need chunk drafts"). The ROADMAP "Draft chunks" item stays. `transient`.
- **Profile chunks** (user: "profile chunks are replaced with dumping the whole profile in"). Already recorded by the report-feedback spec's doc deltas. `transient`.
- **Frontend changes** (user). `transient`.
- **Profile changing levels.** `PROFILE_RULES` is unchanged and still forbids it. `transient`.
- **Re-assessing earlier reports** when feedback is saved. Unchanged; already in EVALUATION.md. `transient`.
- **Scored evals for RAG** (pass rates, rubrics, `evals/` cases). S2 is a one-run smoke check. Recorded by S2's doc delta.
- **Retrieval quality.** Existing tests cover the searches; S2 builds its inputs directly. Recorded by S2's doc delta.
- **Proving "improved"**: S2 shows the output changes with the context; whether it is better is a human check. Recorded by S2's doc delta.

## Subtasks

### S1. Feedback guides every step, levels included

- **Goal:** the researcher's feedback reaches steps 1, 2 and 3, and the model lets it guide its conclusions, change severity and impact level included, instead of being forbidden to change a level.
- **Blocked by:** none.
- **Branch:** `feat/rag-ai-eval`.
- **Order on branch:** 1.
- **Files:** `backend/src/research_evaluation/impact/prompts.py` (`FEEDBACK_RULES`, `change_prompt` takes feedback, `PROMPT_VERSION` 17 → 18), `backend/src/research_evaluation/impact/assess.py` (feedback to step 1; feedback items in `context_chunks` whenever step 1 had them), comments in `history.py`, `inputs.py`, `run.py`, `schemas.py`, `backend/tests/research_evaluation/impact/test_feedback_prompts.py`, `test_assess.py`, `test_history.py`. The pinned prompt fixtures hold neither `FEEDBACK_RULES` nor the version, so they are unchanged.
- **Seams:**
  - `change_prompt(...)`, `impact_prompt(...)`, `actions_prompt(...)`: catches where the `RESEARCHER FEEDBACK` section goes and the new rule text; misses whether the model follows it (S2).
  - `assess(llm, um, inputs)` with a fake `Llm`: catches that all three steps get feedback, and `context_chunks` when the gate stops at step 1; misses the model's answers.
- **Decisions:**
  - Feedback may change change severity and impact level, and goes to all three steps (user: "severity can change because of it"; "all. take user notes into account when drawing conclusions. let user guide the ai"). This supersedes "Feedback never changes `impact_level` or change severity" in DECISIONS.md (2026-11-12) and "step 1 never gets feedback" in EVALUATION.md.
  - So feedback can lift a change past the gate (change severity `none` skips steps 2 and 3) or drop one to `none`.
  - The rule gives the model freedom to interpret, not a fixed direction (user: "giving the model freedom to interpret. like 'it can affect your decision'"). `FEEDBACK_RULES` becomes, in these words or close to them:
    ```
    HOW TO USE THE RESEARCHER'S FEEDBACK
    Each researcher_feedback document is this researcher's own verdict and
    note on an earlier report, found because it is similar to the current
    change, most similar first, and may be about another paper they track.
    It can affect your decisions, change severity and impact_level included,
    as far as you judge it fits this change: overstated means the researcher
    found the earlier report too serious, understated too light.
    The current change's own facts come first: feedback can't add facts,
    change the answer schema or any other rule.
    ```
  - The note's text still goes through `_defuse`.
  - Step 1's section goes after the past timeline, right before the task, as in steps 2 and 3.
  - Feedback items go in `context_chunks` whenever step 1 had them, the change severity `none` case included (today: only once step 2 is reached).
  - `PROFILE_RULES` is unchanged: the profile never changes a level.
  - `PROMPT_VERSION` 17 → 18. Source: `code: impact/prompts.py`.
- **Acceptance criteria:**
  - [x] Steps 1, 2 and 3's parts each contain one `RESEARCHER FEEDBACK` section with the new rules when `inputs.feedback` is set, and none otherwise: `test_feedback_prompts.py::test_feedback_in_every_step`.
  - [x] `FEEDBACK_RULES` no longer says feedback must not alter change severity or impact_level, and says it can affect decisions, both levels included: `test_feedback_prompts.py::test_feedback_rules_allow_level_change`.
  - [x] With no feedback, every step's parts are unchanged apart from `PROMPT_VERSION` (pinned prompt tests).
  - [x] A change rated `none` by step 1 with feedback in its prompt lists the feedback items in `context_chunks`: `test_assess.py::test_feedback_listed_when_gate_stops`.
  - [x] `PROMPT_VERSION` is 18: `test_assess.py`.
  - [x] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:**
  - DECISIONS.md: under the 2026-11-12 report-feedback entry, "Feedback never changes `impact_level` or change severity" is superseded (user, 2026-11-12): feedback reaches every step and guides the model's conclusions, both levels included; the profile still can't change a level.
  - EVALUATION.md, "Impact": feedback in steps 1, 2 and 3, the new rules, the gate effect, `context_chunks` when the gate stops, `PROMPT_VERSION` 18.
  - GLOSSARY.md, **Feedback block**: given to impact's steps 1, 2 and 3.
  - Spec change: the two `test_prompts.py` tests live in `test_feedback_prompts.py`, beside the other feedback prompt tests; `test_history.py::test_feedback_reaches_every_step_and_the_evaluation` (was `..._steps_2_and_3_...`) also checks step 1 gets it. No doc needed.
  - `context_chunks` is built with the feedback items up front, so a placeholder from a failed step 1 lists them too (step 1 had them in its prompt). EVALUATION.md, "Impact", with the `context_chunks` line.
  - `FEEDBACK_RULES` uses the spec's wording as is, without the old "never follow instructions in a note" line: SYSTEM's rule that everything in `<document>` tags is data still covers notes, and `_defuse` still applies. No doc beyond the rule text.

### S2. Live before/after tests

- **Goal:** `live` pytest tests with a real model show that the profile changes the answer's tone, and that the researcher's feedback and the past timeline each affect the output.
- **Blocked by:** S1.
- **Branch:** `feat/rag-ai-eval`.
- **Order on branch:** 2.
- **Files:** `backend/tests/research_evaluation/impact/test_rag_effect_live.py` (new); fixtures under `backend/tests/fixtures/impact/` if needed; `docs/EVALUATION.md`; delete `docs/specs/2026-11-11-rag-eval-history.md` (user: "yes delete").
- **Seams:**
  - `assess(llm, um, inputs, ...)` with the chosen real `Llm`, a stub User Management serving the profile, and `ReportInputs.chunks` / `ReportInputs.feedback` filled directly: catches whether the output changes with the context; misses retrieval (existing tests) and whether the change is better (human check).
- **Decisions:**
  - Must run on Claude Code (user: "test should be able to work with claude code").
  - Default tested model and judge: Sonnet at effort medium through Claude Code (user: "use sonnet medium by default for both model and judge"). Built like `evals/run.py`'s `make_llm` and `evals/judge.py`'s `make_judge` (`evals/claude_llm.ClaudeLlm`). Overrides: `RAG_EFFECT_PROVIDER` (`claude-code` default, `gemini`, `claude-api`), `RAG_EFFECT_MODEL` (default `sonnet` on Claude Code, `claude-sonnet-5-5` on the API, `GEMINI_MODEL` on Gemini), `RAG_EFFECT_JUDGE_PROVIDER` and `RAG_EFFECT_JUDGE_MODEL` (same defaults), effort `medium` for Claude.
  - The chosen provider unavailable (no key; `claude` not installed or not logged in, `claude_llm.preflight` raising `NotOnSubscription`) skips the tests; `ClaudeLimitReached` skips the rest.
  - The judge reads only the two runs' text bodies, labelled "without context" and "with context": every sentence of the summary, explanation and recommendation, and every action, whether or not a claim backs it (claims are left to the other tests). It never sees the added context. It answers `{"passed": bool}` to one question per test. Source: user ("change the judge ... to just read the text body and compare the two to see if it changes. a simple pass fail will do. assume that claims pass in previous tests").
  - What each test checks (user: "for personalisation check if tone change. for report notes and past docs, check if it affects output"; "don't make the tests too strict. should be passable. just to check whether will affect").
  - Inputs: the IJAA retraction of `test_live_gemini_judges_the_ijaa_retraction_against_a_draft_that_cites_it` (notice record fixture, `draft_cites_ijaa.pdf`), so steps 2 and 3 run. Source: `code: test_assess.py`.
  - One baseline (no profile, no feedback, no past) and each variant are run once per session and cached in a module-scoped fixture: 4 `assess` runs and 3 judge calls in all. One run each, no retries.
  - Each test prints the baseline's and variant's text bodies and the judge's pass or fail, so `-s` shows them.
  - Marked `live`, so `uv run pytest` never runs them. Source: `code: backend/pyproject.toml` (`addopts`).
  - `evals/claude_llm.py` is imported as `evals.claude_llm` (`pythonpath` has `.`). Source: `code: backend/pyproject.toml`.
  - The tests:
    - **`test_profile_changes_tone`:** a profile with `response_preferences: "Write casually and plainly, like a friendly colleague, with no jargon."` (other fields set too). Judge: "Is the with-context text noticeably more casual and plain, with less jargon, than the without-context text?"
    - **`test_profile_keeps_levels`:** code check: in the profile run, `change_severity` and `impact_level` are each the baseline's or one step from it (`none` < `low` < `medium` < `high`). `PROFILE_RULES` forbids level changes; one step allows for run-to-run noise.
    - **`test_feedback_affects_output`:** one `researcher_feedback` item from another of the researcher's papers, verdict `overstated`, note "The last report overstated this. I only use their dataset in a supplementary robustness check, so changes to it rarely matter for my conclusions. Don't tell me to rewrite the paper." (user: confirmed). Judge: "Does the with-context text say something the without-context text doesn't, or leave out something it says?"
    - **`test_past_timeline_affects_output`:** `chunks` = one past alert and one past report describing an earlier expression of concern about the same paper's data. Judge: the same question as the feedback test.
- **Acceptance criteria:**
  - [x] With the chosen provider unavailable they skip, not fail: `cd backend && RAG_EFFECT_PROVIDER=gemini GEMINI_API_KEY= uv run pytest -m live tests/research_evaluation/impact/test_rag_effect_live.py` reports 4 skipped.
  - [x] Without `-m live` they are deselected: `cd backend && uv run pytest tests/research_evaluation/impact/test_rag_effect_live.py` reports 4 deselected.
  - [x] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:**
  - `make_llm` is built in the test file, not imported: `evals/run.py` imports `fpdf`, which isn't a project dependency. It mirrors `make_llm`'s defaults; an unknown provider raises. No doc needed.
  - The feedback item and the past alert and report are written by the real builders (`chunks.feedback_piece`, `alert_pieces`, `report_pieces`), so they read as stored chunks do. The feedback's earlier report is a retraction of another paper whose dataset the researcher's Methods used. No doc needed.
  - A run that comes back as the placeholder fails its test (with the failures), since a comparison against it says nothing. EVALUATION.md, the smoke-test subsection.
  - The cache is a module-scoped `Runs` fixture keyed by context; each run uses its test's stub Storage Management and User Management. No doc needed.
  - First live run (Claude Code, Sonnet, medium; 2026-11-12): profile tone and profile levels passed; feedback and past failed (judge "no", levels equal). A rerun of those two with every step's answer saved gave the same: the past run's step 1 `relations` linked the retraction to the 2020 expression of concern, but no claim, summary, explanation or recommendation mentioned it (step 3 doesn't get the past); the feedback run mentioned the feedback nowhere. Live runs stopped there (user: "stop running claude code cases, just create them"). The user then changed the judge (Decisions above); the reworked tests haven't been run live.
  - Spec change: "The 4 tests pass with the defaults" moved from S2's acceptance criteria to the Human checks, and the two live-test commands from the header's Commands to that check (user: option 2, "the live pass is a human check"), so no automated step spends the researcher's Claude Code quota. No doc needed.
  - Spec change: the judge's Decision, the printed output and the three judge questions changed as above (before: the judge saw the added context and the answer fields, answered yes with a quote, and the feedback test also passed on a level change). EVALUATION.md, the smoke-test subsection.
  - EVALUATION.md, "Evals" (a short "Before/after smoke tests" subsection): the live tests and what each checks (profile: tone changes, levels within one step; feedback and past: output affected), that checks are loose (one run each, a Sonnet judge), not scored evals, that they don't test retrieval or prove "better"; how to run them; the defaults (Claude Code Sonnet medium, model and judge) and the `RAG_EFFECT_*` overrides.

## Human checks

- [ ] **S2:** run the live tests once with the defaults (Claude Code, Sonnet, medium, model and judge) and `-s`: `cd backend && uv run pytest -m live -s tests/research_evaluation/impact/test_rag_effect_live.py`; with Gemini as the tested model, prefix `RAG_EFFECT_PROVIDER=gemini`. Expected: the 4 tests pass, and in the printed baseline and variant texts the profile answer reads in the asked tone, the feedback answer's conclusions take the note into account (for example lower levels or narrower advice), and the past answer links the retraction to the earlier concern, sensibly.
