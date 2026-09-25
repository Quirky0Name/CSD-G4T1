# Storage Management

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
