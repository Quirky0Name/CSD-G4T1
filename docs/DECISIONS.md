# Decisions log

Dated log of decisions and why they were made, so settled questions stay
settled and anyone (including the TA) can see the reasoning. Newest first.

---

## 2026-09-28 — Impact runs right after investigation, on the reports it finished

The plan is [EVALUATION-IMPACT.md](EVALUATION-IMPACT.md), S5.

### Team decisions

- **The nudge's background task is investigation, then impact** on the
  ids investigation returns, one report after another. Nothing in it
  changes the nudge's reply, and Updating never waits for it.
- **Impact only assesses an `investigated` report.** It reads the report
  first and skips any other status before fetching a PDF or calling
  Gemini, so a repeated or stale id costs one read.
- **Without `GEMINI_API_KEY` impact doesn't run** (one log line); the
  reports stay `investigated`. The service still starts without the key.
- **A report whose assessment fails isn't retried** (like investigation):
  a Gemini error or timeout, an answer that doesn't fit its schema, or
  Storage Management failing leaves it `investigated`, with nothing
  stored, and the next report is still assessed. Picking such reports up
  again is for a later sprint (or the manual trigger, S6).
- **Each Gemini call has its own timeout,** `IMPACT_LLM_TIMEOUT_SECONDS`
  (default 120), passed to the SDK, which makes one attempt per call (no
  hidden retries).

### Also settled while building it

- Failures are logged with the report id and a cause (the status code from
  Storage Management or Gemini, the exception class), never response
  bodies, prompts or the draft's text.

---

## 2026-09-28 — Impact asks Gemini three questions, reading the PDFs itself

The plan is [EVALUATION-IMPACT.md](EVALUATION-IMPACT.md), S4.

### Team decisions

- **Impact's LLM is Gemini** (`GEMINI_API_KEY`, `GEMINI_MODEL`, default
  `gemini-flash-latest`; the story owner's call, commit `1cb2e09`).
  DeepSeek stays for stance and claims. Gemini reads PDFs natively (several
  per request, each up to 50 MB or 1,000 pages), so the stored paper, a
  new version, the current copy and the researcher's draft go in as the
  files themselves; impact needs no PDF text extraction, which doesn't
  exist yet. Every answer is structured output against a pydantic schema,
  with no tools.
- **Three calls, one gate** (the story owner's call):
  1. what changed and how severe it is: a summary and a severity of
     `none` / `low` / `medium` / `high` for anyone relying on the paper;
  2. how it impacts the researcher: how the draft uses the tracked paper
     (each citing sentence, its role, the claim relied on) and whether the
     change affects each use, giving an impact level;
  3. the recommended actions.

  At severity `none` impact stops after the first call and stores only
  the summary and the severity. Each call has one job and is tested alone
  with a fake model; the gate saves two calls for the common "not
  meaningful" cases (a notice about another article, an affiliation fix).
- **The draft is read only after the gate, and goes only into the second
  call.** A change that isn't meaningful never touches the draft, and the
  draft never shares a call with third-party notice text.
- **The impact level follows a fixed table** of change severity against
  how the draft uses the paper (RE-changes-explained.md §6): a methods or
  data dependency escalates even a medium change, and a background mention
  stays `low` even for a retraction. The table is in the prompt.
- **Drafts go to whichever Gemini key is configured, free tier included**
  (the story owner's call: it doesn't matter for this project), knowing
  that Google may use free-tier content to improve its products and that
  people may read it.
- **Fetched text and PDFs stay data.** Documents go in `<document>` tags,
  and a document tag written inside a document's own text (any case or
  spacing) is defused; every PDF is announced by a label; the system
  instruction says never to follow instructions inside them.
  `PROMPT_VERSION` and the answering model's version are stored with each
  result.

### Rejected

- **One call with everything.** It gives up the gate, puts the draft next
  to notice text, and can't be tested step by step.
- **Extracting PDF text first** (GROBID full text or a Python library).
  Not built, and Gemini reads the PDFs itself, layout and figures included.

### Also settled while building it

- The free tier allows about 20 requests per model per day (seen live on
  2026-09-28, as `429 RESOURCE_EXHAUSTED`), and `gemini-flash-latest`
  answered `503` ("high demand") several times in a row. A meaningful
  report takes three calls, so the free tier covers about six a day.
- `gemini-flash-latest` currently resolves to `gemini-3.8-flash`;
  `gemini-2.5-flash` is no longer available to new keys.

---

## 2026-09-28 — A report's impact evaluation is stored once, through report-id endpoints

The plan is [EVALUATION-IMPACT.md](EVALUATION-IMPACT.md), S1 and S2.

### Team decisions

- **Impact reads and writes a report by its id alone**
  (`GET /internal/reports/{reportId}`,
  `PUT /internal/reports/{reportId}/evaluation`). The handoff from
  investigation is a bare report id (2026-09-27), and the nested endpoints
  need the paper id too. The report names its paper (`paper_id`), so
  impact gets everything else from it. Flat like the document endpoints;
  the nested ones stay for investigation.
- **The evaluation is written once, and only on an `investigated`
  report** (the story owner's call). An `investigating` report isn't
  ready (its documents may be missing) and an `assessed` one keeps its
  first evaluation, like a stored document: both are `409`. Re-assessing
  (a new draft, a better prompt) is for a later sprint. It's one
  conditional update on the status, so two writes at once can't both land.
- **"Is the change meaningful?" is the change severity**, `none` / `low`
  / `medium` / `high`, with `none` meaning not meaningful. There's no
  separate yes/no that could disagree with it. When it's `none`, only the
  summary and the severity are stored (the story owner's call): impact
  stops there, and `impact_level`, `evaluation` and `recommendation` must
  be null. Otherwise all three are required. The report is `assessed`
  either way.
- **Two level columns, three texts, and the rest as JSON.** The change
  severity and the impact level (what the frontend will sort and badge
  by), the change summary, the evaluation and the recommendation are
  columns; `assessment` holds impact's full answer (per-alert judgments,
  how the draft uses the paper, the list of actions, the model) as JSON,
  stored as sent like `crossref_record`, so its shape can change without a
  migration. One enum, `AssessmentLevel`, serves both levels.
- **Impact never changes alerts.** The alerts' rule-based severity (by
  change type) stays as it was; the change severity and impact level are
  impact's judgment, on the report. So whether a paper was retracted never
  depends on an LLM.

