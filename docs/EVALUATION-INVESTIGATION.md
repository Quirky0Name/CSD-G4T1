# Investigating a detected change (plan)

**Status: plan, awaiting approval.** Work happens on
`feat/eval-investigation`. Background on how each change shows up in real
life, and what can be fetched for it, is `docs/RE-changes-explained.md` on
`feat/eval-impact` (sections 5.2 and 5.3), which isn't merged.

## The goal

When Research Evaluation stores a **new** alert, fetch what the change
actually is and store it in Storage Management, so the later impact step
has something to read:

- **the notice, if there is one:** its Crossref record (title, type, date,
  which articles it says it updates), its text when it's open access, and
  its PDF when one can be downloaded;
- **the new paper, if there is one:**
  - a newer version under another DOI (a Crossref `new_version` or
    `new_edition` entry, whose notice DOI *is* the new version), or
  - the paper's own DOI as it reads now, for changes made in place (a
    correction, a retraction watermark, JAMA's retract-and-replace at the
    same DOI). It only counts as new when its PDF differs from the one
    Storage Management already holds.

Investigation only fetches and stores facts. It doesn't judge them.

## Two new packages

```
backend/src/research_evaluation/
  investigation/   this plan: fetch the notice and the new paper, store them in SM
  impact/          later plan: an LLM judges whether the change matters, how it
                   affects the researcher, and what to do
```

`impact/` isn't created by this plan.

```
nudge ─▶ detection ─▶ key check ─▶ rules ─▶ store alert ─▶ 202
                                                  │
                                                  └─▶ (after the 202) investigation ─▶ store documents
                                                                           later:  impact ─▶ revise the alert
```

## What already exists (the searches asked for)

### Updating's lookups by DOI: the overlap

| Code | What it fetches by DOI | Overlap with investigation |
|---|---|---|
| `updating/sources.py` `fetch_crossref` | `api.crossref.org/works/{doi}` with the `mailto` polite pool; returns `CrossrefWork` or `NOT_FOUND` / `ERROR` | **Same endpoint.** But `CrossrefWork` keeps only `DOI` and `updated-by`. Investigation needs `title`, `type`, `published`, `container-title`, `update-to` and `relation`. |
| `updating/sources.py` `fetch_openalex` | `api.openalex.org/works/doi:{doi}`: retraction flag, journal, DOAJ, authors | None. Investigation doesn't need OpenAlex (Storage Management's PDF client already uses its PDF links). |
| Updating's snapshots (via Storage Management) | each notice's DOI, type, label, date, `source`, `record_id`, and the paper's own `doi` | **This is where investigation's input comes from.** RE's `Snapshot` model drops the paper's `doi` (`extra="ignore"`), so it has to be kept (S5). |
| `common/doi.py` `normalize_doi` | — | Reused as is. |

Decision proposed: investigation gets its **own** small Crossref fetcher,
following the same ok / not-found / error pattern and reading the same
`CROSSREF_MAILTO`. Research Evaluation doesn't import from `updating/`
(separate services that share an image), and widening Updating's
`CrossrefWork` is Updating's code. Moving the shared request code into
`common/` is a later refactor with Zhuo En, like `ServiceTokenAuth`.

### Storage Management and storing papers

- **One PDF per paper, no versions.** `papers.file_key` holds the key;
  `LocalFileStore.save(bytes)` writes `<uuid>.pdf` under `UPLOAD_DIR`. It
  has no `read`. Uploads keep their PDF, and tracking by DOI downloads an
  open-access copy with `OpenAccessPdfClient.download(doi)`: every OpenAlex
  PDF link, then Semantic Scholar's, with a browser-like user agent,
  redirects followed, only real PDFs up to 25 MB.
- **`OpenAccessPdfClient.download(doi)` is exactly the "new paper" fetch**
  (for the paper's own DOI, a new-version DOI, or a notice's DOI). It's
  Java, inside Storage Management, with no endpoint.
- **`GET /internal/papers/{id}/pdf` is in CONTRACTS.md but not built**, in
  Java or in the stub. Nothing can read a stored PDF yet.
- **`background_text`** ("raw text for week-13 LLM input") is in
  ARCHITECTURE §2's schema list but not built.
- **No endpoint lets another service store a file or a document.** Research
  Evaluation keeps no database, so everything investigation fetches needs a
  new home in Storage Management (S1, S2).
