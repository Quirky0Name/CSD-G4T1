# Architecture

## Overview

The core loop is: detect a meaningful change in a tracked paper → assess
its impact → recommend an action → the researcher decides. That's a direct
reframe from the professor's review of the original pitch: the module
theme is measuring change in a landscape, not issuing a one-time trust
verdict.

The original product vision — paper upload and management, background
checks on a paper's author/journal/retraction status, claim validation
against related work, and topic-based paper discovery — still stands, but
its role has shifted. Background-info checking is now the raw signal
layer the detect-change loop reads from, not the headline feature on its
own.

### Week 7 (midterm) scope

Mostly zero-LLM by design, so the live demo stays reliable, with one
deliberate exception: Research Evaluation's stance/claims LLM work is in
scope for week 7 too, not deferred to week 13.

- Ingest a paper (PDF upload or DOI-only)
- Notes: a plain-text editor per paper
- Background-info snapshot: CrossRef/OpenAlex status, journal and author
  data, fetched and stored by Updating (see Section 4), plus GROBID-extracted
  COI text + citation-neighbourhood metrics (self-citation ratio,
  retraction cascade, citation diversity, citations/year) from Research
  Evaluation
- Change detection: Updating diffs two snapshots of the same paper and
  records the raw change; Research Evaluation evaluates it (severity,
  impact statement, recommendation)
- Stance detection and methodology/claims validation (LLM), scoped to
  Research Evaluation only — see below

### Week 13 (final) scope

Everything that needs open-ended reasoning beyond the week-7 LLM work:

- Methodology/claims validation refinements
- Topic-based paper discovery
- Highlighting the most relevant passages in a paper
- Updating: surfacing newly-appearing related papers (not just status
  diffs on tracked papers) — out of scope for week 7 by team decision

## Shared architecture

Five services sit behind one React frontend and talk to each other over
REST; a single JWT, issued by User Management at login, is what the rest
of the system trusts — no service calls back to User Management to check
a token, they validate its signature themselves, so nothing else goes
down if that one service does.

```
flowchart LR
   FE[React Frontend] --> UM[User Management]
   FE --> SM[Storage Management]
   UM -- JWT --> SM
   UM -- JWT --> RE
   SM --> DB[(Postgres)]
   SM --> FILES[(S3 / local disk)]
   SM --> GROBID[GROBID]
   RE[Research Evaluation - LLM] --> SM
   RE --> EXT[CrossRef / OpenAlex]
   UPD[Updating - Scheduler] --> EXT
   UPD -- /evaluate/change --> RE
   UPD --> SM
```

Sprint 1 runs everything locally with a shared placeholder `JWT_SECRET`;
User Management doesn't exist yet, so the JWT paths above are wired up
but not backed by real logins.

| Service | Stack | Owns | Folder |
|---|---|---|---|
| User Management | Spring Boot (backend) + React (Vite, frontend) | `users`, `folders`; auth | `frontend/` |
| Storage Management | Java + Spring Boot | `papers`, `notes`, `background_metadata`, `background_text`, `authors_background`; Postgres + S3/disk | `storage/` |
| Research Evaluation | Python | Change evaluation (severity, impact, recommendation), COI text, citation-neighbourhood metrics, LLM reasoning (claims + stance, week 7) | `backend/` |
| Updating | Python (shares the `backend/` project with Research Evaluation) | Crossref/OpenAlex status, journal and author fetching; snapshots (sent to Storage Management); `change_events`; the polling scheduler | `backend/` |
| Deployment | Docker + a public cloud target | Containerisation, environment config, CI | (cross-cutting) |

Rubric note: Java + Spring Boot for at least one component is satisfied by
Storage Management on its own, so Research Evaluation being Python
carries no compliance risk.

## Section 1 — User Management

Owns accounts and research folders. Nothing else in the system should
touch the `users` or `folders` tables directly.

- **Data model:** `users (id, email, password_hash, created_at)`,
  `folders (id, owner_id -> users, name, created_at)`
