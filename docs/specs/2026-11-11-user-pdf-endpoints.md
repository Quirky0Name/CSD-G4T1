# Researcher reads their own PDFs, and the retrieved documents of a report

**Status:** draft

**Base commit:** not set

**Repo:** `D:\SMU\CS203\project\CSD-G4T1`

**Commands:**

| What | Command |
|---|---|
| Full test suite | `cd storage && ./mvnw test` |
| Single test file | `cd storage && ./mvnw test -Dtest=<ClassName>` |
| Typecheck | none |
| Lint | none |

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 `GET /papers/{id}/pdf` | none |
| S2 `GET /documents/{id}/pdf` | none |
| S3 `GET /draft/pdf` | none |
| S4 Retrieved documents of a report | OPEN Q2 |

Run order: S1, S2, S3, S4.

External blockers:

- OPEN Q5: `feat/claim-passages-backend` (not merged into `main`) commit `871f48c` already adds I1, I2 and I3 with owner checks and tests (`PaperPdfTest`, `DocumentPdfTest`, `DraftPdfTest`). S1–S3 duplicate it.
- OPEN Q2 and OPEN Q4 decide what S4 is.
- `docs/specs/2026-11-11-rag-vector-store.md` S7 (`context_chunks` on a report, I5 there) is not executed yet. Its I5 says "A `document` item opens with `GET /documents/{id}/pdf` at `page`": S2 here is that endpoint. `transient`.

## Open questions

1. ASSUMED: "their PDFs" means all three PDFs Storage Management keeps for a user: the tracked paper's (S1), a report document's (S2) and the project's draft (S3). Affects S1, S2, S3.
2. OPEN Q2: what "fetching RAG documents" needs beyond S2. Today's I5 item is `{source_type, source_id, page}` only. A document chunk with no page (Europe PMC `text`, split every 3200 characters) can't be located from that: the frontend can only show the whole `text`. Options:
   - (a) nothing more: S2 plus RAG spec S7 is enough; a page-less document shows its whole `text` from `GET /papers/{id}/reports`.
   - (b) a user endpoint `GET /reports/{id}/context-chunks` that resolves the report's `context_chunks` to the stored chunk text from the `chunks` table (needs `chunk_index` added to I5).
   - (c) add `content` to each I5 item, so the report keeps exactly the text the model read.
   Affects S4, and RAG spec S6/S7.
3. ASSUMED: the token goes in the `Authorization` header only, never a query parameter. The frontend fetches the PDF and shows it from a blob URL; a plain link opened in a new tab can't send the token. Affects S1, S2, S3.
4. OPEN Q4: whether RAG spec S7 (`context_chunks` stored on the report and returned by `GET /papers/{id}/reports`) moves into this spec, or stays there. Affects S4.

## Branch plan

Starting branch: `main`

| Order | Branch | Based on | Subtasks |
|---|---|---|---|
| 1 | `feat/user-pdf-endpoints` | `main` | S1, S2, S3, S4 |

## Out of scope

- **Frontend changes** (user). Nothing in the UI calls these endpoints yet. Recorded by S1's doc delta in ROADMAP.md.
- **Range requests / streaming.** Each endpoint returns the whole file as `byte[]`, like the internal PDF endpoints (25 MB max per file). `transient`.
- **Admin access to a researcher's PDFs.** Admins keep only the dev-mode routes they have. `transient`.

## Interfaces

All three are user-token endpoints under the existing rule in `SecurityConfig` (everything outside `/internal/**`, `/admin/**`, `/public/fake-report/**` is `ROLE_USER`). From the browser they are `/api/...`. Doc delta: CONTRACTS.md, "Frontend ↔ Storage Management" (table row and one section each).

### I1. `GET /papers/{id}/pdf`

`200` `application/pdf`: the caller's tracked paper's stored PDF, the same bytes `GET /internal/papers/{id}/pdf` returns.

Errors, as problem details: `400` an id that isn't a UUID; `401` missing or bad token; `403` a service token; `404`:

| `detail` | When |
|---|---|
| `No paper <id>` | no such paper, **or another user's paper** |
| `Paper <id> has no stored PDF` | the caller's paper has no file |
| `The PDF for paper <id> is missing from disk` | the row points at a missing file |

### I2. `GET /documents/{id}/pdf`

`200` `application/pdf`: a report document's stored PDF, when the document's report is on a paper the caller owns (`report_documents.report_id` → `reports.paper_id` → `papers.owner_id`). Any report of the paper, past ones included.

Errors: as I1, with `404`:

| `detail` | When |
|---|---|
| `No document <id>` | no such document, **or a document on another user's paper** |
| `Document <id> has no stored PDF` | `pdf_status` isn't `ok` (a notice, `pending`, `not_found`) |
| `The PDF for document <id> is missing from disk` | the row points at a missing file |

### I3. `GET /draft/pdf?project_id=`

`200` `application/pdf`: the caller's current draft for the project (left out or `""`: the default project), keyed by owner and `project_id` like `GET /draft`.

Errors: `400` a `project_id` that isn't a UUID; `401`; `403` a service token; `404` `No draft in project <id>` / `No draft in the default project` (also for another user's project with the same id, which is another project), or `The draft in project <id>|the default project is missing from disk`.

## Subtasks

### S1. The researcher reads their tracked paper's PDF

- **Goal:** `GET /papers/{id}/pdf` (I1) returns the caller's paper's PDF, and never another user's.
- **Blocked by:** none.
- **Branch:** `feat/user-pdf-endpoints`.
- **Order on branch:** 1.
- **Files:** `storage/src/main/java/com/g4t1/storage/paper/PaperController.java`, `PaperService.java`, `storage/src/test/java/com/g4t1/storage/paper/PaperPdfTest.java` (new).
- **Seams:**
  - `GET /papers/{id}/pdf` (MockMvc, H2, files in a temp upload dir): catches the bytes, content type, every `404`, the owner check, `401`/`403`; misses the proxy path `/api/...` (human check).
