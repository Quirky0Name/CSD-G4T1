# Claim highlighting: frontend, on dev mode's fake paper and fake report

**Status:** implemented

**Repo:** `CSD-G4T1`

**Branch:** `feat/highlighting-front-end`

**Story:** "As a researcher, I want the passages most relevant to my research highlighted in the paper, so that the AI claim can be validated."

**Commands:**

| What | Command |
|---|---|
| Frontend typecheck and build | `cd frontend/csd-frontend-vite && npm run build` |
| Frontend lint | `cd frontend/csd-frontend-vite && npm run lint` |
| Frontend unit tests (added in S1) | `cd frontend/csd-frontend-vite && npm test` |
| Storage tests | `cd storage && ./mvnw test` (single: `-Dtest=<Class>`) |
| Run | `docker compose up -d --build` with `DEV_MODE=true` in `.env` |

## Goal

The frontend shows a report's **claims** and highlights each claim's **passages**: the sentence in the researcher's draft and the passage in the cited source. It reads claims in the exact shape the backend will send them (the `Claim` contract from `feat/claim-passages-backend`).

There are no real PDFs to test with, so dev mode provides the whole example. The example folder `storage/src/main/resources/dev/fake-report/` already holds a made-up paper (`paper.pdf`), the researcher's draft that cites it (`draft.pdf`), its new version and current copy, and a report about it (`report.json`). This spec lets the admin:

1. **add a fake paper**: `paper.pdf`, tracked by a chosen researcher (new, D1);
2. **add a fake report** on it: the existing "Add fake report" button.

The researcher then opens the report and sees the example's claims highlighted in the example's draft and paper.

What changes where:

- **Storage, dev mode code only (D1, D2):** the fake paper endpoint, and the public fake-report route also serves `paper.pdf` and `draft.pdf`. Nothing outside the `admin` package and its tests changes. Dev mode off means none of it exists.
- **Frontend (D3, S1–S4):** the dev mode button, the claim types, mock claims, the highlighter and the report page.
- **Not changed:** Research Evaluation, Updating, the report and claims storage, `report.json`. `feat/claim-passages-backend` is **not** merged.

## Dependencies

| Subtask | Blocked by |
|---|---|
| D1 Fake paper endpoint (Storage, dev mode) | none |
| D2 Serve the example paper and draft (Storage, dev mode) | none |
| D3 "Add fake paper" in dev mode (frontend) | D1 |
| S1 Contract types, normalising and mock claims (frontend) | D2 |
| S2 PDF and text highlighter (frontend) | S1 |
| S3 Report page (frontend) | S1, S2 |
| S4 Docs | all |

Run order: D1, D2, D3, S1, S2, S3, S4.

## The contract the frontend reads

The shapes below are copied from `docs/CONTRACTS.md` on `feat/claim-passages-backend` ("`GET /papers/{id}/reports`" and "`Claim`"). The frontend types must match them exactly, so the mock can later be swapped for the real response without touching the page.

```ts
// src/api.ts
export type AssessmentLevel = 'none' | 'low' | 'medium' | 'high'

export type UserReport = {
  id: number
  paper_id: string
  status: ReportStatus
  created_at: string
  investigated_at: string | null
  evaluation: string | null
  recommendation: string | null
  evaluated_at: string | null
  change_summary: string | null
  change_severity: AssessmentLevel | null
  impact_level: AssessmentLevel | null      // null when change_severity is 'none'
  claims: Claim[] | null                    // null until assessed, and on reports with no claims
  alerts: Alert[]
  documents: UserDocument[]
}

export type ClaimKind = 'change' | 'impact' | 'action'

export type Claim = {
  id: string                                // `<kind>-<index in that kind>`, unique in the report
  kind: ClaimKind
  text: string                              // model output: render as text, never HTML
  supported: boolean                        // true exactly when a passage is 'verbatim'
  passages: Passage[]                       // may be empty
}

export type PassageTarget = 'paper' | 'draft' | 'document' | 'alert'
export type PassageVerified = 'verbatim' | 'not_found' | 'no_text'

export type Passage = {
  target: PassageTarget | null              // null: found nowhere and its source named no text
  document_id: number | null                // set only when target is 'document'
  alert_id: number | null                   // set only when target is 'alert'
  quote: string                             // render as text, never HTML
  page: number | null                       // 1-based start page when found in a PDF, else null
  verified: PassageVerified
}
```

