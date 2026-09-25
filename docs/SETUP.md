# Setup

What you need before Research Evaluation / Updating can run for real
(anything calling an external API or the LLM). None of this is needed to
run the empty scaffolding — see the "Scaffolding only" section at the
bottom.

## Accounts and API keys

| Service | Needed for | Notes |
|---|---|---|
| **Semantic Scholar** | abstract/TL;DR/snippet lookups | Request a key through their API-key request form **first** — approval can take days. Keyless calls work meanwhile but hit frequent 429s. Header: `x-api-key`. |
| **DeepSeek** | claims + stance LLM calls | Create an account at platform.deepseek.com, top up a few USD (covers development and the demo many times over — calls cost well under 1¢ each), create an API key. |
| **OpenAlex** | retraction status, citation counts, authors, journal/DOAJ-membership flag (fetched by Updating and Storage Management), citation-neighbourhood metrics (Research Evaluation) | Create a free account and key. This is the only key you need to request for Updating. A key is now required for the full $1/day free-usage budget (keyless calls get 1/10 of that). Passed as the `api_key` query param. Storage Management and Updating share that budget if they use the same key, so Updating fetches each DOI once per poll. |
| **Crossref** | retraction/correction notices, canonical metadata (Updating, Storage Management) | No key needed. Pick a contact email for the `mailto` polite-pool parameter (`CROSSREF_MAILTO`) — improves rate limits, doesn't require registration. |
| **GROBID** | COI/funding text extraction, full text for the claims LLM prompt | No key — self-hosted via Docker. |

## Software

Checked on this machine (2026-09-18): Docker 29.8, Docker Compose 5.5, uv
0.12.5, git are all installed.

- **Docker daemon is stopped and disabled, and your user isn't in the
  `docker` group.** Run:
  ```
  sudo systemctl enable --now docker.socket
  sudo usermod -aG docker $USER
  ```
  then log out and back in for the group change to take effect.
- **No separate Python install needed.** uv downloads the pinned 3.13
  itself on `uv sync`; your system Python (3.14) is untouched.
- Optional, saves time later: `docker pull grobid/grobid:0.9.1-crf`
  (~500 MB).

## Team coordination

- **Sprint 1 runs locally.** Nothing is hosted yet, so `DATABASE_URL`
  points at a local Postgres (Updating's `updating` schema). When we host,
  get a Supabase connection string and use the **session pooler** string
  (port 5432) — the transaction pooler breaks asyncpg's prepared
  statements.
- `JWT_SECRET` must be **base64 of 32+ random bytes**, even in sprint 1 while
  there's no User Management: generate one with `openssl rand -base64 32`.
  Storage Management base64-decodes it and its JWT library rejects keys under
  32 bytes, so a made-up placeholder string can't work. Use the same value in Storage Management's local run and in
  `backend/.env`; Updating checks the format at startup. Every service decodes
  it the same way (see [CONTRACTS.md](CONTRACTS.md)).
- Storage Management runs on `localhost:8081` by default (`PORT` overrides
  it). A containerised Updating reaches it at `http://host.docker.internal:8081`
  (the compose file sets `SM_BASE_URL` and the Linux `extra_hosts` for it). Storage
  Management's local Postgres and the compose Postgres both bind host port
  5432, so run one or remap the other.
- Confirm the Storage Management snapshot endpoint and fields in
  CONTRACTS.md with that owner.
- Download the three demo PDFs by hand ahead of the demo (see
  [DEMO.md](DEMO.md)) for manual upload through Storage Management.

## Env vars (`backend/.env`, copy from `backend/.env.example`)

| Var | Where to get it |
|---|---|
| `OPENALEX_API_KEY` | openalex.org account |
| `S2_API_KEY` | Semantic Scholar API key request form |
| `LLM_API_KEY` | platform.deepseek.com |
| `LLM_MODEL` | `deepseek-flash` (default) |
| `LLM_BASE_URL` | `https://api.deepseek.com` |
| `CROSSREF_MAILTO` | any team contact email |
| `JWT_SECRET` | `openssl rand -base64 32`; the same value in every service (base64, 32+ bytes) |
| `DATABASE_URL` | local Postgres in sprint 1 (`postgresql+asyncpg://dev:dev@localhost:5432/research_assistant` for the compose Postgres), or `sqlite+aiosqlite:///./updating.sqlite3` with no Postgres; Supabase session-pooler connection string once hosted |
| `SM_BASE_URL` | Storage Management's running URL (`http://localhost:8081`); the stub in `backend/dev/` listens on the same port |
| `RE_BASE_URL` | Research Evaluation's running URL |
| `GROBID_URL` | `http://grobid:8070` in Docker Compose |
| `ADMIN_API_KEY` | any value you pick and share with the team, for `/admin/run-poll` |
| `POLL_INTERVAL_HOURS` | `24` (default) |
| `CACHE_MAX_ENTRIES` | `5000` (default) |

## Scaffolding only (no keys needed)

To just bring up the skeleton and confirm it boots (Updating needs `JWT_SECRET`
and `DATABASE_URL` in `backend/.env` to start; Research Evaluation is still an
empty stub):

```
cd backend
docker compose -f docker-compose.dev.yml up
```

This starts GROBID, Postgres, and both FastAPI apps. The containerised
Updating reaches Storage Management on the host at
`host.docker.internal:8081`. See `ARCHITECTURE.md` and the plan for the full
build order.

## Running Updating locally (stub Storage Management)

Storage Management's `/internal/**` endpoints don't exist yet (CG-68), so
`backend/dev/stub_storage.py` stands in for them: in memory, checking the
service token the way the real service does. Run Updating as **one process**
(one uvicorn worker); the scheduler doesn't coordinate across processes.

```
cd backend
uv sync
cp .env.example .env    # then fill in the values below
```

In `.env`: `JWT_SECRET` from `openssl rand -base64 32`, `DATABASE_URL` as in the
table above, and `POLL_INTERVAL_HOURS=0.01` for a quick loop (the first poll
runs at startup if none has run yet, then every interval; a restart doesn't
reset the timer).

```
# terminal 1: the stub on 8081 (it reads JWT_SECRET from the environment)
uv run --env-file .env uvicorn dev.stub_storage:create_app --factory --port 8081

# terminal 2: Updating on 8001
uv run uvicorn updating.main:app --port 8001

# terminal 3: seed papers (stub-only endpoints, no token needed)
curl -X POST localhost:8081/dev/papers -H 'content-type: application/json' \
  -d '{"doi": "10.1016/j.ijantimicag.2020.105949"}'
curl -X POST localhost:8081/dev/papers -H 'content-type: application/json' -d '{}'   # no DOI: skipped
curl -X POST localhost:8081/dev/reset                                                # forget everything
```

Each poll stores a snapshot per paper in the stub; read them back with a
service token from `GET /internal/papers/{id}/background-info/history`. The poll
summary is in Updating's `poll_runs` table.

Tests need no keys, Docker or Postgres (they use SQLite and the stub):

```
uv run pytest             # everything except live
uv run pytest -m live     # also hits the real Crossref/OpenAlex APIs, to catch drift
uv run ruff check
```
