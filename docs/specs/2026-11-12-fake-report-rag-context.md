# Dev mode's fake report comes with an invented past: earlier report, its documents, feedback and a profile

**Status:** approved

**Base commit:** `4748f16c472c780bf45f58cb4ea8627ab752f2ae`

**Repo:** `d:\SMU\CS203\project\CSD-G4T1`

**Md file to update:** none named

**Commands:**

| What | Command |
|---|---|
| Storage, full | `cd storage && ./mvnw test` |
| Storage, single | `cd storage && ./mvnw test -Dtest=<Class>` |
| Frontend, full | `cd frontend/csd-frontend-vite && npm test` |
| Frontend, single | `cd frontend/csd-frontend-vite && npx vitest run <path>` |
| Typecheck | `cd frontend/csd-frontend-vite && npx tsc -b`; none separate for storage (`./mvnw test` compiles) |
| Lint | `cd frontend/csd-frontend-vite && npm run lint`; none for storage |

**Goal of the task:** `POST /admin/papers/{id}/fake-report` with `status=assessed` (the dev page's "Add fake report") also stores an invented past on the same paper, so the report page's Context tab ("What the assessment read beside this report") has something to show without Gemini or a second poll. The fake report's `context_chunks` names past items of all three kinds (`alert`, `report`, `document`), the researcher's feedback (verdict and note) on the past report, and an invented researcher profile. Everything is invented once and saved as example files beside `report.json`; the application still generates nothing.

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 The invented past example files | none |
| S2 Storage stores the past with the fake report | S1 |
| S3 The Context tab shows a profile item | S2 |
| S4 Dev page message and demo doc | S2 |

Run order: S1, S2, S3, S4.

**External blockers:** none.

- The context-panel frontend this task builds on is committed on `feat/rag-frontend` (`4748f16`). `transient`.
- The repo has no ADRs (`docs/adr/` doesn't exist); past decisions are in `docs/DECISIONS.md`. `transient`.

## Branch plan

Starting branch: `feat/rag-frontend`

| Order | Branch | Based on | Subtasks |
|---|---|---|---|
| 1 | `dev-mode-rag` | `feat/rag-frontend` | S1, S2, S3, S4 |

## Out of scope

| Item | Recorded by |
|---|---|
| Embedding the invented past into `chunks`, or nudging the feedback embedder | S2 doc delta |
| Reading or changing the researcher's real profile in User Management | S2 doc delta |
| Real impact sending a `profile` item; only fake reports carry one | S3 doc delta |
| A customisable past (request body); the past is fixed, like the fake report | S2 doc delta |
| Feedback on another paper of the researcher; the feedback item is always on this paper's past report | S2 doc delta |
| Changing real impact's retrieval or prompts | `transient` |

## Interfaces

### I1. `context_chunks` of an assessed fake report (S2 writes, S3 reads)

Past items first, then the feedback item, then the profile item. Past and feedback items keep their existing shapes (CONTRACTS.md, `GET /papers/{id}/reports`).

```json
"context_chunks": [
  {"source_type": "document", "source_id": "<past new_version id>", "page": 2},
  {"source_type": "alert", "source_id": "<past alert id>", "page": null},
  {"source_type": "document", "source_id": "<past notice id>", "page": null, "start": 0, "end": 400},
  {"source_type": "report", "source_id": "<past report id>", "page": null},
  {"source_type": "document", "source_id": "<past related id>", "page": 1},
  {"source_type": "feedback", "source_id": "<past report id>", "page": null,
   "paper_id": "<paper uuid>", "paper_title": "<papers.title or null>",
   "source_date": "<past feedback_updated_at, ISO-8601>", "verdict": "overstated"},
  {"source_type": "profile", "source_id": "example", "page": null,
   "profile": {"occupation": "PhD student", "occupation_other": null, "research_fields": ["Medicine"],
               "research_interests": ["iron deficiency"], "work_types": ["Clinical"],
               "response_preferences": "..."}}
]
```

- The example holds the items with placeholders (the index of the past alert or document in `past.json`, or the past report); `AdminService` swaps each for the new row's id. The exact placeholder shape is settled in S1 and recorded in CONTRACTS.md.
- Every `page` is a page its PDF really has; every `start`/`end` lies inside the notice's `text`.
- `profile` item: `source_id` is always `"example"`, `page` null, `profile` the full profile body in `GET /auth/me/profile`'s shape, values within the profile rules. It is the only item that carries its content, since there is no row to find it by.

## Subtasks

### S1. The invented past example files

- **Goal:** `storage/src/main/resources/dev/fake-report/` holds an invented past for the example's paper: `past.json` (one earlier report: alerts, documents, evaluation, feedback; an invented profile; the fake report's `context_chunks` with placeholders) and its new PDFs.
- **Blocked by:** none
- **Branch:** `dev-mode-rag`
- **Order on branch:** 1
- **Files:** `storage/src/main/resources/dev/fake-report/past.json`, `past-new-version.pdf`, `past-related.pdf`; `storage/src/main/java/com/g4t1/storage/admin/FakeReportExample.java` (`pastContent()`, the two PDFs added to `SERVED_PDFS`); `storage/src/test/java/com/g4t1/storage/admin/FakeReportExampleTest.java` (new).
- **Seams:**
  - `FakeReportExample.pastContent()`: catches a file that doesn't parse, a placeholder naming nothing, an unknown kind, a profile breaking the profile rules; misses whether the text reads well.
  - `GET /public/fake-report/{file}`: catches the new PDFs being served and `past.json` not; misses their look.
- **Decisions:**
  - Invented once, by Claude during the run, and committed; the application never generates anything. The story is the example paper's (iron supplementation and fatigue, `10.9999/fake.2019.011`) before the current fake report, e.g. an earlier correction and new version, so the current report's expression of concern and retraction read as the next chapter. Source: user ("make invent and add example RAG documents"). Doc delta.
  - The past items cover the three kinds `alert`, `report`, `document`. Source: user ("alert, reports, document"). Doc delta.
  - "Report document notes" is the researcher's feedback on the past report: verdict `overstated` and an invented note of at most 500 characters. Source: user (accepted assumption). Doc delta.
  - One past document of each document kind: `notice` (text only, with a `start`/`end` item), `new_version` (`past-new-version.pdf`), `current_version` (the existing `paper.pdf`), `related` (`past-related.pdf`). Source: user (accepted assumption). Doc delta.
  - The profile is invented and fixed in `past.json`; it is never the researcher's real profile. Source: user ("just make it fake a profile, dn read the real one"). Doc delta.
  - The PDFs are made with a throwaway script (not in the repo), like the existing example; `.gitattributes` there keeps PDFs byte for byte. Doc delta.
- **Acceptance criteria:**
  - [x] `pastContent()` parses, and every placeholder in its `context_chunks` names an existing past alert, document, or the past report (`FakeReportExampleTest`).
  - [x] Every `document` item's `page` is within its PDF's page count, and every `start`/`end` lies inside the notice text (`FakeReportExampleTest`).
  - [x] The example profile has only listed occupation, field and work-type values, at most 5 interests of 1 to 60 characters, and `response_preferences` of at most 500 characters (`FakeReportExampleTest`).
  - [x] `GET /public/fake-report/past-new-version.pdf` and `past-related.pdf` answer `200` with bytes starting `%PDF-`; `GET /public/fake-report/past.json` answers `404` (`FakeReportTest`).
  - [x] `cd storage && ./mvnw test` passes.
- **Doc deltas:** CONTRACTS.md "Example files in `storage/src/main/resources/dev/fake-report/`": `past.json`, its shape and placeholders, the two new PDFs. STORAGE.md "Fake reports": the past example, its story, how it was made.
  - Placeholder shape (CONTRACTS.md): `past.json`'s `context_chunks` holds only the past items, each `{"source": "alert" | "document" | "report", "index": <position in past.json's alerts or documents; absent for report>, "page"?, "start"?, "end"?}`; an unknown `source` fails to parse. The `feedback` and `profile` items aren't in it: they are built from `past.json`'s `feedback` and `profile`, so the verdict and profile are written once. Why: an index is the only stable name for a row that doesn't exist yet.
  - The story (STORAGE.md): a correction notice `10.9999/fake.2019.011c` adding the trial registration number and that Fake Pharma Ltd donated the iron sulphate; the corrected version `10.9999/fake.2019.011v1.1` (`past-new-version.pdf`, 3 pages, slope still 0.45); the current copy `paper.pdf`; and a related letter to the editor `10.9999/fake.2020.044` (`past-related.pdf`, 2 pages: the letter questioning the Table 3 slope, and the authors' reply standing by it). The past report rates the change `low` and the impact `medium`, which the feedback calls overstated. Why: the letter's slope question is what the current report's expression of concern and retraction later confirm, so it reads as the earlier chapter.
  - The past evaluation has `"claims": []` (STORAGE.md). Why: the frontend's `withMockClaims` gives an assessed fake report with null claims the current example's claims, which quote documents the past report doesn't have.
  - The two PDFs use fpdf2's core Helvetica font, not the DejaVu of the existing ones (STORAGE.md, how it was made). Why: no DejaVu font was at hand; core fonts embed nothing.
  - `FakeReportExampleTest` copies the profile lists from `users/src/main/resources/profile/options.json` and reads a PDF's page count from its uncompressed page tree's `/Count`. Why: storage has no PDF library and its tests don't read the users module. No doc needed beyond the test's comment.

### S2. Storage stores the past with the fake report

- **Goal:** `POST /admin/papers/{id}/fake-report` with `status=assessed` stores the past report (alerts, documents, evaluation, feedback) and then the fake report, whose `context_chunks` is I1 with the past rows' new ids.
- **Blocked by:** S1
- **Branch:** `dev-mode-rag`
- **Order on branch:** 2
- **Files:** `admin/AdminService.java`; `report/ReportService.java` (`storeFake` extended, or a new `storeFakePast`); `report/Report.java` and `report/ReportDocumentService.java` if back-dated timestamps need setters; `storage/src/test/java/com/g4t1/storage/admin/FakeReportTest.java`.
- **Seams:**
  - `POST /admin/papers/{id}/fake-report` then `GET /papers/{id}/reports` (researcher token): catches both reports, their order, the past's dates and feedback, `context_chunks` ids resolving to rows of the same paper, the profile item; misses the frontend.
- **Decisions:**
  - Only `assessed` stores the past; `investigating` and `investigated` store the one report as today. Each call stores a fresh past report. Source: user (accepted assumption). Doc delta.
  - The past's timestamps (alerts' `detected_at`, the report's `created_at`, `investigated_at`, `evaluated_at`, documents' `created_at`, `feedback_updated_at`) are 30 days before the call, truncated to microseconds. Source: user (accepted assumption). Doc delta.
  - No `chunks` rows are written and Research Evaluation is never nudged (`FeedbackChanged` not published), so a later real assessment won't retrieve the invented past. Source: user (accepted assumption). Doc delta.
  - The researcher's real profile is never read or written; the profile item comes from `past.json`. Source: user ("dn read the real one"). Doc delta.
  - The past's assessment has `"fake": true` like the fake report's. Source: `code: dev/fake-report/report.json`. Doc delta.
  - The past report and the fake report are stored in one transaction: both or neither, and each PDF copy is deleted if it doesn't commit, as `storeFake` does. Source: `code: ReportService.storeFake`, STORAGE.md "Fake reports". Doc delta.
  - The past's alerts get `change_key` `fake:<uuid>` and snapshot ids `0`, like the fake report's. Source: `code: AdminService.createFakeReport`.
  - The response stays the fake report alone (`UserReportResponse`). `transient`.
- **Acceptance criteria:**
  - [ ] With `status=assessed`, the paper's report list gains two reports: the fake report first, the past one second, both `assessed` (`FakeReportTest`).
  - [ ] The past report's alerts, documents, `evaluated_at` and `feedback.updated_at` are 30 days before the call (±1 minute), and its feedback is the example's verdict and note (`FakeReportTest`).
  - [ ] Every `alert`, `report` and `document` item of the fake report's `context_chunks` names a row of the past report on the same paper; the `feedback` item names the past report with the paper's id, title and the feedback's time; the last item is the example profile (`FakeReportTest`).
  - [ ] With `status=investigated` and `status=investigating`, only one report is stored and its `context_chunks` is null (`FakeReportTest`).
  - [ ] No `chunks` row exists after the call, and the Research Evaluation client is never called (`FakeReportTest`, `@MockitoBean ResearchEvaluationClient`).
  - [ ] `cd storage && ./mvnw test` passes.
- **Doc deltas:** CONTRACTS.md "`POST /admin/papers/{id}/fake-report?status=`": the past report it also stores with `assessed`, its dates, `context_chunks` (I1). STORAGE.md "Fake reports": the same; no chunks and no nudge; the real profile untouched; the Out of scope items it records (no customisation, feedback only on this paper, no embedding).

### S3. The Context tab shows a profile item

- **Goal:** a `profile` item in `context_chunks` shows in the report page's Context tab as its own group, "From your profile", with a pane listing the item's profile answers.
- **Blocked by:** S2
- **Branch:** `dev-mode-rag`
- **Order on branch:** 3
- **Files:** `frontend/csd-frontend-vite/src/api.ts` (`ProfileChunk` in `ContextChunk`, `profile` in `CONTEXT_SOURCES`), `src/context/resolve.ts`, `src/context/describe.ts`, `src/components/ReportContext.tsx` (`ProfileBody`, icon, group), `src/context/resolve.test.ts`, a `ReportContext` test.
- **Seams:**
  - `resolveContext(items, reports, paperId)`: catches a `profile` item becoming a `profile` entry with the item's profile, and one with no `profile` object being dropped; misses rendering.
  - `ContextList` / `ContextPane` (vitest + Testing Library): catches the "From your profile" group after feedback, the row title, and the pane's fields; misses layout.
- **Decisions:**
  - The pane shows the profile carried by the item, never `GET /auth/me/profile`. Source: user ("just make it fake a profile, dn read the real one"). Doc delta (CONTRACTS.md: the item carries its profile).
  - Real impact never sends a `profile` item; only a fake report has one. Source: user (Q1). Doc delta (CONTRACTS.md, ROADMAP.md if impact should later list the profile it read).
  - Group order: "From this paper's past", "From your feedback", "From your profile". Source: I1 order. Doc delta not needed (follows the item order).
  - Row title `Your profile`; the pane lists occupation (or `occupation_other` for `Other`), research fields, research interests, type of work and how reports are written, leaving out empty answers, all as text. Source: GLOSSARY.md "Profile". `transient`.
- **Acceptance criteria:**
  - [ ] `resolveContext` turns a `profile` item into a `profile` entry carrying its profile; an item with `profile` missing or not an object is dropped (`resolve.test.ts`).
  - [ ] With past, feedback and profile items, the Context list shows three groups in that order; picking `Your profile` shows its non-empty answers and no empty ones (`ReportContext` test).
  - [ ] `npm test`, `npx tsc -b` and `npm run lint` pass in `frontend/csd-frontend-vite`.
- **Doc deltas:** CONTRACTS.md, `GET /papers/{id}/reports` `context_chunks` row: the `profile` item (shape in I1), sent only by dev mode's fake report, shown from its own `profile`.

### S4. Dev page message and demo doc

- **Goal:** the dev page tells the admin that an assessed fake report brings an earlier report with it, and DEMO.md shows how to demo the Context tab.
- **Blocked by:** S2
- **Branch:** `dev-mode-rag`
- **Order on branch:** 4
- **Files:** `frontend/csd-frontend-vite/src/pages/DevMode.tsx`, a DevMode test, `docs/DEMO.md`.
- **Seams:**
  - `DevMode` page (vitest + Testing Library, mocked `addFakeReport`): catches the success message for `assessed`; misses layout.
- **Decisions:**
  - No new control on the dev page. Source: user (accepted assumption). Doc delta (DEMO.md).
- **Acceptance criteria:**
  - [ ] After "Add fake report" with `assessed`, the message reads `Fake report added, with an earlier report, feedback and a profile for its Context tab. The researcher gets the toast within 30 s.` (DevMode test).
  - [ ] `npm test`, `npx tsc -b` and `npm run lint` pass in `frontend/csd-frontend-vite`.
- **Doc deltas:** DEMO.md: a step to open the fake report's Context tab and what each item shows.

## Human checks

- [ ] **S2, S3.** With `DEV_MODE=true`: as admin, add a fake paper and an `assessed` fake report on it. As the researcher, open the report's Context tab. Expected: "From this paper's past" lists the past documents, alert and report, each opening its pane (PDFs at the stated page, the notice text marked); "From your feedback" lists one item, `Overstated`, linking to the past report; "From your profile" shows the invented profile, not the researcher's own.
- [ ] **S1.** Read `past.json` and open the two new PDFs. Expected: the story reads as an earlier chapter of the example paper and matches the current fake report's facts (same title, DOI, numbers).
- [ ] **S4.** The dev page's message after adding an assessed fake report reads well.
