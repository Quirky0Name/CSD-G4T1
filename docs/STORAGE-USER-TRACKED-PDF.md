# Tracked papers' stored PDFs

**Status: built** on `feat/storage-current-paper`: Research Evaluation can
read any tracked paper's stored PDF with `GET /internal/papers/{id}/pdf`.
Nothing in Research Evaluation calls it yet (see "TODO for other owners").

A tracked paper is a row in `papers`: one user tracking one paper, added by
uploading its PDF or by DOI. Storage Management keeps every tracked paper's
PDF so Research Evaluation has the paper itself to read when it evaluates a
change (DECISIONS.md, "2026-09-25 — Storage keeps every tracked paper's
PDF"). This isn't the researcher's own paper (their draft), which is a
separate thing, one per project: see STORAGE-USER-RESEARCH-PAPER.md. The
contract is in CONTRACTS.md (`GET /internal/papers/{id}/pdf`) and the
reasoning in DECISIONS.md ("2026-09-27 — Research Evaluation reads tracked
papers' PDFs").

## How the PDFs get stored (current state)

Both ways of adding a paper, `POST /papers`, store its PDF before the row
is saved:

| Path | Where | What's stored |
|---|---|---|
| Upload (multipart) | `PaperService.uploadPdf` | the uploaded file, as it was sent. GROBID reads the DOI and title from it; the file is kept even when GROBID finds nothing |
| Track by DOI (JSON) | `PaperService.trackByDoi` | an open-access copy, downloaded by `OpenAccessPdfClient` (OpenAlex's PDF links, then Semantic Scholar's). No copy that downloads means `422` and no paper |

Either way the bytes go through `LocalFileStore.save`, which writes
`<uuid>.pdf` into `UPLOAD_DIR` and returns that name, and the name is kept
in `papers.file_key`. Postgres never holds the bytes.

- **Every paper tracked since PDFs are kept has a file.** `file_key` is
  null only on rows written some other way: older rows, or tests that
  save a `Paper` directly.
- **The file never changes and is never deleted.** There's no endpoint to
  replace a tracked paper's PDF or to delete a paper.
- **The frontend only sees `file_available`** (whether `file_key` is set)
  in the paper response. It has no endpoint to download the PDF.
- Tracked PDFs share `UPLOAD_DIR` with the researchers' own papers.

## Where the code lives

All in `storage/src/main/java/com/g4t1/storage/`:

| What | Where |
|---|---|
| `GET /internal/papers/{id}/pdf` | `paper/InternalPaperController.java` |
| Finding and reading the PDF | `PaperService.storedPdf` in `paper/PaperService.java` |
| Storing it on upload / DOI tracking | `PaperService.uploadPdf`, `PaperService.trackByDoi` |
| Files on disk | `file/LocalFileStore.java` (`save`, `read`) |
| The `file_key` column | `paper/Paper.java`, migration `V1__create_papers.sql` |
| Tests | `src/test/java/com/g4t1/storage/paper/InternalPaperPdfTest.java` |

## How `GET /internal/papers/{id}/pdf` runs

Service JWT only (`SecurityConfig` limits `/internal/**` to service
tokens), and any service token can read any user's paper, as on the other
internal endpoints. The paper's owner gets `403` with their user token.

`InternalPaperController.pdf` → `PaperService.storedPdf`:

1. Load the paper. None: `404` `No paper <id>`, the exact text Research
   Evaluation's client (`backend/src/research_evaluation/storage.py`,
   `_raise_for_status`) reads as "the paper is gone".
2. No `file_key`: `404` `Paper <id> has no stored PDF`.
3. Read the file with `LocalFileStore.read`. Not there: `404` `The PDF for
   paper <id> is missing from disk`, logged as a warning.
4. Otherwise `200` with the bytes as `application/pdf`.

The last two `404`s have their own text on purpose, so Research Evaluation
doesn't mistake "no file" for "no paper". Errors are problem details
(`application/problem+json`) even when the request sends
`Accept: application/pdf`, so the `detail` is always there to read. A
tracked paper's file never changes, so unlike the research-paper read
there's no retry.

## What Research Evaluation can read, per paper id

| What | Endpoint | On `main` |
|---|---|---|
| The paper's PDF | `GET /internal/papers/{id}/pdf` | yes |
| The researcher's own paper for the paper's project | `GET /internal/papers/{id}/research-paper` | yes |
| The snapshots (DOI, title, journal, retraction status, authors, …) | `GET /internal/papers/{id}/background-info/history` | **no**, see below |
| Change keys already stored as alerts | `GET /internal/papers/{id}/alerts/change-keys` | yes |

**There's no endpoint for the `papers` row's own details** (title,
journal, owner, folder, …), on purpose: the snapshots carry the same
identity fields and are fresher (`papers.title` is set once at ingest and
goes stale), and Research Evaluation doesn't need the rest. See
DECISIONS.md, 2026-09-27, Rejected.

## Gotchas

- **The snapshot endpoints aren't on `main` yet.** They're built on
  `feat/storage-snapshot-endpoints` (CG-68), but PR #20 merged that branch
  into `feat/storage-snapshot-table` instead of `main`. Until Amir merges
  it, Updating and Research Evaluation only get snapshots from
  `backend/dev/stub_storage.py`. On that branch an unknown paper's `404`
  says `No paper with id <id>`, not `No paper <id>` as CONTRACTS.md
  requires, and `last=N` is ignored.
- **Reset the database and the upload folder together**
  (LOCAL_STORAGE_DB.md). A row whose file was deleted answers `404`
  "missing from disk"; a stray file with no row costs only disk space.
- **The tests run on H2**, and they mock GROBID, CrossRef and the
  open-access download; the PDF bytes are a few made-up bytes starting
  with `%PDF-`.

## TODO for other owners

- **Research Evaluation:** nothing calls `GET /internal/papers/{id}/pdf`
  yet (the evaluation only reads snapshots so far). When a stage needs the
  paper, for example stage 3's LLM investigation or GROBID's COI text, add
  a call next to the others in `backend/src/research_evaluation/storage.py`
  with the service token, and treat a `404` whose `detail` isn't
  `No paper <id>` as "no PDF", not as the paper being gone. The dev stub
  (`backend/dev/stub_storage.py`) doesn't serve this route.
- **Amir:** merge `feat/storage-snapshot-endpoints` into `main`, and match
  the `No paper <id>` `detail` there (see Gotchas).
