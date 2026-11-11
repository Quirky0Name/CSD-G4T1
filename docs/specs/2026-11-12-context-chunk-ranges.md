# A report says where a cut retrieved chunk sits in its document

**Status:** approved

**Base commit:** `6ce00b0`

**Repo:** `D:\SMU\CS203\project\chunk-monkey` (a worktree of CSD-G4T1)

**Commands:**

| What | Command |
|---|---|
| Full test suite | `cd backend && uv run pytest` and `cd storage && ./mvnw test` |
| Single test file | `cd backend && uv run pytest <path>` or `cd storage && ./mvnw test -Dtest=<ClassName>` |
| Typecheck | none |
| Lint | none |

Already on `feat/claim-passages-backend`, so not in this spec (`transient`):

- The user PDF endpoints `GET /papers/{id}/pdf`, `GET /documents/{id}/pdf`, `GET /draft/pdf?project_id=` (commit `871f48c`, with `PaperPdfTest`, `DocumentPdfTest`, `DraftPdfTest`).
- `context_chunks` stored on a report and returned by `GET /papers/{id}/reports` (`V14__add_report_context_chunks.sql`). Storage Management keeps it as a raw `JsonNode` and never looks inside, so a new item field needs no Storage Management code.

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 Impact sends `start`/`end` for a cut text chunk | none |

Run order: S1.

External blockers: none.

## Branch plan

Starting branch: `feat/claim-passages-backend`

| Order | Branch | Based on | Subtasks |
|---|---|---|---|
| 1 | `feat/context-chunk-ranges` | `feat/claim-passages-backend` | S1 |

## Out of scope

- **Frontend view of `context_chunks`** (user). Stays a ROADMAP.md item; S1's doc delta rewords it to the display rule in I1.
- **Storing the chunk's text on the report.** Rejected by the user as too expensive; `alert`, `report` and paged `document` items are found from the report list and the PDF. Recorded by S1's doc delta in CONTRACTS.md (the item fields).
- **Marking the part of a split PDF page** (user: ok). A page over 8,000 characters gets no `start`/`end`; the frontend opens the page without marking the part. Impact can't tell the last piece of a split page from a whole page from the search hit (`chunk_index` counts across the whole document), and offsets into pypdf's page text don't match pdf.js's text layer. Recorded by S1's doc delta in CONTRACTS.md.
- **Chunks of reports assessed before this change.** Their items have no `start`/`end`; not backfilled. Recorded by S1's doc delta in CONTRACTS.md.

## Interfaces

### I1. A `context_chunks` item

Sent by impact in `PUT /internal/reports/{id}/evaluation`, stored as sent, returned by `GET /papers/{id}/reports`:

```json
{"source_type": "document", "source_id": "7", "page": null, "start": 2800, "end": 6000}
```

| Field | Type | Meaning |
|---|---|---|
| `source_type` | string | `document`, `alert` or `report` |
| `source_id` | string | the document's, alert's or report's own id (not the report it belongs to) |
| `page` | int or null | the PDF page the chunk came from; null when it isn't from a PDF page |
| `start`, `end` | int, both or neither | only on a `document` item with `page` null whose `text` was cut into more than one chunk: the chunk's character range in the document's `text`, `text.slice(start, end)`. Absent otherwise |

How a frontend shows an item:

- `alert` / `report`: the alert or report with that id in `GET /papers/{id}/reports` (one chunk is the whole alert or report).
- `document` with `page`: `GET /documents/{id}/pdf` at `page`.
- `document` without `page` (a notice or other Europe PMC text): the document with that `id` in any report's `documents`, its `text` shown whole; with `start`/`end`, `text.slice(start, end)` marked, like the highlighting `TextPane`. `text_truncated` true means the stored text was cut at 60,000 characters and only that part was chunked.

Doc delta: CONTRACTS.md, both endpoints (S1).

## Subtasks

### S1. Impact sends `start`/`end` for a cut text chunk

