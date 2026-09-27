# A paper's reports, for the frontend

**Status: built** on `feat/storage-reports-user-endpoint`: the frontend can
read a paper's reports with `GET /papers/{id}/reports`. Nothing in the
frontend calls it yet (see "TODO for other owners").

A report groups the alerts one nudge stored for a paper and holds what
Research Evaluation's investigation fetched about them (its documents)
and, once impact exists, its evaluation of those alerts together. How
reports are made is in
[EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md); this doc is
only about showing them to the researcher. The contract, with every field,
is in CONTRACTS.md (`GET /papers/{id}/reports`), and the reasoning is in
DECISIONS.md ("2026-09-28 — The frontend reads a paper's reports").

## Where the code lives

All in `storage/src/main/java/com/g4t1/storage/`:

| What | Where |
|---|---|
| `GET /papers/{id}/reports` | `report/ReportController.java` (next to `InternalReportController`, like `AlertController` and `InternalAlertController`) |
| Ownership check, loading and assembling | `ReportService.listForOwner` in `report/ReportService.java` |
| The documents, as the researcher sees them | `ReportDocumentService.forOwnerByReport` (turns `crossref_record` back into JSON with the same `toNode` as the internal response) |
| The JSON | `ReportListResponse` (`{"reports": [...]}`), `UserReportResponse`, `UserDocumentResponse`; alerts are the alert API's `AlertResponse` |
| Queries | `ReportRepository.findByPaperIdOrderByCreatedAtDescIdDesc`, `AlertRepository.findByReportIdIn`, `ReportDocumentRepository.findByReportIdInOrderByIdAsc` |
| Tests | `src/test/java/com/g4t1/storage/report/ReportListTest.java` |

The internal responses (`ReportResponse`, `ReportAlertResponse`,
`ReportDocumentResponse`) are Research Evaluation's and unchanged: they
keep `change_key`, `file_key` and `sha256`, which the researcher's view
leaves out.

## How a request runs

User JWT only (`SecurityConfig` gives everything outside `/internal/**` to
user tokens; a service token gets `403`). `ReportController.list` →
`ReportService.listForOwner`:

1. Load the paper. Missing, or owned by someone else: `404` `No paper
   <id>`, the same answer for both, as in `AlertService.listForPaper`.
2. The paper's reports, newest first (`created_at`, then `id`). None: `[]`,
   and the next two queries are skipped (no empty `in ()`).
3. Every alert of those reports in one query, sorted with the alert list's
   own order (`AlertService.LIST_ORDER`: `detected_at` descending, then
   severity, then `id` descending), then grouped by `report_id`. Grouping
   keeps the sorted order inside each report.
4. Every document of those reports in one query, oldest first, grouped by
   `report_id`.
5. Each report with its alerts and documents; a report with none gets
   `[]`.

So it's four queries whatever the number of reports.

## What's shown and what isn't

- **Every report, every status** (`investigating`, `investigated`,
  `assessed`). An `investigating` report can be one still being fetched
  or one a crash left behind; nothing tells the two apart yet.
- **Every alert the report holds, dismissed ones included**, with their
  status, in the alert API's shape (no `change_key`).
- **Documents without `file_key`, `sha256` and `report_id`.**
  `crossref_record` is stored as text and returned as a JSON object;
  `text` is sent in full (at most 60,000 characters each).
- **Alerts not in a report yet aren't in any report**: those stored by a
  nudge whose investigation hasn't opened a report yet (it runs right after
  the nudge's reply), or one a crash left ungrouped. They're still in
  `GET /papers/{id}/alerts`.

## Matching documents to alerts

There's no link between a document and an alert (DECISIONS.md,
2026-09-27): a `notice` or `new_version` belongs to the report's alert
whose `notice_doi` equals its `doi` (both are normalised DOIs, so compare
them as they are), and the `current_version` is about the whole report.
Two cases match nothing:

- two retraction notices in one nudge's window: both are fetched, but the
  `retraction` alert carries one notice DOI;
- a report whose alerts moved on (below).

## Gotchas

- **A report can have no alerts.** When a retraction notice arrives after
  a retraction alert stored without one, Storage Management replaces the
  alert and takes it out of its report (`AlertRepository.replaceNoticelessRetraction`),
  so the paper's next report gets it. The earlier report stays, with its
  documents (usually the current copy) and no alerts.
  `ReportListTest.aReportWhoseAlertMovedToALaterReportIsStillListedWithNoAlerts`
  covers it.
- **`Alert.reportId` is read-only in JPA** (EVALUATION-INVESTIGATION.md,
  S1). Grouping alerts by it here is fine; never set it by saving an
  alert.
- **`pending` means no PDF yet**, not "coming soon": it's also what a
  download interrupted by a crash leaves, and nothing retries it.
- **The frontend has no way to download a document's stored PDF.**
  `pdf_source_url` is the public link it came from. If that's not enough,
  a user endpoint would sit next to `GET /internal/documents/{id}/pdf`,
  with the ownership check through the document's report and paper.
- **Show `text` and `crossref_record` values as text, never as HTML.**
  They come from publishers through Crossref and Europe PMC.
- **The tests run on H2** and seed reports with `ReportService.open` and
  documents straight through `ReportDocumentRepository`, so no download or
  Research Evaluation is involved.

## TODO for other owners

- **Frontend:** add the types and a `listReports(paperId)` call to
  `frontend/csd-frontend-vite/src/api.ts` (on `feat/frontend-api-wiring`).
  The alerts inside a report are the same `Alert` type as the alert list.
  Match a notice to its alert by `notice_doi` = `doi`.
- **Impact (Research Evaluation):** when impact writes `evaluation`,
  `recommendation` and `evaluated_at` and sets `assessed`, they show up
  here with no change to this endpoint.
