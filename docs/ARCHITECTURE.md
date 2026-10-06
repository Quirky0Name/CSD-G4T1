# Architecture

The system map: what each service owns, how data flows between them, and
the cross-cutting rules. How each service works inside, where its code
lives and its gotchas are in its own doc:
[STORAGE.md](STORAGE.md), [EVALUATION.md](EVALUATION.md),
[UPDATING.md](UPDATING.md). Endpoint shapes are in
[CONTRACTS.md](CONTRACTS.md), the reasoning in [DECISIONS.md](DECISIONS.md),
and what isn't built in [ROADMAP.md](ROADMAP.md).

## Overview

The core loop: **detect a meaningful change in a tracked paper → assess
its impact on the researcher's own draft → recommend an action → the
researcher decides.** That reframes the original pitch after the
professor's review: the module theme is measuring change in a landscape,
not issuing a one-time trust verdict. Background-info checking
(retraction status, journal, authors) is the raw signal layer the loop
reads from, not a feature on its own.

### Week 7 (midterm) scope: what's built

- Track a paper by PDF upload or by DOI; Storage Management keeps its PDF.
- The researcher's own paper (their draft), one per project.
- Updating polls Crossref and OpenAlex on a schedule, stores a snapshot
  per paper per poll, and nudges Research Evaluation when a snapshot
  differs in a field the alerts depend on.
- Research Evaluation detects the changes (retraction, correction,
  erratum, expression of concern, DOAJ delisting, other Crossref notices)
  and stores each as an **alert** with a rule-based severity.
- It then **investigates** them (fetches each notice's Crossref record and
  open-access text, the paper's current copy, a new version) into a
  **report**, and Gemini judges the report's **impact** on the
  researcher's draft (change severity, impact level, evaluation,
  recommendation).
- A Telegram message to one hard-coded chat when a report is assessed.
- The frontend: tracked papers, alerts (acknowledge, dismiss, notes),
  reports, the draft upload, a manual poll, deleting a paper.
- Demo tools in Storage Management to replay a change on a real paper.

Everything else from the original plan (User Management, notes per paper,
stance and claims checks, GROBID COI text, citation metrics, deployment,
new related papers, topic discovery) is in [ROADMAP.md](ROADMAP.md).

## Services

| Service | Stack | Owns | Folder |
|---|---|---|---|
| User Management | Spring Boot (planned) | `users`, `folders`, login and JWTs. **Not built**: a hand-made demo token stands in (SETUP.md) | — |
| Frontend | React + TypeScript (Vite, Tailwind) | the researcher's UI; calls Storage Management and Updating through the Vite proxy | `frontend/csd-frontend-vite/` |
| Storage Management | Java 25 + Spring Boot, Postgres, Flyway | all persistence: papers and their PDFs, snapshots, alerts and notes, reports and documents, research papers | `storage/` |
| Research Evaluation | Python 3.13, FastAPI | detecting and judging changes (detection, rules, investigation, impact, notification); no database of its own | `backend/src/research_evaluation/` |
| Updating | Python 3.13, FastAPI, APScheduler | the polling job and its small state (`tracked_papers`, `poll_runs`) in its own schema | `backend/src/updating/` |

Research Evaluation and Updating share one Python project (`backend/`, one
`pyproject.toml`, `common/` for the DOI normaliser and the service-token
auth) but are separate apps with a Dockerfile each. Neither imports the
other's package.

Rubric note: Java + Spring Boot for at least one component is satisfied by
Storage Management.

## Data flow

```
Frontend ──/api──▶ Storage Management ◀──/internal── Research Evaluation ──▶ Crossref, Europe PMC, Gemini, Telegram
   │                  │  ▲        │                        ▲
   │                  │  │        └─▶ GROBID, Crossref,    │ POST /evaluate/changes (paper ids)
   │                  ▼  │            OpenAlex, S2          │
   │              Postgres + PDF folder                     │
   └──/updating──▶ Updating ──/internal (snapshots)──────────┘
                     └─▶ Crossref, OpenAlex
```

One poll, end to end:

1. **Updating** lists tracked papers (`GET /internal/papers`), fetches each
   DOI once from Crossref and OpenAlex, stores a snapshot per paper in
   Storage Management, and compares it with the paper's previous one.
2. If it differs in a nudge field, Updating sends the changed paper ids to
   `POST /evaluate/changes`. A nudge carries ids only, never data.
