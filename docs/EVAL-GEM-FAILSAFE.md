# Falling back through Gemini models when a call fails (plan)

**Status: done (S0–S2), 2026-09-28.** Work happened on
`fix/gem-request-failure`; see "Codebase context" for what was built and
where.

It changes one thing in [EVALUATION-IMPACT.md](EVALUATION-IMPACT.md):
what happens when a Gemini call fails. Today impact calls one model
(`GEMINI_MODEL`, default `gemini-flash-latest`) once per step, and any
failure fails the report: it stays `investigated` and nothing retries it.
On the free tier `gemini-flash-latest` is often `503` ("high demand"), and
each model has its own daily quota (about 20 requests), so one model failing
says little about the others.

## The goal

- **A list of models to fall back to.** `GEMINI_FALLBACK_MODELS`, comma
  separated, is tried after `GEMINI_MODEL`, in order. Default:
  `gemini-flash-lite-latest`. Empty turns the fallback off (only
  `GEMINI_MODEL` is tried). The default leaves out two names on purpose
  (DECISIONS.md, 2026-09-28, "Impact asks Gemini three questions, reading
  the PDFs itself", under "Also settled while building it"):
  `gemini-3.8-flash` is what `gemini-flash-latest` resolves to, so it is
  the same overloaded model, and `gemini-2.5-flash` is no longer available
  to new keys.
- **Each Gemini call walks the list** until a model answers: every one of
  impact's three steps starts again from `GEMINI_MODEL`. A model that fails
  is logged (the model and the cause, never the prompt) and the next one is
  tried.
- **What counts as a failure** of one model: any Gemini-side failure of the
  call:
  - a Gemini API error, whatever its code (`503` high demand, `429` quota,
    `404` a retired model name, …);
  - a transport error talking to Gemini (`httpx.TransportError`): a timeout
    (`IMPACT_LLM_TIMEOUT_SECONDS`), a refused or dropped connection;
  - no text in the answer;
  - an answer that doesn't fit the step's schema.
- **When every model fails at any step**, the report gets a placeholder
  evaluation, stored and marked `assessed` like any other:

  | Field | Value |
  |---|---|
  | `change_summary` | `FUCK U GEMINI FLASH. WHO TF IS EVEN USING GEMINI FLASH` |
  | `change_severity` | `low` |
  | `impact_level` | `low` (Storage Management requires one when the severity isn't `none`) |
  | `evaluation` | the same text as `change_summary` |
  | `recommendation` | the same text as `change_summary` |
  | `assessment` | `placeholder: true`, the step that ran out of models, each model's failure cause, and the answers of the steps that did finish |

- **Storage Management failures are unchanged.** Unreachable, a `409`, a
  `400` on the `PUT`: the report fails as before and stays `investigated`.
  Only running out of Gemini models gives the placeholder.
- **Which model answered each step** is recorded in `assessment`
  (`answered_by`), since steps 1 to 3 can now be answered by different
  models.

```
step N ─▶ GEMINI_MODEL ─fail─▶ fallback 1 ─fail─▶ … ─fail─▶ last fallback ─fail─▶ placeholder ─▶ PUT ─▶ assessed
             │ ok                 │ ok                           │ ok
             ▼                    ▼                              ▼
          step N's answer (answered_by records which model)
```

## Consequences (accepted by the story owner, 2026-09-28)

- **A placeholder locks the report.** Storage Management accepts one
  evaluation per report (`409` after that), so a report that got the
  placeholder while every model was overloaded keeps it: `POST
  /evaluate/reports` skips it as `assessed`. Re-assessing is still a later
  sprint (EVALUATION-IMPACT.md, "Later sprints").
- **The placeholder is sent like any evaluation:** the Telegram
  notification (EVALUATION-NOTIF.md) carries its text, and the report body
  the frontend reads shows it. `assessment.placeholder` tells it apart.
- **The default model name isn't checked against a key's model list.**
  `gemini-flash-lite-latest` is Google's alias for its current Flash-Lite,
  a smaller model than Flash with its own quota. A name that doesn't exist
  fails with a `404` and is skipped like any other failure. Extend or
  override the list in `.env` with the models the key can use.
- **More calls, and longer, against the free tier.** Each of the three
  steps can make up to (1 + fallbacks) calls, each with its own timeout,
  so a report's worst case is (1 + fallbacks) × 3 × `IMPACT_LLM_TIMEOUT_SECONDS`:
  with the default list and 120 s, 2 × 3 × 120 s = 12 minutes. Reports are
  assessed one after another (`assess_reports`), so every later report in
  the same run waits behind it.

## Subtasks

Research Evaluation (from `backend`):
```
python -m uv run pytest
python -m uv run ruff check
```

No test calls Gemini: the model is a fake, as in EVALUATION-IMPACT.md.

### S0: The plan

- **Goal:** this document, and a README row linking it.
- **Files:** `docs/EVAL-GEM-FAILSAFE.md`, `README.md`
- **Verification:** the settings, classes and paths the doc names match the
  code as it is or as S1 and S2 build it; the README links it.
- **Doc deltas:** none (it is the doc).
- **Commit message:**
  ```
  docs: plan falling back through gemini models when a call fails
  ```

### S1: Fall back through the models

- **Goal:**
  - `config.py`: `gemini_fallback_models: list[str]` from
    `GEMINI_FALLBACK_MODELS`, comma separated, blanks dropped, with the
    default above.
  - `impact/llm.py`: `Answer` gains `model` (the name that answered;
    optional, default `None`, so existing fakes that build `Answer(value,
    model_version)` still work). A new
    `FallbackLlm(client, models)` implements `Llm`; its `model` is the first
    name. `generate` tries each model in order and returns the first answer;
    on `genai_errors.APIError`, `httpx.TransportError`,
    `ValidationError` or `ValueError` it logs one warning and tries the
    next. When every model failed it raises a new `AllModelsFailed` with
    each `(model, cause)`.
  - `main.py`'s lifespan builds `FallbackLlm(client, [GEMINI_MODEL,
    *GEMINI_FALLBACK_MODELS])`.
  - `.env.example` gains the setting.
- **Files:** `backend/src/research_evaluation/config.py`, `impact/llm.py`,
  `main.py`; `backend/.env.example`;
  `backend/tests/research_evaluation/impact/test_llm.py`,
  `backend/tests/research_evaluation/test_config.py`,
  `backend/tests/conftest.py`
- **Verification:** with a fake Gemini client: the first model answering
  means one call; each failure kind (a `503` API error, a timeout, a
  connection error,
  off-schema JSON, no text) moves on to the next model, in order; the
  answer's `model` is the one that answered; every model failing raises
  `AllModelsFailed` with each model and its cause; each `generate` starts
  again from the first model; the fallback's log names the model and cause
  and no prompt text. The setting's default, a comma list with blanks and
  spaces, and empty (no fallbacks); the lifespan passes the whole list.
  Every existing test and `ruff check` pass.
- **Doc deltas:**
  - **SETUP:** the `GEMINI_FALLBACK_MODELS` row.
  - **DECISIONS:** falling back through a list of models, and why.
  - **EVALUATION-IMPACT:** "Failures" mentions the fallback.
  - **EVAL-GEM-FAILSAFE:** codebase context for S1.
- **Commit message:**
  ```
  feat: fall back through a list of gemini models when a call fails
  ```

### S2: A placeholder evaluation when every model fails

- **Goal:** `impact/assess.py` records `answered_by` per step; when any
  step raises `AllModelsFailed` it returns the placeholder `Evaluation`
  above (`change_severity` and `impact_level` `low`, the three texts the
  message, `assessment` with `placeholder: true`, the step, the failures and
  the earlier steps' answers). `run.py` stores it and notifies as for any
  evaluation; Storage Management errors still fail the report.
- **Files:** `backend/src/research_evaluation/impact/assess.py`,
  `schemas.py` (the message); `backend/tests/research_evaluation/impact/test_assess.py`,
  `test_run.py`
- **Verification:** with a fake model, against the stub: running out of
  models at step 1, 2 or 3 each gives the placeholder body with the right
  step and the earlier answers kept; at step 1 the draft is never
  requested; end to end the report ends `assessed` with the placeholder
  and the notifier gets it; a Storage Management failure still leaves it
  `investigated`; a normal run records `answered_by`. Every existing test
  and `ruff check` pass.
- **Doc deltas:**
  - **CONTRACTS:** `/evaluate/changes`'s impact step: the placeholder
    evaluation; `POST /evaluate/reports`: a report whose models all failed
    is `assessed` with the placeholder, so it can't be re-run there.
  - **DECISIONS:** the placeholder and its consequences (the report locked
    `assessed`, sent to Telegram).
  - **EVALUATION-IMPACT:** "Failures" and "What goes into the report".
  - **EVALUATION-NOTIF:** the placeholder is notified like any evaluation.
  - **ARCHITECTURE §3** (impact, stage 4): the fallback and the
    placeholder.
  - **EVAL-GEM-FAILSAFE:** codebase context for S2.
- **Commit message:**
  ```
  feat: store a placeholder evaluation when every gemini model fails
  ```

## Codebase context

Filled in as each subtask lands: where each piece lives, how they fit
together, and the gotchas.

### Falling back through the models (S1)

**Status: done.**

- **The setting:** `config.py` `gemini_fallback_models` (default
  `DEFAULT_GEMINI_FALLBACK_MODELS`, `("gemini-flash-lite-latest",)`). It is
  `Annotated[list[str], NoDecode]` with a `before` validator that splits on
  commas and drops blanks, so the env var (and `.env`) is a plain comma
  list, not JSON.
- **`impact/llm.py`:**
  - `FallbackLlm(client, models)` is the `Llm` the lifespan builds: one
    Gemini client, a tuple of names. `model` is the first name (what
    `assessment.model` records); an empty tuple is refused. `generate`
    calls the module's `generate` once per model, in order, and returns the
    first answer.
  - `MODEL_FAILURES` (`genai_errors.APIError`, `httpx.TransportError`,
    `ValidationError`, `ValueError`) are what move on to the next model;
    each is logged once as `Gemini model <name> failed (<cause>)`.
    Anything else propagates.
  - `failure_cause(exc)` says what went wrong without bodies or text: the
    API code and status, the transport error's class, the schema error
    count, "no text in the answer" (`NoText`, which `generate` now raises
    for an empty answer; a `ValueError`), or else the class name only.
  - When every model failed: `AllModelsFailed.failures`, a list of
    `ModelFailure(model, cause)` in the order tried.
  - `Answer.model` is the name the answer was asked of (default `None`, so
    fakes that build `Answer(value, version)` still work).
  - `GeminiLlm` (one model) is kept; the tests still use it.
- **`main.py`:** the lifespan builds
  `FallbackLlm(make_client(config), (GEMINI_MODEL, *GEMINI_FALLBACK_MODELS))`
  with duplicates removed in order (`dict.fromkeys`).
- **Gotchas:**
  - `AllModelsFailed` never reaches `run.assess_report`: `assess` turns it
    into the placeholder (S2).
  - A `ValueError` includes errors the SDK raises client side (for example
    its `UnknownApiResponseError`); they move on too, and are recorded by
    class name.
  - The SDK makes one attempt per call (no `retry_options`), so every
    retry is a fallback here.
- **Tests:** `tests/research_evaluation/impact/test_llm.py` (a scripted
  fake client answering per model name) and
  `tests/research_evaluation/test_config.py` (the setting and the
  lifespan).

### The placeholder evaluation (S2)

**Status: done.**

- **Where:** `impact/assess.py` `assess` runs the three steps inside one
  `try`, tracking the current step (`change`, `impact`, `actions`); an
  `AllModelsFailed` from any of them goes to `_placeholder`, which returns
  the `Evaluation` with `PLACEHOLDER_TEXT` (in `impact/schemas.py`) as all
  three texts and `low` for both levels. Nothing else is caught there:
  `run.assess_report` stores and notifies the placeholder like any
  evaluation, and still fails the report on a Storage Management error.
- **`assessment`,** always: `answered_by` (`change`, `impact`, `actions`:
  the `Answer.model` of each step, null for a step not run) and
  `placeholder` (`false`). For the placeholder: `placeholder: true`,
  `failed_step`, `failures` (`[{model, cause}]` from
  `AllModelsFailed.failures`), and whatever the finished steps left:
  `change` (and `model_version`) from step 1, `impact` from step 2,
  `draft` as far as it got. Unfinished steps stay null.
- **The draft** is still read only after step 1 answered: a placeholder
  at step 1 never requests it (`draft` `not_needed`).
- **Gotchas:**
  - A placeholder report is `assessed` for good: Storage Management takes
    one evaluation, and `POST /evaluate/reports` skips `assessed` reports.
    To assess it for real, the evaluation columns and `status` would have
    to be reset in the database.
  - The notifier can't tell a placeholder from a real `low` evaluation;
    only `assessment.placeholder` can. The frontend should check it too.
  - Only `AllModelsFailed` gives the placeholder. A plain `Llm` (such as
    `GeminiLlm`, or a test fake) raising a Gemini error still fails the
    report as before.
- **Tests:** `tests/research_evaluation/impact/test_assess.py` (the
  placeholder at each step, what `assessment` keeps, `answered_by` on a
  normal and a gated run) and `test_run.py` (end to end: stored
  `assessed`, notified, not assessed again, and a failing `PUT` still
  leaves it `investigated`).
