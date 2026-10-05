# Roadmap

What isn't built yet, and known gaps in what is. Week-7 scope is what's
built (ARCHITECTURE.md); everything else is here. Grouped by area, roughly
most needed first within each.

## Repo housekeeping

- [ ] **Work out what `feat/storage-snapshot-table` is for and why it's
  unmerged.** What's known so far: it's on `origin` only, last touched
  2026-09-26, and holds the CG-68 snapshot endpoints (`SnapshotService`,
  `SnapshotTest`, …) plus the merge of PR #20 from
  `feat/storage-snapshot-endpoints` (also unmerged, same 5 commits). PR #20
  merged into `feat/storage-snapshot-table` *after* that branch had already
  gone into `main` (PR #19), so the endpoints never reached `main` that
  way; `fix/storage-snapshot-endpoints` (PR #26) brought them in later.
  Check whether `main` has everything from both branches, then delete
  them, or merge what's missing.
- [ ] **Clean up `backend/.env.example`:** `LLM_API_KEY`, `LLM_MODEL`,
  `LLM_BASE_URL` and `CACHE_MAX_ENTRIES` are read by nothing (the
  DeepSeek plan), and `S2_API_KEY` and `GROBID_URL` are Storage
  Management's, not the Python apps'.
- [ ] **Share the Crossref request code** between Updating's
  `sources.fetch_crossref` and Research Evaluation's
  `investigation/crossref.py` through `common/` (with Updating's owner).

- [ ] **Settle the naming, then rename consistently** (tables, entities,
  endpoints, JSON fields, docs, frontend copy). Candidates:
  - the researcher's own paper → **draft** (`research_papers`,
    `/research-paper`, `draft_pdf` already in Research Evaluation);
  - background-info / `background_metadata` / `background-info/history` →
    **snapshot(s)**;
  - folder → **project** (`folder_id`, "no folder" project);
  - and check the rest while at it: tracked paper vs paper vs work,
    alert vs change, report documents, `nudge`.

  Decide before the DOI re-key below, so the new tables and endpoints get
  the final names once, and renames of existing columns go in new
  migrations.

## Data model: DOI as the shared key

- [ ] **Re-key everything that describes a paper by its DOI, so no work is
  repeated across users.** Today every row hangs off `paper_id` (one user
  tracking one DOI), so for *n* users tracking one DOI: Updating fetches
  once but reads history, POSTs the same snapshot and compares *n* times;
  Storage Management stores *n* identical snapshots, PDFs and alerts;
  Research Evaluation detects, investigates (Crossref, Europe PMC, PDF
  downloads) and runs Gemini *n* times. Agreed direction (2026-10-05):
  - **`paper_id` is the user's tracking of a DOI in one project**: one
    row per (user, folder, DOI). The one-DOI-per-user rule becomes
    one-DOI-per-project. It's only the key for user state.
  - **Keyed by DOI (shared):** snapshots, the paper's stored PDF, detected
    changes (today's alert content: type, `change_key`, rule severity and
    text, `detected_at`), reports and their documents, and impact step 1
    (what changed, how severe; it doesn't read the draft).
  - **Keyed by `paper_id` / project (per user):** owner and folder, the
    researcher's alert status and notes, impact steps 2–3 (they read the
    project's draft), notifications.
  - **Updating and Research Evaluation work on DOIs:** Updating keeps one
    `tracked_papers` row, one history read, one snapshot and one nudge
    entry per DOI; the nudge carries DOIs; Research Evaluation detects
    and investigates once per DOI, runs step 1 once, then steps 2–3 once
    per project tracking that DOI.

  Open before planning: a change detected before a user started tracking
  (show it, or keep today's per-user baseline); uploads with no DOI (a
  private, never-polled paper, or refused); which PDF a DOI keeps when
  users upload different copies; DOIs in internal URLs (they contain `/`:
  query parameter or body, or a surrogate id); migrations V9+ plus a
  one-time local reset. Supersedes DECISIONS.md 2026-09-24's "snapshots
  per paper" and 2026-09-27's "a tracked paper is in exactly one project".
  Needs a plan doc with subtasks per service, signed off by each owner.

## User Management and auth

- [ ] **User Management service**: `users` and `folders`, register
  (BCrypt), login issuing the JWT, folder CRUD
  (`GET/POST/PUT/DELETE /folders…`). Nothing exists; a hand-made demo
  token stands in.
- [ ] Frontend login, auth state and a JWT-attaching interceptor in place
  of `VITE_DEMO_TOKEN`.
- [ ] `POST /run-poll` takes the user's JWT (CG-99).
- [ ] Real folder ids from the frontend for papers and drafts.
- [ ] Notifications to the paper's owner instead of one hard-coded
  Telegram chat (needs users and contact details).

## Frontend and the researcher's view

- [ ] **Return impact's fields to the frontend.** `UserReportResponse`
  (`GET /papers/{id}/reports`) has `evaluation`, `recommendation` and
  `evaluated_at` but not `change_summary`, `change_severity`,
  `impact_level` or `assessment`. The frontend already reads the first
  three (`api.ts`, `ReportList.tsx`), so its severity and impact badges
  never show and its summary falls back to `evaluation`. Add them to the
  record and its `from`, `ReportListTest`, and CONTRACTS.md. (The merge
  note in the impact plan said whichever branch merged second would add
  them; neither did.)
- [ ] Show a placeholder evaluation as such (`assessment.placeholder`),
  and let the researcher confirm or reject an evaluation.
- [ ] `GET /alerts` across all of a user's papers (filters `folder_id`,
  `status`, `include_dismissed`), for an overview or a "3 new" badge:
  one query joining `alerts` to `papers` on the owner, no migration.
- [ ] A paper's severity state as a field, instead of the frontend
  working it out from the alerts.
- [ ] Download a report document's stored PDF (a user endpoint next to
  `GET /internal/documents/{id}/pdf`, ownership through report and paper),
  if `pdf_source_url` isn't enough.
- [ ] Notes per paper: one editable text per paper (`notes` table,
  `PUT /papers/{id}/notes`), and `GET /papers/{id}` with the paper's
  details and notes joined. Not the alert notes, which are built.

## Pipeline robustness

- [ ] **A status for impact in progress or failed.** While impact runs and
  after it fails, a report is `investigated`, the same as before it
  started, so the frontend can't tell pending, running, failed or off
  (no `GEMINI_API_KEY`). Add `assessing` (set when impact starts; the
  `PUT` accepts it), a failed state or a cause column, and a rule for an
  `assessing` report a crash left behind. Keep the single write at the end.
- [ ] **Retries.** Nothing retries a report left `investigating`, a
  document left `pending`, a failed Crossref/Europe PMC/PDF fetch, or a
  report whose impact failed. E.g. on the next nudge for the paper or on
  a schedule.
- [ ] **Re-assessing**: when the project's draft changes, or to replace a
  placeholder evaluation (today it locks the report: the evaluation
  columns and status would have to be reset by hand).
- [ ] **Nudges that keep failing** (a `JWT_SECRET` mismatch, a bug) are
  only a log line on every poll. Surface them (in the poll summary, or as
  an alert after repeated failures). Optionally: Updating keeps the flag
  only on `failed_paper_ids` (a contract change), and retries sooner than
  the next poll.
- [ ] Raise `EVALUATION_SNAPSHOT_WINDOW` (e.g. 30) before deployment.
- [ ] Close the Gemini client at shutdown; record `model_version` per
  step, not step 1's only.

## Detection and evaluation

- [ ] **Judge a paper's flagged changes together with an LLM**, replacing
  the "one alert per Crossref notice, most severe type" stopgap. Its known
  losses: a notice relabelled as more severe (other than to a retraction)
  raises nothing, and a notice with two unclassified types is named after
  the first.
- [ ] Nudge on any new Crossref entry, so an unclassified type
  (`withdrawal`, `removal`, …) arriving on its own reaches Research
  Evaluation (Updating's change).
- [ ] Replace a wrong retraction notice stored first (R3's
  self-reference, R5's notice about another article), e.g. when
  `update_to_includes_paper` is false or the title shows another article.
- [ ] Follow replacement DOIs named in a notice's text (R4) as new versions.
- [ ] Judge whether a downloaded current copy is really a new version (an
  LLM comparing text; delete the copy if it's the same), and whether a
  re-fetched document that differs from its stored row matters.
- [ ] Impact: read the paper's earlier reports as context (E5: a
  correction, EoC and retraction in three reports; E4: an EoC lifted);
  verify quotes against PDF text; compute the impact level in code from
  the severity table; cache how a draft uses a paper.

## Original scope not built

These were in the week-7 plan and weren't built; they now sit with the
week-13 work. The 2026-09-18 design picked DeepSeek for them; impact uses
Gemini, so pick the provider again when they're started.

- [ ] **Stance detection**: does a newly appearing paper support or
  contradict a tracked one. CONTRACTS.md's `/evaluate/stance` is from the
  old design and under review (likely an internal step, not an endpoint).
- [ ] **Methodology / claims validation** of a paper.
- [ ] **GROBID COI/funding text and full text** from the stored PDFs, and
  a `background_text` table for it.
- [ ] **Citation-neighbourhood metrics** (self-citation ratio, retracted
  references, citing institutions and sources, citations per year) from
  OpenAlex. `/evaluate/background-info` and
  `/evaluate/citation-neighbourhood` are under review, like stance.
- [ ] Semantic Scholar abstract / TL;DR, and snippets cached per (claim,
  candidate) for stance.
- [ ] **Newly appearing related papers** (Updating), topic-based
  discovery, and highlighting the most relevant passages in a paper.
- [ ] Alerting on citation counts (stored, never compared).

## Deployment

- [ ] Host the compose stack on one VM; PDFs on a persistent volume (S3
  later if it outgrows one VM).
- [ ] Postgres on Supabase (session pooler, port 5432; it starts empty and
  runs every migration).
- [ ] CI that builds each image on merge to `main` and redeploys (today CI
  is a Trivy scan and Telegram PR pings).
- [ ] Before a real deployment: remove the `/dev` tools and Swagger (or
  put them behind a setting), real secrets per service, CORS instead of
  the Vite proxy.
