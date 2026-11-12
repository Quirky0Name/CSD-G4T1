# Storage Management

The team's persistence service: Postgres and every PDF. Java 25 + Spring
Boot, `storage/`. The frontend reads and acts on everything through it;
Updating and Research Evaluation read and write through its `/internal/**`
endpoints. It never calls Research Evaluation. Endpoint shapes and every
error are in CONTRACTS.md; running it is in SETUP.md; the schema diagram
is [diagrams/erd-flyway-V9.html](diagrams/erd-flyway-V9.html) (not
regenerated since: V10 to V15 are only in the migrations; V14 adds
`reports.context_chunks` and V15 `reports.claims`).

## Code layout

`storage/src/main/java/com/g4t1/storage/`, one package per area. Each
follows the same layering: a controller (HTTP only; `Internal…Controller`
for `/internal/**`), request records with Bean Validation (shape checks,
`400` before the service runs), a service (rules that need the database:
ownership, existence, idempotency; throws `ResponseStatusException`), a
Spring Data repository, the entity, and response records.

| Package | What |
|---|---|
| `paper/` | tracked papers: upload, track by DOI, list, delete (`PaperService`), the internal list and PDF read, deleting a paper with everything that refers to it (`PaperDeletion`) |
| `project/` | clearing a project User Management deleted: its papers and its draft (`ProjectService`, `DELETE /internal/projects/{id}`) |
| `user/` | clearing an account User Management deleted: the user's papers and drafts in every project, and the user's profile chunks (`UserService`, `DELETE /internal/users/{id}`) |
| `chunk/` | embedded chunks: store a source's chunks, search a paper's (`InternalChunkController`, `ChunkService`, `ChunkRepository` on `JdbcTemplate`) |
| `snapshot/` | `snapshots`: store a snapshot, read the snapshot history (`after_id`, `last`, `limit`) |
| `alert/` | alerts (internal store, change keys; user list, status); `LowercaseEnumConverter` |
| `report/` | reports and documents: open, read, mark investigated, store the evaluation (`ReportService`), store a document and download its PDF (`ReportDocumentService`), the user's report list |
| `draft/` | the researcher's draft per project (`DraftService`) |
| `dev/` | demo tools (`DevController`, `DevService`) and Swagger config |
| `admin/` | dev mode: the admin's snapshot edit (`SnapshotEditRequest`, `AdminSnapshotService`, `AdminSnapshotController`), reads and clear history (`AdminController`, `AdminService`, `AdminPaperResponse`), fake reports (`FakeReportExample`, `FakeReportPdfController`) |
| `file/` | `LocalFileStore` (`save`, `read`, `delete` in `UPLOAD_DIR`), `Pdfs` (the `%PDF-` check) |
| `metadata/` | `MetadataClient` (Crossref/OpenAlex metadata, DOI normalising), `OpenAccessPdfClient` |
| `grobid/` | `GrobidClient` (DOI and title from an uploaded PDF) |
| `security/` | `JwtAuthFilter`, `SecurityConfig`: `/internal/**` needs a service token, everything else a user token |

Config: `src/main/resources/application.yml` (snake_case JSON, problem
details on, `ddl-auto: validate`, 25 MB PDF limit). Migrations:
`src/main/resources/db/migration/`, and V13 (chunks) per database in
`db/vendor/postgresql` and `db/vendor/h2` (`spring.flyway.locations`). Tests: `src/test/java/.../<package>/`.

## Papers and their PDFs

A tracked paper is a `papers` row: one user tracking one paper, in one
project (owner + `project_id`). A user can't track a DOI twice
(`uq_papers_owner_doi`).

| Path (`POST /papers`) | `PaperService` | What's stored |
|---|---|---|
| Upload (multipart) | `uploadPdf` | the file as sent. GROBID reads the DOI and title, then Crossref/OpenAlex fill in metadata; the file is kept even if GROBID finds nothing |
| Track by DOI (JSON) | `trackByDoi` | an open-access copy from `OpenAccessPdfClient`: every OpenAlex PDF link (`best_oa_location` first), then Semantic Scholar's; the first response that starts with `%PDF-` and is under 25 MB. None: `422`, no paper |

- Every tracked paper has a PDF (`file_key`, a `<uuid>.pdf` in
  `UPLOAD_DIR`). The file never changes; it's deleted only with the paper
  (`DELETE /papers/{id}`). The frontend sees only `file_available`.
