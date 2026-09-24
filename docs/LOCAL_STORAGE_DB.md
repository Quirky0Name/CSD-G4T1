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

## MK review notes

Open items from cross-checking `Paper.java` against
`V1__create_papers.sql` (2026-09-24). The two match: column names, types
and nullability all line up, and `ddl-auto: validate` passes in the tests.
Check these off when a commit covers them.

- [ ] **Duplicate paper → 409.** `PaperRepository.existsByOwnerIdAndDoi`
  isn't called anywhere yet. When the create-paper flow lands, two
  requests at once can both pass the `exists` check, and the second
  `save` then hits the `uq_papers_owner_doi` constraint and throws
  `DataIntegrityViolationException`. That should come back as a 409,
  not a 500.
- [ ] **Normalise DOIs.** DOIs are case-insensitive but the unique
  constraint isn't, so `10.1000/ABC` and `10.1000/abc` can both be saved
  for one user. Lowercase and trim the DOI before saving it and before
  calling `existsByOwnerIdAndDoi`.
- [ ] **`idx_papers_owner` isn't needed.** The unique constraint on
  `(owner_id, doi)` already gives an index that starts with `owner_id`.
  It's harmless, so drop it in a `V2` only if we care, and never by
  editing `V1` once it has run on a shared DB.
- [ ] *(optional)* **Show the unique constraint on the entity.** Add
  `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "doi"}))`
  to `Paper.java` so the rule is visible in Java. `validate` doesn't
  check constraints, so leaving it out breaks nothing.

When reviewing any schema change, also check:

- It's a new `V<n>__<description>.sql` file (two underscores), and no
  existing migration has been edited.
- `Paper.java` (or the new entity) is updated in the same commit, and
  `./mvnw test` passes, since that runs `validate` against the migrated
  H2 schema.
- No one else has used the same version number on another branch.
