# Claim passages: backend

**Status:** approved
**Repo:** `D:\SMU\CS203\project\frontend-hiwoufeiwhfeoiewifhewo`
**Context file to update:** none named

**Story:** "As a researcher, I want the passages most relevant to my research
topic/question highlighted in the paper, so that the AI claim can be validated."

- Given the AI produces an assessment or recommendation, then the claim links
  at least one supporting passage.
- Given a claim and supporting passage, the quote should accurately back the
  claim.

**Commands:**
- Research Evaluation and evals (from `backend/`):
  - Full test suite: `uv run pytest`
  - Single test file: `uv run pytest <path>`
  - Typecheck: none
  - Lint: `uv run ruff check`
  - Evals are scored by command, not pytest (`docs/DECISIONS.md`, 2026-10-27):
    `uv run --with fpdf2 --with rich python evals/score.py <results folder>`
- Storage Management (from `storage/`, `.\mvnw.cmd` on Windows):
  - Full test suite: `./mvnw test`
  - Single test class: `./mvnw test -Dtest=<ClassName>`
  - Typecheck: none (compiled by `./mvnw test`)
  - Lint: none

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | none |
| S3 | S1 |
| S4 | S1, S2, S3 |
| S5 | S4 |
| S6 | none |

Run order: S1, S2, S3, S4, S5, S6.

External blockers: none.

Git state: working tree clean apart from this spec, which stays uncommitted
until `/loopeng-auto` runs. Current branch `feat/frontend-highlighting` and
local `main` are both at `1ff8771`, 27 commits behind `origin/main` (`dfc2589`).
Those commits rewrite `backend/evals/score.py`, `judge.py` and `run.py`.
Branch: `feat/claim-passages-backend`, from `origin/main`
(user), not `feat/frontend-highlighting`, so S5 works on the current eval code.

## Suggested splits

Two groups have no dependency between them and change no common files:

- **Group A, claims:** S1, S2, S3, S4, S5.
- **Group B, PDF endpoints:** S6.

Each could run as its own `/loopeng-auto`. Kept as one spec, because the frontend
highlighting needs both (`transient`).

## Out of scope

- Frontend rendering of PDFs, highlights and claims (`transient`: the frontend
  spec on `feat/frontend-highlighting` does it).
- A research topic or question field (S3 records it in
  `docs/ROADMAP.md`).
- Bounding boxes or character offsets in passages (S4 records
  it in `docs/CONTRACTS.md`).
- Backfilling reports already assessed (S2 records it in
  `docs/CONTRACTS.md`).
- Character ranges tying a claim to words of `change_summary`, `evaluation` or
  `recommendation`: claims are a separate list covering their statements, not
  offsets into them (S4 records it in `docs/EVALUATION.md`).
- OCR of scanned PDFs: a PDF with no text layer gives `verified: "no_text"` (S4
  records it in `docs/EVALUATION.md`).

## Interfaces

### `Claim`

`claims` is a new optional JSON array on `PUT /internal/reports/{reportId}/evaluation`.
Storage Management stores it as sent and returns it on
`GET /internal/reports/{reportId}`, `GET /internal/papers/{id}/reports/{reportId}`
and `GET /papers/{id}/reports`. It is null on reports assessed before this.

```json
{
  "id": "impact-0",
  "kind": "impact",
  "text": "The draft's dose choice rests on the finding that 200 mg of caffeine improves working memory",
  "supported": true,
  "passages": [
    {
      "target": "paper",
      "document_id": null,
      "alert_id": null,
      "quote": "200 mg caffeine improved n-back accuracy",
      "page": 3,
      "verified": "verbatim"
    }
  ]
}
```

| Claim field | Meaning |
|---|---|
| `id` | `<kind>-<index in that step's claims>`; unique in the report |
| `kind` | the step that made it: `change`, `impact` or `action` |
| `text` | the statement, as the model wrote it; a claim isn't tied to an alert, use or action |
| `supported` | true when at least one passage has `verified` `verbatim`; false with no passages |
| `passages` | the claim's passages, as the model ordered them |