What each field promises:

- `verbatim` with a `page`: the quote starts on that page and may run onto the next. It matches after normalisation: NFKC, curly quotes straightened, whitespace collapsed, lower-case, and a word hyphenated across a line break joined. These are the rules of the backend's `quotes.py` on that branch, and the frontend uses the same ones.
- `verbatim` with `page: null`: found in text that isn't a PDF page, such as a notice's `text` or an alert's `description`.
- `not_found` or `no_text`: nothing to highlight. Show the quote and the reason.
- There are no bounding boxes or offsets. The frontend finds the quote on the page itself.

### Normalising the response

On this branch, `GET /papers/{id}/reports` leaves out `change_summary`, `change_severity`, `impact_level` and `claims`. `listReports` passes each report through `toUserReport(raw)`, which sets any of the four that are missing to `null`. Nothing else in the frontend checks for `undefined`. When the backend branch lands, the fields arrive and `toUserReport` changes nothing.

## Dev mode additions (Storage)

Both follow the existing admin endpoints: present only with `DEV_MODE=true` (`@ConditionalOnBooleanProperty("storage.dev-mode")`), `/api/...` from the browser, and `404` with dev mode off.

### D1: `POST /admin/users/{ownerId}/fake-paper`

Admin token only, as the other `/admin/**` routes. No body, no query parameters. Stores a paper owned by `ownerId` in their **default project** (`project_id` null). The admin can't list a researcher's projects (User Management owns them), so the default project is the only one it can name. The paper:

