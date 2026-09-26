# Seeing and reviewing paper changes (plan)

**Status: built** (S1–S9 done and verified). Work happens on
`feat/eval-reviewing-changes`. As each subtask is built and verified, the
parts of it that change a contract or a decision move into CONTRACTS.md,
ARCHITECTURE.md and DECISIONS.md, and the subtask is marked done below.

## The story

> As a researcher, I want to see the changes detected on one of my papers
> sorted by recency, so I can decide how to respond.

Once a paper has an alert for a type of update, the researcher should be
told what changed and how it affects them. Acceptance criteria:

- the user sees their alerts sorted by recency;
- each alert has a severity, a description, a recommendation and a
  detection time;
- no alerts shows an empty list;
- the user can acknowledge or dismiss an alert.

## What an alert is

An alert is one detected change to one tracked paper, together with
Research Evaluation's assessment of that change. It is a row in Storage
Management's `alerts` table, not a message between services.

- **Updating never sends alerts.** It sends Research Evaluation a nudge
  with only the ids of the changed papers
  (`POST /evaluate/changes {"paper_ids": [...]}`), as in CONTRACTS.md.
- **Research Evaluation creates alerts.** It reads the snapshots from
  Storage Management, compares them, classifies each difference and
  assesses it (severity, description, recommendation). Then it stores
  each alert in Storage Management with
  `POST /internal/papers/{id}/alerts`.
- **Storage Management stores alerts and serves them to the frontend.**
  The frontend calls `GET /papers/{id}/alerts` when it shows the paper,
  and `PATCH /alerts/{id}` when the researcher acts. Nothing is pushed to
  the browser.

```
Updating --POST /evaluate/changes (paper ids)--> Research Evaluation
                                                     |  GET  .../background-info/history
                                                     |  POST /internal/papers/{id}/alerts
                                                     v
Frontend --GET /papers/{id}/alerts------------> Storage Management (snapshots, alerts)
         --PATCH /alerts/{id}----------------->
```

"Change" and "alert" name the same thing seen from two sides: Research
Evaluation detects a change, and the researcher sees and acts on an
alert. A nudge can produce no alerts, one or several. For example, an
`in_doaj` flip caused by a switch to a repository location nudges but
isn't a delisting, and a re-sent nudge finds alerts that already exist.

### Why Storage Management holds the alerts

Storage Management is the team's persistence service (ARCHITECTURE.md,
Section 2), and the frontend already talks to it. Storing alerts there
means:
- the frontend uses one backend for a paper and its alerts;
- Storage Management already checks user JWTs and knows who owns each
  paper, so alerts need no copied `owner_id`;
- Research Evaluation stays off the frontend's path, so the CONTRACTS
  line "Research Evaluation isn't called by the frontend" stays true;
- Research Evaluation needs no database of its own (see S5).

