# Storage Management

> **Ran Storage Management locally before 2026-09-26?** Reset your local
> database once: `docker rm -f storage-db`, then run the `docker run` below
> again and empty your upload folder. The migrations were tidied, and an
> old database won't start without the reset. See
> [Resetting your local database](#resetting-your-local-database).

## Running locally

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

Then from `storage/`:

```
./mvnw spring-boot:run
```

It runs on `localhost:8081` and creates its tables on first start.

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
Postgres holds only each file's key (see ARCHITECTURE.md, Section 2).

- **Not built yet.** The code on `main` still discards uploads. Until the
  file store and the `papers` file key come back (DECISIONS.md,
  "2026-09-25 — Storage keeps every tracked paper's PDF"), nothing is
  written to disk.
- The folder is set by `UPLOAD_DIR`, default `uploads/` in the directory
  you start Storage Management from (so `storage/uploads/` with the steps
  above). It will be created on first start. Don't commit it.
- **PDFs only exist on the machine that stored them.** If you point your
  local Storage Management at a shared database, its rows will have keys
  for PDFs that aren't on your disk, and yours won't be on anyone else's.
  Use your own local Postgres with your own folder.
- The database and the folder go together: if you reset one, reset the
  other, or rows will point at missing files.
