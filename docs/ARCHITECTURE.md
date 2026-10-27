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
not issuing a one-time trust verdict. The snapshots (retraction status,
journal, authors) are the raw signal layer the loop reads from, not a
feature on their own.

### Week 7 (midterm) scope: what's built

- Track a paper by PDF upload or by DOI; Storage Management keeps its PDF.
- The researcher's draft, one per project.
- Updating polls Crossref and OpenAlex on a schedule, stores a snapshot
  per paper per poll, and nudges Research Evaluation when a snapshot
  differs in a field the alerts depend on.
- Research Evaluation detects the changes (retraction, correction,
  erratum, expression of concern, DOAJ delisting, citation jump, other
  Crossref notices)
  and stores each as an **alert** with a rule-based alert severity.
- It then **investigates** them (fetches each notice's Crossref record and
  open-access text, the paper's current copy, a new version, and
  documents reached by following links into the paper's history) into a
  **report**, and Gemini judges the report's **impact** on the
  researcher's draft (change severity, impact level, evaluation,
  recommendation).
- A Telegram message to one hard-coded chat when a report is assessed.
- The frontend: tracked papers, alerts (acknowledge, dismiss),
  reports, the draft upload, a manual poll, deleting a paper.
- Demo tools in Storage Management to replay a change on a real paper.

Everything else from the original plan (stance and claims checks, GROBID COI text, citation metrics, deployment, new related papers,
topic discovery) is in [ROADMAP.md](ROADMAP.md).

## Services

| Service | Stack | Owns | Folder |
|---|---|---|---|
| User Management | Java 25 + Spring Boot, Postgres, Flyway | `users` and `projects`: register, login and the JWTs, each researcher's projects, and deleting an account | `users/` |
| Frontend | React + TypeScript (Vite, Tailwind) | the researcher's UI; calls the services at `/api`, `/auth`, `/projects` and `/updating` on its own address (the Vite proxy in development, Caddy in the root stack) | `frontend/csd-frontend-vite/` |
| Storage Management | Java 25 + Spring Boot, Postgres, Flyway | all persistence: papers and their PDFs, snapshots, alerts, reports and documents, drafts | `storage/` |
| Research Evaluation | Python 3.13, FastAPI | detecting and judging changes (detection, rules, investigation, impact, notification); no database of its own | `backend/src/research_evaluation/` |
| Updating | Python 3.13, FastAPI, APScheduler | the polling job and its small state (`tracked_papers`, `polls`) in its own schema | `backend/src/updating/` |

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
   researcher acknowledges or dismisses them.

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
  depends on an LLM; alerts keep their rule-based alert severity, and Gemini's
  judgment sits on the report next to them.
- **Background work never raises and is never retried.** A failure is
  logged and leaves the report where it was (ROADMAP.md lists the gaps).
- **Third-party text is data.** Notice text and PDFs go to Gemini tagged
  as documents, and nothing acts on them.

## Auth

- One JWT (HS256, `sub` = user id, `exp`), issued by User Management at
  register and login. Every service validates the signature itself with
  the shared `JWT_SECRET` (base64 of 32+ bytes, decoded before use); none
  calls back to User Management.
- **Service tokens:** Updating (`svc:updating`) and Research Evaluation
  (`svc:research-evaluation`) mint short-lived tokens with `role=service`
  (`common/service_token.py`), and so does User Management (`svc:users`)
  to clear a deleted project or account. Storage Management's `/internal/**` takes
  only service tokens; everything else takes only user tokens.
  `POST /evaluate/changes` and `/evaluate/reports` take any service token.
- **Deleting an account** (`DELETE /auth/me`, with the password) has
  Storage Management delete everything the user owns first
  (`DELETE /internal/users/{id}`), then deletes the user and, through the
  foreign key, their projects. Tokens aren't revoked, so one outlives its
  account by up to 24 hours.
- **For now** Updating's `POST /run-poll` takes no token (CG-99).

## Projects

Projects belong to User Management (`GET/POST/PUT/DELETE /projects`);
Storage Management holds only a bare `project_id` with no FK and no check.
`project_id` null is the user's default project, which has no row in User
Management. Storage Management keys a project by (owner, `project_id`), so
the same project id from two users is two projects. A tracked paper is in
exactly one project, and its project's draft is the one impact reads.
Deleting a project first has Storage Management delete its papers and
draft (`DELETE /internal/projects/{id}`); if that fails, the project stays
and can be deleted again. Details in CONTRACTS.md, "Projects".

## Data model

Storage Management's Postgres (Flyway, `storage/src/main/resources/db/migration/`;
diagram, up to V9: [diagrams/erd-flyway-V9.html](diagrams/erd-flyway-V9.html); V11 drops its
`alert_notes`):

| Table | One row per | Migration |
|---|---|---|
| `papers` | user tracking a paper (owner, project, DOI, metadata at ingest, PDF `file_key`) | V1, V9 |
| `alerts` | detected change on a paper (type, `change_key`, rule-based alert severity and text, the researcher's status, `report_id`) | V3, V7, V9 |
| `snapshots` | snapshot Updating sent (insert-only, the full snapshot history per paper) | V4, V9 |
| `drafts` | project's draft PDF | V6, V9 |
| `reports` | nudge that stored new alerts for a paper (status, impact's evaluation) | V7, V8 |
| `report_documents` | DOI fetched for a report (notice, new version, related document, current copy) | V7 |

Everything referring to a paper is deleted with it. PDFs are files in
`UPLOAD_DIR`, never in Postgres.

Updating's own schema (`updating`, Alembic): `tracked_papers` (`paper_id`,
`doi`, `last_snapshot_id`, `nudge_pending`) and `polls`.

User Management's own schema (`users`, Flyway, `users/src/main/resources/db/migration/`):
`users` (`id`, the JWT's `sub`; lowercase `email`, unique; BCrypt
`password_hash`; `created_at`) and `projects` (`id`; `owner_id`, the user's
id, deleted with the user; `name`, one of each per owner; `created_at`).

## Deployment

What exists: a Dockerfile per service (`storage/`, `users/`,
`backend/research-evaluation.Dockerfile`, `backend/updating.Dockerfile`)
and one for the website (`frontend/csd-frontend-vite/`: the built frontend
served by Caddy, which also passes `/api`, `/auth` and `/updating` on to
the services, so the browser talks to one address and no service needs
CORS). The root `docker-compose.yml` runs all of them with GROBID and one
shared Postgres, each service in its own schema, and publishes only the
web server (SETUP.md, "Running the whole stack"). The per-service compose
files stay for development. CI (`.github/workflows/`) is a Trivy
vulnerability scan on PRs, pushes to `main` and weekly, plus Telegram
messages when a PR is opened or approved. Nothing is hosted yet; the
target is one EC2 VM running the root compose stack, with the database
and PDF volumes on its disk (ROADMAP.md).
