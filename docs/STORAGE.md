# Storage Management

The team's persistence service: Postgres and every PDF. Java 25 + Spring
Boot, `storage/`. The frontend reads and acts on everything through it;
Updating and Research Evaluation read and write through its `/internal/**`
endpoints. It never calls Research Evaluation. Endpoint shapes and every
error are in CONTRACTS.md; running it is in SETUP.md; the schema diagram
is [erd/erd-flyway-V8.html](erd/erd-flyway-V8.html).

## Code layout

`storage/src/main/java/com/g4t1/storage/`, one package per area. Each
follows the same layering: a controller (HTTP only; `Internal…Controller`
for `/internal/**`), request records with Bean Validation (shape checks,
`400` before the service runs), a service (rules that need the database:
ownership, existence, idempotency; throws `ResponseStatusException`), a
Spring Data repository, the entity, and response records.

| Package | What |
|---|---|
| `paper/` | tracked papers: upload, track by DOI, list (`PaperService`), the internal list and PDF read |
| `snapshot/` | `background_metadata`: store a snapshot, read the history (`after_id`, `last`, `limit`) |
| `alert/` | alerts (internal store, change keys; user list, status), alert notes; `LowercaseEnumConverter` |
| `report/` | reports and documents: open, read, mark investigated, store the evaluation (`ReportService`), store a document and download its PDF (`ReportDocumentService`), the user's report list |
| `research/` | the researcher's draft per project (`ResearchPaperService`) |
| `dev/` | demo tools (`DevController`, `DevService`) and Swagger config |
| `file/` | `LocalFileStore` (`save`, `read`, `delete` in `UPLOAD_DIR`), `Pdfs` (the `%PDF-` check) |
| `metadata/` | `MetadataClient` (Crossref/OpenAlex metadata, DOI normalising), `OpenAccessPdfClient` |
| `grobid/` | `GrobidClient` (DOI and title from an uploaded PDF) |
| `security/` | `JwtAuthFilter`, `SecurityConfig`: `/internal/**` needs a service token, everything else a user token |

Config: `src/main/resources/application.yml` (snake_case JSON, problem
details on, `ddl-auto: validate`, 25 MB PDF limit). Migrations:
`src/main/resources/db/migration/`. Tests: `src/test/java/.../<package>/`.

## Papers and their PDFs

A tracked paper is a `papers` row: one user tracking one paper, in one
project (owner + `folder_id`). A user can't track a DOI twice
(`uq_papers_owner_doi`).

| Path (`POST /papers`) | `PaperService` | What's stored |
|---|---|---|
| Upload (multipart) | `uploadPdf` | the file as sent. GROBID reads the DOI and title, then Crossref/OpenAlex fill in metadata; the file is kept even if GROBID finds nothing |
| Track by DOI (JSON) | `trackByDoi` | an open-access copy from `OpenAccessPdfClient`: every OpenAlex PDF link (`best_oa_location` first), then Semantic Scholar's; the first response that starts with `%PDF-` and is under 25 MB. None: `422`, no paper |

- Every tracked paper has a PDF (`file_key`, a `<uuid>.pdf` in
  `UPLOAD_DIR`). The file never changes; it's deleted only with the paper
  (`DELETE /dev/papers/{id}`). The frontend sees only `file_available`.
- `papers.title` is set once at ingest and goes stale (Crossref later
  prefixes "RETRACTED:"); the newest snapshot has the current one.
- `GET /internal/papers/{id}/pdf` (`PaperService.storedPdf`) serves any
  tracked paper's PDF to a service token, with three different `404`
  details (`No paper`, no stored PDF, missing from disk).
- `GET /internal/papers` (`InternalPaperResponse`: id, owner, DOI, ISSN)
  is Updating's list, kept apart from `PaperResponse` so the frontend's
  shape can change freely.

## The researcher's draft (`research_papers`)