- **Goal:** a `context_chunks` item for a page-less document chunk whose `text` was cut into more than one chunk carries `start`/`end` (I1); every other item is unchanged.
- **Blocked by:** none.
- **Branch:** `feat/context-chunk-ranges`.
- **Order on branch:** 1.
- **Files:** `backend/src/research_evaluation/impact/schemas.py` (`ContextChunk`), `backend/src/research_evaluation/impact/assess.py`, `backend/src/research_evaluation/impact/history.py` if the search hit's `chunk_index` isn't kept yet, `backend/src/research_evaluation/embedding.py` (`chunk_text` size/overlap as named constants, if they aren't), `backend/tests/research_evaluation/impact/test_history.py`, `storage/src/test/java/com/g4t1/storage/report/ReportControllerTest.java`.
- **Seams:**
  - `assess_report(sm, ImpactContext(...), REPORT)` with a mocked Storage Management search: catches `start`/`end` on the evaluation `PUT` for a cut text chunk, their absence otherwise, prompt order; misses storage.
  - `chunk_text(text)` against the computed range: catches that `text[start:end]` equals the chunk's text without its header, for every chunk of a long text; misses impact.
  - `PUT /internal/reports/{id}/evaluation` then `GET /papers/{id}/reports` (MockMvc, H2): catches an item with `start`/`end` returned unchanged; misses impact.
- **Decisions:**
  - "if pdf -> show page. if plain text -> show plaintext similar to what highlighting does now". Source: user.
  - "can add only if it cuts out. so if a notice is too long or one page is too long. then add so i know where to highlight." Source: user. The page half is dropped: a split PDF page gets no range (user: "ok"), see Out of scope.
  - No chunk text is stored on the report ("sounds expensive to store"). Source: user.
  - Cut means the document's `text` is longer than one chunk: the hit's `chunk_index` > 0, or its text after the header is exactly the chunk size (3,200). Source: `code: embedding.chunk_text` (step 2,800 = size 3,200 − overlap 400, `chunk_index` = position in `chunks`, from 0, per CONTRACTS.md `PUT /internal/chunks`).
  - `start = chunk_index × 2,800`, `end = start + length of the chunk's text after its header`. The header is `"<kind> <doi> (report of <date>)\n\n"`, added by `chunks.document_pieces`. Source: `code: chunks.document_pieces`. The range is computed when impact reads the chunk, so later changes to the chunk size don't move stored ranges.
  - A document's `id` is unique across reports, so no `report_id` is sent; the frontend finds the document in any report's `documents`. Source: user (agreed it isn't needed).
- **Acceptance criteria:**
  - [x] A cut page-less document chunk (`chunk_index` 2 of a long text) gets `start` 5600 and the right `end`; a whole short notice, a paged document chunk, an alert and a report chunk have neither key: `test_history.py::test_context_chunks_sent` (updated).
  - [x] For a text longer than 3 chunks, every chunk's computed range gives back exactly that chunk's text without its header: `test_history.py::test_context_chunk_range_matches_text` (new).
  - [x] No history: `context_chunks` is still `[]`: `test_history.py::test_context_chunks_empty_without_history`.
  - [x] An item with `start`/`end` round-trips through Storage Management unchanged: `ReportControllerTest#contextChunksRoundTrip` (updated).
  - [x] `cd backend && uv run pytest` and `cd storage && ./mvnw test` pass.
- **Doc deltas:**
  - CONTRACTS.md, `GET /papers/{id}/reports`, `context_chunks` row: I1's fields and display rule; a split PDF page has no range; items of reports assessed before this change have no `start`/`end`.
  - CONTRACTS.md, `PUT /internal/reports/{id}/evaluation`: the example and the `context_chunks` row gain the optional `start`/`end`.
  - CONTRACTS.md: move the errors paragraph that the `20431ac` merge left after "User PDFs" (`404` ... `No paper <id>`, as for `GET /papers/{id}/alerts`) back to the end of `GET /papers/{id}/reports`, before `#### Claim`.
  - EVALUATION.md, the `context_chunks` bullet: `start`/`end` for a cut text chunk, and how they're computed.
  - ROADMAP.md, "A frontend view of a report's `context_chunks`": I1's display rule.
  - New: `impact.history.context_chunk(chunk)` builds each `context_chunks` item from a search hit, and `assess` calls it; why: the range depends on the hit's `chunk_index` and content, which only the search hit has. Needs no doc beyond EVALUATION.md's `context_chunks` bullet.
  - New: the header is everything up to and including the content's first blank line (`"\n\n"`); why: `document_pieces`' header is one line ending in a blank line, and a kind or DOI has no newline. Recorded in `context_chunk`'s docstring.
  - New: `ContextChunk` leaves `start`/`end` out of its JSON when unset (a wrap `model_serializer`), while `page` stays `null`; why: I1 says "both or neither" and "absent otherwise". Recorded in `ContextChunk`'s docstring.
  - New: `embedding.CHUNK_SIZE` (3,200) and `CHUNK_OVERLAP` (400) are `chunk_text`'s defaults, read by `context_chunk`; why: one source for the step. Needs no doc beyond EVALUATION.md.
  - Spec change: **Repo** is now the `chunk-monkey` worktree (was `D:\SMU\CS203\project\CSD-G4T1`, which has `feat/claim-passages-backend` checked out); why: the run works on `feat/context-chunk-ranges` there. Needs no doc.

## Human checks

- [ ] **S1:** with the root stack up and a paper whose past report has a notice over 3,200 characters, trigger a new report and let impact assess it. Then `curl -H "Authorization: Bearer <token>" http://localhost/api/papers/<id>/reports`. Expected: a `document` item for that notice with `start`/`end`, and `text.slice(start, end)` of that document reads as a passage of the notice.
