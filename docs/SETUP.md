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
- `JWT_SECRET` is any shared placeholder in sprint 1, since there's no User
  Management yet. Use the same value in Storage Management's local run and
  in `backend/.env`. When real logins arrive, agree the format (see
  [CONTRACTS.md](CONTRACTS.md) — base64 of 32+ random bytes, every
  service decodes it the same way).
- Storage Management runs on `localhost:8081` by default (`PORT` overrides
  it). A containerised Updating reaches it at `http://host.docker.internal:8081`; on Linux that also
  needs `extra_hosts: ["host.docker.internal:host-gateway"]`. Storage
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
| `JWT_SECRET` | any shared placeholder in sprint 1 (base64-encoded once User Management exists) |
| `DATABASE_URL` | local Postgres in sprint 1; Supabase session-pooler connection string once hosted |
| `SM_BASE_URL` | Storage Management's running URL |
| `RE_BASE_URL` | Research Evaluation's running URL |
| `GROBID_URL` | `http://grobid:8070` in Docker Compose |
| `ADMIN_API_KEY` | any value you pick and share with the team, for `/admin/run-poll` |
| `POLL_INTERVAL_HOURS` | `24` (default) |
| `CACHE_MAX_ENTRIES` | `5000` (default) |

## Scaffolding only (no keys needed)

To just bring up the empty skeleton and confirm it boots:

```
cd backend
docker compose -f docker-compose.dev.yml up
```

This starts GROBID, Postgres, and both FastAPI apps with no routes wired
up yet — enough to confirm the containers build and start. See
`ARCHITECTURE.md` and the plan for the full build order.