One PDF per project, keyed by (owner, `folder_id`) with `unique nulls not
distinct` (so the "no folder" project is unique too; needs Postgres 15+).
The owner always comes from the JWT; `folder_id` missing or `""` means no
folder; folder ids aren't checked.

- **Upload** (`ResearchPaperService.upload`): size and `%PDF-` checks, save
  the file, then in one transaction a `SELECT … FOR UPDATE` on the
  project's row: insert (`201`) or point it at the new file (`200`, same
  `id`). The old file is deleted after the commit; a failed write deletes
  the new file. Two first uploads racing: the unique constraint rejects
  one, which retries once as a replace.
- **Delete** uses the same locked lookup; the PDF goes after the commit.
- **Internal read** (`pdfForPaper`, `GET /internal/papers/{id}/research-paper`):
  finds the draft through the tracked paper's owner and folder. Its "no
  research paper" `404` has its own detail; if the file vanished
  mid-read (a re-upload), it looks the row up once more.
- No GROBID or Crossref on upload: a draft has no DOI.

## Snapshots (`background_metadata`)

Insert-only, one row per `POST /internal/papers/{id}/background-info`,
full history per paper. Scalar fields are columns; `crossref_updates`,
`authors` and `source_status` are stored as the JSON text sent and never
looked inside, so Updating can add fields without a storage change. The
history read writes every field, nulls included (Updating's parser needs
them all). An unknown paper is `404` `No paper <id>` exactly: Research
Evaluation relies on that text.

## Alerts and notes

- **Store** (`AlertService.store`, internal): idempotent on (`paper_id`,
  `change_key`). New: `201`. Existing: `200` with the stored alert
  unchanged, the researcher's status kept. Not one transaction: on a race
  the unique constraint rejects the second insert and the service re-reads
  in a fresh transaction (a failed insert aborts a Postgres transaction).
- **The one replacement:** a stored `retraction` with no `notice_doi`, and
  a request `retraction` with one: `AlertRepository.replaceNoticelessRetraction`,
  one conditional update (`where notice_doi is null`) that takes the
  request's assessment, `detected_at` and snapshot ids, resets `status` to
  `new`, clears `status_changed_at` and `report_id`, and answers `201`. Id
  and notes stay.
- **List** (`GET /papers/{id}/alerts`): `detected_at` descending, then
  severity, then `id` descending (`AlertService.LIST_ORDER`, an in-memory
  sort). Dismissed ones hidden unless `include_dismissed=true`.
- **Status** (`PATCH /alerts/{id}`): `acknowledged` ↔ `dismissed` freely,
  never back to `new`; repeating a status changes nothing.
- **Notes** (`alert_notes`): an append-only log per alert, 1–2000 UTF-16
  units, newest first, never touching the status. Not the paper notes
  planned in ROADMAP.md.
- `AlertService.requireOwnAlert` is the one ownership check for `PATCH`
  and both note endpoints: another user's alert and a missing one get the
  same `404`, as another user's paper does everywhere.

## Reports and documents

A report groups the alerts one nudge stored for a paper, holds what
investigation fetched (documents) and impact's evaluation. Status:
`investigating` → `investigated` → `assessed`.

- **Opening** (`ReportService.open`): if the paper has alerts with no
  `report_id`, insert a report and run `AlertRepository.assignUnreportedToReport`,
  one bulk update `where report_id is null`; if it took nothing (another
  open won), delete the report and answer `204`. So an alert is never in
  two reports.
- **Documents** (`ReportDocumentService.store`): one per (`report_id`,
  `doi`), DOIs compared exactly. A new `notice` is `skipped`; a new
  `new_version` or `current_version` is saved `pending`, then its PDF is
  downloaded (`OpenAccessPdfClient.downloadWithSource`, which also gives
  the link that worked) and recorded `ok` (with `file_key`, `sha256`,
  `pdf_source_url`, `pdf_fetched_at`) or `not_found`. **Only the request
  that creates the row downloads**; an existing row comes back as stored,
  whatever its status. `store` isn't `@Transactional`, so a slow download
  holds no transaction.