- **Auth flow:** register (BCrypt hash) → login (verify hash, issue signed
  JWT with the user's id) → every request to Storage Management or
  Research Evaluation carries `Authorization: Bearer <token>`; those
  services validate the signature themselves, no callback per request.
- **Frontend** (owned by this service): Vite, React Router, Axios with a
  JWT-attaching interceptor, react-hook-form + zod, Tailwind + shadcn/ui,
  React Context for auth state.
- **Endpoints:** `POST /auth/register`, `POST /auth/login`,
  `GET/POST/PUT/DELETE /folders...`

Cross-service note: a folder's papers live in Storage Management via a
bare `folder_id` reference — no enforced FK across services.

## Section 2 — Storage Management

Owns all Postgres and file persistence.

- **Ingestion, two paths:** upload (PDF → S3/disk → GROBID header extract
  → `papers` row) and DOI-only (CrossRef metadata → `papers` row, no
  file).
- **Schema:** `papers`, `notes` (separate table/endpoint from `papers`),
  `background_metadata` (insert-only history, kept in full and per paper —
  never overwrite, that's what Updating diffs; one row per tracked paper
  per poll, even when nothing changed), `background_text` (raw text for
  week-13 LLM input; nothing here is diffed in week 7),
  `authors_background`.
- **File storage:** PDF bytes never in Postgres — S3/local disk holds
  bytes, Postgres holds the key.
- **Endpoints:** `POST/GET /papers`, `GET /papers/{id}` (joined DTO),
  `PUT /papers/{id}/notes`, `POST/GET /internal/papers/{id}/background-info`.
- **DB hosting:** Supabase free tier.

## Section 3 — Research Evaluation

See [CONTRACTS.md](CONTRACTS.md) for the full endpoint/DTO contract.
Owns evaluating what a detected change means, and the paper-evaluation/
comparison logic behind it, including LLM reasoning — live in week 7, not
deferred. Fetching the retraction, correction, DOAJ, journal and author
fields moved to Updating (Section 4).

- **Change evaluation:** given a raw change from Updating, returns a
  severity, an impact statement and a recommendation (`POST
  /evaluate/change`).
- **Structured signal layer** (no reasoning, cheap): citation-neighbourhood
  metrics computed from OpenAlex reference/citation data, GROBID (COI/
  funding text, verbatim, never judged).
- **Also captured, storage only in week 7:** Semantic Scholar abstract/
  TL;DR, with snippets fetched (and cached/reused) at stance-comparison
  time rather than stored per paper.
- **LLM reasoning (week 7):** stance detection (does a newly-appearing
  paper support or contradict a tracked one) and methodology/claims
  validation, both live in the demo. Provider: DeepSeek (see
  [DECISIONS.md](DECISIONS.md)). Results are cached and pre-warmed for
  the demo so a slow/failed call can't stall it.
- **Endpoints (internal):** `POST /evaluate/change`,
  `POST /evaluate/background-info`, `POST /evaluate/citation-neighbourhood`,
  `POST /evaluate/stance`.

## Section 4 — Updating

Owns the scheduled job that re-checks tracked papers: it fetches their
current status from Crossref and OpenAlex, snapshots it, and records what
changed. Research Evaluation then says what each change means.

- **Why polling:** none of CrossRef/OpenAlex offer webhooks, and
  OpenAlex's `from_updated_date` filter is Premium-only. Polling is the
  right answer, not a workaround.
- **Job design:** on a schedule (`POLL_INTERVAL_HOURS`), list tracked
  papers from Storage Management, fetch each DOI once from Crossref
  (`updated-by`) and OpenAlex (retraction, DOAJ, journal, authors), and
  send one snapshot per tracked paper to Storage Management (insert-only,
  even when nothing changed). Then diff each paper's two most recent
  snapshots after its watermark and write a `change_events` row per change.
  Papers with no DOI are skipped and logged.
- **Diff rules:**
  - `is_retracted` false → true (OpenAlex), or a new Crossref
    `updated-by` entry of type `retraction`.
  - A new `updated-by` entry of type `correction`, `erratum` or
    `expression_of_concern`. An entry is identified by (notice DOI, type);
    Crossref lists the same notice once per source (publisher,
    Retraction Watch), and a new source for a known pair is not a new
    change.
  - `in_doaj` true → false, only when it's the same journal source
    (`journal` type). `in_doaj` belongs to the journal, so an OpenAlex
    switch of primary location to a repository must not read as
    delisting.
  - A source that failed or didn't know the DOI is stored as null and
    never diffed (a failure must not look like a status change). A
    paper's first snapshot is only a baseline.
- **Week 7 scope:** status diff only, for the changes above. Citation
  counts are stored but not alerted on. Detecting newly-appearing related
  papers is explicitly **out of scope** for week 7 (team decision — see
  DECISIONS.md).
- **Handoff to Research Evaluation:** each change is written as a
  `change_events` row with `evaluation_status=pending`, then pushed to
  `POST /evaluate/change`; the returned severity, impact statement and
  recommendation are stored and the row becomes `done`. If Research
  Evaluation is down, the row stays `pending` and the next poll re-sends
  it. Zero LLM calls in Updating.
- **`change_events` schema:** `id, paper_id, doi, change_type, source,
  changed_field, old_value, new_value, details, snapshot_ids, detected_at,
  evaluation_status` (+ severity, impact_text, recommendation, status —
  see CONTRACTS.md). Severity, impact and recommendation are null until
  evaluated.
- **Endpoints:** `GET /papers/{id}/changes`, `PATCH /changes/{id}`,
  `POST /admin/run-poll`.

## Section 5 — Deployment

Owns getting every service (and GROBID) reachable from the public
internet.

- **Containerisation:** one Dockerfile per service, composed via Docker
  Compose for local dev and as the deployable unit. Research Evaluation
  and Updating share a single image (see `backend/`).
- **Cloud target:** a single VM running the whole Compose stack.
- **Data services:** Postgres via Supabase free tier; PDF storage on
  S3 or local disk (mounted as a persistent volume if local).
- **Config/secrets:** environment variables per service, never committed.
- **CI:** GitHub Actions builds each service's image on merge to `main`
  and redeploys to the VM.
