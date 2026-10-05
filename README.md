# Research Assistant Project

A tool for researchers to track papers over time. The core loop: **detect a
meaningful change in a tracked paper → assess its impact → recommend an
action → the researcher decides.**

The services sit behind one React frontend and talk to each other over
REST, trusting a single JWT (issued at login once User Management exists;
a demo token stands in for now). See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

## Where to look

| Doc | What's in it |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | The system map: week-7 scope, services and ownership, data flow, auth, folders and projects, data model, deployment |
| [docs/STORAGE.md](docs/STORAGE.md) | Storage Management inside: papers and PDFs, drafts, snapshots, alerts and notes, reports and documents, demo tools, code layout, gotchas |
| [docs/EVALUATION.md](docs/EVALUATION.md) | Research Evaluation inside: detection and rules, investigation, Gemini impact and its fallback, the Telegram notification, code layout, gotchas |
| [docs/UPDATING.md](docs/UPDATING.md) | Updating inside: the poll, the outage gate, when it nudges, the schedule, snapshots, code layout, gotchas |
| [docs/CONTRACTS.md](docs/CONTRACTS.md) | Every endpoint between services and the frontend: request and response shapes, errors, auth |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Dated log of design decisions and why they were made |
| [docs/SETUP.md](docs/SETUP.md) | Keys, env vars, running each service (Docker, local, stubs, mock harness), demo token, Telegram bot, tests |
| [docs/DEMO.md](docs/DEMO.md) | The week-7 demo runbook |
| [docs/ROADMAP.md](docs/ROADMAP.md) | What isn't built yet and known gaps |
| [docs/RE-changes-explained.md](docs/RE-changes-explained.md) | Research: every way each detected change shows up in real life, with real examples (the R1, C6, … case codes) |
| [docs/architecture.html](docs/architecture.html) | The service architecture as a diagram page (open in a browser) |
| [docs/erd/erd-flyway-V8.html](docs/erd/erd-flyway-V8.html) | Storage Management's schema as an ERD, generated from the Flyway migrations up to V8 |

## Services

| Service | Stack | Folder |
|---|---|---|
| Frontend | React + TypeScript (Vite) | `frontend/csd-frontend-vite/` |
| Storage Management | Java + Spring Boot | `storage/` |
| Research Evaluation | Python (FastAPI) | `backend/` |
| Updating | Python (FastAPI) | `backend/` |
| User Management | Spring Boot (not built, see docs/ROADMAP.md) | — |

Research Evaluation and Updating share one Python project in `backend/`
(two FastAPI apps, a Dockerfile each).

## Status

Week 7 (midterm) scope is built (docs/ARCHITECTURE.md); what's left is in
docs/ROADMAP.md.