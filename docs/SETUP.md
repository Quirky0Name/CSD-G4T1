# Setup

Accounts, keys and env vars, and how to run each service locally, alone or
together. Ports: Research Evaluation 8000, Updating 8001, GROBID 8070,
Storage Management 8081, Postgres 5432 (backend) and 5433 (Storage
Management in compose), frontend 5173.

## Accounts and API keys

| Service | Needed for | Notes |
|---|---|---|
| **Gemini** | impact's LLM (Research Evaluation) | aistudio.google.com → "Get API key"; the free tier is enough (about 20 requests per model per day). Without it impact doesn't run |
| **Telegram** | the notification when a report is assessed (Research Evaluation) | Optional; see "Telegram bot" below |
| **OpenAlex** | retraction status, DOAJ, journal, authors (Updating); metadata and open-access PDF links (Storage Management) | Free account and key; keyless calls get 1/10 of the $1/day budget. Passed as the `api_key` query param. The two services share the budget if they use one key, so Updating fetches each DOI once per poll |
| **Crossref** | notices (Updating), metadata (Storage Management), each notice's record (Research Evaluation) | No key. Set a contact email as `CROSSREF_MAILTO` for the polite pool |
| **Semantic Scholar** | open-access PDF links when Storage Management tracks by DOI | Optional key (`S2_API_KEY`, header `x-api-key`); keyless calls hit 429s more often |
| **GROBID** | DOI and title from uploaded PDFs (Storage Management) | No key, self-hosted: `grobid/grobid:0.9.1-crf` (~500 MB) |
| **Europe PMC** | open-access notice text (Research Evaluation) | No key |

## Software

- Python: none to install. `uv` downloads the pinned 3.13 on `uv sync`
  (on Windows run it as `py -m uv`).
- Java: JDK 25 for Storage Management outside Docker (`./mvnw` downloads
  Maven).
- Docker, for Postgres, GROBID and the compose stacks.
- Node, for the frontend.

## Env vars

`backend/.env` (copy `backend/.env.example`; never commit it). Keep
comments on their own line: Docker Compose's env-file parser bakes inline
comments into the value.

| Var | Read by | Value |
|---|---|---|
| `JWT_SECRET` | every service | base64 of 32+ random bytes (`openssl rand -base64 32`), the same everywhere. Storage Management's JWT library rejects shorter keys; the Python apps check at startup |
| `SM_BASE_URL` | RE, Updating | `http://localhost:8081` (compose overrides it) |
| `RE_BASE_URL` | Updating | `http://localhost:8000` |
| `DATABASE_URL` | Updating | `postgresql+asyncpg://dev:dev@localhost:5432/research_assistant` (the compose Postgres), or `sqlite+aiosqlite:///./updating.sqlite3` with no Postgres |
| `OPENALEX_API_KEY` | Updating (and Storage Management) | openalex.org |
| `CROSSREF_MAILTO` | Updating, RE (and Storage Management) | a team contact email; empty sends none |
| `POLL_INTERVAL_HOURS` | Updating | `24` |
| `EVALUATION_SNAPSHOT_WINDOW` | RE | `5`, at least `2`: how many newest snapshots each nudge compares. A change is lost after N − 2 failed nudges in a row; raise it (e.g. `30`) before deployment |
| `INVESTIGATION_PDF_TIMEOUT_SECONDS` | RE | `120`: how long investigation waits for Storage Management to store a document (it downloads the PDF first) |
| `GEMINI_API_KEY` | RE | aistudio.google.com. Optional: the service starts without it, impact doesn't run |
| `GEMINI_MODEL` | RE | `gemini-flash-latest` |
| `GEMINI_FALLBACK_MODELS` | RE | `gemini-flash-lite-latest`: comma-separated models tried in order when a call fails; empty for none |
| `IMPACT_LLM_TIMEOUT_SECONDS` | RE | `120`, per Gemini call |
| `TELEGRAM_BOT_TOKEN`, `NOTIFY_TELEGRAM_CHAT_ID` | RE | the bot and the one chat that gets every notification; off unless both are set |

`backend/.env.example` still lists `S2_API_KEY`, `GROBID_URL`,
`LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL` and `CACHE_MAX_ENTRIES`: no
Python app reads them (see ROADMAP.md).

