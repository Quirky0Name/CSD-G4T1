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
| **OpenAlex** | retraction status, citation counts, authors, citation-neighbourhood metrics, DOAJ-membership flag | Create a free account and key. A key is now required for the full $1/day free-usage budget (keyless calls get 1/10 of that). Passed as the `api_key` query param. |
| **Crossref** | retraction/correction notices, canonical metadata | No key needed. Pick a contact email for the `mailto` polite-pool parameter (`CROSSREF_MAILTO`) — improves rate limits, doesn't require registration. |
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

- Get a Supabase connection string for Updating's `updating` Postgres
  schema. Use the **session pooler** string (port 5432) — the
  transaction pooler breaks asyncpg's prepared statements.
- Confirm the `JWT_SECRET` format with the User Management owner (see
  [CONTRACTS.md](CONTRACTS.md) — base64 of 32+ random bytes, every
  service decodes it the same way).
- Confirm the Storage Management schema changes and new internal
  endpoints in CONTRACTS.md with that owner.
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
| `JWT_SECRET` | shared with User Management, base64-encoded |
| `DATABASE_URL` | Supabase session-pooler connection string |
| `SM_BASE_URL` | Storage Management's running URL |
| `RE_BASE_URL` | Research Evaluation's running URL |
| `GROBID_URL` | `http://grobid:8070` in Docker Compose |
| `ADMIN_API_KEY` | any value you pick, for `/admin/run-poll` |
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
