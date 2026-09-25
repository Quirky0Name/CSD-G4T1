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
- Change detection: Updating stores a snapshot of each paper on every
  poll and, when a snapshot differs from the previous one in the fields we
  alert on, tells Research Evaluation which papers changed. It records no
  changes itself. Research Evaluation reads the snapshots from Storage
  Management, works out the differences and evaluates them (severity,
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
   SM --> GROBID[GROBID]
   RE[Research Evaluation - LLM] --> SM
   RE --> EXT[CrossRef / OpenAlex]
   UPD[Updating - Scheduler] --> EXT
   UPD -- "changed paper ids" --> RE
   UPD --> SM
```

Updating and Research Evaluation share data only through Storage
Management. Updating writes snapshots there and, when a poll finds a
change, sends Research Evaluation just the ids of the changed papers.
Research Evaluation reads everything it needs from Storage Management:
the snapshots (the updatable data) and the paper, notes and extracted
text (the non-updatable data).

Sprint 1 runs everything locally with a shared throwaway `JWT_SECRET`
(still base64 of 32+ bytes); User Management doesn't exist yet, so the
JWT paths above are wired up but not backed by real logins.

| Service | Stack | Owns | Folder |
|---|---|---|---|
| User Management | Spring Boot (backend) + React (Vite, frontend) | `users`, `folders`; auth | `frontend/` |
| Storage Management | Java + Spring Boot | `papers`, `notes`, `background_metadata`, `background_text`, `authors_background`; Postgres | `storage/` |
| Research Evaluation | Python | Change evaluation (severity, impact, recommendation), read from Storage Management when nudged by Updating; COI text, citation-neighbourhood metrics, LLM reasoning (claims + stance, week 7) | `backend/` |
| Updating | Python (shares the `backend/` project with Research Evaluation) | Crossref/OpenAlex status, journal and author fetching; sending snapshots to Storage Management; nudging Research Evaluation when a snapshot changed; the polling scheduler and its small polling state (`tracked_papers`) | `backend/` |
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

Owns all Postgres persistence.

- **Ingestion, two paths:** upload (PDF → GROBID header extract →
  `papers` row) and DOI-only (CrossRef metadata → `papers` row).
- **Schema:** `papers`, `notes` (separate table/endpoint from `papers`),
  `background_metadata` (insert-only history, kept in full and per paper —
  never overwrite, that's what Updating and Research Evaluation compare;
  one row per tracked paper per poll, even when nothing changed, except
  that Updating stores none for a paper on a poll where Crossref or
  OpenAlex failed for its DOI, see Section 4), `background_text` (raw text for
  week-13 LLM input; nothing here is diffed in week 7),
  `authors_background`.
- **No file storage:** an uploaded PDF is only read by GROBID, then
  discarded.
- **Endpoints:** `POST/GET /papers`, `GET /papers/{id}` (joined DTO),
  `PUT /papers/{id}/notes`, `POST/GET /internal/papers/{id}/background-info`.
- **DB hosting:** Supabase free tier.

## Section 3 — Research Evaluation

See [CONTRACTS.md](CONTRACTS.md) for the full endpoint/DTO contract.
Owns working out what changed in a paper and what that means, and the
paper-evaluation/comparison logic behind it, including LLM reasoning —
live in week 7, not deferred. Fetching the retraction, correction, DOAJ,
journal and author fields moved to Updating (Section 4).

- **Change evaluation:** Updating calls `POST /evaluate/changes` with the
  ids of the papers that changed and nothing else. Research Evaluation
  reads those papers' snapshots and their non-updatable data from Storage
  Management, **works out the differences between the snapshots itself**,
  classifies them (retraction, correction, erratum, expression of concern,
  DOAJ delisting) and produces a severity, an impact statement and a
  recommendation. Nothing goes back to Updating. How the evaluation is
  stored, and the change list and researcher actions (acknowledge,
  dismiss) the frontend uses, are Research Evaluation's design and aren't
  specified here yet.
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
- **Endpoints (internal):** `POST /evaluate/changes`,
  `POST /evaluate/background-info`, `POST /evaluate/citation-neighbourhood`,
  `POST /evaluate/stance`.

## Section 4 — Updating

Owns the scheduled job that re-checks tracked papers: it fetches their
current status from Crossref and OpenAlex and snapshots it. It records
snapshots only, never changes: when a new snapshot differs from the
previous one it tells Research Evaluation which papers changed, and
Research Evaluation works out the differences and what they mean.

- **Why polling:** none of CrossRef/OpenAlex offer webhooks, and
  OpenAlex's `from_updated_date` filter is Premium-only. Polling is the
  right answer, not a workaround.
- **Job design:** on a schedule (`POLL_INTERVAL_HOURS`), list tracked
  papers from Storage Management, fetch each DOI once from Crossref
  (`updated-by`) and OpenAlex (retraction, DOAJ, journal, authors), and
  send one snapshot per tracked paper to Storage Management (insert-only,
  even when nothing changed). Then compare each paper's new snapshot with
  its previous one, which Updating reads back from Storage Management, to
  decide whether to nudge Research Evaluation. Each snapshot's
  `fetched_at` records when the paper was last checked. Papers with no DOI
  are skipped and logged.
- **Outage gate:** if Crossref or OpenAlex returns `error` for a paper's
  DOI, Updating stores no snapshot for that paper this poll, lists it under
  `source_errors` in the run summary and retries on the next poll. Only
  `error` gates: `not_found` (e.g. DataCite DOIs) is a stable answer and is
  stored. Without the gate, a change published during an outage would be
  lost, because a field whose source wasn't `ok` is never compared (see
  DECISIONS.md, 2026-09-25); with it, every stored snapshot is comparable
  with the one before it.
- **Schedule:** the first poll after a start runs one interval after the
  last scheduled poll (immediately if that is already due), so restarts and
  redeploys can't keep postponing it.
- **When to nudge:** Updating does a plain comparison of the fields the
  alerts depend on and doesn't classify what changed. It nudges when the
  new snapshot differs from the previous one in any of:
  - `is_retracted` false → true;
  - a new `crossref_updates` entry of type `retraction`, `correction`,
    `erratum` or `expression_of_concern`, where an entry is identified by
    (notice DOI, type): Crossref lists the same notice once per source
    (publisher, Retraction Watch), and a new source for a known pair is
    not a new entry;
  - `in_doaj` true → false.

  A field that is null in either snapshot, or whose source wasn't `ok`, is
  never compared (a failure must not look like a status change). A paper's
  first snapshot is only a baseline. Classifying the difference (including
  the DOAJ rule that a delisting needs the same journal source) is
  Research Evaluation's job; see CONTRACTS.md.
- **Week 7 scope:** the changes above. Citation counts are stored but not
  alerted on. Detecting newly-appearing related papers is explicitly **out
  of scope** for week 7 (team decision — see DECISIONS.md).
- **Handoff to Research Evaluation (a nudge, not a payload):** the flow
  for one poll is: fetch from the APIs → store the snapshot in Storage
  Management → compare with the previous snapshot → if it differs,
  `POST /evaluate/changes` to Research Evaluation with the changed
  `paper_ids`. A poll with no changes sends nothing. No snapshot data is
  passed; Research Evaluation reads it, and works out the differences,
  from Storage Management. Updating keeps a `nudge_pending` flag per paper
  in `tracked_papers`, cleared once Research Evaluation accepted the nudge,
  so if it was down the next poll re-sends those paper ids (the next
  snapshot would otherwise look unchanged). Zero LLM calls in Updating.
- **Updating's own data:** `tracked_papers` (`paper_id`, `doi`,
  `last_snapshot_id`, `nudge_pending`) and `poll_runs` (a summary per
  run). There are no change records; the snapshots in Storage Management
  are the only history.
- **Endpoints:** `POST /run-poll`.

## Section 5 — Deployment

Owns getting every service (and GROBID) reachable from the public
internet.

- **Containerisation:** one Dockerfile per service, composed via Docker
  Compose for local dev and as the deployable unit. Research Evaluation
  and Updating share a single image (see `backend/`).
- **Cloud target:** a single VM running the whole Compose stack.
- **Data services:** Postgres via Supabase free tier.
- **Config/secrets:** environment variables per service, never committed.
- **CI:** GitHub Actions builds each service's image on merge to `main`
  and redeploys to the VM.