- `papers.title` is set once at ingest and goes stale (Crossref later
  prefixes "RETRACTED:"); the newest snapshot has the current one.
- `GET /internal/papers/{id}/pdf` (`PaperService.storedPdf`) serves any
  tracked paper's PDF to a service token, with three different `404`
  details (`No paper`, no stored PDF, missing from disk).
- `GET /internal/papers` (`InternalPaperResponse`: id, owner, DOI, ISSN)
  is Updating's list, kept apart from `PaperResponse` so the frontend's
  shape can change freely.

## The researcher's draft (`drafts`)

One PDF per project, keyed by (owner, `project_id`) with `unique nulls not
distinct` (so the default project is unique too; needs Postgres 15+).
The owner always comes from the JWT; `project_id` missing or `""` means the
default project; project ids aren't checked.

- **Upload** (`DraftService.upload`): size and `%PDF-` checks, save
  the file, then in one transaction a `SELECT … FOR UPDATE` on the
  project's row: insert (`201`) or point it at the new file (`200`, same
  `id`). The old file is deleted after the commit; a failed write deletes
  the new file. Two first uploads racing: the unique constraint rejects
  one, which retries once as a replace.
- **Delete** uses the same locked lookup; the PDF goes after the commit.
- **Internal read** (`pdfForPaper`, `GET /internal/papers/{id}/draft`):
  finds the draft through the tracked paper's owner and project. Its "no
  draft" `404` has its own detail; if the file vanished
  mid-read (a re-upload), it looks the row up once more.
- **User read** (`ownedPdf`, `GET /draft/pdf?project_id=`): the owner's
  project's current draft. It reads the row again once when the file is
  gone, as `pdfForPaper` does, since a re-upload can delete the file between
  the two reads. Only the current upload is kept, so a highlight quoting a
  draft replaced after the assessment may not be found in it.
- No GROBID or Crossref on upload: a draft has no DOI.

## Snapshots (`snapshots`)

Insert-only, one row per `POST /internal/papers/{id}/snapshots`, the
full snapshot history per paper. Scalar fields are columns; `crossref_updates`,
`authors` and `source_status` are stored as the JSON text sent and never
looked inside, so Updating can add fields without a storage change (one
exception: the admin edit's `requireSourceOk` reads `source_status`, see
"Dev mode"). The
snapshot history read writes every field, nulls included (Updating's parser needs
them all). An unknown paper is `404` `No paper <id>` exactly: Research
Evaluation relies on that text.

## Alerts

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
  stays.
- **List** (`GET /papers/{id}/alerts`): `detected_at` descending, then
  alert severity, then `id` descending (`AlertService.LIST_ORDER`, an in-memory
  sort). Dismissed ones hidden unless `include_dismissed=true`.
- **Status** (`PATCH /alerts/{id}`): `acknowledged` ↔ `dismissed` freely,
  never back to `new`; repeating a status changes nothing.
- `AlertService.requireOwnAlert` is the ownership check for `PATCH`:
  another user's alert and a missing one get the same `404`, as another
  user's paper does everywhere.
- **No notes:** researchers don't keep notes on alerts or papers. The
  `alert_notes` table V5 made is dropped in V11 (DECISIONS.md, 2026-10-27).

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
  `new_version`, `related` or `current_version` is saved `pending`, then its
  PDF is downloaded (`OpenAccessPdfClient.downloadWithSource`, which also gives
  the link that worked) and recorded `ok` (with `file_key`, `sha256`,
  `pdf_source_url`, `pdf_fetched_at`) or `not_found`. **Only the request
  that creates the row downloads**; an existing row comes back as stored,
  whatever its status. `store` isn't `@Transactional`, so a slow download
  holds no transaction. `DocumentKind.RELATED.hasPdf()` is true, and a
  `related` PDF is stored locally in `LocalFileStore` like every other.
- **`links`** (V10, `report_documents.links` text): the DOIs and URLs a
  document points at, stored as text and returned as JSON exactly as sent,
  like `crossref_record` (any JSON value, not checked to be an array; a JSON
  `null` is stored as SQL null). Null for rows stored before V10.
- **The evaluation** (`ReportService.recordEvaluation`): shape checked
  (`none` → the other three null; otherwise all present), then one
  conditional update `where status = investigated` that also sets
  `assessed` and `evaluated_at`. Nothing updated: `409` (not investigated
  yet, or already assessed: the first evaluation is kept). `assessment`
  is stored as text and returned as a JSON object, like `crossref_record`.