| Passage field | Meaning |
|---|---|
| `target` | `paper` (the stored PDF), `draft`, `document` or `alert`; null when the quote was found nowhere and its source names no text |
| `document_id` | the report document's id when `target` is `document`, else null |
| `alert_id` | the alert's id when `target` is `alert`, else null |
| `quote` | as the model wrote it |
| `page` | 1-based page where the quote starts when found in a PDF, else null |
| `verified` | `verbatim`, `not_found` (the text was read; the quote isn't in it) or `no_text` (no text to check: no PDF, unreadable, or no text layer) |

All of it is model output and fetched text: show as text, never as HTML.

### User-facing PDFs

User JWT, `application/pdf`, only the owner. Another user's item gets the same
`404` as a missing one.

- `GET /papers/{id}/pdf`
- `GET /documents/{id}/pdf`
- `GET /draft/pdf?project_id=` (the project's current draft, default project when left out)

## Subtasks

### S1: Move quote matching into the service (prefactor)

- **Goal:** `norm`, `quoted_in` and the hyphen joining live in one module of the
  service, and the evals import them from there. No behaviour changes.
- **Blocked by:** none
- **Files:** `backend/src/research_evaluation/impact/quotes.py` (new),
  `backend/evals/score.py`, `backend/evals/real/extract.py`,
  `backend/pyproject.toml`, `backend/uv.lock`,
  `backend/tests/research_evaluation/impact/test_quotes.py` (new)
- **Seams:** `quotes.norm(text)`, `quotes.quoted_in(quote, text)`,
  `quotes.join_hyphens(text)`, `quotes.pdf_pages(data) -> list[str]`
- **Decisions:**
  - `pypdf` becomes a project dependency: `backend/evals/real/extract.py` already
    uses it, and it is BSD-licensed (doc delta).
- **Acceptance criteria:**
  - [x] `uv run pytest tests/research_evaluation/impact/test_quotes.py` passes:
    NFKC ligatures, curly quotes, whitespace, `...` skipping, a hyphen joined
    across a line break.
  - [x] `pdf_pages` returns one string per page for a fixture PDF, and raises
    `quotes.PdfUnreadable` for corrupt bytes (same test file).
  - [x] `uv run --with fpdf2 --with rich python evals/run.py --dry 01_correction_effect_size` succeeds.
  - [x] `uv run --with fpdf2 --with rich python -c "import sys; sys.path.insert(0, 'evals'); import score; assert score.quoted_in is not None"` succeeds.
  - [x] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:** `docs/EVALUATION.md`, code layout table: `impact/quotes.py`.
  - Decisions made in implementation (S1):
    - `pdf_pages` returns each page's raw text; callers join hyphens
      (`extract.py` joins the pages with a blank line, then `join_hyphens`), so
      the committed `.txt` files come out byte-identical. No doc: code shows it.
    - `pdf_pages` turns any exception pypdf raises into `PdfUnreadable`, since
      pypdf raises many types for a broken file. No doc: code shows it.
    - The two-page fixture `tests/fixtures/pdfs/two_pages.pdf` is made here
      (once, with fpdf2) and reused by S4. No doc: the test shows it.
    - `evals/real/extract.py` imports `research_evaluation.impact.quotes` from
      `src/`, so it no longer needs `--with pypdf`: run it as
      `uv run python evals/real/extract.py`. EVALUATION.md, the `extract.py`
      command.
    - `pyproject.toml` and `uv.lock` keep their CRLF line endings after
      `uv add pypdf`. No doc.

### S2: Store and return claims

- **Goal:** Storage Management stores `claims` once with the evaluation and
  returns it, and the researcher's report API returns the fields of the
  evaluation it left out.
- **Blocked by:** none
- **Files:** `storage/src/main/resources/db/migration/V10__add_report_claims.sql`
  (new), `storage/.../report/Report.java`, `ReportEvaluationRequest.java`,
  `ReportResponse.java`, `UserReportResponse.java`, `ReportService.java`,
  `ReportRepository.java`, `storage/src/test/.../report/InternalReportEvaluationTest.java`,
  `ReportListTest.java`, `backend/dev/stub_storage.py`,
  `backend/tests/research_evaluation/test_stub_reports.py`
- **Seams:** `PUT /internal/reports/{reportId}/evaluation`,
  `GET /internal/reports/{reportId}`, `GET /papers/{id}/reports`
- **Decisions:**
  - user: `claims` is its own column, not read out of `assessment`, because "Storage
    Management never looks inside" `assessment` (`docs/CONTRACTS.md`) stays true
    (doc delta).
  - user: no backfill; `claims` is null on earlier reports (doc delta).
  - user: `GET /papers/{id}/reports` returns `change_summary`,
    `change_severity` and `impact_level` (doc delta).
- **Acceptance criteria:**
  - [ ] `./mvnw test -Dtest=InternalReportEvaluationTest` passes: `claims` is
    returned exactly as sent; a `PUT` without it stores null.
  - [ ] `./mvnw test -Dtest=ReportListTest` passes: `GET /papers/{id}/reports`
    returns `claims`, `change_summary`, `change_severity` and `impact_level`, and
    never `assessment`.
  - [ ] `./mvnw test` passes (Flyway applies V10 under `ddl-auto: validate`).
  - [ ] `uv run pytest tests/research_evaluation/test_stub_reports.py` passes with
    the stub keeping `claims`.
- **Doc deltas:** `docs/CONTRACTS.md`: the `claims` field and `Claim` shape on all
  three endpoints, the three added fields, no backfill. `docs/STORAGE.md`: the
  column. `docs/diagrams/erd-flyway-V9.html`: not regenerated; note V10 in
  `docs/STORAGE.md`.

### S3: Ask the model for passages

- **Goal:** Each step's answer lists its factual statements as claims with passages,
  from any material the step was given, and step 2 reads the tracked paper so its
  claims can quote what the draft relies on.
- **Blocked by:** S1
- **Files:** `backend/src/research_evaluation/impact/schemas.py`, `prompts.py`,
  `assess.py`, `backend/tests/research_evaluation/impact/test_assess.py`,
  `test_prompts.py` (new)
- **Seams:** `assess.assess(llm, sm, inputs)` with a fake `Llm`,
  `prompts.impact_prompt(...)`, `prompts.change_prompt(inputs)`, `schemas.*`
- **Decisions:**
  - user: a claim can be made about anything, so claims aren't derived from alert
    judgments, uses or actions. `ChangeAssessment`, `ImpactAssessment` and
    `RecommendedActions` each gain `claims: list[Claim]`, where a claim is
    `{text, passages: list[Evidence]}` (doc delta).
  - user: the prompt requires one claim per factual statement in that step's
    `summary`, `explanation` or `recommendation` (doc delta).
  - user: a passage's source can be any material the step was given: `stored paper`,
    a notice's DOI, `new version`, `current copy`, `draft` or `alert <id>` (doc delta).
  - user: `evidence` and `Use.quote` stay as they are, because the evals score them
    (doc delta).
  - user: the draft stands in for the research topic; step 2 writes an `impact`
    claim per use, quoting the draft's sentence and the tracked paper's passage behind
    it, when the stored paper was attached (doc delta, `docs/ROADMAP.md`).
  - user: each step's `claims` has `min_length=1`, so an empty list is a schema
    misfit that `FallbackLlm` handles as it does any other. `passages` has no minimum:
    a statement with nothing to quote has none (doc delta).
  - user: a citation jump's and a flag-only retraction's `change` claims quote the
    alert's line as `alert <id>`; `action` claims quote what the `change` and `impact`
    claims they rest on quote, including "upload your draft" (doc delta).
  - Quotes must be contiguous: no `...` (doc delta).
  - Step 2 attaches the draft first, then the stored paper if the two fit
    `MAX_INLINE_PDF_BYTES`. With no draft, the stored paper isn't attached. What
    was attached or left out goes in `assessment.impact_pdfs`, shaped like
    `assessment.pdfs` (doc delta).
  - `PROMPT_VERSION` becomes 5 (doc delta).
- **Acceptance criteria:**
  - [ ] `uv run pytest tests/research_evaluation/impact/test_prompts.py` passes:
    step 2 with a draft has both PDFs in order; with no draft, none; when both
    exceed the budget, only the draft, and the left-out is recorded.
  - [ ] Same file: each of the three tasks asks for one claim per factual statement,
    and step 1's names `alert <id>`.
  - [ ] `uv run pytest tests/research_evaluation/impact/test_assess.py` passes: an
    answer with empty `claims` fails on the first model and the next model is used; a
    claim with empty `passages` is accepted; a citation jump's empty `evidence` is
    still accepted.
  - [ ] `test_assess.py`: `assessment.prompt_version` is 5; the draft is still read
    only after the gate.
  - [ ] `uv run --with fpdf2 --with rich python evals/run.py --dry 01_correction_effect_size` succeeds.
  - [ ] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:** `docs/EVALUATION.md`, "Impact": the table's step 2 reads, each
  step's `claims`, `impact_pdfs`, `PROMPT_VERSION` 5, the `alert <id>` label.
  `docs/ROADMAP.md`: the draft stands in for a research topic; a topic field isn't
  built.

### S4: Verify quotes and build claims

- **Goal:** Every evaluation carries `claims`: every step's claims with their
  passages, each quote checked in code against the text it came from.
- **Blocked by:** S1, S2, S3
- **Files:** `backend/src/research_evaluation/impact/claims.py` (new),
  `assess.py`, `schemas.py`,
  `backend/tests/research_evaluation/impact/test_claims.py` (new), `test_assess.py`,
  `backend/tests/fixtures/pdfs/` (a small PDF with text on two pages)
- **Seams:** `claims.build_claims(inputs, draft, change, impact, actions) ->
  list[Claim]`, `assess.assess(...)`
- **Decisions:**
  - Sources resolve as: a report document's DOI to that `document`; the paper's own
    DOI or `stored paper` to `paper`; `current copy` to the `current_version`
    document; `new version` to the `new_version` document; `draft` to `draft`;
    `alert <id>` to `alert` (doc delta).
  - A document is checked against its PDF when it has one, its `text`, and the values
    of its `crossref_record` and `links`, so a notice with no open-access text can be
    quoted from its record. A match in the record or links gives `page: null` (doc delta).
  - `paper` also covers the paper's details as the prompt lays them out
    (`prompts.paper_block`); a match there gives `page: null` (doc delta).
  - Claims quote only source material, never the model's own earlier answers (doc delta).
  - A quote not in the source it names, but in another of the report's texts, is
    re-targeted to where it was found and counts as `verbatim`, so the highlight
    lands in the right place. The raw answer in `assessment` stays as it was, and
    the evals still count it as misattributed (doc delta).
  - user: a claim is never dropped; `supported` is false when no passage
    qualifies (doc delta).
  - user: a passage gives its document, quote and 1-based page; no bounding
    boxes (doc delta).
  - PDF text is extracted in `asyncio.to_thread`, once per PDF per report (doc delta).
  - A placeholder evaluation carries the claims of the steps that finished. A
    change severity `none` report carries its `change` claims (doc delta).
  - Quotes and PDF text are never logged (`docs/EVALUATION.md`, "Gotchas").
- **Acceptance criteria:**
  - [ ] `uv run pytest tests/research_evaluation/impact/test_claims.py` passes:
    - every step's claims in order, with the `id`s and `kind`s of the interface;
    - a claim with no passages gives `supported: false`;
    - a quote on page 2 of the fixture PDF gives `page: 2`, `verified: "verbatim"`;
    - a quote in the notice's text gives `target: "document"`, its id, `page: null`;
    - a quote from a notice's `crossref_record` with no text gives `verbatim`, `page: null`;
    - a quote from the paper's details gives `target: "paper"`, `page: null`;
    - an invented quote gives `not_found`, and `supported: false` when it is the
      claim's only passage;
    - a quote from a corrupt PDF or one with no text layer gives `no_text`;
    - a quote naming the wrong source is re-targeted;
    - a citation jump's alert-line quote gives `target: "alert"`, its `alert_id`;
    - nothing from the quote or the PDF appears in `caplog`.
  - [ ] `uv run pytest tests/research_evaluation/impact/test_assess.py` passes: the
    `Evaluation` has `claims` on a full run, on change severity `none` (only
    `change` claims) and on a placeholder (the finished steps' claims).
  - [ ] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:** `docs/EVALUATION.md`: how quotes are verified, source resolution,
  re-targeting, what `supported` means, no OCR, no character ranges into the summary
  texts.
  `docs/CONTRACTS.md`: no bounding boxes or offsets. `GLOSSARY.md`: **Claim** and
  **Passage**. `docs/ROADMAP.md`: tick "verify quotes against PDF text".
  `docs/DECISIONS.md`: dated entry: claims never dropped, and quotes verified in code.

### S5: Score passages in the evals

- **Goal:** An eval run reports claims with no passage, invented or misattributed
  passages, passages an independent judge says don't back their claim, and statements
  in the summary texts that no claim covers.
- **Blocked by:** S4
- **Files:** `backend/evals/score.py`, `judge.py`, `thresholds.json`, `compare.py`
  if it lists failure types, `backend/evals/check_passage_scoring.py` (new)
- **Seams:** `score.check_quotes(ev, case)`, `score.check_passages(ev)`,
  `score.verdict(r, thresholds)`, by command
- **Decisions:**
  - `check_quotes` also covers each step's `claims[].passages`, against their source;
    `source_label` learns `draft`, and `quote_sources` adds each document's Crossref
    record and links, as S4 does (doc delta).
  - New failure types, with thresholds `max_unlinked: 0`, `max_unbacked: 0` and
    `max_uncovered: 0` (doc delta):
    - `unlinked_claim`: a claim with no passage. An `impact` claim on a run whose
      `assessment.draft` isn't `read` is exempt, because a general judgment has nothing
      to quote.
    - `unbacked_passage`: the judge says it doesn't back the claim.
    - `uncovered_statement`: the judge finds a factual statement in `change_summary`,
      `evaluation` or `recommendation` that no claim covers. Code can't prove coverage,
      so this is how it is measured (user).
  - user: no runtime check that a quote backs its claim, and no fourth Gemini call.
    `supported` means only that a quote was found verbatim. Whether quotes back their
    claims is measured here, offline (doc delta).
  - The judge model grades backing, and "the judge
    reports, the code decides" (`docs/DECISIONS.md`, 2026-10-27) (doc delta).
  - Runs without `claims` (saved before `PROMPT_VERSION` 5) skip all three thresholds, so
    older folders still score (doc delta).
- **Acceptance criteria:**
  - [ ] `uv run --with fpdf2 --with rich python evals/check_passage_scoring.py`
    exits 0. The script uses inline fixtures and asserts: a `change` claim with empty
    `passages` gives `unlinked_claim`; an `impact` claim with empty `passages` on a run
    with `draft: "none"` doesn't; a judged `no` gives `unbacked_passage`; a judged
    uncovered statement gives `uncovered_statement`; an invented claim quote counts as
    invented; a run with no `claims` key has none of the three failures.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:** `docs/EVALUATION.md`, "Evals": the new checks, thresholds and
  failure types.

### S6: User-facing PDF endpoints

- **Goal:** The frontend can read the stored paper, a report's document and the draft
  as PDFs with the user's token.
- **Blocked by:** none
- **Files:** `storage/.../paper/PaperController.java`, `PaperService.java`,
  `storage/.../report/ReportController.java` or a new `DocumentController.java`,
  `ReportDocumentService.java`, `storage/.../draft/DraftController.java`,
  `DraftService.java`, and their tests under `storage/src/test/`
- **Seams:** the three endpoints of "User-facing PDFs"
- **Decisions:**
  - user: the backend serves the PDFs (doc delta).
  - Another user's item gets the same `404` as a missing one, as for
    `GET /papers/{id}/reports` (`docs: docs/CONTRACTS.md`) (doc delta).
  - A draft replaced after an assessment means a draft quote may not be found in the
    current upload. Only the current upload is served (doc delta).
- **Acceptance criteria:**
  - [ ] `./mvnw test -Dtest=<paper PDF test class>`: `200`, `application/pdf` and the
    stored bytes for the owner; `404` for another user and a missing paper; `403` for
    a service token; `401` with no token.
  - [ ] Same for `GET /documents/{id}/pdf`, plus `404` for a notice.
  - [ ] Same for `GET /draft/pdf`, with and without `project_id`, plus `404` for a
    project with no draft.
  - [ ] `./mvnw test` passes.
- **Doc deltas:** `docs/CONTRACTS.md`: the three endpoints; remove "The frontend has
  no endpoint to download a document's stored PDF". The feature table. `docs/STORAGE.md`:
  a draft highlight applies to the current upload.

## Human checks

- [ ] **S5:** From `backend/`, with `GEMINI_API_KEY` set, run
  `uv run --with fpdf2 --with rich python evals/all.py`. Expected: every case's
  `unlinked_claim`, `invented_quote`, `unbacked_passage` and `uncovered_statement`
  counts meet
  `evals/thresholds.json`, and tag and text pass rates don't regress against a
  baseline from `PROMPT_VERSION` 4 by more than the noise floor (`evals/compare.py`).
- [ ] **S2, S4:** Run the stack (`docker compose up -d --build`) and trigger a change
  on a demo paper with a draft (`docs/DEMO.md`). Expected: `GET /papers/{id}/reports`
  returns `claims`, each `impact` claim about a use has a `paper` passage with a page,
  and the quote is on that page of the PDF.
- [ ] **S6:** With the same report, fetch the three PDF endpoints as the owner and as
  another user. Expected: the owner gets each PDF, and the other user gets `404`.
