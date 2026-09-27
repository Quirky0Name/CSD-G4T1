# Research Assistant Project

A tool for researchers to track papers over time. The core loop: **detect a
meaningful change in a tracked paper → assess its impact → recommend an
action → the researcher decides.**

Five services sit behind one React frontend and talk to each other over
REST, trusting a single JWT issued at login. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

## Where to look

| Doc | What's in it |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Services, ownership, data flow, and the week-7 vs week-13 scope split |
| [docs/CONTRACTS.md](docs/CONTRACTS.md) | Cross-service API contracts: endpoints, request/response shapes, auth |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Dated log of design decisions and why they were made |
| [docs/SETUP.md](docs/SETUP.md) | Accounts, API keys, software, and how to run the stack locally |
| [docs/DEMO.md](docs/DEMO.md) | The week-7 demo runbook |
| [docs/LOCAL_STORAGE_DB.md](docs/LOCAL_STORAGE_DB.md) | Running Storage Management, its Postgres and its PDF folder locally |
| [docs/EVALUATION-REVIEW-CHANGES.md](docs/EVALUATION-REVIEW-CHANGES.md) | The "seeing and reviewing paper changes" story: alerts, how Research Evaluation detects and assesses changes, and TODOs for other owners |
| [docs/EVALUATION-INVESTIGATION.md](docs/EVALUATION-INVESTIGATION.md) | Investigation: grouping a nudge's new alerts into a report and fetching each change's notice and new paper into it, with the codebase context and what's left for later sprints |
| [docs/RE-changes-explained.md](docs/RE-changes-explained.md) | Research behind investigation: every way each detected change shows up in real life, with real examples, and what can be fetched for it |
| [docs/STORAGE-USER-RESEARCH-PAPER.md](docs/STORAGE-USER-RESEARCH-PAPER.md) | The researcher's own paper, one per project (folder, or "no folder"): how it's stored and replaced, where the code lives, and gotchas |
| [docs/STORAGE-USER-TRACKED-PDF.md](docs/STORAGE-USER-TRACKED-PDF.md) | Tracked papers' stored PDFs: how both ingest paths keep them, how Research Evaluation reads them, and what else it can read from Storage Management |
| [docs/STORAGE-USER-REPORTS.md](docs/STORAGE-USER-REPORTS.md) | A paper's reports for the frontend (`GET /papers/{id}/reports`): what's shown and hidden, how documents match alerts, where the code lives, and gotchas |

## Services

| Service | Stack | Folder |
|---|---|---|
| User Management (+ frontend) | Spring Boot + React | `frontend/` |

| Storage Management | Java + Spring Boot | `storage/` |
| Research Evaluation | Python | `backend/` |
| Updating | Python | `backend/` |

Research Evaluation and Updating share one Python project in `backend/`
(two FastAPI apps, a Dockerfile each) — see `backend/` for details.

## Status

Week 7 (midterm) scope is in progress. See docs/ARCHITECTURE.md for what's in
and out of scope for this milestone.