- **`claims`** (V15, `reports.claims` text): impact's claims as JSON, stored
  as sent like `assessment` and returned as a JSON array. A column of its own,
  so Storage Management still never looks inside `assessment`. Any JSON value
  is accepted; left out or `null` stores null. Null on reports assessed before
  V15: not backfilled.
- **PDFs for the researcher** (`GET /papers/{id}/pdf` in `PaperService.ownedPdf`,
  `GET /documents/{id}/pdf` in `DocumentController` and
  `ReportDocumentService.ownedPdf`): owner only. A document's owner is checked
  with one query joining its report and paper
  (`ReportDocumentRepository.findOwned`). Someone else's item, a missing one,
  a notice, a paper or document with no stored PDF and a file gone from disk
  are all `404`s.
- **The user's view** (`GET /papers/{id}/reports`, `ReportService.listForOwner`):
  four queries whatever the count (paper and ownership, reports newest
  first, all their alerts in the alert list's order, all their documents
  oldest first), each report with every alert it grouped, dismissed ones
  included, in the alert API's shape. Documents leave out `file_key`,
  `sha256` and `report_id`. `UserReportResponse` has `evaluation`,
  `recommendation`, `evaluated_at`, `change_summary`, `change_severity`,
  `impact_level`, `claims` and `context_chunks`, but **never** `assessment`.
- **`context_chunks`** (V14, `reports.context_chunks` text): the chunks
  impact added to its prompt (`source_type`, `source_id`, `page` each), stored
  as sent and returned as a JSON array in `GET /papers/{id}/reports`, like
  `claims` and `assessment`; Storage Management never looks inside. Absent or
  `null` in the evaluation `PUT` stores null, and reports assessed before V14
  return null. An empty list is kept as `[]` (only absent or `null` stores
  null), so a report that found no past is told apart from one assessed
  before V14. `GET /internal/reports/{id}` doesn't return it.
- Documents aren't linked to alerts: a `notice` or `new_version` belongs
  to the report's alert whose `notice_doi` is its `doi`; the
  `current_version` is for the whole report. A report can end up with no
  alerts (its retraction moved to a later report), and a document can
  match no alert (two retraction notices in one window).

## Chunks (`chunks`)

A chunk is a piece of an alert's, report's, report document's or profile's
text stored with its embedding (1536 numbers) for similarity search.
Research Evaluation embeds; Storage Management stores and searches
(`chunk/`). It never looks inside `content`, and knows only that a `profile`
chunk is a profile's and whose (`user_id`), never what fields it holds.
Nothing writes profile chunks yet (ROADMAP.md).

- **One table for every source**, filtered with `where` and then ordered by
  cosine distance. Columns: `id`; nullable `alert_id`, `report_id`,
  `document_id` (each a foreign key `on delete cascade`) and `user_id`
  (a uuid, no FK: users live in User Management), exactly one set;
  `paper_id` (FK `on delete cascade`), set for alert, report and document
  chunks and null for profile chunks (a check ties it to `source_type`);
  `source_type` (`alert`, `report`, `document`, `profile`, checked);
  `chunk_index`, `content`, `page`, `embedding vector(1536)`, `embed_model`,
  `created_at`. `page` is null or at least 1 (a check). Every chunk from a
  PDF has a `page`, so what went into a prompt can be shown by opening the
  document's PDF at that page; chunks from Europe PMC text, alerts, reports
  and profiles have none. `embed_model` is at most 64 characters (a `400`
  above that), the column's size.
- **Replace, not append:** `PUT /internal/chunks` deletes the source's
  chunks and inserts the new ones in one transaction; an empty list only
  deletes.
- **`paper_id` is copied from the source row**, never from the request. The
  owner and project are read from `papers` by join, so `chunks` stores
  neither.
- **Constraints and indexes:** unique on (`source_type`, source id,
  `chunk_index`), declared `nulls not distinct` because only one of the four
  source ids is ever set and plain `unique` would let duplicates through;
  an HNSW index with `vector_cosine_ops`; indexes on (`paper_id`,
  `source_type`) and `user_id`, for search's filters and the user delete.
- **The cascade:** deleting an alert, report, document or paper deletes its
  chunks. `DELETE /internal/users/{id}` deletes that user's profile chunks
  in `UserService`, since there is no FK.
