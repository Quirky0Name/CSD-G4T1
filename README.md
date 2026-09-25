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

## Services

| Service | Stack | Folder |
|---|---|---|
| User Management (+ frontend) | Spring Boot + React | `frontend/` |

| Storage Management | Java + Spring Boot | `storage/` |
| Research Evaluation | Python | `backend/` |
| Updating | Python | `backend/` |

Research Evaluation and Updating share one Python project in `backend/`
(two FastAPI apps, one image) — see `backend/` for details.

## Status

Week 7 (midterm) scope is in progress. See docs/ARCHITECTURE.md for what's in
and out of scope for this milestone.