| Field | Value |
|---|---|
| file | the bytes of `paper.pdf`, through `LocalFileStore.save`, as an upload stores it, so deleting the paper deletes the file |
| `title` | `Dose-response of iron supplementation on fatigue` (the PDF's own title) |
| `journal` | `J. Fake Nutrition` |
| `publication_year` | `2019` |
| `doi`, `openalex_id`, `issn` | `null`. The PDF prints `10.9999/fake.2019.011`, a made-up DOI. Left null so Updating skips the paper (its poll skips papers with no DOI) instead of looking it up |

No GROBID or metadata lookup runs. The researcher's draft is not touched. Each call adds another paper. The no-duplicate rule is about DOIs, and this paper has none.

**`201`:** the paper, in the `GET /admin/users/{ownerId}/papers` entry shape (`latest_snapshot` null).

Errors: `400` an id that isn't a UUID; `401`; `403` a user or service token; `404` dev mode off.

The owner is not checked against User Management, as for the other admin calls on a user's papers. The dev mode UI only offers ids from `GET /auth/admin/users`.

### D2: `GET /public/fake-report/{file}` serves two more files

`FakeReportExample.SERVED_PDFS` becomes `new-version.pdf`, `current-version.pdf`, `paper.pdf` and `draft.pdf`. Still no token, still dev mode only, still `404` for any other name. All four files are made up.

This route is how the frontend reads the example PDFs. This branch has no endpoint for researchers to download a stored PDF, and none is added here.

## Mock claims for fake reports (frontend)

### How a fake report is recognised

A report is fake when any of its documents has a `pdf_source_url` containing `/public/fake-report/`. This is the rule `isFake` already uses in `src/pages/DevMode.tsx`; move it to `src/mocks/fakeReport.ts` and import it from both places.

### The overlay

`withMockClaims(report: UserReport): UserReport`, applied in `listReports` after `toUserReport`:

- It applies only to a fake report with `status` `assessed`, and fills only fields that are `null`. A value from the server always wins, so real claims replace the mock once the backend sends them.
- `change_summary`, `change_severity` and `impact_level` are copied from the example's `evaluation` in `report.json`: `"medium"`, `"low"`, and its `change_summary` text, verbatim.
- `claims`: the fixture below, with each passage's document and alert resolved to the ids in **this** report. Fake rows get new ids on every "Add fake report", so the fixture names them by DOI instead:
  - `document_doi` → the `id` of the report's document with that `doi`;
  - `alert_notice_doi` → the `id` of the report's alert with that `notice_doi`.

  After resolution, each passage has exactly the `Passage` fields, with no extra keys. If a DOI matches nothing, the passage gets `target: null`, `document_id: null`, `alert_id: null` and `verified: "not_found"`.

### Where each passage is shown

```ts
// src/highlight/sources.ts
export type PassageSource =
  | { kind: 'pdf'; key: string; label: string; url: string }
  | { kind: 'text'; key: string; label: string; text: string }
  | { kind: 'none'; label: string }

export function sourceFor(report: UserReport, passage: Passage): PassageSource
```

For a fake report:

| `target` | Source | Label |
|---|---|---|
| `draft` | `/api/public/fake-report/draft.pdf` | "Your draft (example)" |
| `paper` | `/api/public/fake-report/paper.pdf`: the same bytes the fake paper stores | "Paper" |
| `document` with `pdf_status` `ok` | the document's `pdf_source_url` (`/api/public/fake-report/new-version.pdf` or `current-version.pdf`) | "New version" or "Current copy", from `kind` |
| `document`, a notice | the document's `text` | the notice's `crossref_record.title`, or its DOI |
| `alert` | the alert's `description` | "Alert: " + the change type label |
| `null`, or an id not in the report | none | "Not found in any source" |

For any other report, `draft` and `paper` give `{kind: 'none', label: 'Not available yet'}`. Text sources work the same as for a fake report. When the researcher PDF endpoints exist, only `sourceFor` changes.

The draft pane always shows the example `draft.pdf`, not the researcher's own draft, because the fake report's quotes come from the example draft. The label says "(example)" so a tester isn't misled.

### The fixture

`src/mocks/fakeReportClaims.ts`. Every quote and page below was checked against the example PDFs and notice texts with the backend's normalisation rules.

| id | text | passages: target · where · quote · page · verified |
|---|---|---|
| `change-0` | The editors issued an expression of concern about how the paper's dose-response analysis was calculated. | document `10.9999/fake.2019.011e` (notice text) · "The editors have received concerns about the calculation of the dose-response analysis in this article and are investigating." · null · verbatim<br>paper · "slope: 0.45 points per 10 mg (95% CI 0.30 to 0.60)" · 3 · verbatim |
| `change-1` | The paper was retracted and replaced by a corrected version. | document `10.9999/fake.2019.011r` (notice text) · "this article is retracted and replaced by a corrected version" · null · verbatim<br>document `10.9999/fake.2019.011v2` (new version PDF) · "This corrected version replaces the retracted article" · 1 · verbatim |
| `change-2` | The dose-response slope was corrected from 0.45 to 0.28 points per 10 mg (95% CI 0.15 to 0.41). | document `10.9999/fake.2019.011r` · "it was reported as 0.45 points per 10 mg and the correct value is 0.28 points per 10 mg" · null · verbatim<br>document `10.9999/fake.2019.011v2` · "slope: 0.28 points per 10 mg (95% CI 0.15 to 0.41)" · 3 · verbatim |
| `change-3` | The copy at the original DOI still shows the old slope with no retraction mark. | document `10.9999/fake.2019.011` (current copy PDF) · "slope: 0.45 points per 10 mg (95% CI 0.30 to 0.60)" · 3 · verbatim |
| `change-4` | The finding that iron reduces fatigue is unchanged. | document `10.9999/fake.2019.011r` · "The finding that iron supplementation reduces fatigue is unchanged." · null · verbatim |
| `change-5` | The publisher flagged the paper with an expression of concern. | alert with `notice_doi` `10.9999/fake.2019.011e` · "The publisher issued an expression of concern about this paper" · null · verbatim |
| `impact-0` | Your Discussion compares your slope of 0.41 with the paper's 0.45 per 10 mg, and treats the closeness as support for your design. | draft · "slope of 0.41 fatigue points per 10 mg of iron is close to that of Park and Singh [7] (0.45 per 10 mg), which supports the validity of our design" · 2 · verbatim<br>paper · "slope: 0.45 points per 10 mg (95% CI 0.30 to 0.60)" · 3 · verbatim |
| `impact-1` | Your draft lists the paper as reference [7]. | draft · "[7] Park, J., Singh, R. (2019). Dose-response of iron supplementation on fatigue. J. Fake Nutrition." · 3 · verbatim |
| `impact-2` | Your own methods and estimate don't depend on the paper. | none (`supported: false`) |
| `impact-3` | Your Abstract repeats the paper's effect size. | draft · "consistent with the 0.45 slope reported previously" · null · not_found (`supported: false`) |
| `action-0` | Replace 0.45 with the corrected 0.28 per 10 mg (95% CI 0.15 to 0.41) and cite the corrected version. | draft · "(0.45 per 10 mg), which supports the validity of our design" · 2 · verbatim<br>document `10.9999/fake.2019.011v2` · "slope: 0.28 points per 10 mg (95% CI 0.15 to 0.41)" · 3 · verbatim |
| `action-1` | Update reference [7] to point to the corrected version. | draft · "[7] Park, J., Singh, R. (2019)" · 3 · verbatim |
| `action-2` | Check the corrected version's supplementary data before resubmitting. | paper · "Supplementary Table S2" · null · no_text (`supported: false`) |

`impact-2`, `impact-3` and `action-2` are there only to cover the no-passage, `not_found` and `no_text` states. Mark them with a comment in the fixture.

## Out of scope

- Merging `feat/claim-passages-backend`, or any change to Research Evaluation, Updating, report storage or `report.json`.
- Researcher endpoints for downloading PDFs (the backend branch adds them).
- Putting the fake paper in a project other than the default one, or setting the researcher's draft.
- Mock claims for real (non-fake) reports, or for fake reports not at `assessed`.
- Highlighting in a notice's Crossref record or links.
- OCR of scanned PDFs.

## Subtasks

### D1: Fake paper endpoint (Storage, dev mode)

- **Files:** `storage/.../admin/AdminController.java`, `AdminService.java`, `FakeReportExample.java` (add a reader for `paper.pdf`'s bytes), a new test `storage/src/test/.../admin/FakePaperTest.java`.
- As in "D1: `POST /admin/users/{ownerId}/fake-paper`" above.
- **Acceptance:**
  - [ ] `./mvnw test -Dtest=FakePaperTest`:
    - `201` with the paper and its fields as in the table.
    - The stored file's bytes equal `paper.pdf`'s.
    - The paper is listed in `GET /admin/users/{ownerId}/papers` and, with the owner's token, in `GET /papers`.
    - Two calls make two papers.
    - `POST /admin/papers/{id}/fake-report` on the new paper gives `201`.
    - `DELETE /papers/{id}` as the owner removes the paper and its file.
    - `403` for a user token; `401` with no token; `404` with dev mode off.
  - [ ] `./mvnw test` passes.

### D2: Serve the example paper and draft (Storage, dev mode)

- **Files:** `FakeReportExample.java` (`SERVED_PDFS`), its test.
- **Acceptance:**
  - [ ] `GET /public/fake-report/paper.pdf` and `draft.pdf` return `200` and `application/pdf` with no token, with the bytes of the files.
  - [ ] `report.json` and any other name are still `404`, and so is every name with dev mode off.
  - [ ] The update to the class comment ("`paper.pdf` and `draft.pdf` are never served") is made.

### D3: "Add fake paper" in dev mode (frontend)

- **Files:** `src/pages/DevMode.tsx`, `src/api.ts`.
- `addFakePaper(ownerId: string): Promise<AdminPaper>`.
- Next to the **Paper** picker, an "Add fake paper" button. It's enabled once a researcher is picked, so it also works for a researcher with no papers. On success:
  - the papers list reloads and the new paper is selected;
  - the message says `Fake paper added. Now add a fake report on it.`
- Follow the dev mode surface brief (`.impeccable/surfaces/frontend-csd-frontend-vite-src-pages-devmode-tsx.md`): use the existing `dev-button` styles.
- **Acceptance:**
  - [ ] For a researcher with no papers: Add fake paper, then Add fake report at `assessed`. The researcher sees the paper in their default project, and the report toast within 30 s.
  - [ ] `npm run build` and `npm run lint` pass.

### S1: Contract types, normalising and mock claims

- **Files:** `src/api.ts`, `src/mocks/fakeReport.ts`, `src/mocks/fakeReportClaims.ts`, `src/highlight/sources.ts`, `src/pages/DevMode.tsx` (import `isFake`), `package.json` (add `vitest` and `npm test`), tests beside the modules.
- Types exactly as in "The contract the frontend reads". `change_summary`, `change_severity` and `impact_level` stop being optional; remove the comment saying they're absent.
- `toUserReport`, `isFake`, `withMockClaims` and `sourceFor` as specified above.
- **Acceptance:**
  - [ ] `npm test`:
    - `toUserReport` on a report without the four fields gives each as `null`, and leaves a report that has them unchanged.
    - `withMockClaims` on an assessed fake report gives the fixture's 13 claims. Every `document_id` and `alert_id` names a document or alert of that report, and no passage has a key outside `Passage`.
    - `supported` is true exactly when a passage is `verbatim`.
    - `withMockClaims` leaves a non-fake report, a fake report at `investigated`, and a fake report whose `claims` the server already sent unchanged.
    - A fixture DOI that isn't in the report gives `target: null`, `verified: "not_found"`.
    - `sourceFor` returns each row of the sources table.
  - [ ] `npm test` (fixture check): every `verbatim` passage with a page is found by S2's `findQuote` on that page, or that page joined with the next, of its PDF. The PDFs are read with `pdfjs-dist` in Node from `storage/src/main/resources/dev/fake-report/` (read only). Every notice quote is found in that notice's `text` in `report.json`, and the `not_found` quote is found nowhere in the draft. This test is what stops the fixture drifting from the PDFs.
  - [ ] `npm run build` and `npm run lint` pass.

### S2: PDF and text highlighter

- **Files:** `src/highlight/normalise.ts`, `src/highlight/PdfPane.tsx`, `src/highlight/TextPane.tsx`, tests; `package.json`.
- Add `pdfjs-dist` at an exact version, with its worker loaded through Vite (`pdfjs-dist/build/pdf.worker.min.mjs?url`).
- **`normalise.ts`** follows the backend's `quotes.py` rules:
  - `norm(text)`: NFKC, `’` → `'`, `“` and `”` → `"`, whitespace collapsed, trimmed, lower-case;
  - `joinHyphens(text)`: a letter, `-`, a line break, then a letter: joined when the next letter is lower-case;
  - `findQuote(quote, text): {start, end} | null`: matches on normalised text and returns offsets into the **original** text, by keeping an index map while normalising.
- **`PdfPane`:**

  ```ts
  <PdfPane
    url={string}
    highlight={{ quote: string; page: number } | null}
    onResult={(r: 'found' | 'not_on_page' | 'unavailable') => void}
  />
  ```

  - Loads once per `url` and keeps it cached for the page's lifetime. Renders pages lazily, each as a canvas plus a text layer.
  - With a highlight: builds the text of `page` and `page + 1` from the text-layer items, recording each item's offsets, then runs `findQuote`.
  - On a match: maps it back to the text-layer spans, draws boxes over exactly the matched characters (a DOM `Range` for partly matched spans), and scrolls the first box into view.
  - With no match: scrolls to `page` and reports `not_on_page`. A failed load (for example `404` with dev mode off) reports `unavailable`.
- **`TextPane`:** `<TextPane text={string} quote={string | null} />`. Renders plain text with the match in a `<mark>`, scrolled into view. Never uses `dangerouslySetInnerHTML`.
- **Acceptance:**
  - [ ] `npm test`:
    - `norm` gives the right output for a ligature (`ﬁ`), curly quotes, runs of whitespace, and a word hyphenated across a line break.
    - `findQuote` returns original-text offsets for a quote that crosses a line break and one that crosses a hyphenated break.
    - A missing quote gives `null`.
  - [ ] In the browser: `impact-0`'s draft quote is boxed on page 2 of `draft.pdf`, across its line break.
  - [ ] A quote that isn't on the given page reports `not_on_page` and draws nothing.
  - [ ] `npm run build` and `npm run lint` pass.

### S3: Report page

- **Files:** `src/pages/ReportView.tsx` (new), `src/App.tsx`, `src/components/ReportList.tsx`, `src/format.ts`.
- **Route:** `/reports/:paperId/:reportId`, under `RequireResearcher` and `AppLayout`.
  - The report and the paper come from `useTracking()`, which already loads every report, so there's no new request.
  - While tracking is loading, show the existing `Spinner`.
  - Once ready, if the report isn't there, show "This report doesn't exist or isn't yours" with a link to `/reports`.
- **Entry:** in `ReportList`, an `assessed` report with `claims` gets an "Open report" link to the route. "View details" stays.
- **Layout.** At `lg` and up, claims on the left and two panes stacked on the right, so each page is wide enough to read (this replaced side-by-side panes, and the sketch below still shows those). Below `lg`, claims first, then tabs for "Your draft" and "Cited source".

  ```text
  ┌──────────────────────────────────────────────────────────────────────────┐
  │ ← Alerts   <paper title>   [Assessed] [medium change] [low impact]       │
  │ change_summary                                                           │
  ├───────────────────────┬────────────────────────┬─────────────────────────┤
  │ What changed          │ Your draft (example)   │ Paper · p. 3            │
  │  ✓ change-0 …         │ · p. 2                 │                         │
  │ How it affects your   │ …Our estimated ▒slope  │ …▒slope: 0.45 points per│
  │ draft                 │ ▒of 0.41 fatigue points│ ▒10 mg (95% CI 0.30 to  │
  │ ● impact-0 …          │ ▒per 10 mg of iron is  │ ▒0.60)▒. Adding a …     │
  │   ▸ Your draft, p. 2  │ ▒close to that of Park▒│                         │
  │   ▸ Paper, p. 3       │                        │                         │
  │ What to do            │                        │                         │
  │  ✓ action-0 …         │                        │                         │
  └───────────────────────┴────────────────────────┴─────────────────────────┘
  ```

- **Claims list:**
  - Grouped by `kind` in the order change, impact, action, under the headings "What changed", "How it affects your draft" and "What to do". Server order within a group.
  - One group shows at a time, under the tabs "Changes", "Impact" and "Recommendation" (this replaced three headed lists shown together, and the sketch below still shows those).
  - Each claim shows its `text`, and "No quote found" when it isn't `supported`. A supported claim shows no label.
  - A pane with more than one passage for the claim has a pager in its header. A passage that isn't `verbatim` says why in the pane: "Not found in the source" or "Source had no readable text".
  - A pane the claim has no passage for isn't shown.
- **Selecting:**
  - Selecting a claim puts its first `verbatim` `draft` passage in the draft pane, and its first `verbatim` passage with any other target in the cited source pane.
  - Paging to a passage puts it in its own pane only.
  - A passage that isn't `verbatim`, or has no source, isn't placed. The pane shows the quote in a muted box with the reason.
  - The selected claim is kept in the URL (`?claim=impact-0`), so a reload or a shared link opens the same claim.
- **Panes:**
  - The draft pane is a `PdfPane` for `draft` sources.
  - The cited source pane is a `PdfPane` or a `TextPane`, depending on the source.
  - A pane whose PDF is `unavailable` says "No copy of this document to show".
  - A pane that reports `not_on_page` says "Couldn't find this passage on page N" and shows the quote.
- **Other states:**
  - Assessed with `claims` `null`: show `change_summary`, `evaluation` and `recommendation` as text, and "There are no highlighted passages for this report."
  - `change_severity` `none`: show `change_summary` and "This change doesn't affect your work", plus only the `change` claims.
  - Not `assessed`: "This report is still being assessed."
- **Safety:** every model or publisher string is rendered as text: `text`, `quote`, `change_summary`, `evaluation`, `recommendation` and a document's `text`.
- **Acceptance.** Setup: the Human checks walkthrough, steps 1–3.
  - [ ] The report has an "Open report" link. The page opens, and reloading with `?claim=impact-0` opens the same claim.
  - [ ] `impact-0` boxes the Discussion sentence on page 2 of the draft and the 0.45 slope on page 3 of the paper, one pane above the other.
  - [ ] `change-1` shows the retraction notice's text with the quote marked. Its second passage opens the new version at page 1 with the quote boxed.
  - [ ] `change-3` opens the current copy at page 3 with the 0.45 slope boxed.
  - [ ] `change-5` shows the expression of concern alert's description with the quote marked.
  - [ ] `impact-2` (no passages), `impact-3` (`not_found`) and `action-2` (`no_text`) each show their state, with no highlight and no error.
  - [ ] A real (non-fake) assessed report has no "Open report" link. Opened by URL, it shows the no-claims state.
  - [ ] Works at phone width with no horizontal page scroll.
  - [ ] `npm run build`, `npm run lint` and `npm test` pass.

### S4: Docs

- `docs/CONTRACTS.md`, "Admin endpoints (dev mode)": `POST /admin/users/{ownerId}/fake-paper`. Also update "`GET /public/fake-report/{file}`" for the four files, and remove "`paper.pdf` and `draft.pdf` … are never served or stored on a paper".
- `docs/STORAGE.md`, "Dev mode": fake papers.
- `PRODUCT.md`, "Capabilities and Constraints": dev mode can also add a fake paper.
- Frontend `README.md`:
  - the report page route and `npm test`;
  - that claims for fake reports come from `src/mocks/fakeReportClaims.ts`, and that the server's `claims` replaces them when sent.
- `docs/DEMO.md`: the walkthrough below.

## Human checks

Use two windows that don't share a session. The session lives in `localStorage`, so use a normal window and a private window, or `localhost:8088` and `127.0.0.1:8088`.

1. **Window A, researcher:** register at `/register` (for example `tester@example.com`). There's no need to add a paper or a draft.
2. **Window B, admin:** sign in at `/signin` with `admin` and `12345679`, which opens `/dev`.
   - Pick the researcher.
   - Click **Add fake paper**; the new paper is selected.
   - Under **Fake report**, choose **assessed** and click **Add fake report**.
3. **Window A:** within 30 s, the "Report ready" toast appears. Go to **Reports** and click **Open report**.
4. **Window A:** go through S3's acceptance list.
5. **Window B:** add a second fake report on the same paper. In window A, its notice and alert passages show that report's own rows.
6. **Window B:** add a fake report at **investigated**. In window A, it has no **Open report** link.