- **Search** (`POST /internal/chunks/search`) is per paper and source type,
  ranked by similarity only: no date, order or exclusion filter, no recency.
  Every chunk of the paper is a candidate because a report's chunks are
  written only after impact has assessed it. With `profile`, the candidates
  are the profile chunks of the paper's owner (`papers.owner_id`). Search
  sets `hnsw.iterative_scan = strict_order` for its transaction, because the
  HNSW index with a `where` filter otherwise stops at `ef_search`
  candidates and can return fewer than `limit` matches. `report_id` and
  `source_date` are picked by `source_type` with a `case` over the joined
  source row; a profile chunk has a null `report_id` and its own
  `created_at`. An unknown `source_type` and a user token are `400` and
  `403` on search too, like the store endpoint.
- **`JdbcTemplate`, no JPA entity,** because tests run the real Flyway
  migrations on H2, which has no `vector` type: the pgvector migration is
  in `db/vendor/postgresql` and an H2 stand-in (`embedding real array`) in
  `db/vendor/h2`, and with no entity `ddl-auto: validate` never checks
  `chunks`. `ChunkRepository` binds a vector as `cast(? as vector)` from its
  text form on Postgres and as a Java array on H2, picked from the JDBC
  driver's product name, so one repository serves both.
- **Tests:** store-endpoint and validation tests run on H2
  (`ChunkControllerTest`), which can't rank. Ranking, `score`, scoping and
  the real column run on Postgres in Testcontainers
  (`pgvector/pgvector:pg17`: `ChunkPostgresIT`, `ChunkSearchPostgresIT`),
  skipped when Docker is missing.

## Demo tools (`/dev/papers/{id}/...`)

On unless `DEV_TOOLS=false` (`storage.dev-tools`), which takes them and
Swagger off a server. User token, own papers only. `DevService`:

- `undoChange`: stores a copy of the latest snapshot with one change type
  removed (and `is_retracted` false for a retraction, `cited_by_count` 0
  for a citation jump), so the next poll finds it again. Needs one real
  poll first.
- `clearHistory`: deletes the paper's reports (documents cascade), alerts and
  snapshots, then the documents' PDFs, so a
  rehearsal raises the same change again.
- `deletePaper`: the above plus the paper row and its PDF. Updating drops
  it from `tracked_papers` on its next poll. Kept for the demo; the
  frontend deletes through `DELETE /papers/{id}` (`PaperService.delete`).

The deleting in both is `PaperDeletion`'s, which `DELETE /papers/{id}` and
clearing a deleted project use too, so switching the demo tools off can't
break either.

Swagger UI is at `/swagger-ui.html`, on and off with the demo tools.

## Dev mode (`/admin/**`, `/public/fake-report/**`)

Only with `DEV_MODE=true` (`storage.dev-mode: ${DEV_MODE:false}`), separate
from `DEV_TOOLS`; with it off the endpoints answer `404`. `/admin/**` needs
`ROLE_ADMIN`: an admin token gets `ROLE_ADMIN` only, so researcher
endpoints and `/internal/**` answer it `403`. The admin works on any user's
paper. Why and the admin account: ARCHITECTURE.md, "Auth". Endpoint shapes:
CONTRACTS.md, "Admin endpoints (dev mode)". Code is in `admin/`.

**Editing flags** (`POST /admin/papers/{id}/snapshots`). The admin demo is:
1. the researcher polls the paper (`POST /run-poll?paper_id=`, from the app
   or with their own token; the admin has no poll of their own);
2. the admin changes the flag;
3. the researcher polls again, finds the change and raises its alert.

- The edit is a new snapshot row (snapshots are insert-only): a copy of
  the latest, with the given fields changed. Updating and Research
  Evaluation don't change; the next poll reads a row stored after its last
  poll (`backend/src/updating/compare.py`, `poll.py`).
- Only `is_retracted`, `in_doaj`, `cited_by_count` and removing Crossref
  notices by type can be edited. Account details, paper metadata,
  projects, drafts, alert statuses and report contents are not editable.
- The next poll fetches the real values, so the edit raises a change only
  by setting a value to what it was before a real change (use a really
  retracted paper). A change the paper doesn't really have, such as a
  retraction on a paper that isn't retracted, can't be raised this way.
- `AdminSnapshotService` reuses `DevService.copyOf`, which now also takes
  `in_doaj`. The request binds to raw JSON so its checks are explicit
  (an exception to "request records with Bean Validation"; values aren't
  coerced). `requireSourceOk` reads `source_status`, the one place
  storage looks inside it (an exception to "never looked inside").

