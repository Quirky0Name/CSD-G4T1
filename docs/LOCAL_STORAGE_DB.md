# Storage Management

> **Ran Storage Management locally before 2026-09-26?** Reset your local
> database once: `docker rm -f storage-db`, then run the `docker run` below
> again and empty your upload folder. The migrations were tidied, and an
> old database won't start without the reset. See
> [Resetting your local database](#resetting-your-local-database).

## Running with Docker (quickest)

`storage/docker-compose.yml` runs Storage Management and its own Postgres
in containers, with nothing else to install. Put `JWT_SECRET=...` (the
same value as `backend/.env`) in `storage/.env`, which is gitignored, then
from `storage/`:

```
docker compose up --build
```

- Storage Management is on `localhost:8081`; its Postgres is on host port
  5433, so it doesn't clash with the backend's on 5432.
- GROBID isn't started here: it uses the one `backend/docker-compose.dev.yml`
  runs on 8070 (`docker compose up grobid` in `backend/` for just that).
  Without GROBID, uploads still save, just without a DOI.
- Stored PDFs and the database live in Docker volumes, so they survive
  restarts. `docker compose down -v` wipes both together, which is also
  the reset if a migration error ever stops it starting.
- `CROSSREF_MAILTO`, `OPENALEX_API_KEY` and `S2_API_KEY` are optional and
  also go in `storage/.env`.

The steps below run it without Docker for Storage Management itself.

## Running locally

You need Docker (for Postgres) and JDK 25.

Start Postgres:

```
docker run -d --name storage-db -p 5432:5432 -e POSTGRES_DB=storage -e POSTGRES_USER=storage -e POSTGRES_PASSWORD=storage postgres:17
```

Set these env vars:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/storage
SPRING_DATASOURCE_USERNAME=storage
SPRING_DATASOURCE_PASSWORD=storage
JWT_SECRET=<your own value, don't commit it>
```

`JWT_SECRET` must be base64 of 32 or more random bytes, or Storage
Management won't start. Make one with `openssl rand -base64 32`, or in
PowerShell:

```
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

In PowerShell, set each var as `$env:JWT_SECRET="..."` in the terminal
you start Storage Management from.

Then from `storage/`:

```
./mvnw spring-boot:run
```

(`.\mvnw.cmd spring-boot:run` on Windows.) It runs on `localhost:8081`
and creates its tables on first start.

## Getting a token (no User Management yet)

Every endpoint needs `Authorization: Bearer <token>`. Until User
Management exists there's no login, so make one "demo user" token by hand
and use it for everything: whatever you create belongs to that user, and
the list endpoints return it. When logins arrive, only where the token
comes from changes.

With `$env:JWT_SECRET` set to the same value Storage Management runs
with, this PowerShell prints a token that lasts 30 days (change the
`sub` if you want a different demo user):

```
$key = [Convert]::FromBase64String($env:JWT_SECRET)
function Url64($b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
$exp = [DateTimeOffset]::UtcNow.AddDays(30).ToUnixTimeSeconds()
$head = Url64 ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$body = Url64 ([Text.Encoding]::UTF8.GetBytes("{`"sub`":`"11111111-1111-1111-1111-111111111111`",`"exp`":$exp}"))
$sig = Url64 ([Security.Cryptography.HMACSHA256]::new($key).ComputeHash([Text.Encoding]::ASCII.GetBytes("$head.$body")))
"$head.$body.$sig"
```

A token only works against a Storage Management started with the same
`JWT_SECRET`. Don't commit either of them.

## Calling it from the frontend

Storage Management doesn't send CORS headers, so a browser page on
another port can't call it directly. In development, let Vite forward the
calls: in `vite.config.ts`,

```ts
server: {
  proxy: {
    '/api': { target: 'http://localhost:8081', changeOrigin: true, rewrite: (p) => p.replace(/^\/api/, '') },
  },
},
```

then call `/api/papers`, `/api/alerts/7` and so on. The `/api` prefix
keeps these apart from page routes with the same name (like `/alerts`).
Keep the demo token in `frontend/csd-frontend-vite/.env.local` (e.g.
`VITE_DEMO_TOKEN=...`, gitignored) and send it as
`Authorization: Bearer ${import.meta.env.VITE_DEMO_TOKEN}`. Vite copies
`VITE_` values into the built JavaScript, so this is fine for a local demo
token but never for a real secret.

## Endpoints

Full request and response shapes, and every error, are in CONTRACTS.md.

### Endpoints needed for frontend

Storage Management is `localhost:8081` (`/api/...` through the Vite proxy
above) and every call here needs the demo user token. Updating is
`localhost:8001` and needs no token in sprint 1.

1. **Alert List**
    1. **Retrieve all alert list.** Not built as one call. For now, get
       the papers (2.1), then `GET /papers/{id}/alerts` for each one.
       Dismissed alerts are left out unless you add
       `?include_dismissed=true`.
    2. **Retrieve alert by id.** Not built. Find it in its paper's list
       from `GET /papers/{id}/alerts`.

    Also available on an alert:
    - `PATCH /alerts/{id}` with `{"status": "acknowledged"}` or
      `{"status": "dismissed"}`: respond to it.
    - `POST /alerts/{id}/notes` with `{"text": "..."}`: add a note.
    - `GET /alerts/{id}/notes`: its notes, newest first.

2. **Cited Source List**
    1. **Retrieve list of cited sources.** `GET /papers`: your tracked
       papers, newest first, as `{"papers": [...]}`.
    2. **Manual check paper by id.** Updating, not Storage Management:
       `POST /run-poll?paper_id=<id>` on `localhost:8001`. It fetches the
       paper's latest status now instead of waiting for the schedule.
       Updating has no CORS headers either, so add a second Vite proxy
       entry, e.g. `'/updating': { target: 'http://localhost:8001',
       changeOrigin: true, rewrite: (p) => p.replace(/^\/updating/, '') }`,
       and call `/updating/run-poll?paper_id=<id>`.
    3. **Scheduled checks papers.** Nothing to call: Updating checks every
       tracked paper by itself every `POLL_INTERVAL_HOURS` (24 by default).
    4. **Upload paper.** `POST /papers`, either multipart with `file` (a
       PDF, 25 MB max) and optional `folder_id`, or JSON
       `{"doi": "10.xxxx/...", "folder_id": "..."}` to track by DOI (needs
       an open-access PDF, otherwise `422`).
    5. **Retrieve paper severity state.** Not built as a field. Work it out
       from `GET /papers/{id}/alerts`: the highest `severity` (`high`,
       `medium`, `low`) among alerts whose `status` is `new`, or none if
       there aren't any.

3. **Notification**
    1. **Ability to send user toast on change notice.** No push from the
       backend. Re-fetch the alerts every minute or so and show a toast
       for any alert id you haven't seen before whose `status` is `new`.

Your own research paper (the draft) for a project:
`POST /research-paper` (multipart `file`, optional `folder_id`) to upload
or replace it, and `GET` / `DELETE /research-paper?folder_id=` to read or
remove it.

For other services only (a service token; the frontend can't call these):
`GET /internal/papers`, `POST` and `GET /internal/papers/{id}/background-info`
(`/history` for the GET), `POST /internal/papers/{id}/alerts`,
`GET /internal/papers/{id}/alerts/change-keys`,
`GET /internal/papers/{id}/pdf`, `GET /internal/papers/{id}/research-paper`.

## Resetting your local database

Migrations were tidied on 2026-09-26 (see DECISIONS.md): the old
`V2__drop_papers_file_key.sql` is gone. If your local database ran it,
Storage Management won't start ("applied migration not resolved
locally"). Reset it once; it's only test data:

```
docker rm -f storage-db
```

then run the `docker run` above again, and empty your upload folder too
(see below). The same reset fixes any other migration error on a local
database.

**Migration rule.** Never edit or delete a migration once it's on `main`,
always add a new, higher-numbered one. Numbers can have gaps (there's no
V2); Flyway only cares that they go up.

## PDFs

Storage Management keeps every tracked paper's PDF on local disk, and
each project's research paper (the researcher's own draft, see
STORAGE-USER-RESEARCH-PAPER.md). Postgres holds only each file's key (see
ARCHITECTURE.md, Section 2).

- Both kinds of PDF go in the same folder, named `<uuid>.pdf`. Replacing a
  project's research paper deletes the old file.
- The folder is set by `UPLOAD_DIR`, default `uploads/` in the directory
  you start Storage Management from (so `storage/uploads/` with the steps
  above). It will be created on first start. Don't commit it.
- **PDFs only exist on the machine that stored them.** If you point your
  local Storage Management at a shared database, its rows will have keys
  for PDFs that aren't on your disk, and yours won't be on anyone else's.
  Use your own local Postgres with your own folder.
- The database and the folder go together: if you reset one, reset the
  other, or rows will point at missing files.
