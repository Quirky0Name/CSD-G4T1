# Decisions log

Dated log of decisions and why they were made, so settled questions stay
settled and anyone (including the TA) can see the reasoning. Newest first.

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
