# Updating

The scheduled job that re-checks tracked papers. Python, FastAPI and
APScheduler, `backend/src/updating/`. It fetches each paper's status from
Crossref and OpenAlex, stores a snapshot in Storage Management on every
poll, and nudges Research Evaluation with the ids of papers whose snapshot
changed. It records snapshots only, never changes, and makes no LLM calls.
Endpoint and snapshot shapes are in CONTRACTS.md ("Snapshot fields",
"Updating"); running it, the stubs and the mock harness are in SETUP.md.

Polling is the answer, not a workaround: neither Crossref nor OpenAlex has
webhooks, and OpenAlex's `from_updated_date` filter is Premium-only.

## One poll

`poll.run_poll` (scheduled) or `run_manual_poll` (`POST /run-poll`):

1. **Sync** `tracked_papers` from `GET /internal/papers`. Papers without a
   DOI are skipped (`skipped_no_doi`); papers Storage Management no longer
   lists are dropped.
2. **Fetch each DOI once** (several users can track one DOI):
   `sources.fetch_crossref` (`updated-by`) and `fetch_openalex`
   (retraction flag, DOAJ, journal, authors, citation count), plus one
   batched `fetch_openalex_authors` for h-index and works count.
3. **Outage gate:** if Crossref or OpenAlex answered `error` for a DOI, no
   paper with that DOI gets a snapshot this poll; they're listed under
   `source_errors` and retried next poll. `not_found` (e.g. DataCite DOIs)
   is a stable answer and is stored; a failed author batch is stored too
   (authors aren't compared). So every stored snapshot is comparable with
   the one before it.
4. **Read the previous snapshot** (`storage.latest_snapshot`) *before*
   storing the new one, then `snapshot.build_snapshot` and `post_snapshot`.
   If the read fails, nothing is stored (`store_errors`): storing anyway
   would make the next poll compare against this snapshot and miss the
   change.
5. **Compare** (`compare.nudge_reasons`) and set `nudge_pending` on the
   paper if they differ in a nudge field. The reasons are logged, not
   stored.
6. **Nudge** every paper with `nudge_pending` (`evaluation.send_changes`,
   service token). Only a `202` clears the flags; anything else leaves
   them, and the next poll re-sends those ids. A poll with no pending
   paper sends nothing.

The run summary is stored in `poll_runs` and returned by `POST /run-poll`.

## When it nudges

A plain field comparison, no classification (that's Research
Evaluation's):

- `is_retracted` false → true;
- a new `crossref_updates` entry, identified by (`notice_doi`, `type`), of
  type `retraction`, `correction`, `erratum` or `expression_of_concern`
  (a new source for a known pair isn't new);
- `in_doaj` true → false.

Nulls and fields whose source wasn't `ok` are never compared. A paper's
first snapshot is a baseline. Citation counts, authors and titles are
stored but never nudge, and neither does an unclassified Crossref type on
its own (it's only seen when it arrives with a known change).

## Schedule

- Every `POLL_INTERVAL_HOURS` (default 24). The first poll after a start
  runs one interval after the last *scheduled* poll (immediately if that
  is already due, or on a fresh database), so restarts can't keep
  postponing it (`scheduler.first_run_time`).
- One poll at a time: the scheduler (`max_instances=1`) and
  `POST /run-poll` share a lock. A scheduled tick that finds it held waits;
  a manual call that finds it held gets `409`. Manual runs are recorded
  with trigger `manual` and never move the schedule.
- `paper_id` on a manual run limits fetching and storing to that paper;
  the nudge still sends every pending paper.

## Snapshots

Built in `snapshot.py`. Rules that matter for anyone reading them:

- Every nullable field is **null, never false**, when its source failed or
  didn't know the DOI; `source_status` says which.
- `crossref_updates` comes from Crossref's `updated-by` (not `relation`,
  not `update-to`), every entry kept, duplicates across sources included.
- `in_doaj` is the journal's (OpenAlex `primary_location.source.is_in_doaj`,
  no separate DOAJ call); repository locations are always false.
- `authors`: the first 10 in authorship order. The batched `/authors`
  answer is unordered, so it's re-ordered by `authorships`; institutions
  are the affiliations on this paper, not `last_known_institutions`.
- `cited_by_count` is OpenAlex's only (Crossref's differs).

## Where the code lives

| What | Where |
|---|---|
| App, lifespan (scheduler, clients, DB), logging | `main.py` |
| `POST /run-poll` | `routes.py` |
| Settings | `config.py` (`UpdatingSettings`; `JWT_SECRET` format checked at startup) |
| The poll, summary types, outage gate | `poll.py` |
| Crossref / OpenAlex fetchers and their models | `sources.py` |
| Building a snapshot | `snapshot.py` |
| The nudge decision | `compare.py` |
| Storage Management client (`SERVICE_SUBJECT = "svc:updating"`) | `storage.py` |
| Research Evaluation client | `evaluation.py` |
| Schedule | `scheduler.py` |
| Tables (`tracked_papers`, `poll_runs`) and engine | `models.py`, `db.py`; Alembic in `migrations/` |
| Stubs for running alone | `backend/dev/stub_storage.py`, `backend/dev/stub_research_evaluation.py`, `backend/dev/scenarios.py` |
| Tests | `backend/tests/updating/` (`test_scenarios.py` replays the mock-harness scenarios on recorded responses) |

## Gotchas

- **One process only** (one uvicorn worker, one replica): the scheduler
  and its lock don't coordinate across processes.
- **The OpenAlex key travels in the query string**, so log lines carry only
  the DOI and a status code or exception class, never URLs or exception
  text.
- **Postgres via Alembic, SQLite without schemas.** `db.py` maps the
  `updating` schema to none on SQLite and creates the tables directly;
  tests use SQLite. On Supabase, use the session pooler (port 5432): the
  transaction pooler breaks asyncpg's prepared statements.
- **Live data drifts.** A manual run calls the real APIs, so a seeded
  scenario can list more reasons than intended. Crossref publisher
  entries have no `record-id`; IJAA's publisher `retraction` entry points
  at the paper's own DOI.
- **The nudge waits for Research Evaluation's evaluation**, so its HTTP
  timeout (20 s) must cover a few Storage Management calls per paper; a
  timed-out nudge is simply re-sent.