- **The evaluation** (`ReportService.recordEvaluation`): shape checked
  (`none` → the other three null; otherwise all present), then one
  conditional update `where status = investigated` that also sets
  `assessed` and `evaluated_at`. Nothing updated: `409` (not investigated
  yet, or already assessed: the first evaluation is kept). `assessment`
  is stored as text and returned as a JSON object, like `crossref_record`.
- **The user's view** (`GET /papers/{id}/reports`, `ReportService.listForOwner`):
  four queries whatever the count (paper and ownership, reports newest
  first, all their alerts in the alert list's order, all their documents
  oldest first), each report with every alert it grouped, dismissed ones
  included, in the alert API's shape. Documents leave out `file_key`,
  `sha256` and `report_id`. `UserReportResponse` has `evaluation`,
  `recommendation` and `evaluated_at` but **not** `change_summary`,
  `change_severity`, `impact_level` or `assessment` (ROADMAP.md).
- Documents aren't linked to alerts: a `notice` or `new_version` belongs
  to the report's alert whose `notice_doi` is its `doi`; the
  `current_version` is for the whole report. A report can end up with no
  alerts (its retraction moved to a later report), and a document can
  match no alert (two retraction notices in one window).

## Demo tools (`/dev/papers/{id}/...`)

Always on (take them out, or put them behind a setting, before a real
deployment). User token, own papers only. `DevService`:

- `undoChange`: stores a copy of the latest snapshot with one change type
  removed (and `is_retracted` false for a retraction), so the next poll
  finds it again. Needs one real poll first.
- `clearHistory`: deletes the paper's reports (documents cascade), alerts
  (notes cascade) and snapshots, then the documents' PDFs, so a
  rehearsal raises the same change again.
- `deletePaper`: the above plus the paper row and its PDF. Updating drops
  it from `tracked_papers` on its next poll. The frontend uses this to
  delete a paper.

Swagger UI is at `/swagger-ui.html` (also always on).

## Gotchas

- **Never edit or delete a migration on `main`**; add a higher number.
  Gaps are fine (there's no V2). Pick a number no other open branch uses:
  two branches each adding the same number break `main` once both merge
  (it happened: alert notes moved from V4 to V5). A local database that
  ran an old migration needs the reset in SETUP.md.
- **`Alert.reportId` is read-only in JPA** (`insertable = false,
  updatable = false`). Only bulk updates may write it; mapped normally,
  saving an alert loaded before the grouping would write `report_id` back
  to null.
- **Enum parameters in JPQL updates go through the lowercase converters**;
  binding a raw enum name matches nothing.
- **Timestamps are truncated to microseconds when set**, so a response
  matches later reads from Postgres.
- **Errors are problem details even when the request asks for
  `Accept: application/pdf`**, so the `detail` is always readable. Callers
  tell "gone" from "no file" by the exact `detail`.
- **PDFs live only on the machine that stored them.** Reset the database
  and the upload folder together, or rows point at missing files ("missing
  from disk" `404`s). A stray file only costs disk space.
- **Don't call `ResearchPaperService.upload` inside another transaction**:
  its race retry needs a fresh one.
- **`filename` is display text** as the client sent it; never build a path
  from it.
- **Text from publishers** (`text`, `crossref_record`) must be shown as
  text, never HTML.
- **Race safety relies on Postgres** re-checking the `where` after the row
  lock (READ COMMITTED). Tests run on H2 with scripted repositories for
  the races; nothing runs on real Postgres.
- **Tests mock the network:** GROBID, Crossref and the open-access
  download are mocked (`@MockitoBean`); PDF bytes are a few bytes starting
  with `%PDF-`.
- Known edges: `{"text": 123}` is accepted as `"123"`; a `\u0000` in a
  text field gives `500` from Postgres; an alert deleted between the
  ownership check and a note insert gives `500` (FK) instead of `404`.