`storage/.env` (gitignored), for Storage Management: `JWT_SECRET` (the same
value), and optionally `CROSSREF_MAILTO`, `OPENALEX_API_KEY`, `S2_API_KEY`.
Outside compose it also reads `SPRING_DATASOURCE_URL`, `_USERNAME`,
`_PASSWORD`, `GROBID_URL` (default `http://localhost:8070`), `UPLOAD_DIR`
(default `uploads/` in the working directory) and `PORT` (default 8081).

## Running everything in Docker

Every service but the frontend:

```
cd backend && docker compose -f docker-compose.dev.yml up -d --build
cd ../storage && docker compose up -d --build
```

- `backend/docker-compose.dev.yml`: GROBID, Postgres (Updating's),
  Research Evaluation, Updating. Both apps need only `JWT_SECRET` to boot;
  the compose file sets `DATABASE_URL` and `SM_BASE_URL`
  (`host.docker.internal:8081`, with the Linux `extra_hosts`).
- `storage/docker-compose.yml`: Storage Management and its Postgres (host
  port 5433). It uses the backend's GROBID on 8070 (`docker compose up
  grobid` in `backend/` for just that); without GROBID, uploads save
  without a DOI. PDFs and the database are Docker volumes.
- `down` stops and keeps the data; `down -v` also wipes the databases and
  the stored PDFs (also the fix for any migration error).

A poll through the real services:

```
TOKEN=$(uv run python -c "import base64, uuid, jwt; from dotenv import dotenv_values; print(jwt.encode({'sub': str(uuid.uuid4())}, base64.b64decode(dotenv_values('.env')['JWT_SECRET']), algorithm='HS256'))")
curl -X POST localhost:8081/papers -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{"doi": "10.1371/journal.pmed.0020124"}'
curl -X POST localhost:8001/run-poll
```

The first poll only stores a baseline (`stored`, `nudged` empty). Many
publishers block the open-access download (ScienceDirect; JBC behind a
Cloudflare check), so tracking those by DOI fails with `422`; PLOS works.

## Storage Management without Docker

```
docker run -d --name storage-db -p 5432:5432 -e POSTGRES_DB=storage -e POSTGRES_USER=storage -e POSTGRES_PASSWORD=storage postgres:17
```

Set `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/storage`,
`SPRING_DATASOURCE_USERNAME=storage`, `SPRING_DATASOURCE_PASSWORD=storage`
and `JWT_SECRET`, then from `storage/`: `./mvnw spring-boot:run`
(`.\mvnw.cmd spring-boot:run` on Windows). Flyway creates the tables. This
Postgres and the backend's both want host port 5432: run one, or remap.

In PowerShell, set vars as `$env:JWT_SECRET="..."`; a secret:

```
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

**Resetting the local database:** if Storage Management won't start with a
Flyway error ("applied migration not resolved locally", or a validation
failure on V4: migrations were tidied on 2026-09-26/27), it's test data:
`docker rm -f storage-db`, run the container again, and empty the upload
folder. Always reset the database and the upload folder together.

Swagger UI: `http://localhost:8081/swagger-ui.html` (Authorize with a user
token, or a service token for `/internal`).

## A demo user token

There's no User Management, so make one "demo user" token and use it for
everything; whatever you create belongs to that user. It only works
against services with the same `JWT_SECRET`. Don't commit it.

PowerShell, with `$env:JWT_SECRET` set (lasts 30 days; change `sub` for
another user):

```
$key = [Convert]::FromBase64String($env:JWT_SECRET)
function Url64($b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
$exp = [DateTimeOffset]::UtcNow.AddDays(30).ToUnixTimeSeconds()
$head = Url64 ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$body = Url64 ([Text.Encoding]::UTF8.GetBytes("{`"sub`":`"11111111-1111-1111-1111-111111111111`",`"exp`":$exp}"))
$sig = Url64 ([Security.Cryptography.HMACSHA256]::new($key).ComputeHash([Text.Encoding]::ASCII.GetBytes("$head.$body")))
"$head.$body.$sig"
```

Linux/macOS, from `backend/`, with `JWT_SECRET` exported:

```
uv run python -c "import base64, os, time, jwt; print(jwt.encode({'sub': '11111111-1111-1111-1111-111111111111', 'exp': int(time.time()) + 30 * 86400}, base64.b64decode(os.environ['JWT_SECRET']), algorithm='HS256'))"
```

## Frontend

From `frontend/csd-frontend-vite/`: copy `.env.example` to `.env.local`
and set `VITE_DEMO_TOKEN` to a demo user token, then `npm install` and
`npm run dev`. Storage Management and Updating send no CORS headers, so
`vite.config.ts` proxies `/api` → `localhost:8081` and `/updating` →
`localhost:8001`. `VITE_` values are baked into the built JavaScript:
fine for a local demo token, never for a real secret.

## Telegram bot

1. Message **@BotFather**, `/newbot`, pick a name and username; it answers
   with the token (`123456:ABC...`): `TELEGRAM_BOT_TOKEN`.
2. Send your bot any message (a bot can only write to someone who wrote to
   it first; otherwise Telegram answers `403`).
3. Open `https://api.telegram.org/bot<token>/getUpdates`; the chat id is
   `result[0].message.chat.id`: `NOTIFY_TELEGRAM_CHAT_ID`. For a group, add
   the bot, send a message there, and use the group's (negative) id.
4. Check it from `backend/`:
   `uv run pytest -m live tests/research_evaluation/test_notify.py` sends
   one sample message.

## Updating alone, against the stubs

`backend/dev/stub_storage.py` stands in for Storage Management (in memory,
checking the service token like the real one) and
`backend/dev/stub_research_evaluation.py` for Research Evaluation (checks
the token, records nudges). Both read `JWT_SECRET` from the environment.
Run Updating as **one process** (one uvicorn worker).

```
cd backend
uv sync
cp .env.example .env    # JWT_SECRET, DATABASE_URL; POLL_INTERVAL_HOURS=0.01 for a quick loop

uv run --env-file .env uvicorn dev.stub_storage:create_app --factory --port 8081
uv run --env-file .env uvicorn dev.stub_research_evaluation:create_app --factory --port 8000
uv run uvicorn updating.main:app --port 8001

curl -X POST localhost:8081/dev/papers -H 'content-type: application/json' \
  -d '{"doi": "10.1016/j.ijantimicag.2020.105949"}'
curl -X POST localhost:8081/dev/reset           # forget everything
```

Snapshots: `GET /internal/papers/{id}/background-info/history` on the stub
(service token). Poll summaries: Updating's `poll_runs` table. Nudges:
`GET localhost:8000/dev/received`; `POST localhost:8000/dev/fail`
(`?on=false` to stop) makes the stub refuse them, to see a re-send. To run
the real Research Evaluation against the stub Storage Management instead,
start it with `uv run --env-file .env uvicorn research_evaluation.main:app --port 8000`
(the stub's `/dev/papers/{id}/pdf`, `/dev/papers/{id}/research-paper` and
`/dev/pdfs` set the PDFs it serves).

### Mock harness (seeded scenarios)

To see a nudge without waiting for a real change, seed a paper whose
"before" snapshot differs from what the APIs say now:
`POST localhost:8081/dev/seed?scenario=<name>` on the stub, then
`POST /run-poll` with its `paper_id` (Swagger at `localhost:8001/docs`).

| `scenario` | Paper | Expected nudge |
|---|---|---|
| `openalex_retraction` | IJAA | `is_retracted false -> true` |
| `crossref_retraction` | IJAA | a new `retraction` notice |
| `corrections` | Lancet | new `correction`, `erratum`, `expression_of_concern` notices |
| `doaj_delisting` | Lancet | `in_doaj true -> false` |
| `no_change` | JBC | none |
| `no_doi` | none | none: `skipped_no_doi` |

**Order matters:** start the stubs and Updating, then seed, then trigger,
with the default 24 h interval (a poll in between takes the nudge; on a
fresh database Updating polls once at startup). The reason is in
Updating's log (`paper <id>: snapshot N changed since M: …`).

## Tests

```
cd backend
uv run pytest                 # everything except live; no keys, Docker or Postgres
uv run pytest -m live         # hits real Crossref/OpenAlex/Europe PMC/Gemini/Telegram
uv run ruff check
uv run pytest tests/updating/test_scenarios.py   # the mock harness on recorded responses

cd storage
./mvnw test                   # H2, network mocked

cd frontend/csd-frontend-vite
npm run build && npm run lint
```