- **Decisions:**
  - Another user's paper gets the same `404` `No paper <id>` as a missing one. Source: `code: PaperService.delete`, `docs: CONTRACTS.md` (`GET /papers/{id}/reports`).
  - The owner check is done before the file is read. Source: user ("make sure there's the relevant checks to make they can only get their pdfs").
  - ASSUMED (Q3): token in `Authorization` header only.
- **Acceptance criteria:**
  - [ ] Owner gets `200`, `application/pdf`, the stored bytes: `PaperPdfTest#ownerGetsPdf`.
  - [ ] Another user's paper: `404` `No paper <id>`, identical body to an unknown id: `PaperPdfTest#otherUsersPaperIsNotFound`.
  - [ ] No stored file, and file missing from disk: their `404` details: `PaperPdfTest#noFile`, `PaperPdfTest#missingFromDisk`.
  - [ ] No token `401`, service token `403`, non-UUID `400`: `PaperPdfTest#auth`.
  - [ ] `./mvnw test` passes.
- **Doc deltas:**
  - CONTRACTS.md: I1, and a "Frontend ↔ Storage Management" table row "View a tracked paper's PDF".
  - ROADMAP.md: the frontend doesn't call the user PDF endpoints yet.

### S2. The researcher reads a report document's PDF

- **Goal:** `GET /documents/{id}/pdf` (I2) returns a document's PDF only when its report's paper is the caller's.
- **Blocked by:** none.
- **Branch:** `feat/user-pdf-endpoints`.
- **Order on branch:** 2.
- **Files:** `storage/src/main/java/com/g4t1/storage/report/` (new `DocumentController`, `ReportDocumentService`), `storage/src/test/java/com/g4t1/storage/report/DocumentPdfTest.java` (new).
- **Seams:**
  - `GET /documents/{id}/pdf` (MockMvc, H2): catches the bytes, the ownership chain document → report → paper → owner, every `404`; misses the proxy path.
- **Decisions:**
  - A document on another user's paper gets the same `404` `No document <id>` as a missing one. Source: `code: PaperService.delete` (same pattern).
  - Documents of every report of the paper are readable, past ones included, since a retrieved past document (RAG spec I5) can be in an earlier report. Source: `docs: docs/specs/2026-11-11-rag-vector-store.md` I5.
  - This is the endpoint RAG spec I5 names for a `document` item. Source: same.
  - ASSUMED (Q3).
- **Acceptance criteria:**
  - [ ] Owner gets `200`, `application/pdf`, the stored bytes, for a document in the paper's latest and in an earlier report: `DocumentPdfTest#ownerGetsPdf`.
  - [ ] Another user's document: `404` `No document <id>`, identical body to an unknown id: `DocumentPdfTest#otherUsersDocumentIsNotFound`.
  - [ ] A notice (`skipped`), a `pending` and a `not_found` document: `404` `Document <id> has no stored PDF`: `DocumentPdfTest#noStoredPdf`.
  - [ ] File missing from disk: its `404`: `DocumentPdfTest#missingFromDisk`.
  - [ ] No token `401`, service token `403`, non-numeric id `400`: `DocumentPdfTest#auth`.
  - [ ] `./mvnw test` passes.
- **Doc deltas:**
  - CONTRACTS.md: I2; in `GET /papers/{id}/reports`, replace "The frontend has no endpoint to download a document's stored PDF." with a pointer to I2 (`id` with `pdf_status` `ok`).

### S3. The researcher reads their draft's PDF

- **Goal:** `GET /draft/pdf?project_id=` (I3) returns the caller's draft PDF for that project.
- **Blocked by:** none.
- **Branch:** `feat/user-pdf-endpoints`.
- **Order on branch:** 3.
- **Files:** `storage/src/main/java/com/g4t1/storage/draft/DraftController.java`, `DraftService.java`, `storage/src/test/java/com/g4t1/storage/draft/DraftPdfTest.java` (new).
- **Seams:**
  - `GET /draft/pdf` (MockMvc, H2): catches bytes, default and named project, another owner's same `project_id`, `404`s; misses the proxy path.
- **Decisions:**
  - Lookup is by caller plus `project_id`, so another user's project with the same id is never found. Source: `docs: CONTRACTS.md` ("Projects").
  - A re-upload deleting the file between reading the row and the file is retried once, as `pdfForPaper` does. Source: `code: DraftService.pdfForPaper`.
  - ASSUMED (Q3).
- **Acceptance criteria:**
  - [ ] Owner gets the draft's bytes for the default project and for a named project: `DraftPdfTest#ownerGetsPdf`.
  - [ ] Another user's draft in a project with the same id: `404` `No draft in project <id>`: `DraftPdfTest#otherUsersDraftIsNotFound`.
  - [ ] No draft, file missing from disk: their `404`s: `DraftPdfTest#notFound`.
  - [ ] No token `401`, service token `403`, bad `project_id` `400`: `DraftPdfTest#auth`.
  - [ ] `./mvnw test` passes.
- **Doc deltas:**
  - CONTRACTS.md: I3, and the "The draft for a project" row gains `GET /draft/pdf`.

### S4. Retrieved documents of a report

OPEN Q2, OPEN Q4. Written once those are settled.

## Human checks

- [ ] **S1–S3:** with the root stack up, sign in as user A, then `curl -H "Authorization: Bearer <A's token>" http://localhost/api/papers/<A's paper>/pdf -o a.pdf` (and the same for `/api/documents/<id>/pdf`, `/api/draft/pdf`). Expected: valid PDFs. With user B's token on A's ids: `404`.