The cost is that the alert endpoints are Storage Management code, so
Amir (Storage Management's owner) has to agree to them and review them.

### Alert fields (API)

What `GET /papers/{id}/alerts`, `PATCH /alerts/{id}` and
`POST /internal/papers/{id}/alerts` return for each alert. The database
table stores more than this; see "`alerts` table (database)" below.

| Field | Example / meaning |
|---|---|
| `id` | bigint |
| `paper_id` | the paper the alert is about |
| `change_type` | `retraction`, `correction`, `erratum`, `expression_of_concern`, `doaj_delisting`, `other` (a Crossref notice type we don't classify yet) |
| `severity` | `high` / `medium` / `low` |
| `description` | what changed and how it affects the researcher |
| `recommendation` | what to do about it |
| `notice_doi` | the Crossref notice, when there is one |
| `detected_at` | the `fetched_at` of the first snapshot the change shows up in |
| `status` | `new`, then `acknowledged` or `dismissed` |
| `status_changed_at` | when the status last changed |

### `alerts` table (database)

In Storage Management's Postgres, created by a Flyway migration (S1).

| Column | Type | In the API? | Why it exists |
|---|---|---|---|
| `id` | bigint, primary key | yes | identifies the alert for `PATCH /alerts/{id}` |
| `paper_id` | uuid, FK → `papers`, `on delete cascade` | yes | which paper; ownership comes from `papers.owner_id`; an alert is deleted with its paper |
| `change_type` | varchar (enum) | yes | the kind of change, including `other` from the start, so the later LLM story needs no migration to handle it |
| `severity` | varchar (enum) | yes | `high` / `medium` / `low` |
| `description` | text | yes | what changed and how it affects the researcher |
| `recommendation` | text | yes | what to do about it |
| `notice_doi` | varchar, nullable | yes | link to the Crossref notice |
| `detected_at` | timestamptz | yes | the sort key for "sorted by recency" |
| `status` | varchar (enum), default `new` | yes | `new` / `acknowledged` / `dismissed` |
| `status_changed_at` | timestamptz, nullable | yes | when the researcher acted on it |
| `change_key` | varchar | no | identifies the change, e.g. `retraction` or `correction:10.1016/…` |
| `snapshot_id` | bigint | no | the snapshot the change first appeared in |
| `previous_snapshot_id` | bigint | no | the snapshot it was compared against |
| `created_at` | timestamptz | no | when the alert was stored |

- **Unique (`paper_id`, `change_key`)** stops a re-sent nudge, or a
  retraction reported by both OpenAlex and Crossref, from creating a
  second alert.
- **An index on (`paper_id`, `detected_at`)** serves the list query.
- The columns not in the API are bookkeeping: `change_key` prevents
  duplicates, and the two snapshot ids let anyone replay how an alert was
  produced.

## Scope

In scope: the whole path from Updating's nudge to the researcher acting
on an alert. The alert store and the frontend-facing endpoints are in
Storage Management (`storage/`, Java). Finding and assessing changes is in
Research Evaluation (`backend/src/research_evaluation/`, Python).

Out of scope:
- **The frontend panel.** There is no `frontend/` yet, and it belongs to
  User Management's owner.
- **A list of alerts across all of a user's papers, or one folder.** The
  story is about one paper. Until it's added, a frontend overview would
  need one `GET /papers/{id}/alerts` per paper. See "Later stories",
  "Alerts across papers and folders".
- **Updating's nudge.** Updating's owner builds it. It will need to send a
  service token (see S5).
- **Judging whether a change is meaningful and how it matters to the
  researcher,** with an LLM, the PDF or notes, or stance checks between
  papers. Those are separate user stories (see "Later stories"). This
  story builds the evaluation pipeline they plug into, with one
  rule-based assessor (see S4).
- **The snapshot history endpoint in the real Storage Management.**
  `GET /internal/papers/{id}/background-info/history` (and the
  `background_metadata` table behind it) is Storage Management's, but it
  isn't built in Java yet (CG-68). Only the Python stub
  (`backend/dev/stub_storage.py`) has it, so Research Evaluation is
  developed and tested against the stub, like Updating. Research
  Evaluation calls whatever runs at `SM_BASE_URL`: against the real
  Storage Management today, the history read gets a bare `404` and every
  nudge answers `503`, until the endpoint exists. No Research Evaluation
  change is needed then. See "TODO for other owners".

## API

Storage Management, for the frontend (user JWT):
- `GET /papers/{id}/alerts?include_dismissed=false` returns
  `{"alerts": [...]}`, sorted by `detected_at` descending, ties broken by
  `id` descending. No alerts gives `{"alerts": []}`. Dismissed alerts
  are hidden unless `include_dismissed=true`. A paper that doesn't exist
  or isn't the caller's gets `404`.
- `PATCH /alerts/{id}` with `{"status": "acknowledged" | "dismissed"}`.

Storage Management, for Research Evaluation (service JWT):
- `POST /internal/papers/{id}/alerts` stores one alert. It's idempotent on
  (`paper_id`, `change_key`): a new alert gets `201`, and an existing one
  gets `200` with the stored alert, unchanged (the researcher's status is
  kept).

Research Evaluation, for Updating (service JWT):
- `POST /evaluate/changes` with `{"paper_ids": [...]}`. It answers `202`
  once every alert is stored in Storage Management, and `503` for any
  failure while evaluating. Auth and body errors are `401`, `403` and
  `422` (see S5).

## How the Storage Management code is laid out (S1–S3)

Every class below runs inside Storage Management, the Java service.
Postgres is a separate program that Storage Management talks to over SQL:

```
Research Evaluation / Frontend ──HTTP──▶ Storage Management (controller, request, service, repository)
                                                   │ SQL
                                                   ▼
                                              Postgres (the tables)
```

The new code goes in `storage/src/main/java/com/g4t1/storage/alert/`, and
follows the `paper/` package:

| Class | Job | Existing equivalent |
|---|---|---|
| `InternalAlertController` | The HTTP layer for Research Evaluation: `POST /internal/papers/{id}/alerts` (takes the paper id from the path and the body, calls the service, answers `201` or `200`) and `GET /internal/papers/{id}/alerts/change-keys` (S7). No business logic. | `PaperController` |
| `AlertController` | The same for the frontend's `GET /papers/{id}/alerts` and `PATCH /alerts/{id}` (S2, S3). | `PaperController` |
| `NewAlertRequest` | A Java `record` that holds the JSON **body** of the POST, and nothing else. It also carries the shape rules as annotations (`@NotBlank`, `@NotNull`, enum types), which Spring checks before the service runs because the controller's parameter is marked `@Valid`. | — |
| `AlertService` | The business rules, which need the database: does the paper exist, is this `change_key` already stored, does this user own the alert's paper. It builds and saves alerts. Storing is deliberately not one transaction: if two requests race, the unique constraint rejects the second insert and the service re-reads the first alert in a fresh transaction (on Postgres, a failed insert aborts the transaction around it). | `PaperService` |
| `AlertRepository` | Database access. It's a Spring Data JPA interface: methods such as `findByPaperIdAndChangeKey(...)` are declared, and Hibernate generates the SQL. No SQL is written by hand. | `PaperRepository` |
| `Alert` | The entity: one Java object per row of `alerts`, with fields mapped to columns. | `Paper` |
| `AlertResponse` | The outgoing JSON shape, built from an `Alert`, leaving out the internal columns. | `PaperResponse` |
| `ChangeType`, `Severity`, `AlertStatus` | The allowed values, as Java enums. | — |
| `V3__create_alerts.sql` | Creates the table. Flyway runs it at startup, and Hibernate only checks that `Alert` matches it (`ddl-auto: validate`). | `V1__create_papers.sql` |

Each part of the HTTP request is handled in a different place:

```
Authorization header  → JwtAuthFilter / SecurityConfig        401, 403
URL path {id}         → controller parameter (a UUID)
JSON body             → NewAlertRequest, shape-checked         400
                              ↓
                        AlertService, checked against Postgres  404, 200 (existing), 201 (new)
                              ↓
                        AlertRepository → SQL → Postgres
                              ↓
                        Alert → AlertResponse → HTTP response
```

- **`401` and `403` already work.** `SecurityConfig` already requires a
  service token on `/internal/**` and a user token everywhere else, and
  `JwtAuthFilter` validates the token before any controller runs.
- **Checks that only need the request go on `NewAlertRequest`.** Examples:
  `severity` isn't `high`/`medium`/`low`, `description` is empty, or
  `detected_at` isn't a timestamp. They fail with `400` before Postgres is
  asked anything.
- **Checks that need the database go in `AlertService`.** Examples: the
  paper doesn't exist (`404`), or the alert is already stored (`200`,
  return it unchanged). A well-shaped request can still name a paper that
  doesn't exist, and only Postgres knows that.
- **Errors come back as problem details** (already enabled in
  `application.yml`). `AlertService` throws `ResponseStatusException`,
  as `PaperService` does for its `409` and `413`.

## Subtasks

Each subtask is verified on its own.

Storage Management (from `CSD-G4T1/storage`):
```
./mvnw test
```

Research Evaluation (from `CSD-G4T1/backend`):
```
python -m uv run pytest
python -m uv run ruff check
```

### S1: Storage Management stores alerts (internal write)

**Status: done, verified (PASS).**

- **Goal:**
  - A Flyway migration creates the `alerts` table above, with its unique
    key and index. Built as `V3__create_alerts.sql`; the number still has
    to be agreed with Amir, since the `papers` file key is also due back
    in a new migration.
  - `POST /internal/papers/{id}/alerts` (service JWT only) stores one
    alert:
    - `201` with the stored alert when it's new;
    - `200` with the existing alert, unchanged, when (`paper_id`,
      `change_key`) is already stored;
    - `404` for an unknown paper;
    - `400` for a missing required field or an unknown `change_type` or
      `severity` (the body has no status: a new alert always starts as
      `new`);
    - `401` for a missing or bad token, `403` for a user token.
- **Files:**
  - `storage/src/main/resources/db/migration/V3__create_alerts.sql`
  - `storage/src/main/java/com/g4t1/storage/alert/`: `Alert.java`,
    `AlertRepository.java`, `AlertService.java`,
    `InternalAlertController.java`, `NewAlertRequest.java`,
    `AlertResponse.java`, `ChangeType.java`, `Severity.java`,
    `AlertStatus.java`, `LowercaseEnumConverter.java` (stores the enums
    as the same lowercase values the API uses)
  - `storage/src/test/java/com/g4t1/storage/alert/InternalAlertTest.java`,
    `AlertServiceRaceTest.java` (the race path, with a scripted
    repository)
- **Verification:** tests against H2, like the existing ones:
  - the stored row matches the request
  - a repeat returns `200`, keeps the first alert and its status, and
    doesn't add a row
  - the same `change_key` on a different paper is a separate alert
  - `404`, `400`, `401` and `403`
  - the migration applies (`ddl-auto: validate` passes)
- **Doc deltas:**
  - **CONTRACTS:** the endpoint, in "Storage Management ↔ Research
    Evaluation / Updating". The Research Evaluation section's "how
    Research Evaluation stores the evaluation … aren't specified here yet"
    is replaced.
  - **ARCHITECTURE §2:** `alerts` in the schema list and the endpoint
    list, and `alerts` in the services table.
  - **DECISIONS:** alerts live in Storage Management, and why (see "Why
    Storage Management holds the alerts").

### S2: Owner-scoped list

**Status: done, verified (PASS).**

- **Goal:**
  - `GET /papers/{id}/alerts` (user JWT) returns the paper's alerts in
    the order and shape above.
  - No alerts gives `{"alerts": []}`.
  - Dismissed alerts are hidden unless `include_dismissed=true`.
  - A paper that doesn't exist or belongs to another user gets `404`. A
    service token gets `403`, and a missing or bad token gets `401`.
- **Files:** `alert/AlertController.java`, `AlertListResponse.java`
  (the `{"alerts": [...]}` wrapper), `AlertService.java`,
  `AlertRepository.java`, `storage/src/test/java/com/g4t1/storage/alert/AlertListTest.java`
- **Verification:**
  - order, including ties on `detected_at`
  - every field is present, and the internal columns are not
  - the empty list
  - another user's paper gets `404`
  - dismissed alerts hidden and shown
  - each auth outcome
- **Doc deltas:**
  - **CONTRACTS:** the endpoint, in "Frontend ↔ Storage Management".
  - **ARCHITECTURE §2:** the endpoint list.
  - **DECISIONS:** detection time = the snapshot's `fetched_at`, and why.

### S3: Acknowledge or dismiss

**Status: done, verified (PASS).**

- **Goal:**
  - `PATCH /alerts/{id}` (user JWT) sets `status` and `status_changed_at`
    and returns the alert.
  - Either status can be set from any other. Repeating the current status
    changes nothing. `new` can't be set.
  - A missing alert, or an alert on another user's paper, gets `404`. A
    bad status gets `400`.
- **Files:** `alert/AlertController.java`, `AlertService.java`,
  `StatusChangeRequest.java` (the request body),
  `storage/src/test/java/com/g4t1/storage/alert/AlertActionTest.java`
- **Verification:** tests cover each transition, the `404` and `400`
  cases, repeating a status, auth outcomes, and a dismissed alert
  dropping out of `GET /papers/{id}/alerts`.
- **Doc deltas:**
  - **CONTRACTS:** the endpoint, in "Frontend ↔ Storage Management".
  - **DECISIONS:** the status rules, and that dismissing hides an alert by
    default (the user's call, 2026-09-25).

### S4: Stage 1 (detection) and stage 2 (rule-based assessment) (Research Evaluation, pure functions)

**Status: done, verified (PASS).** Two changes from the plan below, both in
DECISIONS.md: the `change_key` is set by stage 1, not stage 2, and a
Crossref entry whose notice DOI is also listed as a `retraction` gives no
alert of its own (it's part of the retraction). On 2026-09-26 that was
widened, and verified: a notice listed under several types gives one alert,
of the most severe type. That's temporary (see "Later stories", "LLM
evaluation of all flagged changes together").

The evaluation runs in three stages. This story builds stages 1 and 2 and
leaves a placeholder for stage 3:

| Stage | Job | File | Built in |
|---|---|---|---|
| 1. Detection | the `if` checks: compare two snapshots field by field and list what changed | `changes.py` | this story (S4) |
| 2. Rule-based assessment | give every change a severity, description and recommendation from fixed rules | `rules.py` | this story (S4) |
| 3. LLM investigation | for changes stage 1 can't classify (`other`), let an LLM investigate and assess them | `llm.py` | placeholder in S5; the real thing is a later story |

The stages don't call each other. `evaluate.py` (S5) runs them once each,
in order, for every change, and stores the results; see S5.

- Detection stays deterministic: whether a paper was retracted must not
  depend on an LLM. Stage 1 decides *that* something changed, and stages 2
  and 3 decide *what it means*.
- Stage 2 runs on every change, so every alert always has a complete
  assessment. Stage 3 can later revise it instead of producing one from
  scratch.

- **Goal:**
  - Given two consecutive snapshots, stage 1 returns the changes worth an
    alert, using the classification table in CONTRACTS.md:
    - Nulls and fields whose source wasn't `ok` are never compared.
    - An entry is identified by (`notice_doi`, `type`), and a new source
      for a known entry isn't new.
    - A DOAJ delisting needs the same `journal_source_id` and the
      `journal` source type. An `in_doaj` flip that fails this guard (the
      paper's location switched, for example to a repository) is not a
      change and is ignored.
    - **There is one retraction alert per paper.** `is_retracted`
      flipping and a Crossref `retraction` entry merge into one alert
      (change key `retraction`), even if they arrive on different polls.
    - **A new Crossref entry of a type we don't classify** (such as
      `withdrawal`, `removal`, `partial_retraction`) is **kept, not
      dropped**, as `change_type: other`, with its raw details (Crossref
      type, label, notice DOI, date). These are what stage 3 will
      investigate.
  - Stage 2 gives each change a `change_key` and a rule-based severity,
    description and recommendation:

    | Change | Severity | Change key |
    |---|---|---|
    | retraction | high | `retraction` |
    | expression of concern | medium | `expression_of_concern:<notice_doi>` |
    | correction | medium | `correction:<notice_doi>` |
    | erratum | low | `erratum:<notice_doi>` |
    | DOAJ delisting | low | `doaj_delisting:<snapshot_id>` |
    | other | medium | `other:<crossref type>:<notice_doi>` |

    An `other` alert gets a generic text, for example: *"Crossref recorded
    a 'Withdrawal' notice (10.xxxx/…) on this paper. It isn't a type we
    assess yet; read the notice to judge its impact."* Its severity is
    `medium` so it isn't overlooked.
- **Files:**
  - `backend/src/research_evaluation/changes.py` (stage 1),
    `backend/src/research_evaluation/rules.py` (stage 2)
  - `backend/tests/research_evaluation/test_changes.py`,
    `backend/tests/research_evaluation/test_rules.py`
  - `backend/tests/support.py` (`load_fixture` reads fixtures as UTF-8, so
    the suite also passes on Windows)
- **Verification:**
  - stage 1: each row of the classification table, each null and
    not-`ok` guard, the duplicate-source case, both DOAJ guards, the
    retraction merge, and an unclassified Crossref type coming out as
    `other` with its details
  - stage 2: the severity and change key for every type, including
    `other`, and non-empty text for every type
  - the whole backend suite is green on Windows
- **Doc deltas:**
  - **CONTRACTS:** change types (including `other`), severities and
    change keys.
  - **ARCHITECTURE §3:** the three stages, and that stage 3 is a
    placeholder so far.
  - **DECISIONS:** the severity table and why; why a retraction is merged
    into one alert; why detection is deterministic; why unclassified
    Crossref types become `other` alerts instead of being dropped. Also a
    deviation: descriptions are templates for now, although ARCHITECTURE
    §3 says the evaluation reads the PDF and notes.

### S5: `POST /evaluate/changes`, end to end (Research Evaluation)

**Status: done, verified (PASS).** Built differently from the plan below
in a few details, all in DECISIONS.md: the paper-not-found `404` is
recognised by its `detail` (`No paper <id>`); a failing paper doesn't stop
the others; the replies carry a summary; the stub answers `422` for a bad
alert body.

- **Goal:**
  - Service JWT only: a user token gets `403`, a missing or bad token
    gets `401`, a bad body gets `422`.
  - `evaluate.py` runs the stages. For each paper id:
    1. read the paper's **full** snapshot history from Storage
       Management (since S9: only the newest N snapshots);
    2. run stage 1 on every consecutive pair (the first snapshot is only a
       baseline);
    3. run stage 2 on every change, then stage 3 on the `other` changes;
    4. store each alert with `POST /internal/papers/{id}/alerts`.
  - **Stage 3 is a placeholder.** `llm.py` has an `investigate(change,
    context, assessment)` function that returns the stage-2 assessment
    unchanged, and `evaluate.py` already calls it for `other` changes. The
    LLM story replaces the function's body and nothing else (see "Later
    stories").
  - The evaluation has exactly two outcomes:
    - **`202`:** every paper's alerts are stored.
    - **`503`:** anything else went wrong while evaluating, so Updating
      keeps its `nudge_pending` flag and re-sends the ids on the next
      poll.

    For now, `503` covers every failure, temporary or not: Storage
    Management unreachable or timing out, Storage Management answering
    `5xx`, `401`/`403` (a `JWT_SECRET` mismatch) or `400` on saving an
    alert, a snapshot Research Evaluation can't parse, and a bug in
    Research Evaluation. Each failure is logged with the paper id and the
    cause, since the status code alone doesn't say which.

    Replying `503` is the only failure handling in this story. A failure
    that doesn't go away on its own (a config error or a bug) fails again
    on every poll until someone fixes it. Handling that edge case is left
    for later (see "Later stories", "Failure handling").
  - A paper Storage Management doesn't know is skipped, with a log line.
    Only Storage Management's paper-not-found `404` (a problem-details
    body) counts. Any other `404`, such as the history endpoint not
    existing yet in the real Storage Management, gives `503`, so a missing
    route can't pass as "no such paper" and silently produce no alerts.
  - A second nudge for the same ids creates no new alerts: the same
    history gives the same change keys, and Storage Management keeps the
    alerts it already has.
  - Research Evaluation keeps no database for this. Its settings are
    `JWT_SECRET` and `SM_BASE_URL`.
  - The stub Storage Management gains `POST /internal/papers/{id}/alerts`
    with the same idempotent behaviour.
- **Files:**
  - `backend/src/research_evaluation/`: `config.py`, `auth.py`,
    `storage.py`, `evaluate.py`, `llm.py` (the stage-3 placeholder),
    `main.py`
  - `backend/src/common/service_token.py`: adds a `ServiceTokenAuth`
    that takes the subject. Updating's copy is left alone.
  - `backend/dev/stub_storage.py`
  - `backend/tests/research_evaluation/`: `conftest.py`,
    `test_evaluate.py`, `test_auth.py`
- **Verification:** integration tests against the stub and its alerts:
  - each change type, including `other`, from nudge to stored alert
  - the stage-3 placeholder is called for `other` changes only, and its
    alerts are stored with the stage-2 assessment
  - repeated nudges
  - several new snapshots, each alert with the right `detected_at`
  - the baseline-only case
  - a Storage Management `500`, timeout, refused connection, `401` on
    the history read and `400` on saving an alert each give `503`, and a
    retry once the fault is gone stores the alerts
  - an unparseable snapshot gives `503`, not an unhandled `500`
  - a paper-not-found `404` is skipped (`202`), and a bare route `404`
    gives `503`
  - each failure is logged with the paper id and the cause
  - auth outcomes
- **Doc deltas:**
  - **CONTRACTS:** `/evaluate/changes` needs a `svc:updating` service
    token (Auth section too). The evaluation now happens **before** the
    `202`, not after, and any failure while evaluating gives `503`.
  - **ARCHITECTURE §3:** Research Evaluation writes alerts to Storage
    Management and keeps no state for change evaluation.
  - **DECISIONS:** why a service token, and why the evaluation happens
    before the `202`. Updating already retries any nudge that doesn't
    get a `202`, so Research Evaluation needs no pending flag or
    watermark of its own, and re-reading the full history is safe
    because storing alerts is idempotent. The cost is that Updating waits
    for the evaluation, which is two or three Storage Management calls per
    paper while the evaluation is rule-based.

### S6: Running it locally

**Status: done, verified (PASS).** The compose file was checked with
`docker compose config` only (Docker's daemon wasn't running), and the
smoke run used the stub.

- **Goal:**
  - Research Evaluation starts from `backend/.env` and in compose, with
    the same `SM_BASE_URL` override that `updating` already has.
  - A manual smoke run works: stub + Research Evaluation under uvicorn →
    seed a paper and two snapshots → nudge → read the alert from the
    stub.
- **Files:** `backend/docker-compose.dev.yml`, `backend/.env.example`,
  `backend/src/research_evaluation/main.py` (docstring)
- **Verification:**
  - a settings test: startup fails fast without `JWT_SECRET`
  - `docker compose -f docker-compose.dev.yml config` parses
  - the smoke run, with its commands and output recorded
- **Doc deltas:**
  - **SETUP:** running Research Evaluation locally against the stub, and
    its env vars.
  - **DEMO:** live steps 3–4 point at these endpoints until the frontend
    panel exists.

### S7: Storage Management lists a paper's change keys

**Status: done, verified (PASS).**

Added after S1–S6, so that Research Evaluation can check which changes it
has already evaluated before evaluating (see S8). It matters once
evaluation uses an LLM: without it, every nudge would re-evaluate every
old change in the paper's history.

- **Goal:** `GET /internal/papers/{id}/alerts/change-keys` (service token
  only) returns `{"change_keys": [...]}`: every change key stored for the
  paper, whatever the alert's status. It gives `[]` when there are none.
  An unknown paper gets `404` with `detail` "No paper <id>", matching the
  other internal endpoints. A user token gets `403`, and a missing or bad
  token gets `401`.
- **Files:**
  - `storage/.../alert/`: `AlertRepository.java` (a query that returns
    only the keys), `AlertService.java` (`changeKeys`),
    `InternalAlertController.java` (the mapping), `ChangeKeysResponse.java`
    (new)
  - `storage/src/test/java/com/g4t1/storage/alert/InternalChangeKeysTest.java`
- **Verification:** keys for the paper only, not other papers' keys;
  acknowledged and dismissed alerts' keys included; the empty list; `404`;
  a non-UUID id; each auth outcome.
- **Doc deltas:** CONTRACTS (the endpoint), ARCHITECTURE §2 (the endpoint
  list), this plan (S7, and `InternalAlertController` in the layout
  table).

### S8: Research Evaluation evaluates only new changes

**Status: done, verified (PASS).** Decided while building it, all in
DECISIONS.md ("2026-09-26 — Only changes not stored yet are evaluated"):
the key lookup is skipped when detection finds nothing; a change key that
appears in two pairs of one history is evaluated once, keeping the earlier
pair's change; a "No paper" `404` from the lookup skips the paper, like
the other Storage Management calls.

- **Goal:**
  - `evaluate_paper` fetches the paper's stored change keys once, after
    detection (stage 1 still runs on the whole history, since it's cheap;
    since S9, on the newest N snapshots);
  - stage 2, stage 3 and storing run only for changes whose key isn't
    stored yet;
  - the stub Storage Management gets the same endpoint;
  - a failed key lookup gives `503`, like any other Storage Management
    failure.
- **Files:** `backend/src/research_evaluation/storage.py`, `evaluate.py`,
  `backend/dev/stub_storage.py`, the Research Evaluation tests.
- **Verification:**
  - a re-nudge makes no store calls for known changes, and doesn't call
    stages 2 or 3 for them;
  - a new change among old ones is the only one evaluated and stored;
  - stage 3 isn't called for an already stored `other` change;
  - a key-lookup failure gives `503`, and the retry works;
  - the existing tests still pass.
- **Doc deltas:** CONTRACTS (the `/evaluate/changes` flow); ARCHITECTURE §3
  (check, then evaluate); DECISIONS (why: future LLM stages must never
  re-evaluate a stored change); this plan (S8 done, and a note under
  "Later stories" that the LLM stages rely on it).

### S9: Evaluate only the newest N snapshots (a setting, default 5)

**Status: done, verified (PASS).**

- **Goal:**
  - A new Research Evaluation setting, `EVALUATION_SNAPSHOT_WINDOW`,
    default `5`, minimum `2` (one pair). Startup fails on anything
    smaller.
  - Research Evaluation asks Storage Management for only the newest N
    snapshots, still oldest first, and runs detection on those. The key
    check (S8) and everything after it are unchanged.
  - The history endpoint gains a `last=N` query parameter. Research
    Evaluation sends it; the stub implements it. No Java code: the Java
    endpoint isn't built yet (CG-68, see "TODO for other owners").
- **The limit it sets:** a change is found as long as a nudge gets through
  within N − 2 failed nudges in a row. With N = 5 and the default 24-hour
  poll, that's about 3 days of failures; after that the change is lost.
  Raise N before deployment.
- **Files:**
  - `backend/src/research_evaluation/`: `config.py` (the setting),
    `main.py` (stored at startup, given to the endpoint), `evaluate.py`,
    `storage.py` (sends `last`)
  - `backend/dev/stub_storage.py` (`last`), `backend/.env.example`
  - `backend/tests/research_evaluation/`: `conftest.py`, `test_config.py`,
    `test_evaluate.py`
- **Verification:**
  - with N = 5, a change 5 or more pairs back isn't detected, and a newer
    one is;
  - a different configured N moves that boundary (N = 6, and N = 2 for
    the last pair only);
  - a change is caught after 3 failed nudges and missed after 4, with
    N = 5;
  - the request carries `last=N`;
  - the default is 5, an env var overrides it, and 1, 0, a negative number
    or a non-number is rejected, including at startup;
  - the stub's `last` returns the newest N, oldest first;
  - all existing tests still pass.
- **Doc deltas:** CONTRACTS (`last`, and the order `after_id`, then
  `last`, then `limit`; the `/evaluate/changes` flow and its limit);
  ARCHITECTURE §3; DECISIONS ("Research Evaluation compares only the
  newest N snapshots", plus pointers from the entries that said "whole
  history"); SETUP (the env var); this plan (S9, and `last` in Amir's
  TODO).

## Later stories

Not built in this story. They plug into the stages in S4 and S5 without
changing detection or storage.

### LLM investigation of unclassified changes (stage 3)

Replaces the body of the `investigate` placeholder in `llm.py`. For an
`other` change, the LLM gets the change's raw details and investigates
what it means for the researcher. The function itself is to be defined in
that story. What's settled so far:

- **Structured, not open-ended.** The LLM gets a fixed brief: what to
  consider (the notice type and label, what the notice says, which
  parts of the paper it affects, how serious it is for someone citing
  the paper) and what it may fetch. It returns a severity, description
  and recommendation in a fixed shape, which replace the stage-2
  assessment.
- **Fetching goes through tools with limits, not the open web.** For
  example: the notice's Crossref record by DOI, the notice page from an
  allowlist of publisher domains, and the paper's stored PDF through
  Storage Management's `GET /internal/papers/{id}/pdf`. Reasons:
  - scraped pages can carry text written to steer the LLM (prompt
    injection), so what it reads is treated as data, never as
    instructions;
  - publishers such as ScienceDirect block bots (see DEMO.md);
  - the service token is never passed to the LLM or sent outside
    Storage Management.
- **Human validation afterwards.** The LLM's assessment is a proposal
  until a person confirms it. Who validates (the researcher, or a team
  member), and whether the alert shows the stage-2 text or the proposal
  meanwhile, is for that story to decide.
- **It runs after the `202`, not before.** LLM calls take seconds each,
  and Updating's HTTP timeout is 20 s. The stage-2 alert is stored first,
  as in this story, and the LLM's result updates it later, likely through
  a new internal endpoint (such as `PATCH /internal/alerts/{id}`) and a
  new Flyway migration for the added columns (such as the proposal, its
  sources and its validation status).
- **Only new changes reach it.** Since S8, Research Evaluation checks
  which change keys already have an alert before evaluating, so an LLM
  stage never re-investigates a change on a later nudge. Keep that
  property: an LLM stage must run only for changes that weren't stored
  yet. Two nudges for the same paper arriving at the same moment could
  still both evaluate a new change (only one alert is stored); if that
  ever matters for LLM cost, the LLM step can instead run only for alerts
  that came back `201`.
- **Updating only nudges on the four known signals,** so an unclassified
  Crossref type on its own never reaches Research Evaluation today. It is
  only seen when it arrives in the same poll as a known change. To catch
  every one, Updating would have to nudge on any new Crossref entry,
  which is a request to Updating's owner.

### Other evaluations

- **LLM meaningfulness:** whether a detected change matters, and how it
  affects this researcher.
- **Stance checks:** using the LLM (`POST /evaluate/stance`) to judge
  whether the stance between two papers changed in a meaningful way.

Both would revise the stage-2 assessment in the same way as stage 3, and
also run after the `202`.

### LLM evaluation of all flagged changes together

Replaces a temporary rule. For now, detection gives one alert per Crossref
notice by fixed rules: when a notice is listed under several types, the
most severe type wins (DECISIONS.md, "2026-09-26 — One alert per Crossref
notice, for now"). The intended design: detection produces a list of
flagged changes for the paper, and an LLM stage evaluates them together,
working out which are duplicates, how they relate (a correction later
followed by a retraction, say) and what they mean for the researcher as a
whole. That also fixes the rule's known losses: a notice relabelled as more
severe (other than as a retraction) raises nothing new, and when one
notice has two unclassified types, the first one listed names the alert.

### Alerts across papers and folders

A future feature, in Storage Management: one endpoint for an overview
(a dashboard, a "3 new alerts" badge, a folder view) instead of one
request per paper.

- `GET /alerts` (user JWT): all of the caller's alerts across every paper
  they track, newest first, in the same shape as the per-paper list (each
  alert carries its `paper_id`). Optional filters that combine:
  `folder_id=<uuid>`, `status=new|acknowledged|dismissed`,
  `include_dismissed=true`.
- The query joins `alerts` to `papers`, always on `papers.owner_id` = the
  caller, and on `papers.folder_id` when a folder is given. No call to User
  Management is needed: Storage Management already holds each paper's
  `folder_id`. The existing index on `papers.owner_id` covers it, so no
  migration is needed.
- An unknown or another user's `folder_id` gives an empty list, not
  `404`: Storage Management doesn't own folders, so it can't tell whether
  one exists, and the owner condition means no one else's alerts can
  appear.
- `GET /papers/{id}/alerts` stays for the paper detail page, with its
  `404` for a paper that isn't the caller's.
- Size: one repository query, one service method and one controller
  mapping in the `alert/` package, plus a test class.

### Failure handling

In this story, any failure while evaluating gives `503`, and Updating
re-sends the paper ids on its next poll. A temporary failure (Storage
Management down or slow) recovers on the next poll. Nothing is lost,
because the snapshots stay in Storage Management and alerts are stored
idempotently. Left for later:

- **Failures that never go away** (a `JWT_SECRET` mismatch, a missing
  endpoint, a bug, a snapshot that can't be parsed) fail on every poll,
  and nobody is told apart from a log line. Options: Updating's poll
  summary lists the papers whose nudge failed, or an alert or report
  after repeated failures.
- **One failing paper holds back the whole batch.** Updating sends every
  changed paper in one request, and a single failure makes the whole
  reply `503`, so Updating re-sends every id. Research Evaluation already
  stores the other papers' alerts anyway and lists the failed ids in the
  reply (`failed_paper_ids`), so re-sending the others is harmless. Left
  for later: Updating keeping the flag only on the failed ids, which
  changes the contract with Updating.
- **The retry waits for the next poll** (24 hours by default). A faster
  retry would be Updating's code.

## Contract changes that affect other owners

1. **Storage Management gains an `alerts` table and three endpoints**
   (Amir, Storage Management). The migration number has to be agreed,
   since the `papers` file key is also coming back in a new migration.
2. **`POST /evaluate/changes` requires a service token, and replies after
   evaluating** (Zhuo En, Updating). Updating's nudge, when it's built,
   has to send its service token (any `role=service` token is accepted;
   Updating's is `svc:updating`) and allow for the evaluation time within
   its HTTP timeout: each Storage Management call has a 10-second timeout
   on Research Evaluation's side, and papers are evaluated one after
   another. A timed-out nudge is re-sent, which is safe.

## TODO for other owners

- [ ] **Amir (Storage Management, CG-68): build the snapshot endpoints.**
  `POST /internal/papers/{id}/background-info` and
  `GET /internal/papers/{id}/background-info/history`, with the
  `background_metadata` table, as in CONTRACTS.md. Research Evaluation
  depends on the history read: snapshots oldest first, as
  `{"snapshots": [...]}`, with no default page size; the `last=N`
  parameter (only the newest N, still oldest first; applied after
  `after_id` and before `limit`), which Research Evaluation sends on every
  nudge; and `404` with `detail` exactly `No paper <id>` for an unknown
  paper. If `last` were left out, Spring would ignore it and return the
  whole history: the same alerts, but the window would have no effect.
  Until the endpoint exists, the whole flow only works against the stub.

- [ ] **Zhuo En (Updating): send a service token with the nudge.** The
  nudge on `main` (cg-43) calls `POST /evaluate/changes` with no token:
  the `re` client in `updating/main.py` has no `auth`, unlike the `sm`
  client. Research Evaluation now requires a service token, so every
  nudge gets `401` and no alert is ever created. Fix: give the `re` client
  `auth=ServiceTokenAuth(...)`, the same as the `sm` client.
- [ ] **Zhuo En (Updating): use the shared `ServiceTokenAuth` and delete
  Updating's copy.** `common/service_token.py` now has
  `ServiceTokenAuth(key, subject)`, the same logic as the class in
  `updating/storage.py` but with the subject as a parameter.
  `ServiceTokenAuth(key, "svc:updating")` from `common` behaves exactly
  like Updating's, so Updating can import it and delete its own class.
  This can be done together with the item above.

## Open questions

- Approval of this plan, including the severity mapping.
- Amir's agreement to the Storage Management endpoints, or whether he
  would rather build S1–S3 himself.
- Commit after each verified subtask, and the ticket id for the commit
  messages.