- **Blocker: Storage Management doesn't start, on `main` or here.** Two
  migrations are both version 4 (`V4__create_alert_notes.sql` and
  `V4__create_background_metadata.sql`), and Flyway stops with "Found more
  than one migration with version 4", so every Java test fails. S0 fixes
  it.

## Design

### Who fetches what

| What | Fetched by | Why |
|---|---|---|
| Crossref record of a DOI | Research Evaluation (`investigation/crossref.py`) | a JSON call, like Updating's |
| Open-access text (Europe PMC) | Research Evaluation (`investigation/europepmc.py`) | the only source that gave notice **text** in the live check (PLOS, Scientific Reports, RSC, PNAS); publisher pages and PMC PDFs are bot-blocked |
| PDF of a DOI | **Storage Management**, with the existing `OpenAccessPdfClient`, when Research Evaluation asks | reuses the download code instead of copying it to Python; the bytes never travel between services; Storage Management can compare them with the stored PDF itself |

Research Evaluation's external calls go only to `api.crossref.org` and
`www.ebi.ac.uk`, with fixed URLs whose only input is a DOI, on their own
HTTP client with no auth. The Storage Management client (which carries the
service token) is never used for them.

### What gets fetched for each change

Each fetched thing is a **document** with a role: `notice`, `new_version`
or `current_version`.

