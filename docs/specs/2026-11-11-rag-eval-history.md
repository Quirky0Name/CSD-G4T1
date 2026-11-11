# Evals: judge impact's output with and without a paper's past

**Status:** draft

**Repo:** `D:\SMU\CS203\project\CSD-G4T1`

**Context file to update:** none named


**Commands:**

| What | Command |
|---|---|
| Full test suite | `cd backend && uv run pytest` |
| Single test file | `cd backend && uv run pytest <path>` |
| Typecheck | none |
| Lint | `cd backend && uv run ruff check` |
| Evals | `cd backend && uv run --with fpdf2 --with rich python evals/all.py` (real Gemini, needs `GEMINI_API_KEY`) |

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| E1 Eval runner reads a case's past; `--no-history` | `docs/specs/2026-11-11-rag-vector-store.md` S6 |
| E2 Eval case 32, with a past | E1 |

Run order: E1, E2.

External blockers:

- The RAG spec's S6 must be merged first. It adds `ReportInputs.chunks: list[Chunk]` (that spec's I4) and the `PAST TIMELINE` section in step 1's prompt.
- `GEMINI_API_KEY`, for the human checks only.

Git state:

- Proposed branch: `feat/rag-eval-history`, from `main` once the RAG spec is merged. `transient`.

## Open questions

1. **ASSUMED Q1: the scenario of case 32.**
   - The past: an expression of concern on the tracked paper, assessed earlier with a "monitor" recommendation, given as one past alert and one past report.
   - Now: a retraction notice.
   - `expected` facts: the retraction is linked to the earlier concern, change severity `high`.
   Affects E2.
2. **ASSUMED Q2: the evals read no vector store.** A case's past comes from its file, already in `Chunk` shape. The evals test the prompt and the model, not retrieval, which the RAG spec's tests cover. Affects E1.

## Suggested splits

No split: E2 needs E1's runner support.

## Out of scope

- Testing retrieval quality (whether the search finds the right chunks). Recorded by E1's doc delta in EVALUATION.md, "Evals".
- `history` in cases 01 to 31. `transient`.

## Interfaces

### I1. A case's `history` field

A list of objects in the RAG spec's `Chunk` shape:

```json
"history": [
  {"source_type": "report", "source_id": "3", "chunk_index": 0, "page": null, "report_id": 3,
   "source_date": "2026-06-01T00:00:00Z", "content": "...", "score": 0.81}
]
```

- Optional. Absent or empty means no past.
- Introduced by E1, used by E2.
- Doc delta: EVALUATION.md, "Evals".

## Subtasks

### E1. Eval runner reads a case's past

- **Goal:** `evals/run.py` passes a case's `history` to `assess` through `ReportInputs.chunks`, and `--no-history` runs any case without it.
- **Blocked by:** RAG spec S6.
- **Files:** `backend/evals/run.py`, `backend/evals/all.py` (passes the flag through, if it runs cases itself), `backend/tests/research_evaluation/test_eval_history.py` (new).
- **Seams:** `evals/run.py`'s `build_inputs(meta)` and its command line.
- **Decisions:**
  - Q2.
  - Each run's saved output records whether history was used, so two runs of one case can be told apart. ASSUMED.
- **Acceptance criteria:**
  - [ ] `build_inputs` on a case with `history` gives `ReportInputs.chunks` with those chunks, in the case's order: `test_eval_history.py::test_history_read`.
  - [ ] With `--no-history`, or with no `history` in the case, `ReportInputs.chunks` is empty: `test_eval_history.py::test_no_history`.
  - [ ] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:** EVALUATION.md, "Evals": a case's `history` field (I1); `--no-history`; evals don't test retrieval.

### E2. Eval case 32, with a past

- **Goal:** a case whose right answer depends on the paper's past.
- **Blocked by:** E1.
- **Files:** `backend/evals/cases/32_<slug>.json`.
- **Seams:** `evals/run.py` on case 32.
- **Decisions:** Q1.
- **Acceptance criteria:**
  - [ ] The case loads and builds its inputs with 2 history chunks, with no Gemini call: `test_eval_history.py::test_case_32_loads`.
- **Doc deltas:** EVALUATION.md, "Evals": case 32 in the case list, if the doc lists cases.

## Human checks

- [ ] **E2:** run case 32 twice with real Gemini, once with `--no-history`. Expected: the outputs differ, and only the run with history links the retraction to the earlier expression of concern.
- [ ] **E1:** run `evals/all.py` on cases 01 to 31 (none has a `history`). Expected: results stay within `evals/thresholds.json`.