### Rejected

- **A `change_meaningful` boolean next to the severity.** Two fields for
  one answer, which could disagree.
- **Overwriting an assessed report's evaluation.** Two impact runs could
  race, and nothing needs it until re-assessment exists.
- **Reading reports by paper and report id, with investigation handing
  over both.** It changes the handoff the team settled on the day before.

### Also settled while building it

- The report of a deleted paper is gone with it (cascade), so the flat
  `GET` answers `No report <id>` for it, not `No paper <id>`.
- The migration is `V8__add_report_assessment.sql`. `feat/storage-reports-user-endpoint`
  (the researcher's `GET /papers/{id}/reports`) has its own
  `UserReportResponse` listing fields, so whichever branch merges second
  adds the four new fields to it.

---

## 2026-09-27 — A late retraction notice replaces a notice-less retraction alert

The plan is [EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md), S7.

### Team decisions

- **When a retraction notice arrives after the paper's `retraction` alert
  was stored without one** (OpenAlex's flag came first), Storage
  Management replaces the row with the notice's details and treats it as a
  new alert: `status` back to `new`, out of its report so the next report
  takes it, answered `201` (the story owner's call). So the notice gets
  investigated like any other.
- **Why only retractions:** a retraction's change key is always
  `retraction` (one retraction alert per paper), so a later notice has the
  same key and would otherwise be skipped by the key check forever. Every
  other type is keyed by notice DOI, so a new notice is always a new alert.
  The notice is the evidence the flag lacked, so the researcher should see
  the alert again.
- **Research Evaluation sends a retraction with a notice even when
  `retraction` is stored,** and Storage Management decides (replace, or
  `200` unchanged). Only Storage Management knows whether the stored alert
  has a notice.
- **Within one history the notice wins:** when the flag and its notice are
  in two pairs of one window, the change with the notice is the one sent,
  so the result is the same as when they arrive on separate nudges. This
  changes the S8 rule "keep the earlier pair's change" for retractions
  (EVALUATION-REVIEW-CHANGES.md, S8), and its test now expects the notice
  and the later detection time.

### Rejected

- **Letting an alert belong to two reports** (a link table instead of
  `alerts.report_id`) so the late notice could join a report without the
  alert changing. More schema and more rules, for the one alert type that
  needs it.
- **Replacing a wrong notice too** (R3's self-referencing entry, R5's
  notice about another article): Storage Management can't tell a wrong
  notice from a right one. Left for a later sprint.

### Also settled while building it

- The replacement is one conditional update (`where id = … and notice_doi
  is null`), so of two notices racing, one replaces and the other gets
  `200` with the result. The row keeps its `id`, so its notes stay; the
  documents fetched for it in its earlier report stay in that report.
- A retraction with a notice is re-sent on every nudge while it's in the
  window, and re-assessed by the rules each time; Storage Management
  answers `200`. That's cheap, and the LLM (impact) works on reports, not
  alerts, so the "never re-evaluate a stored change" property still holds
  where it matters.

---

## 2026-09-27 — Investigation runs after the nudge's reply

The plan is [EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md), S6.

### Team decisions

- **Research Evaluation replies to the nudge once detection has run and
  the alerts are stored, without waiting for investigation** (the story
  owner's call). Investigation then runs as a background task, per paper
  evaluated without failure: open a report, fetch and store its documents,
  mark it `investigated`. A PDF download can take over 30 s per link and
  Updating's nudge timeout is 20 s, so waiting would turn slow publishers
  into failed nudges. Nothing investigation does changes the reply.
- **The same per-paper loop, in two halves.** Evaluation keeps each
  paper's detected changes (all of them, before the key check) and its DOI
  for the second half, which matches the report's alerts to them by
  change key; nothing is re-read.
- **A report left `investigating` isn't resumed, and a failed fetch isn't
  retried** (the story owner's call). A crash or a Storage Management
  failure midway leaves the report as it is; the next nudge opens a new
  report only for new alerts. Retrying is for a later sprint.
- **The handoff to impact is a report id** (the story owner's call).
  Investigation returns the ids of the reports it finished in the run;
  impact (a later plan) takes an id and reads the report, its alerts and
  documents from Storage Management, never investigation's objects. So
  neither package imports the other, and each can be re-run on its own. No
  impact placeholder is added meanwhile.

### Also settled while building it

- Investigation has its own HTTP client for Crossref and Europe PMC, made
  at startup with no base URL and no auth, so the service token can't
  reach an outside host; only `POST /internal/documents` gets the longer
  `INVESTIGATION_PDF_TIMEOUT_SECONDS` (default 120), every other Storage
  Management call keeps the usual 10 s.
- The papers are investigated one after another, and a report's documents
  one at a time, each document's Crossref and text lookups side by side.
- When a nudge answers `503` (some paper failed), the papers that were
  evaluated are still investigated.

---

## 2026-09-27 — Investigation fetches notices itself, deterministically

The plan is [EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md).

### Team decisions

- **Investigation fetches deterministically, before any LLM step,** instead
  of an LLM calling fetch tools (the earlier idea in
  EVALUATION-REVIEW-CHANGES.md, "Later stories"). For each new change, fixed
  code decides which DOIs to fetch and fetches them: the notice's own
  Crossref record now, its open-access text next. Why: cost, time and
  tests are predictable, and nothing an LLM reads (a notice's text can say
  anything) can steer what gets fetched. Impact (a later plan) only reads
  what investigation stored.
- **Research Evaluation has its own Crossref fetcher**
  (`research_evaluation/investigation/crossref.py`) rather than importing
  Updating's `fetch_crossref`. The request is the same (URL, `mailto`
  polite pool, ok / not found / error, about 15 lines), but the two are
  separate services that happen to share an image, `sources.py` is
  Updating's code, and the fields differ: Updating keeps a paper's
  `updated-by`, investigation keeps a notice's title, date, journal,
  `update-to` and `relation`. Sharing the request through `common/` is a
  later refactor with Updating's owner, like `ServiceTokenAuth`.
- **Research Evaluation reads `CROSSREF_MAILTO` too,** the variable
  Updating already uses; empty sends no `mailto`.
- **Europe PMC is the only text source**
  (`research_evaluation/investigation/europepmc.py`). Crossref has no body
  text for notices; publisher pages and PDFs are bot-blocked (a PMC PDF
  link returns a bot-check page, publishers `403`) and would need parsing;
  Europe PMC's REST API returns the full text of anything open access in
  PubMed Central, with no key. Paywalled notices (Elsevier, The Lancet,
  JAMA) are indexed there but not open access, so they get no text:
  `not_open_access`.
- **Fetched text is data for impact, never instructions.** A notice's text
  is written by whoever wrote the notice; impact must treat it as content
  to judge, and nothing in investigation acts on it.

### Also settled while building it

- A fetch never raises: a 404 is `not_found`, and any other status, a
  transport failure or a body that isn't a readable work (including a
  blank DOI) is `error`. Relation ids that aren't DOIs, aren't text or are
  blank are skipped rather than failing the record. Log lines carry only
  the DOI and a status code or exception class.
- The recorded `…31528-2` ("Retraction and republication") record names
  the Lancet commentary (`…31174-0`) as `retraction` and `erratum`, and
  the Lancet paper (`…31180-6`) as `erratum` too; the plan first said it
  didn't name the Lancet paper.
- Europe PMC: the search result is matched by DOI (normalised), since a
  search can return other records; open access means a PMCID and
  `isOpenAccess: Y`; a full-text `404` is `not_open_access`, any other
  failure `error`. The text keeps the title, abstract, body and figure and
  table captions, one block per paragraph, without the reference list,
  capped at 60,000 characters (`truncated` set when cut; the story
  owner's call). Table cells are left out.
- **`update_to_includes_paper` doesn't catch a notice about another
  article (R5), contrary to the plan.** The retracted Lancet commentary
  (`…31174-0`), which Elsevier links onto the Lancet paper as a
  "retraction", lists the Lancet paper in its own Crossref `update-to`, so
  the flag is true for it. It's kept as a fact; the record's title
  ("RETRACTED: <another article>") is what shows it isn't the paper's
  notice, and judging that is impact's job.
- A report plans each DOI once, and fetches the paper's current copy once
  per report when any of its changes can alter the paper (a retraction,
  correction, erratum or `other` other than a new version) or a notice's
  DOI is the paper's own. A Crossref record and a text lookup run side by
  side per document; one failing never stops the other.

---

## 2026-09-27 — Storage Management downloads a document's PDF when it's stored

### Team decisions

- **Storage Management downloads the PDF, when investigation stores the
  document** (the story owner's call). `POST /internal/documents` saves a
  new version's or the current copy's row, then downloads its open-access
  PDF with the existing `OpenAccessPdfClient` and records `ok` or
  `not_found`. The download code stays in Java instead of being copied to
  Python, and PDFs stay where every other PDF is stored.
- **Only when it creates the row** (the story owner's call). A row that's
  already stored is returned as it is and never downloaded again, whatever
  its `pdf_status`. So a failed download isn't retried, and a row left
  `pending` by a crash stays `pending`; retrying is for a later sprint.
- **`GET /internal/documents/{id}/pdf` only reads.** Impact reads a stored
  PDF with it; it never triggers a download.
- **No "is it new" verdict.** A current copy is always stored, with its
  `sha256` and the link it came from (`pdf_source_url`) as facts. Comparing
  hashes with the paper's stored PDF can't tell a new version: the two
  rarely come from the same place (an upload is the user's publisher copy,
  DOI tracking keeps whichever open-access link answered first), many
  publishers stamp the download date into the PDF, and repository copies
  never get in-place corrections. An equal hash means the same file; a
  different one means nothing on its own. A later sprint will judge
  similarity with an LLM (EVALUATION-INVESTIGATION.md, "Later sprints").

### Rejected

- **Research Evaluation downloading in Python and uploading the bytes.**
  It copies the OpenAlex and Semantic Scholar link lookup and the PDF
  checks, and still needs an endpoint to store the file.
- **One endpoint that downloads on first read** (`GET .../pdf` downloading
  when nothing is stored). Considered first; storing and downloading in
  one `POST` means investigation needs no second call, and reads stay
  free of side effects.

### Also settled while building it

- `OpenAccessPdfClient.downloadWithSource(doi)` returns the bytes and the
  link that worked; `download(doi)` delegates to it, so `PaperService` is
  unchanged.
- The row is saved as `pending` before the download, outside any
  transaction, so a slow download holds none open. If saving the updated
  row fails after the file is written, the file is deleted.
- In a race for one DOI, only the request whose insert wins downloads; the
  other gets the winner's row as stored, which can still be `pending`.

---

## 2026-09-27 — Reports group a nudge's new alerts and hold what investigation fetched

The plan is [EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md);
this entry covers what S1 built in Storage Management.

### Team decisions

- **A report per paper per nudge that stored new alerts** (the story
  owner's call). It groups every alert of the paper not in a report yet,
  in practice the alerts that nudge created, and an alert belongs to one
  report. Storage Management does the grouping when Research Evaluation
  opens a report (`POST /internal/papers/{id}/reports`), in one
  transaction, since only it knows which alerts are ungrouped.
- **The report is where evaluation happens, not the alert.** Impact (a
  later plan) judges a report's alerts together, because many real cases
  only make sense together (RE-changes-explained.md: a correction, then an
  EoC, then a retraction; a "correction" that lifts an EoC; a wrong
  retraction notice next to the real one). A conclusion about how alerts
  relate needs a home that a field on one alert can't give. So `reports`
  reserves `evaluation`, `recommendation` and `evaluated_at` now, before
  impact exists, and its status runs `investigating` → `investigated` →
  `assessed`.
- **Documents belong to the report, one per DOI** (`report_documents`,
  unique on `report_id`, `doi`). The paper's current copy is fetched once
  per report and shared by its alerts, rather than once per alert.
  Research Evaluation keeps no database, so this is where investigation's
  results live.
- **No link table between documents and alerts** (the story owner's
  call). A report reaches its alerts (`alerts.report_id`) and its
  documents (`report_documents.report_id`) directly; a notice or new
  version matches the alert whose `notice_doi` is its DOI, and the current
  copy is for the whole report. Impact reads a whole report at once, so a
  per-alert link would add a table for little.
- **Researcher status stays on alerts** and never decides what gets
  investigated: it's the researcher's to-do state, not the pipeline's
  progress.
- **A stored document is never overwritten.** Sending a DOI the report
  already has returns the stored row unchanged, and Research Evaluation
  uses the returned row (the story owner's call). Whether a difference
  matters is left for a later sprint.
- **Owner and project come from the paper**, as for alerts: no copied
  `owner_id` or `folder_id` on reports.

### Rejected

- **One report per paper, rewritten each time.** Loses the history of how
  the judgment changed, and two nudges racing would overwrite each other.
- **Documents per alert.** The current copy would be downloaded once per
  alert, identical each time.
- **Keying documents by DOI alone, shared across reports and users.** The
  current copy is the paper at a given time, so each report needs its own;
  sharing across users is a later-sprint optimisation (the plan's "Later
  sprints").

### Also settled while building it

- The migration is `V7__create_reports.sql` (V6 is `research_papers`).
  `alerts.report_id` is `on delete set null`; reports and their documents
  go with the paper by cascade.
- Two opens racing: the grouping is one conditional update (`report_id is
  null`), so on Postgres the second waits on the first's row locks and
  then takes nothing; a report that took nothing is deleted and the answer
  is `204`.
- `Alert.reportId` is mapped read-only (`insertable = false, updatable =
  false`). Hibernate writes every column when it saves an alert, so a
  status change on an alert loaded before the grouping would otherwise
  write `report_id` back to null and drop the alert out of its report.
- `LowercaseEnumConverter` is now public, so the report enums store their
  lowercase values the same way as the alert enums.
- Storage Management compares document DOIs exactly; Research Evaluation
  sends them normalised.

---

## 2026-09-27 — Alert notes migration renumbered to V5

### Decision

- **`V4__create_alert_notes.sql` is renamed to
  `V5__create_alert_notes.sql`**, contents unchanged (commit `9c1e474`,
  PR #23). `V4__create_background_metadata.sql` keeps V4.
- **The next numbers are taken:** V6 by `feat/storage-user-research-paper`
  (`V6__create_research_papers.sql`), and V7 by the investigation plan's
  reports migration (EVALUATION-INVESTIGATION.md, S1).

### Why

- `background_metadata` (PR #19) and `alert_notes` (PR #21) were each
  written as V4 on their own branches, and both reached `main`. Flyway
  refuses to start with two migrations of one version ("Found more than one
  migration with version 4"), so Storage Management didn't start on `main`
  and every Java test failed, from the context-load test on.
- **It breaks the migration rule** (never edit or delete a migration once
  it's on `main`, LOCAL_STORAGE_DB.md). No new migration can fix two files
  with one version: one of them has to change.
- **Alert notes moves, not `background_metadata`:** `background_metadata`
  was merged first, so more local databases have it as V4, and Amir's
  `feat/storage-snapshot-endpoints` builds on it.

### Consequences

- A local database that already ran alert notes as V4 won't start (Flyway's
  validation fails on the applied V4), and needs the one-time reset in
  LOCAL_STORAGE_DB.md.
- A branch adding a migration now checks the numbers used on other open
  branches, not only on `main`.

---

## 2026-09-27 — No separate LLM step for `other` changes

### Decision

- **`llm.py` and its `investigate()` placeholder are removed.** It ran only
  for `other` changes and returned the stage-2 assessment unchanged.
  `evaluate_paper` now runs detection, the key check, the rules and
  storing, nothing else.
- **The LLM evaluation stays, as one layer over every change.** It judges
  whether a change matters and how it affects the researcher, revising the
  stage-2 assessment. An `other` change goes through it like any other
  change, instead of an extra layer that first works out what the `other`
  change is and then evaluates it again.
- **Stored alerts don't change.** An `other` change is stored as before:
  `change_type` `other`, severity `medium`, the generic "Crossref recorded
  a '…' notice" text.

### Why

- The placeholder did nothing, and its hook (`other` changes only) is the
  wrong shape for an LLM step that covers every change.
- "Investigation" is kept for fetching what a change is (the notice, the
  new version of the paper), which isn't an LLM step.
## 2026-09-27 — Research Evaluation reads tracked papers' PDFs

### Team decisions

- **`GET /internal/papers/{id}/pdf` is built**, as CONTRACTS.md has
  described it since "2026-09-25 — Storage keeps every tracked paper's
  PDF". Storage Management already kept every tracked paper's PDF, but
  nothing served it, so Research Evaluation had no way to read the paper
  itself.
- **Its three `404`s have different `detail`s**: `No paper <id>`, `Paper
  <id> has no stored PDF`, and `The PDF for paper <id> is missing from
  disk`. Research Evaluation skips a paper only on `No paper <id>`, so
  "no file" must not look like "no paper".
- **No single-paper details endpoint for Research Evaluation**
  (`GET /internal/papers/{id}`) for now. The snapshots it already reads
  carry the DOI, OpenAlex id, title, year, journal, ISSN-L, publisher and
  authors, and they're fresher than `papers`, whose title is set once at
  ingest and goes stale (2026-09-24). What only `papers` has (owner,
  folder, ISSN, when tracking started, whether there's a file) Research
  Evaluation doesn't need: it gets the researcher's own paper by paper id,
  and a `404` from `/pdf` already says there's no file.

### Rejected

- **`GET /internal/papers/{id}` with the paper's stored details.** Another
  interface to keep in step with `papers` for data Research Evaluation
  reads fresher from the snapshots. Revisit if a stage needs the owner,
  the folder or the ingest-time metadata.

### Also found while building it

- **The snapshot endpoints never reached `main`.** `POST
  /internal/papers/{id}/background-info` and `GET .../history` are built
  on `feat/storage-snapshot-endpoints` (CG-68), but PR #20 merged that
  branch into `feat/storage-snapshot-table` after that branch was already
  merged into `main` (PR #19). So on `main`, Updating and Research
  Evaluation only get snapshots from the stub. Amir will merge it. Two
  places it differs from this file: an unknown paper's `404` says `No
  paper with id <id>` instead of `No paper <id>`, and `last=N` is ignored
  (allowed for now, see the history endpoint).

---

## 2026-09-27 — The researcher's own paper, one per project

### Team decisions

- **Storage Management keeps the researcher's own paper** (the draft
  they're writing) for each project, uploaded as a PDF with
  `POST /research-paper`. Research Evaluation needs it to judge what a
  change in a tracked paper means for what the researcher is writing, not
  just that the tracked paper changed. It's stored like a tracked paper's
  PDF: the file on local disk, its key in Postgres.
- **A folder is one project, and `folder_id` null means no folder.**
  Everything a user keeps outside folders counts as one more project of
  theirs, the "no folder" project. This is the rule everywhere
  (`papers`, `research_papers`, every request and response): no folder is
  always `null`, never a sentinel id. On input, a missing `folder_id` or
  `""` both mean no folder, as `POST /papers` already accepted.
- **A project is keyed by owner and `folder_id`.** Folders belong to User
  Management, which doesn't exist yet, so Storage can't check who owns a
  folder id, and any client can send any UUID. With the owner in the key,
  one user's upload can never replace or reach another's.
- **One research paper per project; the newest upload replaces it.** The
  row keeps its id, and the old PDF is deleted from disk. A change should
  be judged against the draft as it is now.
- **Research Evaluation reads it by tracked paper id**
  (`GET /internal/papers/{id}/research-paper`), not by folder. It's nudged
  with paper ids, and Storage Management already knows each paper's owner
  and folder, so one call gets the right draft.
- **A tracked paper is in exactly one project**, its owner plus its
  `folder_id`. A user can track a DOI only once, so it can't be in two of
  their folders. Several users tracking one DOI each have their own paper
  row, snapshots and alerts, and Research Evaluation evaluates each row on
  its own, so it only ever needs that row's project's research paper.
- **Its own table, `research_papers`**, not rows in `papers`: a draft has
  no DOI, snapshots or alerts, and isn't polled.
- **No GROBID or CrossRef on upload.** A draft usually has no DOI, and
  nothing reads its metadata yet.

### Rejected

- **A flag on `papers` for the researcher's own paper.** Updating would
  list it for polling, and every paper query would have to filter it out.
- **A sentinel folder id (e.g. the nil UUID) for no folder.** `papers`
  already uses null, and two spellings of "no folder" would have to be
  kept in step.
- **Keeping every version of the draft.** Nothing needs the older ones.
- **Research Evaluation reading by owner and folder**
  (`?owner_id=&folder_id=`), or adding `folder_id` to `GET /internal/papers`
  for it. Research Evaluation only has paper ids, so it would first have to
  look up each paper's owner and folder, and that list is every tracked
  paper of every user, meant for Updating's poll.
- **Finding a paper's projects by its DOI across users.** Each user's row
  is evaluated on its own, so this would hand Research Evaluation other
  users' drafts while it evaluates one user's alert.
- **A papers ↔ folders join table**, so one tracked paper could sit in
  several of a user's folders. It changes `POST /papers`, its response and
  the one-DOI-per-user rule, and raises whether alerts become per project.
  Revisit once User Management has folders.

### Also settled while building it

- The table is migration `V6__create_research_papers.sql`, with
  `unique nulls not distinct (owner_id, folder_id)`: a plain unique
  constraint would let any number of "no folder" rows through. It needs
  Postgres 15+ (the local `postgres:17` and Supabase are; so is H2 2.4,
  which the tests use).
- Two uploads to one project at once: the row is locked while its file is
  replaced, and if both find no row, the database rejects one insert and
  that upload retries as a replace. The old file is deleted only after the
  row points at the new one; a failed write deletes the new file instead.
- The internal read's "no research paper" `404` has its own `detail`, not
  `No paper <id>`, which Research Evaluation reads as the paper being gone.
  If a re-upload deletes the file between reading the row and reading the
  file, the read looks the row up once more.

---

## 2026-09-26 — Researchers can keep a log of notes on an alert

### Team decisions

- **A researcher can add notes to an alert and read them back**
  (`POST` and `GET /alerts/{id}/notes`), e.g. "Removed the citation from
  my draft". The acknowledge/dismiss story asks that researchers can record
  the actions they took after a change is raised and review them later.
  The status only says *that* they dealt with an alert, not *what* they
  did.
- **The notes are an append-only log.** Each note keeps its own time, and
  none is edited or deleted; a correction is a new note. A single note
  field would be overwritten, and then the earlier notes couldn't be
  reviewed.
- **Notes are separate from the status.** Adding a note never changes
  `status` or `status_changed_at`, and a dismissed alert can still get
  notes. The status rules of 2026-09-25 are unchanged, and the status keeps
  only its latest value.
- **Notes have their own endpoints, not a field on every alert.** The alert
  JSON, `PATCH /alerts/{id}`, the response Research Evaluation gets from
  `POST /internal/papers/{id}/alerts`, and the stub Storage Management all
  stay as they were. The cost is one request per alert whose notes the
  frontend shows, which is small for one paper's alerts.
- **Newest first**, like the alert list.
- **No author column.** Only the owner of the alert's paper can read or add
  notes (the same rule as `PATCH /alerts/{id}`), so the author is always
  the paper's owner, known from `papers.owner_id`, as for alerts
  themselves.
- **A note is 1–2000 characters of non-blank text,** enough for a few
  sentences on what was done. The limit is Bean Validation's `@Size`,
  which counts UTF-16 code units (like JavaScript's `length`), so an emoji
  counts as 2.

### Rejected

- **One note field on the alert, overwritten on each save.** It loses the
  earlier notes, which is the gap this closes.
- **A history of status changes** (every acknowledge and dismiss kept, each
  with an optional note). The story owner's call: only the notes are
  needed, and the status keeps its latest value.

### Consequences

- `alert_notes` is migration `V4__create_alert_notes.sql` (renamed to
  `V5__create_alert_notes.sql` on 2026-09-27, see "Alert notes migration
  renumbered to V5"). Like the alert
  endpoints, it's Storage Management code written for this story, so Amir
  reviews it.

---

## 2026-09-26 — Alerts from one poll are listed most severe first

### Decision

- **Storage Management now keeps the snapshots Updating sends** (CG-68):
  one insert-only `background_metadata` row per `POST
  /internal/papers/{id}/background-info`, read back in order by
  `GET .../history`. Updating stops needing the stub for these two calls.
- **`crossref_updates`, `authors` and `source_status` are stored exactly as
  sent**, as JSON text. Storage never reads inside them, so Updating can
  add a field (as PR #9 did with `institutions`) without a storage change.
  Scalar fields get their own columns. Authors stay inside the snapshot,
  not in a separate `authors_background` table — nothing queries single
  authors yet; split them out if a feature needs to.
- **Migrations are V1 (papers), V3 (alerts, PR #15) and V4 (snapshots).**
  PR #12 deleted `V2__drop_papers_file_key.sql`; it isn't restored, since
  V1 already creates `papers` with `file_key`. Snapshots are V4, not V2,
  so they still apply after V3 whichever PR lands first.
- **A DOI-only paper needs an open-access PDF.** Settles the pending
  question in the 2026-09-25 entry below. When a paper is tracked by DOI,
  Storage Management downloads an open-access copy of it: every `pdf_url`
  OpenAlex lists for the work (its `best_oa_location` first, then the rest
  of `locations`), then Semantic Scholar's `openAccessPdf`. The first link
  that actually returns a PDF under 25 MB is stored like an upload — using
  the same `file_key` column V1 already creates, so this needs no new
  migration.
- If none of those links works, the paper **isn't tracked**: `POST
  /papers` answers `422` with a readable message telling the user to pick
  a different paper. Every tracked paper therefore has a stored PDF. The
  message doesn't suggest uploading instead: a paper with no open-access
  copy is usually paywalled, and most users couldn't legally get the PDF
  without buying it.

### Why

- No shared or deployed database exists yet, only local test ones, so the
  cheapest fix for the deleted V2 is a one-off reset for anyone who ran it
  (LOCAL_STORAGE_DB.md) rather than two extra migrations kept forever.
- Research Evaluation needs the paper itself, so a tracked paper with no
  PDF would be one it can't evaluate. Refusing it up front tells the user
  straight away instead of failing later.

### Rejected

- **Restoring V2 and re-adding `file_key` in another migration.** It keeps
  old local databases working, but adds two migrations just to undo each
  other, for data nobody needs.
- **Typed columns or tables for the JSON snapshot fields.** They'd tie
  storage's schema to Updating's snapshot builder.
- **Tracking a DOI-only paper without a file and asking for an upload
  later.** It needs a new "attach a PDF" endpoint and leaves Research
  Evaluation with papers it can't read until someone remembers to upload.
- **OpenAlex's `best_oa_location` only.** For
  `10.1371/journal.pmed.0020124` it has no `pdf_url` and Semantic Scholar
  doesn't know the DOI, but another OpenAlex location (PLOS) serves the
  PDF.
- **Europe PMC, HAL and publisher pages as extra PDF sources.** Tried for
  the demo papers; all answered `403` or an HTML page to a script.

### Also settled while building it

- From here on, a migration on `main` is never edited or deleted; changes
  go in a new, higher-numbered one. The Supabase database will start empty
  and run V1, V3, V4 in order.
- History rows write every field, nulls included: Updating's history
  parser requires all of them.
- **The demo papers can't be tracked by DOI.** All three are on
  ScienceDirect, which answers `403` or an HTML page to a script, and no
  other listed copy downloads. DEMO.md already has them uploaded by hand,
  so the demo doesn't change.
- A link counts only if the response starts with `%PDF-`; publishers often
  answer a PDF link with `200` and a login or cookie page.
- Semantic Scholar is called keyless unless `S2_API_KEY` is set (sent as
  `x-api-key`). A failed or rate-limited lookup on either source just means
  one less place to look, so it ends in the same `422`, not a `503`.

---

## 2026-09-25 — Storage keeps every tracked paper's PDF

### Team decisions

- **Storage Management keeps the PDF of every tracked paper**, whether it
  was added by upload or by DOI. Research Evaluation needs the paper
  itself to evaluate a change: a snapshot only says that a paper was
  retracted or corrected, and judging what that means for the researcher
  needs the paper's text. GROBID's COI/funding extraction and the claims
  prompt, both in week-7 scope, also run on the PDF. This reverses commit
  `32fb3be` ("stop keeping uploaded PDFs"), which read an upload with
  GROBID and then discarded it. That change was never logged here, and it
  left Research Evaluation nothing to read.
- **Local disk for now.** PDFs go in a folder on Storage Management's
  host, never in Postgres; `papers` holds only the file's key. Deployed,
  the folder is a persistent volume on the VM (ARCHITECTURE.md, Section
  5). It needs no cloud account or credentials for anyone running Storage
  Management, and the whole stack runs on one VM anyway.
- **Research Evaluation reads PDFs only through
  `GET /internal/papers/{id}/pdf`** (service JWT), never from the folder.
  That keeps "Research Evaluation reads everything from Storage
  Management" true, and it keeps working if the two services end up on
  different hosts or the files move to S3. `pdf_url` in
  `/evaluate/background-info` now means that endpoint.
- **Pending: where a DOI-only paper's PDF comes from.** Amir decides, as
  part of DOI tracking. The options:
  - download the open-access PDF (OpenAlex `best_oa_location`) and, if
    there's none or the publisher blocks the download, track the paper
    without a file;
  - download the open-access PDF and refuse to track the paper when
    there's none;
  - require the user to upload the PDF for a DOI-only paper.

  Until it's settled a paper may have no stored PDF, so
  `GET /internal/papers/{id}/pdf` returns `404` for one, and no
  `file_available` flag is added to responses yet. Paywalls matter here:
  the three demo papers are on ScienceDirect, which usually blocks bots
  (see DEMO.md).

### Rejected

- **Discarding the PDF once GROBID has read the DOI** (the `32fb3be`
  design). It leaves Research Evaluation nothing to evaluate a change
  against and drops the week-7 COI text and claims input.
- **Keeping only the text GROBID extracts, not the PDF.** It avoids
  storing files, but fixes the extraction at ingest: a better GROBID
  model, a different extraction (such as passages for week-13
  highlighting), or a re-run after a GROBID outage would all need the
  original.
- **S3 now.** It needs an AWS account, credentials for everyone who runs
  Storage Management, and an SDK, for a stack that runs on one VM. Since
  PDFs are only read through the internal endpoint, moving to S3 later
  changes nothing outside Storage Management.
- **Research Evaluation reading the upload folder directly.** It ties
  both services to one disk and skips the service-token check.

### Consequences

- The storage code doesn't match this yet: uploads are still discarded
  (commit `747849b` only changed ARCHITECTURE.md). `LocalFileStore` and
  the `papers` file key have to come back and
  `GET /internal/papers/{id}/pdf` has to be built. Some local databases
  have already run `V2__drop_papers_file_key.sql`, so the column comes
  back in a new migration rather than by deleting V2.
- The DOI-tracking contract on `feat/storage-track-doi` needs the PDF
  rule once the pending decision is made.

---

## 2026-09-25 — Updating stores no snapshot when Crossref or OpenAlex errors

### Decision

- If Crossref or OpenAlex returns `error` (timeout, 429, 5xx, an unreadable
  body) for a paper's DOI, Updating stores **no snapshot** for that paper on
  that poll. It lists the paper under `source_errors` in the run summary and
  retries on the next poll.
- Only `error` gates. `not_found` (e.g. DataCite DOIs) is a stable answer and
  is stored. A failed author batch (`openalex_authors`) is stored too, since
  authors aren't compared.

### Why

The docs never compare a field whose source wasn't `ok`, so a change
published during an outage would be lost for good. Mon ok; Tue Crossref down;
a correction is published; Wed ok. Tue's snapshot has null `crossref_updates`,
so Wed vs Tue is skipped, and Thu matches Wed: the correction is never
nudged. With the gate, Tue stores nothing, so Wed is compared with Mon and
nudges. Every stored snapshot is then comparable with the one before it, so
"compare with the previous snapshot" works unchanged for Updating and for
Research Evaluation.

### Rejected

Per-source "last ok" baselines (compare each source's fields against the last
snapshot where that source was `ok`). They need extra state per paper and
source, and Research Evaluation, which reads the snapshots itself, would have
to copy the same logic.

### Also settled while building it

- **`JWT_SECRET` must be base64 of 32+ bytes even in sprint 1.** The
  2026-09-24 entry below says "any shared value"; Storage Management
  base64-decodes the secret and its JWT library rejects keys under 32 bytes, so
  Updating checks the format at startup.
- **The first scheduled poll counts from the last scheduled poll**, not from
  startup, so redeploys and restarts can't keep pushing a 24-hour poll back.
- Live check: the publisher's `retraction` entry in Crossref's `updated-by` for
  `10.1016/j.ijantimicag.2020.105949` points at that paper's own DOI, and
  publisher entries have no `record-id` at all (Retraction Watch entries do).
  Both are stored as they come.

---

## 2026-09-24 — Updating owns fetching and snapshots; Research Evaluation evaluates

### Team decisions

- **Ownership split.** Updating fetches Crossref/OpenAlex status, journal and
  author data, stores snapshots (via Storage Management) and tells Research
  Evaluation when a snapshot changed. It records snapshots only, never
  changes. Research Evaluation works out the differences between snapshots,
  classifies them, evaluates what they mean (severity, impact statement,
  recommendation) and owns the alert list and actions. This replaces the
  2026-09-18 design where Research Evaluation built the background-info
  snapshot and Updating diffed it, kept `change_events` and attached canned
  impact text from a lookup table.
- **The handoff is a nudge with paper ids, not a payload.** Updating and
  Research Evaluation share data only through Storage Management. Per poll:
  fetch from the APIs → store the snapshot in Storage Management → compare
  it with the previous snapshot (read back from Storage Management) → if it
  differs in a field the alerts depend on, `POST /evaluate/changes` with just
  the changed `paper_ids`. Research Evaluation then reads the snapshots (the
  updatable data) and the paper, notes and extracted text (the non-updatable
  data) from Storage Management itself and works out the differences.
  Reasons: Research Evaluation would otherwise need Updating to hand it data
  it can already read, each side only has to talk to one other service, and
  Updating needs no change table of its own. A poll with no changes sends
  nothing, so Research Evaluation isn't woken on every check. Considered and
  rejected:
  - Pushing each change with its data and storing Research Evaluation's
    answer on Updating's row (the first version of this decision). It
    couples the two services' data shapes and needs Research Evaluation to
    reply.
  - A bare nudge with no ids. Research Evaluation would have to scan Storage
    Management for papers whose latest snapshot differs from the previous
    one and keep its own watermark.
  - Sending change ids and having Research Evaluation read change rows from
    Updating. It would need to call two services, and Updating would need to
    keep change rows.

  Updating's comparison is a plain field comparison to decide whether to
  nudge, not a classification. Because it keeps no change rows, it keeps a
  `nudge_pending` flag per paper in its own `tracked_papers` table, cleared
  once Research Evaluation accepted the nudge (`202`). This is needed
  because after a failed nudge the next snapshot would look unchanged. The
  endpoint must therefore tolerate receiving the same ids twice. Updating no
  longer serves a change list or `PATCH /changes/{id}`; how Research
  Evaluation stores and serves evaluations, changes and researcher actions is
  left to it.
- **Auth is a placeholder in sprint 1.** There's no User Management yet, so
  `JWT_SECRET` is any shared value and services run locally. Updating still
  mints service tokens; Storage Management only accepts those on
  `/internal/**`, which is why the background-info endpoints moved there.
  Nothing is hosted, so Supabase and the session-pooler note wait until
  deployment.
- **Snapshots stay per paper, insert-only, full history.** Considered
  keying them by DOI (one snapshot per DOI per run, fanned out to every
  user tracking it) and rejected it: it saves a few near-identical rows but
  needs fan-out logic, and per-paper history gives each user their own
  baseline, so nobody gets alerted about changes from before they started
  tracking. Keeping full history over only the latest snapshot: since
  snapshots are the only record, Research Evaluation can work out the
  differences after the fact, a missed or false alert can be replayed from
  the stored pair, and new rules can be back-tested. The cost is small (a
  few KB per row; 100 papers polled daily is about 36k rows a year). If
  size ever matters, prune unchanged snapshots that no change references.
- **Snapshots are stored even when nothing changed**, so the poll's
  "new snapshot each run" is checkable. Storage Management's POST is now a
  plain store of the snapshot in the body; it no longer triggers a fetch.
- **Citation counts are stored but not alerted on this sprint.** The
  2026-09-18 week-7 scope listed "did citation count jump"; the sprint's
  alert stories are retraction, correction/erratum/expression of concern,
  and DOAJ delisting only.

### Facts found while checking live Crossref/OpenAlex responses

- **`updated-by` lists duplicates.** The same notice DOI appears once from
  `publisher` and once from `retraction-watch`, sometimes with different
  types (`10.1016/s0140-6736(20)31324-6` is both a Retraction Watch
  "retraction" and a publisher "erratum"). Entries are identified by
  (`notice_doi`, `type`); a new source for a known pair is not a new
  event.
- **DOAJ status belongs to the journal, not the paper.** OpenAlex's
  `is_in_doaj` is per source, and repository locations (PubMed) are always
  false. If OpenAlex switched a paper's `primary_location` from the journal
  to a repository, `in_doaj` would flip to false without any delisting. So a
  delisting needs `in_doaj` true → false with the same `journal_source_id`
  and a `journal` source type.
- **Citation counts disagree between sources** (OpenAlex 1252 vs Crossref
  416 for the same retracted Lancet paper). Only OpenAlex's is stored.
- **OpenAlex's batched `/authors` lookup returns authors in its own
  order.** Re-order by the work's `authorships`, and take `institution`
  from the authorship (the affiliation on that paper), not
  `last_known_institutions`.
- **Crossref uses more update types than we alert on** (`withdrawal`,
  `removal`, `partial_retraction`, ...). They're stored, not alerted on.
- **A paper's stored title goes stale.** Crossref later prefixes
  "RETRACTED:" to a title, but `papers.title` is set once at ingest, so
  the UI should show the latest snapshot's title.
- **DataCite DOIs (Zenodo, arXiv) and wrongly extracted DOIs aren't in
  Crossref.** They're recorded as `not_found`, not treated as a change.

### Consequences

- Research Evaluation's `POST /evaluate/background-info` no longer serves the
  retraction, Crossref update, DOAJ, journal and author fields; its owner
  decides what it keeps serving (see CONTRACTS.md).
- The derived `crossref_retracted` field is dropped: a retraction is an
  entry of type `retraction` in `crossref_updates`.

---

## 2026-09-18 — Research Evaluation + Updating scope and design

### Team decisions (via Q&A)

- **LLM provider: DeepSeek**, not Claude/OpenAI. Chosen for cost — under
  1¢ per stance/claims call — with demo reliability handled by
  pre-warming the cache rather than by provider choice.
- **Updating's stack: Python**, sharing one codebase/image with Research
  Evaluation (two FastAPI apps). Avoids duplicating the
  `BackgroundInfoDTO`, JWT validation and HTTP client code across a
  Python and a Java project, which the original "Updating: TBD" left
  unresolved.
- **Updating's change data (`change_events`, `tracked_papers`,
  `poll_runs`) lives in Updating's own Postgres schema**, not in Storage
  Management's database, even though Section 2 of the original doc says
  Storage Management owns "every background-info/change snapshot." That
  line is read as covering the background-info snapshots themselves
  (which Storage Management does own); the derived diff/event data is
  Updating's own bookkeeping and putting it in Updating's schema avoids
  making every Updating write depend on Storage Management's uptime.
- **New-related-paper detection is out of scope for week 7.** The
  original brief listed two independent detection sources (status diff
  on a tracked paper, and new papers appearing that relate to it). Only
  the first is built for the midterm; the second moves to week 13
  alongside topic-based discovery, which needs similar infrastructure
  (OpenAlex topic/citation queries) anyway.
- **Repo layout:** Research Evaluation + Updating live in `backend/`;
  `frontend/` and `storage/` start as empty placeholder folders for the
  other two owners.

### Deviations from the original brief, found while verifying API facts live

- **Crossref retraction signal comes from `updated-by`, not `relation`.**
  The original brief marked this "UNVERIFIED — needs confirming against
  a live retracted DOI." Checked live against two retracted papers
  (`10.1016/S0140-6736(20)31180-6` and
  `10.1016/j.ijantimicag.2020.105949`): the `relation` object only ever
  held unrelated things like `has-review`. The real signal is the
  `updated-by` array, where each entry has `type` (`retraction`,
  `expression_of_concern`, `correction`, `erratum`, ...), `label`,
  `source` (`publisher` / `retraction-watch`), the notice's own `DOI`,
  and an `updated` date. Column renamed from `update_to` to
  `crossref_updates` to match.
- **`journal_legitimate` (DOAJ) dropped as a separate API call.**
  OpenAlex's work record already carries
  `primary_location.source.is_in_doaj`, sourced from DOAJ's own journal
  list, for free alongside data Research Evaluation fetches anyway. A
  separate DOAJ client would add a call, a 2 req/s rate limit, and a new
  failure mode for no new information. Caveat carried forward: DOAJ
  lists only fully open-access journals, so `in_doaj=false` for a
  subscription journal (e.g. The Lancet) is not a legitimacy signal —
  only a **true→false flip (delisting)** is treated as a change event.
- **Semantic Scholar snippets are stored and reused, not fetched fresh
  every time.** Section 2 said snippets are query-driven and fetched at
  comparison time, not stored per paper — confirmed correct. But
  re-fetching on every stance call would waste calls when nothing
  relevant changed. Research Evaluation now caches snippet results keyed
  by (candidate DOI, claim-text hash), and re-pulls only when: the claim
  text changed, the candidate paper's Crossref `updated-by` list changed
  (checked via one free Crossref lookup), or the caller passes
  `refresh=true`. Snippets still never land in Storage Management's
  per-paper `background_text` row — they belong to a (claim, candidate)
  pair, not to one paper.
- **`/evaluate/stance` takes DOIs, not Storage Management paper ids.** A
  candidate paper a new snippet/citation points at may not exist in
  Storage Management at all, and this keeps Research Evaluation free of
  runtime calls back into Storage Management just to resolve an id.
- **OpenAlex now requires an API key** for its full $1/day free usage
  budget (keyless calls get 1/10 of that). This wasn't true when the
  original brief was written. See SETUP.md.
- **No `GET /health` endpoints.** Considered and dropped — not needed
  for this project's scope; Docker Compose's own container health can
  stand in if ever needed.
- **DeepSeek `reasoning_effort` defaults to `low`**, not `high`. Chosen
  for latency and cost in the common case; raise per-call only if a demo
  pair's stance/claims output comes out wrong under low effort.
- **Research Evaluation's local sqlite cache (`cache.py`) is capped** at
  `CACHE_MAX_ENTRIES` (default 5000) with least-recently-used eviction,
  so it can't grow unbounded across a semester of development and demo
  runs. It was never a source of truth (Storage Management owns
  persistence), so evicting an entry only costs a re-fetch, not data
  loss.

### Rejected / not pursued

- A dedicated DOAJ API client — see above.
- Storing Semantic Scholar snippets in Storage Management's schema — see
  above.
- Giving Updating a hard dependency on Storage Management's database
  (shared schema) instead of its own — rejected for the reason given
  above under "Updating's change data."