| Change | `notice` | `new_version` | `current_version` (paper's own DOI) |
|---|---|---|---|
| Retraction with a notice DOI | ✓ | — | ✓ |
| Retraction from OpenAlex's flag only | — | — | ✓ |
| Expression of concern | ✓ | — | — (content doesn't change) |
| Correction, erratum | ✓ | — | ✓ (often corrected in place) |
| `other`, type `new_version` / `new_edition` | — | ✓ (the notice DOI) | — |
| `other`, any other type (withdrawal, removal, …) | ✓ | — | ✓ |
| DOAJ delisting | — | — | — |

- For every document: its Crossref record, its Europe PMC text, and its
  PDF through Storage Management. Each part records its own status, so a
  failure is stored, never raised.
- **A `current_version` PDF identical to the stored one** (same SHA-256)
  isn't stored again; its status is `unchanged`. That's how "the new paper,
  if any" is decided for in-place changes.
- **A notice whose DOI is the paper's own DOI** (seen on IJAA) becomes one
  `current_version` document, not two.
- A replacement DOI that only the notice's *text* names ("Retraction and
  replacement of: …") isn't resolved: Crossref has no link to it. The text
  is stored, and impact can read it.

### When it runs: after the `202`

The PDF download can take over 30 s per link, and Updating's nudge timeout
is 20 s. So `POST /evaluate/changes` stores the alerts and replies as
today, and investigation runs afterwards as a FastAPI background task, for
the alerts that came back `201` (new). This is what EVALUATION-REVIEW-CHANGES.md
("Later stories") already planned for the LLM steps.

The cost: nothing retries a failed investigation. A failed fetch is still
stored with its status, but if Research Evaluation crashes midway, the
missing documents are never fetched. A retry (e.g. "alerts with no
documents" on the next nudge) is left for later.

### Storage Management: `alert_documents` (new table)

| Column | Type | Why |
|---|---|---|
| `id` | bigint, PK | |
| `alert_id` | bigint, FK → `alerts`, on delete cascade | the change it was fetched for; ownership follows the alert's paper |
| `role` | varchar (enum) | `notice` / `new_version` / `current_version` |
| `doi` | varchar | the DOI fetched |
| `crossref_status` | varchar | `ok` / `not_found` / `error` |
| `crossref_record` | text, nullable | the record as Research Evaluation sent it (JSON); Storage Management never looks inside, like the snapshot's `crossref_updates` |
| `text_status` | varchar | `ok` / `not_indexed` / `not_open_access` / `error` |
| `text` | text, nullable | plain text, capped |
| `text_truncated` | boolean | |
| `pdf_status` | varchar | `pending` / `ok` / `unchanged` / `not_found` / `error` |
| `file_key` | varchar, nullable | the stored PDF, in the same `LocalFileStore` |
| `sha256` | varchar, nullable | of the PDF |
| `created_at`, `pdf_fetched_at` | timestamptz | |

Unique (`alert_id`, `role`, `doi`), so a repeat stores nothing twice.

Endpoints (service JWT, under `/internal/**`):
- `POST /internal/alerts/{alertId}/documents`: store one document's
  Crossref record and text. `201` new; `200` with the stored one,
  unchanged; `404` unknown alert; `400` bad body.
- `POST /internal/alerts/{alertId}/documents/{documentId}/pdf`: Storage
  Management downloads the document DOI's open-access PDF, compares it with
  the paper's stored PDF for `current_version`, stores it, and returns the
  document. Repeating it after `ok` / `unchanged` downloads nothing.
- `GET /internal/alerts/{alertId}/documents`: `{"documents": [...]}`, for
  impact later and for tests.

Reading a stored PDF back (this one, or the paper's
`GET /internal/papers/{id}/pdf`) is left for the impact plan.

## Scope

In scope: fixing the migration clash (S0), storing documents in Storage
Management (S1, S2), the `investigation/` package (S3–S5), and running it
after each nudge (S6), with the stub Storage Management kept in step.

Out of scope:
- **`impact/`**: judging the documents, and revising the alert.
- **Reading documents or PDFs back for an LLM**, PDF text extraction
  (GROBID full text), and `GET /internal/papers/{id}/pdf`.
- **Retrying a failed investigation.**
- **Publisher pages, Crossref Labs / Retraction Watch reasons, Wayback
  Machine.**
- **Changes Updating doesn't nudge on** (EVALUATION-REVIEW-CHANGES.md,
  "Later stories").
- **The frontend.**

## Subtasks

Storage Management (from `CSD-G4T1/storage`): `./mvnw test`

Research Evaluation (from `CSD-G4T1/backend`):
```
python -m uv run pytest
python -m uv run ruff check
python -m uv run pytest -m live   # only the tests that hit the real APIs
```

Fixtures for external APIs are real responses, recorded from the live API.

### S0: Storage Management starts again (migration clash)

- **Goal:** `V4__create_alert_notes.sql` is renamed to
  `V5__create_alert_notes.sql`, contents unchanged. `background_metadata`
  keeps V4: it was merged first (PR #19, before #21), and Amir's
  `feat/storage-snapshot-endpoints` builds on it. `./mvnw test` passes.
  - A local database that already ran alert notes as V4 fails Flyway's
    validation and has to be reset (LOCAL_STORAGE_DB.md, "Resetting your
    local database").
- **Files:** `storage/src/main/resources/db/migration/V5__create_alert_notes.sql` (renamed)
- **Verification:** `./mvnw test` is green (it's red now, from the
  context-load test on); the migration list has unique versions.
- **Doc deltas:**
  - **DECISIONS:** a 2026-09-27 entry: why alert notes moved to V5 rather
    than background_metadata, and the reset needed.
  - **LOCAL_STORAGE_DB:** a note under "Resetting your local database"
    about the V4 clash.
  - **EVALUATION-REVIEW-CHANGES** and anywhere else naming
    `V4__create_alert_notes.sql`: the new name.

### S1: Storage Management stores investigation documents

- **Goal:** the `alert_documents` table (`V6__create_alert_documents.sql`),
  `POST /internal/alerts/{alertId}/documents` and
  `GET /internal/alerts/{alertId}/documents`, as in "Design". A new
  document starts with `pdf_status` `pending`. The stub Storage Management
  gets the same two endpoints.
- **Files:**
  - `storage/src/main/resources/db/migration/V6__create_alert_documents.sql`
  - `storage/src/main/java/com/g4t1/storage/alert/document/`: `AlertDocument`,
    `AlertDocumentRepository`, `AlertDocumentService`,
    `InternalAlertDocumentController`, `NewAlertDocumentRequest`,
    `AlertDocumentResponse`, `AlertDocumentListResponse`, `DocumentRole`,
    and status enums (with `LowercaseEnumConverter`)
  - `storage/src/test/java/com/g4t1/storage/alert/document/InternalAlertDocumentTest.java`
  - `backend/dev/stub_storage.py`
- **Verification:**
  - a stored document matches the request, and `crossref_record` comes back
    byte-for-byte as sent;
  - a repeat returns `200`, keeps the first document and adds no row;
  - the same DOI under another role, or another alert, is a separate
    document;
  - the list returns only that alert's documents;
  - deleting the paper deletes its alerts' documents;
  - `404` unknown alert, `400` missing role or unknown status, `401`,
    `403`;
  - the stub behaves the same (a small pytest against the stub).
- **Doc deltas:**
  - **CONTRACTS:** both endpoints, in "Storage Management ↔ Research
    Evaluation / Updating".
  - **ARCHITECTURE §2:** `alert_documents` in the schema and endpoint
    lists.
  - **DECISIONS:** investigation results live in Storage Management, per
    alert, and why.

### S2: Storage Management fetches a document's PDF

- **Goal:** `POST /internal/alerts/{alertId}/documents/{documentId}/pdf`
  as in "Design":
  - downloads with `OpenAccessPdfClient.download(doi)`; none gives
    `not_found`;
  - for `current_version`, a PDF with the same SHA-256 as the paper's
    stored file gives `unchanged` and stores no file;
  - otherwise stores it with `LocalFileStore` and records `file_key` and
    `sha256`;
  - an already `ok` / `unchanged` document isn't downloaded again;
  - `LocalFileStore` gains `read(key)`.
  - The stub gets the endpoint with a scripted result (no real download).
- **Files:** `alert/document/` (service and controller),
  `file/LocalFileStore.java`, a test class with a mocked
  `OpenAccessPdfClient`, `backend/dev/stub_storage.py`
- **Verification:** each status (`ok`, `unchanged`, `not_found`, and a
  download that throws); `unchanged` only for `current_version`; a changed
  PDF is stored and readable back; no second download after `ok`; a paper
  with no stored file never gives `unchanged`; `404` for an unknown alert
  or a document of another alert; auth outcomes.
- **Doc deltas:**
  - **CONTRACTS:** the endpoint.
  - **DECISIONS:** Storage Management downloads PDFs for Research
    Evaluation (reuse of `OpenAccessPdfClient`), and "new" means a
    different SHA-256.
  - **ARCHITECTURE §2:** file storage now holds investigation PDFs too.

### S3: Crossref records by DOI (Research Evaluation)

- **Goal:** `investigation/crossref.py`: `fetch_record(http, doi, mailto)`
  returns a `CrossrefRecord` (title, type, published date, journal,
  `update_to` entries, `relation`) or `NOT_FOUND` / `ERROR`, never an
  exception. Log lines carry only the DOI and a status or exception class.
  `investigation/http.py` holds the status enum shared with S4. Settings
  gain `crossref_mailto` from `CROSSREF_MAILTO`, default empty.
- **Files:** `backend/src/research_evaluation/investigation/__init__.py`,
  `http.py`, `crossref.py`; `research_evaluation/config.py`;
  `backend/tests/research_evaluation/investigation/test_crossref.py`,
  `test_config.py`; `backend/tests/fixtures/crossref/` (notice records:
  IJAA `10.1016/j.ijantimicag.2024.107416`, the Lancet EoC `…31290-3`, the
  Lancet retraction-and-republication `…31528-2`, F1000Research v2
  `10.12688/f1000research.187739.2`)
- **Verification:** IJAA's notice has its title and `update_to` pointing
  back at the paper; `…31528-2` lists two updated articles; F1000Research
  v2 has `new_version` and `has-version`; `404` → `NOT_FOUND`; `500`,
  timeout, bad JSON and a `200` that isn't a work → `ERROR`; DOIs with
  parentheses encoded like Updating's; `mailto` only when set; no
  `Authorization` header; the setting's default and env var; one `live`
  test.
- **Doc deltas:**
  - **SETUP:** Research Evaluation also reads `CROSSREF_MAILTO`.
  - **DECISIONS:** RE's own fetcher rather than importing Updating's;
    investigation fetches deterministically, not via LLM tool calls.
  - **README:** a row for this doc.

### S4: Open-access text from Europe PMC (Research Evaluation)

- **Goal:** `investigation/europepmc.py`: `fetch_text(http, doi)` searches
  Europe PMC by DOI, keeps only the result whose DOI matches, and for an
  open-access result with a PMCID fetches `{PMCID}/fullTextXML` and returns
  plain text (title, abstract, body, captions; no reference list), capped
  at 60,000 characters with a `truncated` flag. Otherwise the reason:
  `not_indexed`, `not_open_access` or `error`.
- **Files:** `investigation/europepmc.py`; `test_europepmc.py`;
  `backend/tests/support.py` (XML fixture loader);
  `backend/tests/fixtures/europepmc/` (search results for a Scientific
  Reports Author Correction, IJAA's notice and a no-match search; full
  text of PMC11906582 and PMC10836678)
- **Verification:** known sentences present, reference list absent; each
  reason; the cap; the DOI quoted safely in the query; no `Authorization`
  header; one `live` test.
- **Doc deltas:** **DECISIONS:** Europe PMC is the only text source, and
  fetched text is data for impact, never instructions.

### S5: Deciding and fetching the documents for one change (Research Evaluation)

- **Goal:** `investigation/investigate.py`: `plan_documents(change,
  paper_doi)` returns the (role, DOI) pairs from the table in "What gets
  fetched for each change", and `fetch_document(http, doi, mailto)` runs
  S3 and S4 concurrently and returns the body for S1's endpoint. RE's
  `Snapshot` keeps `doi`.
- **Files:** `investigation/investigate.py`, `__init__.py`;
  `research_evaluation/changes.py` (`Snapshot.doi`);
  `tests/research_evaluation/investigation/test_investigate.py`
- **Verification:** every row of the table, including the self-referencing
  notice and a missing paper DOI (no `current_version`); a Crossref failure
  still tries Europe PMC and vice versa; a total outage gives statuses, no
  exception; the existing detection tests still pass.
- **Doc deltas:** this doc's codebase context only.

### S6: Investigation runs after each nudge

- **Goal:**
  - `evaluate_papers` also returns the alerts that came back `201`, with
    their change and paper DOI. `POST /evaluate/changes` replies exactly as
    today, then a background task, for each of those alerts: plans its
    documents, fetches and stores each one (S1), then asks Storage
    Management for its PDF (S2) with a longer per-request timeout
    (`INVESTIGATION_PDF_TIMEOUT_SECONDS`, default 120).
  - Any failure is logged with the alert id and cause and never affects
    the nudge's reply or the other alerts.
  - External calls use their own `httpx.AsyncClient`, created at startup,
    with no auth.
- **Files:** `research_evaluation/evaluate.py`, `main.py`, `storage.py`,
  `config.py`, `investigation/run.py`; `backend/.env.example`;
  `tests/research_evaluation/conftest.py`, `test_evaluate.py`,
  `test_config.py`, `investigation/test_run.py`
- **Verification:** against the stub with external calls mocked:
  - a new alert gets its documents and PDF requests;
  - a re-nudge (`200` alerts, or skipped by the key check) makes no
    external calls;
  - Crossref, Europe PMC or Storage Management failing during
    investigation still gives the same `202` and stored alerts;
  - external requests carry no `Authorization`; SM requests still do;
  - the timeout setting's default, override and rejection of 0 or less;
  - every existing test passes.
- **Doc deltas:**
  - **CONTRACTS:** the `/evaluate/changes` flow: investigation after the
    reply.
  - **ARCHITECTURE §3:** the stages now include investigation.
  - **SETUP:** `INVESTIGATION_PDF_TIMEOUT_SECONDS`.
  - **DECISIONS:** investigation runs after the `202`, only for new
    alerts, with no retry yet, and why.
  - **EVALUATION-REVIEW-CHANGES:** "Later stories" points here.

## Codebase context

Filled in as each subtask lands: where each piece lives, how they fit
together, and the gotchas (the Storage Management client never makes
external calls, DOIs with parentheses, matching Europe PMC results by DOI,
fetched text being data rather than instructions, and SHA-256 deciding
whether a paper changed).

## TODO for other owners

- **Amir (Storage Management):** agree to `alert_documents`, its three
  endpoints and the V5 rename, or build them himself. Separately:
  `GET /internal/papers/{id}/pdf` (in CONTRACTS, not built) will be needed
  by impact.
- **Zhuo En (Updating):** nothing needed. Later, perhaps: share the
  Crossref request code through `common/`.

## Open questions

- **Storage Management downloads the PDFs** (recommended above), or
  Research Evaluation downloads them in Python and uploads the bytes?
- **Investigation after the `202`**, with no retry for now: acceptable?
- **The V5 rename** needs anyone who ran alert notes as V4 locally to
  reset their database. Tell the team, or would you rather rename
  `background_metadata` instead?
- **Defaults:** a 60,000-character text cap and a 120 s PDF timeout.
