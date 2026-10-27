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

## User Management and auth

- [ ] **Projects in the frontend** (UM-B): the projects page lists, creates,
  renames and deletes through `/projects` instead of its one hard-coded
  project, and papers and drafts are sent with the chosen project's id.
  User Management's side is built.
- [ ] Frontend login, auth state and a JWT-attaching interceptor in place
  of the demo token hard-coded in `api.ts`, which expires on 2026-10-28.
  Plus a Vite proxy entry for User Management in development (it sends no
  CORS headers; the root stack's web server already routes `/auth`).
- [ ] `POST /run-poll` takes the user's JWT (CG-99).
- [ ] Notifications to the paper's owner instead of one hard-coded
  Telegram chat (needs users and contact details).

## Frontend and the researcher's view

- [ ] **Return impact's fields to the frontend.** `UserReportResponse`
  (`GET /papers/{id}/reports`) has `evaluation`, `recommendation` and
  `evaluated_at` but not `change_summary`, `change_severity`,
  `impact_level` or `assessment`. The frontend already reads the first
  three (`api.ts`, `ReportList.tsx`), so its change severity and impact
  badges never show and its summary falls back to `evaluation`. Add them to the
  record and its `from`, `ReportListTest`, and CONTRACTS.md. (The merge
  note in the impact plan said whichever branch merged second would add
  them; neither did.)
- [ ] Show a placeholder evaluation as such (`assessment.placeholder`),
  and let the researcher confirm or reject an evaluation.
- [ ] `GET /alerts` across all of a user's papers (filters `project_id`,
  `status`, `include_dismissed`), for an overview or a "3 new" badge:
  one query joining `alerts` to `papers` on the owner, no migration.
- [ ] A paper's alert severity state as a field, instead of the frontend
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
- [ ] Timeouts on User Management's call to clear a project:
  `StorageClient` sets none, so a Storage Management that takes the
  connection but never answers holds the project delete open.
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
  the change severity table; cache how a draft uses a paper.

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
  OpenAlex. `/evaluate/snapshot` and
  `/evaluate/citation-neighbourhood` are under review, like stance.
- [ ] Semantic Scholar abstract / TL;DR, and snippets cached per (claim,
  candidate) for stance.
- [ ] **Newly appearing related papers** (Updating), topic-based
  discovery, and highlighting the most relevant passages in a paper.
- [ ] Alert on a slow, steady rise in citations too, against the count at
  the paper's last citation jump rather than the previous poll's.
- [ ] List the newly citing works in a citation jump's report, so impact
  can judge what the new attention is.

## Deployment

- [ ] Host the root compose stack on one EC2 VM (CG-136), with 4 GB or
  more (the stack idles at about 3 GB, GROBID's 2 GB included). HTTPS on a
  hostname: the hostname as the Caddyfile's site address, 443 published
  as well as 80, and a volume for Caddy's `/data` so its certificates
  survive. Only 80 and 443 open; the database and PDF volumes on its disk
  and backed up together (S3 for the PDFs later if it outgrows one VM).
- [ ] Keep `/updating` off the public web server (or behind Caddy's
  `basic_auth`) until `POST /run-poll` takes the user's JWT (CG-99):
  anyone can start a poll today.
- [ ] Updating retries its startup poll while Storage Management isn't up
  yet (Updating's owner). After a host reboot Docker restarts the
  containers in no set order, and a poll that fails then counts as the
  day's poll.
- [ ] CI that builds each image on merge to `main` and redeploys (today CI
  is a Trivy scan and Telegram PR pings).
- [ ] Before a real deployment: the `/dev` tools and Swagger off by a
  setting, real secrets, a health endpoint for Storage Management, and its
  container not running as root (CG-133); a database role per service
  instead of the one shared role.