3. **Research Evaluation** reads the paper's newest N snapshots, detects
   the changes, skips those already stored (by change key), gives the rest
   a rule-based assessment and stores them as alerts. Then it replies:
   `202`, or `503` so Updating re-sends next poll.
4. After the reply, in the background: **investigation** opens a report
   grouping the paper's new alerts and stores what it fetched as the
   report's documents (Storage Management downloads their PDFs); then
   **impact** asks Gemini about the report and stores its evaluation (the
   report becomes `assessed`); then **notify** sends the Telegram message.
5. The **frontend** reads alerts and reports from Storage Management; the
   researcher acknowledges, dismisses or adds notes.

Rules that hold across the flow:

- **Updating and Research Evaluation share data only through Storage
  Management.** Updating records snapshots, never changes; Research
  Evaluation works out the differences itself.
- **Only Updating calls Research Evaluation** (the nudge), plus a manual
  `POST /evaluate/reports` with a service token. Storage Management and
  the frontend never call it.
- **Storage Management fetches nothing on its own** except what ingest and
  document storage need (GROBID, Crossref/OpenAlex metadata, open-access
  PDF downloads). It stores what it's sent.
- **Detection is deterministic.** Whether a paper was retracted never
  depends on an LLM; alerts keep their rule-based severity, and Gemini's
  judgment sits on the report next to them.
- **Background work never raises and is never retried.** A failure is
  logged and leaves the report where it was (ROADMAP.md lists the gaps).
- **Third-party text is data.** Notice text and PDFs go to Gemini tagged
  as documents, and nothing acts on them.

## Auth

- One JWT (HS256, `sub` = user id, `exp`), meant to be issued by User
  Management at login. Every service validates the signature itself with
  the shared `JWT_SECRET` (base64 of 32+ bytes, decoded before use); none
  calls back to User Management.
- **Service tokens:** Updating (`svc:updating`) and Research Evaluation
  (`svc:research-evaluation`) mint short-lived tokens with `role=service`
  (`common/service_token.py`). Storage Management's `/internal/**` takes
  only service tokens; everything else takes only user tokens.
  `POST /evaluate/changes` and `/evaluate/reports` take any service token.
- **Sprint 1:** no User Management, so one hand-made "demo user" token is
  used for everything, and Updating's `POST /run-poll` takes no token.

## Folders and projects

A folder is one research project. Folders will belong to User Management;
Storage Management holds only a bare `folder_id` with no FK and no check.
`folder_id` null is the user's "no folder" project. Storage Management
keys a project by (owner, `folder_id`), so the same folder id from two
users is two projects. A tracked paper is in exactly one project, and its
project's research paper is the draft impact reads. Details in
CONTRACTS.md, "Folders and projects".

## Data model

Storage Management's Postgres (Flyway, `storage/src/main/resources/db/migration/`;
diagram: [erd/erd-flyway-V8.html](erd/erd-flyway-V8.html)):

| Table | One row per | Migration |
|---|---|---|
| `papers` | user tracking a paper (owner, folder, DOI, metadata at ingest, PDF `file_key`) | V1 |
| `alerts` | detected change on a paper (type, `change_key`, rule-based severity and text, the researcher's status, `report_id`) | V3, V7 |
| `background_metadata` | snapshot Updating sent (insert-only, full history per paper) | V4 |
| `alert_notes` | note in the researcher's log on an alert | V5 |
| `research_papers` | project's draft PDF | V6 |
| `reports` | nudge that stored new alerts for a paper (status, impact's evaluation) | V7, V8 |
| `report_documents` | DOI fetched for a report (notice, new version, current copy) | V7 |

Everything referring to a paper is deleted with it. PDFs are files in
`UPLOAD_DIR`, never in Postgres.

Updating's own schema (`updating`, Alembic): `tracked_papers` (`paper_id`,
`doi`, `last_snapshot_id`, `nudge_pending`) and `poll_runs`.

## Deployment

What exists: one Dockerfile per service (`storage/Dockerfile`,
`backend/research-evaluation.Dockerfile`, `backend/updating.Dockerfile`)
and two compose files that run every service but the frontend locally
(SETUP.md). CI (`.github/workflows/`) is a Trivy vulnerability scan on
PRs, pushes to `main` and weekly, plus Telegram messages when a PR is
opened or approved. Nothing is hosted; the planned target (one VM running
the compose stack, Postgres on Supabase) is in ROADMAP.md.