**Re-raising a change.** Research Evaluation never raises a change key
twice, so a retraction raised once needs its alert gone first.
`DELETE /admin/papers/{id}/history` reuses `PaperDeletion.clearHistory`.
The poll after a clear is a baseline, which never alerts, so: clear the
history, poll, change the flag, poll.

**Fake papers** (`POST /admin/users/{ownerId}/fake-paper`). Stores the
example's `paper.pdf` as a new paper of the researcher in their default
project, so a fake report has the paper its quotes come from and the
researcher needn't upload anything. No DOI (Updating skips it), no GROBID
or metadata lookup, and the researcher's draft is untouched. If saving the
row fails, the file just saved is deleted. Code: `AdminService.createFakePaper`.

**Fake reports** (`POST /admin/papers/{id}/fake-report`). Copies one saved
example onto the paper without a poll; the application never generates
anything.
- The example is `src/main/resources/dev/fake-report/` (`report.json` and
  four PDFs, all served on `/public/fake-report/{file}`: the two document
  PDFs, `paper.pdf`, which a fake paper stores, and `draft.pdf`, which the
  frontend shows as the draft the example's claims quote; it is never
  stored as anyone's draft). `.gitattributes` there keeps
  the PDFs byte for byte. It is read by `FakeReportExample`.
- It was generated once from eval case 11
  (`backend/evals/cases/11_concern_then_retraction_replacement.json`: paper,
  notices, PDFs and draft all made up) by a subagent that ran impact's
  three prompts through three subagents, not Gemini, with `claude-opus-5-5`.
  `assessment` is `{"fake": true, ...}` and names that model in `model` and
  `answered_by`. The writer's helper script was a throwaway, not in the repo.
  Text names the example's paper whichever paper it goes on, and can't be
  customised (no body).
- Alerts get `change_key` `fake:<uuid>`, and snapshot ids `0`; timestamps
  are the time of the call; `status` is `investigating`, `investigated` or
  `assessed` (default) and the report stays in it. Only `assessed` fires the
  "Report ready" toast (the alert toasts in `tracking.tsx` show for a fake
  report's new alerts whatever its status).
- `ReportDocumentService` stores the documents, `FakeDocument` carries each
  with its PDF. Each call stores its own copy of each PDF through
  `LocalFileStore.save`; a transaction synchronization deletes those copies
  if the call's transaction doesn't commit. `POST /internal/documents` is
  not used, because it downloads PDFs. `crossref_record` is `{}` on every
  document.
- The report takes only its own alerts, by id, not
  `assignUnreportedToReport`, so a real alert waiting for its report stays
  out. The draft is only a generation input; a fake report never touches
  the project's draft.
- `GET /public/fake-report/{file}` takes no token and serves only the two
  named PDFs (`FakeReportPdfController`); the report UI links a PDF only
  through `pdf_source_url`, so the frontend needs no change.

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
  matches later reads from Postgres. A chunk's `created_at` too.
- **The chunks migration exists in both vendor folders** (V13 in
  `db/vendor/postgresql` and `db/vendor/h2`); both copies change together.
  Never edit it once on `main`, like any migration.
- **The Postgres test classes share one container**, started once in
  `PgvectorContainer`'s static block: Spring caches the application context
  across classes, so a per-class container would leave it pointing at a
  stopped database.
- **Errors are problem details even when the request asks for
  `Accept: application/pdf`**, so the `detail` is always readable. Callers
  tell "gone" from "no file" by the exact `detail`.
- **PDFs live only on the machine that stored them.** Reset the database
  and the upload folder together, or rows point at missing files ("missing
  from disk" `404`s). A stray file only costs disk space.
- **Don't call `DraftService.upload` inside another transaction**:
  its race retry needs a fresh one.
- **`filename` is display text** as the client sent it; never build a path
  from it.
- **Text from publishers** (`text`, `crossref_record`) must be shown as
  text, never HTML.
- **Race safety relies on Postgres** re-checking the `where` after the row
  lock (READ COMMITTED). Tests run on H2 with scripted repositories for
  the races; only the chunk tests run on real Postgres (Testcontainers).
- **Tests mock the network:** GROBID, Crossref and the open-access
  download are mocked (`@MockitoBean`); PDF bytes are a few bytes starting
  with `%PDF-`.
- Known edges: a `\u0000` in a text field gives `500` from Postgres.
