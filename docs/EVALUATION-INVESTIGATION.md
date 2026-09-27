# Investigating a detected change (plan)

**Status: plan, awaiting approval.** Work happens on
`feat/eval-investigation`. The research behind it (every way a change shows
up in real life, with real examples, and what can be fetched for it) is
[RE-changes-explained.md](RE-changes-explained.md); the case codes below
(R1, C6, …) are its.

## The goal

When a nudge stores new alerts for a paper, group them into one **report**,
fetch what the changes actually are, and store it in the report in Storage
Management, so the later impact step has something to read:

- **the notice, if there is one:** its Crossref record (title, date, which
  articles it says it updates) and its text when it's open access;
- **the new paper, if there is one:**
  - a newer version under another DOI (a Crossref `new_version` or
    `new_edition` entry, whose notice DOI *is* the new version), or
  - the paper's own DOI as it can be downloaded now (the "current copy"),
    for changes made in place: a correction, a retraction watermark, JAMA's
    retract-and-replace at the same DOI.

Investigation fetches and stores facts. It doesn't judge them, and it
doesn't decide whether the current copy differs from the stored paper (see
"No 'is it new' verdict").

## Two packages

```
backend/src/research_evaluation/
  investigation/   this plan: open a report for the paper's new alerts, fetch the
                   notices and the new paper, store them in the report
  impact/          later plan: an LLM judges the report's alerts together: whether
                   they matter, how they affect the researcher, what to do
```

`impact/` isn't created by this plan. It will work on **one report at a
time**: all the alerts the report groups, and its documents, together, and
write its evaluation into the report (the `evaluation` and
`recommendation` columns this plan reserves). A report is where
conclusions about how alerts relate belong ("this
retraction notice is about a different article", "this correction lifts the
earlier EoC"), which a field on one alert can't hold. Whether impact also
reads the paper's earlier reports for context is its plan's call: the
Lancet's correction, EoC and retraction arrived on different polls a week
apart (E5), so they're in three reports.

```
nudge ─▶ detection ─▶ key check ─▶ rules ─▶ store alerts ─▶ 202
             │
             └─ detected changes ─▶ (after the 202) investigation, per paper:
                                      open a report for the new alerts ─▶ store its documents
                                      ─▶ returns the ids of the paper's `investigated` reports
                                    later: impact(report_id) for each ─▶ write its evaluation
```

**The handoff between investigation and impact is a report id.** The
conductor (the background task in S6) runs investigation for a paper, gets
back the ids of the paper's reports that are `investigated` (ready, not
yet `assessed`), and calls impact on each. Impact reads everything else
from Storage Management (`GET` the report: its alerts and its documents),
never from investigation's objects in memory, and marks the
report `assessed` when done. So the two packages don't import each other,
and either can be re-run on its own. The ids are the reports finished in
this run; picking up a report whose impact failed is the impact plan's
call. This plan returns the ids; the impact plan adds the call.

## What already exists

### How a notice is found today (nothing is scraped)

1. **Updating** calls `api.crossref.org/works/{paper_doi}` on every poll and
   copies each `updated-by` entry into the snapshot's `crossref_updates`:
   `{notice_doi, type, label, source, date, record_id}`
   (`updating/snapshot.py`, `_crossref_updates`). The publisher registers a
   notice with `update-to: <paper DOI>`, and Crossref shows the reverse link
   on the paper as `updated-by`; since 2023 Retraction Watch's records are
   merged in too. Every snapshot holds the paper's whole list, not only the
   new entries.
2. **Storage Management** keeps the list as sent, in
   `background_metadata.crossref_updates` (text, never looked inside), and
   serves it back through
   `GET /internal/papers/{id}/background-info/history`.
3. **Detection** (`changes.py`) compares consecutive snapshots' lists and
   returns `Change` objects that already carry `notice_doi`, `notice_type`,
   `notice_label` and `notice_date`, after merging duplicate sources, one
   notice filed under several types, and the retraction flag with a
   retraction notice.

So investigation starts from the `Change` objects. It never re-reads
`crossref_updates` or repeats detection. What the snapshot **doesn't** hold
is anything about the notice itself (its title, its `update-to`, its text):
that's what investigation fetches, with `notice_doi`.

Facts that shape the design:
- **Change keys.** A retraction's key is always `retraction` (one retraction
  alert per paper); a notice's other types are keyed by notice DOI (one
  alert per notice); a DOAJ delisting by snapshot id. Within one pair of
  snapshots the first retraction notice listed wins; across polls, the
  first retraction stored wins. So a retraction alert can have no notice DOI
  (OpenAlex's flag came first) or a wrong one (R3, R5), and today a later,
  better notice produces no new alert. S7 changes that for a retraction
  alert with no notice.
- **`201` from `POST /internal/papers/{id}/alerts`** means Storage
  Management inserted the alert during this call, so this nudge created it.
  The change itself can be older (the window covers nudges that failed), and
  its `detected_at` is that snapshot's time. Since the key check, a `200`
  mostly means another nudge for the same paper stored it first.
- **The snapshot endpoints are in the Java service** (CG-68, merged
  through PR #26), with `last` and the exact `No paper <id>` `404`, so the
  flow works against the real Storage Management as well as the stub
  (`backend/dev/stub_storage.py`), which the tests use.

### Updating's lookups by DOI: the overlap

| Code | What it fetches by DOI | Overlap with investigation |
|---|---|---|
| `updating/sources.py` `fetch_crossref` | `api.crossref.org/works/{doi}` with the `mailto` polite pool; returns `CrossrefWork` or `NOT_FOUND` / `ERROR` | **Same endpoint and error handling (~15 lines).** But `CrossrefWork` keeps only `DOI` and `updated-by`; investigation needs `title`, `published`, `container-title`, `update-to` and `relation`, so the model is new either way. |
| `updating/sources.py` `fetch_openalex` | retraction flag, journal, DOAJ, authors | None. |
| RE's `Snapshot` model | — | Drops the paper's `doi` (`extra="ignore"`), which investigation needs (S5). |
| `common/doi.py` `normalize_doi` | — | Reused as is. |

Decision proposed: investigation copies the ~15 lines of request code into
its own Crossref fetcher, same pattern, same `CROSSREF_MAILTO`. Research
Evaluation doesn't import from `updating/` (separate services that share an
image), and `sources.py` is Zhuo En's. Moving the request into `common/` is
a later refactor with him, like `ServiceTokenAuth`.

### Storage Management and storing papers

- **One PDF per paper, no versions.** `papers.file_key` holds the key;
  `LocalFileStore` writes `<uuid>.pdf` under `UPLOAD_DIR` (`save`), and
  since PR #25 also has `read(key)` (empty when the file is missing) and
  `delete(key)`. Uploads keep their PDF; tracking by DOI downloads one with
  `OpenAccessPdfClient.download(doi)` (every OpenAlex PDF link, then
  Semantic Scholar's; browser-like user agent; redirects followed; only real
  PDFs up to 25 MB).
- **`OpenAccessPdfClient.download(doi)` is the "new paper" download**, for
  the paper's own DOI or a new version's. It's Java, with no endpoint, and
  doesn't say which link it used.
- **`GET /internal/papers/{id}/pdf` is built** (PR #25,
  STORAGE-USER-TRACKED-PDF.md): the tracked paper's stored PDF, which
  never changes. Nothing in Research Evaluation calls it yet; it's the
  "before" that impact can compare a current copy with.
- **The researcher's own paper (their draft) is stored per project**
  (`research_papers`, V6, STORAGE-USER-RESEARCH-PAPER.md), and
  `GET /internal/papers/{id}/research-paper` serves the draft of the
  tracked paper's project. A project is the paper's owner plus its
  `folder_id` (CONTRACTS.md, "Folders and projects"), so a report belongs
  to the same project as its paper, through `paper_id`. Impact will read
  the draft; investigation doesn't.
- **`background_text`** is in ARCHITECTURE §2's schema list but not built.
- **No endpoint lets another service store a document or file.** Research
  Evaluation keeps no database, so everything investigation fetches needs a
  new home in Storage Management (S1, S2).
- **Storage Management didn't start** (fixed by S0): two migrations were
  both version 4 (`V4__create_alert_notes.sql` and
  `V4__create_background_metadata.sql`), so Flyway stopped with "Found
  more than one migration with version 4" and every Java test failed.
  Alert notes is now V5. V6 is taken by `feat/storage-user-research-paper`,
  so this plan's migration is V7.

## Real cases: what investigation gets

From RE-changes-explained.md, checked against the plan. "Current copy" is
the paper's own DOI downloaded when the report is made.

| Case | Stored in the report | Left for impact |
|---|---|---|
| R1 standard retraction notice (IJAA, Lancet, PLOS ONE) | the notice's Crossref record; its text when open access (PLOS: yes; IJAA, Lancet: no); the current copy | what the notice says |
| R2 same notice typed `retraction` (Retraction Watch) and `erratum` (publisher) | one notice document | — |
| R3 the "notice" is the paper itself (IJAA's 2020 entry) | only the current copy | that there's no real notice |
| R5 a notice about a different article (Lancet ← `…31174-0`) | the record, whose title is "RETRACTED: Chloroquine or hydroxychloroquine…" (another article); `update_to_includes_paper` is **true**, since that record's own `update-to` lists the Lancet paper | recognising it's another article, from its title |
| OpenAlex's flag and the notice in the same nudge's window | both changes have the key `retraction`; the notice comes from the second and is stored in the report | — |
| OpenAlex's flag in one nudge, the notice in a later one | the `retraction` alert is replaced with the notice's details and treated as new (S7), so the next report takes it and fetches the notice | — |
| A wrong retraction notice first, the real one later | nothing: the alert already has a notice, so it isn't replaced (see "Later sprints") | — |
| R4 retract and replace | the notice; the current copy (JAMA replaces at the same DOI). A replacement DOI named only in the notice's text isn't followed | whether the current version is the corrected one |
| R6 flag only, no notice | the current copy | — |
| R8 reinstatement (arrives as `addendum`) | the notice's record and text | linking it to the earlier retraction, which is in an earlier report |
| E1 expression of concern | the notice's record and text | what it doubts |
| E4 EoC lifted ("Correction and removal of expression of concern") | the notice, whose title and text say so | that the concern is over |
| E5 correction, EoC, retraction on different polls (Lancet) | three reports, one alert each | reading them together, if impact looks at earlier reports |
| C1 corrected in place (Lancet, Scientific Reports) | the notice; the current copy and where it came from | whether the content changed, by comparing text |
| C2 the notice carries the fix (PLOS ONE) | the notice's text | — |
| C4 one notice for several articles | the notice; `update-to` lists this paper among others | — |
| C6 new version (F1000Research v2, Cochrane `.pub4`) | the new version's record, text and PDF | comparing versions |
| `withdrawal`, `removal` (often self-referencing) | the notice; the current copy, which may now be the withdrawal statement | the stored PDF may be the only copy of the original |
| DOAJ delisting | an alert in the report, no documents | — |
| E2, E3, C5, R7: no Crossref entry, or tracked after the fact | nothing: detection never sees them | — |

**Notice text is only there for open-access notices in PubMed Central**
(PLOS, Scientific Reports, RSC, PNAS). The demo papers' notices (Elsevier,
The Lancet, JAMA) get their Crossref record only. Notice PDFs are left out:
publisher links give `403`, and the IJAA notice's only open-access PDF link
(PMC) returns a bot-check page.

## Design

### What a report is

- **One report per paper per nudge that stored new alerts.** It groups
  every alert of the paper that isn't in a report yet: in practice, the
  alerts this nudge created (`201`), all from changes in the snapshot
  window. An alert belongs to one report at a time; the only way it moves
  is S7 (a notice-less retraction alert replaced by its notice, which the
  next report takes).
- **Storage Management does the grouping**, in one transaction, when
  Research Evaluation asks it to open a report for the paper. So two
  nudges racing can't put an alert in two reports, and alerts left over
  from a crash (stored, but the report never opened) join the paper's next
  report. Alerts stored before reports existed join the paper's first one.
- **A report holds its alerts** (`alerts.report_id`), **its documents**
  (what investigation fetched, `report_documents.report_id`) and, later,
  **the LLM's evaluation** (what impact writes). Documents aren't linked to
  alerts; they match by DOI (see "Matching documents to alerts"). The
  current copy is fetched once per report: the paper as it could be
  downloaded when the report was made.
- **Status:** `investigating` until every document is stored, then
  `investigated`, then `assessed` once impact has written its evaluation.
  A report left `investigating` (Research Evaluation crashed or restarted
  midway) stays that way, with whatever documents it got; nothing resumes
  it (see "Later sprints"). Its alerts are already grouped, so they don't
  join a later report.
- **Not decided by the researcher's status.** Acknowledging or dismissing
  stays on alerts and has no effect on reports: status is the researcher's
  to-do state, not the pipeline's progress. Whether the researcher will act
  on reports too is for the impact and frontend plans.

### Who fetches what

| What | Fetched by | How |
|---|---|---|
| Crossref record of a DOI | Research Evaluation (`investigation/crossref.py`) | `GET api.crossref.org/works/{doi}` |
| Open-access text | Research Evaluation (`investigation/europepmc.py`) | Europe PMC `search?query=DOI:"{doi}"` (keep the result whose DOI matches; read `pmcid`, `isOpenAccess`), then `{pmcid}/fullTextXML` |
| PDF of a new version or the current copy | **Storage Management**, with the existing `OpenAccessPdfClient`, when Research Evaluation stores the document | OpenAlex `pdf_url`s, then Semantic Scholar, keeping the first real PDF |

For a notice, step by step:
1. **Updating** (already built) reads the paper's Crossref record
   (`updated-by`) on every poll, so the notice's DOI is in the snapshot's
   `crossref_updates`, and detection puts it on the `Change`.
2. **S3** takes that DOI to Crossref again, this time for the notice's
   own record: its title, date, journal, and `update-to` (which articles
   it says it's about). Crossref has no body text for notices.
3. **S4** takes the same DOI to Europe PMC for the notice's text, when
   it's open access in PubMed Central. It's the only text source.
4. **S5** runs S3 and S4 at the same time and packs their results into
   one document; **S6** sends it to Storage Management, which creates the
   row (and, for a new version or the current copy, downloads the PDF).

S3 and S4 only call their external API and return a result or a failure
status; they never call Storage Management, so they're tested with
recorded API responses alone.

**Storage Management downloads the PDF when the document is stored.**
`POST /internal/documents` creates the row and, for a new version or the
current copy, downloads the PDF straight away if the row doesn't have it
yet, then returns the full row. Sending a document that's already stored
returns it as it is, downloading its PDF first only if that's still
`pending`. The download code stays in Java (`OpenAccessPdfClient`), and
the PDF bytes aren't sent back: impact reads them later with
`GET /internal/documents/{id}/pdf`, which only reads.

Research Evaluation's own external calls go only to `api.crossref.org` and
`www.ebi.ac.uk`, with fixed URLs whose only input is a DOI, on their own
HTTP client with no auth. The Storage Management client (which carries the
service token) is never used for them.

### What gets fetched for each alert in a report

Each fetched thing is a **document** of one kind: `notice`, `new_version`
or `current_version`. Documents are planned from the detected `Change`
objects whose key is one of the report's alerts.

| Change | `notice` | `new_version` | current copy (once per report) |
|---|---|---|---|
| Retraction with a notice DOI | ✓ | — | ✓ |
| Retraction from OpenAlex's flag only | — | — | ✓ |
| Expression of concern | ✓ | — | — (content doesn't change) |
| Correction, erratum | ✓ | — | ✓ (often corrected in place) |
| `other`, type `new_version` / `new_edition` | — | ✓ (the notice DOI) | — |
| `other`, any other type | ✓ | — | ✓ |
| DOAJ delisting | — | — | — |

- A `notice` gets its Crossref record and text. A `new_version` and the
  current copy get their Crossref record, text and PDF. Each part keeps its
  own status, so a failure is stored, never raised.
- **`update_to_includes_paper`** (notices only): whether the notice's
  Crossref `update-to` lists this paper's DOI, a fact stored without
  judging it. It does **not** catch R5 on the Lancet paper: the retracted
  commentary `…31174-0` lists the Lancet paper in its own `update-to` as a
  `retraction`, so the flag is true there, and only its title ("RETRACTED:
  <another article>") shows it isn't the paper's notice.
- **A notice whose DOI is the paper's own DOI** (R3) needs no notice
  document: the current copy covers it.
- Detection can give two changes with one key (the retraction flag in one
  pair, the notice in a later pair of the same window); both are used, so
  the notice is still fetched.

### When it runs: after the `202`

A PDF download can take over 30 s per link, and Updating's nudge timeout is
20 s. So `POST /evaluate/changes` stores the alerts and replies as today,
and investigation runs afterwards as a FastAPI background task, for the
papers evaluated without failure. Nothing it does changes the reply. This
is what EVALUATION-REVIEW-CHANGES.md ("Later stories") already planned for
the LLM steps.

### Storage Management: `reports`, `report_documents`, `alerts.report_id`

`reports` (new):

| Column | Type | Why |
|---|---|---|
| `id` | bigint, PK | |
| `paper_id` | uuid, FK → `papers`, on delete cascade | |
| `status` | varchar (enum) | `investigating` / `investigated` / `assessed` |
| `created_at`, `investigated_at` | timestamptz | |
| `evaluation` | text, nullable | the LLM's written evaluation of the report's alerts together, including how they relate |
| `recommendation` | text, nullable | what the researcher should do |
| `evaluated_at` | timestamptz, nullable | when impact wrote them |

The three evaluation columns are reserved in this plan's migration, so a
report is the evaluation's home from the start, but nothing writes them
yet: impact's plan adds the endpoint that fills them and sets `assessed`.
Per-alert judgments (is the change real, what it affects), which the
research says to keep apart from the rule-based severity, are for the
impact plan.

`alerts` gains `report_id` (bigint, nullable, FK → `reports`): the report
that grouped it. It isn't in the alert API yet.

`report_documents` (new):

| Column | Type | Why |
|---|---|---|
| `id` | bigint, PK | |
| `report_id` | bigint, FK → `reports`, on delete cascade | |
| `kind` | varchar (enum) | `notice` / `new_version` / `current_version` |
| `doi` | varchar | the DOI fetched |
| `crossref_status` | varchar | `ok` / `not_found` / `error` |
| `crossref_record` | text, nullable | the record as Research Evaluation sent it (JSON); never looked inside, like the snapshot's `crossref_updates` |
| `update_to_includes_paper` | boolean, nullable | notices only; null when there's no record |
| `text_status` | varchar | `ok` / `not_indexed` / `not_open_access` / `error` |
| `text` | text, nullable | plain text, capped |
| `text_truncated` | boolean | |
| `pdf_status` | varchar | `skipped` (notices) / `pending` / `ok` / `not_found` |
| `file_key`, `sha256`, `pdf_source_url` | varchar, nullable | the stored PDF, its hash, and the link it came from |
| `created_at`, `pdf_fetched_at` | timestamptz | |

- **`kind`** says what the document is. Storage Management needs it (a
  notice gets no PDF), and it can't work the kind out from the alerts'
  change keys, which it treats as opaque.
- **Unique (`report_id`, `doi`).** Within one report a DOI is only ever
  one kind (the paper's own DOI is always the current copy, and a new
  version's DOI is never also a notice). Planning (S5) already fetches
  each DOI once per report even when several alerts need it; the
  constraint is the backstop, so a DOI sent twice is stored (and
  downloaded) once. Across reports
  the current copy is fetched again, which is intended: it's the paper at
  a different time.

**Matching documents to alerts.** There's no link table between documents
and alerts (dropped: the user's call, 2026-09-27). A report reaches its
alerts directly (`alerts.report_id`) and its documents directly
(`report_documents.report_id`), and they match like this:
- a `notice` or `new_version` document belongs to the report's alert whose
  `notice_doi` is the document's `doi` (each is fetched from exactly one
  alert's notice DOI);
- the `current_version` document is the paper itself, for the whole
  report;
- a self-referencing notice (R3) has the paper's DOI as `notice_doi`, which
  matches the current copy;
- the one case that matches no alert: two retraction notices in different
  pairs of one window are both fetched, but the `retraction` alert carries
  only one notice DOI. The other notice's document stays in the report
  unmatched; its own Crossref record (title, `update-to`) says what it is.

Impact evaluates a whole report at once, so it gets every alert and every
document together either way.

Endpoints (service JWT, under `/internal/**`). The report endpoints are
under the paper, so an unknown paper is `404` with `detail` `No paper <id>`,
like the other internal endpoints. The document endpoints are flat: a
document id, or the `report_id` in the body, already identifies the report
and paper. Reports:
- `POST /internal/papers/{id}/reports`: opens a report grouping every
  alert of the paper not in a report yet. `201` with the report and its
  alerts; `204` when there's nothing to group.
- `GET /internal/papers/{id}/reports/{reportId}`: one report with its
  alerts, its documents and its evaluation fields (null until impact
  exists).
- `PATCH /internal/papers/{id}/reports/{reportId}`: `{"status":
  "investigated"}`. Setting `assessed` is left to impact's endpoint.

Documents (both write and read the `report_documents` table):
- `POST /internal/documents`: body `report_id`, `kind`, `doi`,
  `crossref_status`, `crossref_record`, `update_to_includes_paper`,
  `text_status`, `text`, `text_truncated`.
  - **No row for that DOI in the report:** creates it. A `notice` gets
    `pdf_status` `skipped`. A `new_version` or
    `current_version` is saved as `pending` first, then its PDF is
    downloaded with `OpenAccessPdfClient` and stored (`ok`, with
    `file_key`, `sha256`, `pdf_source_url`, `pdf_fetched_at`), or recorded
    as `not_found`. `201` with the full row.
  - **Row already there:** the body is ignored, nothing is overwritten and
    **nothing is downloaded**, whatever the row's `pdf_status` (the user's
    call, 2026-09-27). `200` with the full row as stored. A row left
    `pending` by a crash between saving and downloading stays `pending`
    (see "Later sprints").
  - **Both answers are the entire row as stored in the database**, read
    back after any download: every `report_documents` field (including
    `id`, `pdf_status`, `sha256`, `pdf_source_url`, the timestamps); the PDF
    bytes aren't included.
  - **Research Evaluation uses the returned row as the document**, not what
    it sent, whether or not the two differ. Nothing compares them for now;
    whether a difference matters is left to a later sprint (see "Later
    sprints").
  - `not_found` isn't retried, like every other failed fetch in this plan.
  - The row is saved before the download, not in the same transaction, so
    a slow download doesn't hold the database open.
  - Errors: `404` with `detail` `No report <id>`; `400` for a bad body.
  - It can take as long as the download (over 30 s per link), so Research
    Evaluation calls it with a long timeout, after the `202`.
- `GET /internal/documents/{documentId}/pdf`: the stored PDF, as
  `application/pdf`. It only reads, never downloads: `404` when the
  document has no stored PDF (`not_found`, `pending`, or a notice) or the
  id is unknown (`detail` `No document <id>`). It isn't keyed by DOI: one
  DOI can have several stored copies (one per report for the current copy,
  and one per user tracking the paper), and a DOI's `/` doesn't fit in a
  path. For impact, later.

A frontend `GET /papers/{id}/reports` is left for the impact plan. Impact
also reads the tracked paper's stored PDF (`GET /internal/papers/{id}/pdf`)
and the researcher's draft (`GET /internal/papers/{id}/research-paper`),
both already built.

### No "is it new" verdict

The first draft stored a current copy only when its SHA-256 differed from
the stored PDF. That doesn't work:
- the two files rarely come from the same place: an upload is the user's
  publisher copy, and DOI tracking keeps whichever open-access link answered
  first (often a PMC, preprint or repository copy);
- many publishers stamp the download date or IP into the PDF, so the same
  version hashes differently on every download;
- repository copies (author manuscripts) never get in-place corrections
  (C1), so the corrected version may not be downloadable at all.

So investigation stores the current copy with its `sha256` and
`pdf_source_url` as facts. An equal hash means it's certainly the same
file; a different hash means nothing on its own. Whether the content
changed is a text comparison, left to impact. A later sprint will use an
LLM to judge whether a copy is similar enough to be the same version, and
delete it if so or keep it if not (see "Later sprints").

## Scope

In scope: fixing the migration clash (S0), reports and their documents in
Storage Management (S1, S2), the `investigation/` package (S3–S5), and
running it after each nudge (S6), and a late retraction notice replacing a
notice-less retraction alert (S7), with the stub Storage Management kept in
step.

Out of scope:
- **`impact/`:** judging a report, writing its evaluation (and the
  endpoint for it), per-alert judgments, comparing versions, reading
  earlier reports for context, and anything the frontend shows.
- **Reading the paper's own stored PDF or the researcher's draft** (both
  endpoints exist; impact reads them), and PDF text extraction (GROBID
  full text).
- **Retrying a failed fetch** (see "Later sprints").
- **A retraction notice replacing a wrong earlier one** (see "Later
  sprints"); a notice replacing a notice-less retraction alert is S7.
- **Following a replacement DOI named only in a notice's text** (R4), and
  **comparing a newly fetched document with the stored row**: both in
  "Later sprints".
- **Notice PDFs, publisher pages, Retraction Watch reasons, the Wayback
  Machine.**
- **Changes detection never sees** (E2, E3, C5, R7), and changes Updating
  doesn't nudge on (EVALUATION-REVIEW-CHANGES.md, "Later stories").

## Subtasks

Storage Management (from `CSD-G4T1/storage`): `./mvnw test`

Research Evaluation (from `CSD-G4T1/backend`):
```
python -m uv run pytest
python -m uv run ruff check
python -m uv run pytest -m live   # only the tests that hit the real APIs
```

Fixtures for external APIs are real responses, recorded from the live API.

### S0: Storage Management starts again (migration clash)

**Status: done, verified (PASS).** The code is commit `9c1e474`, merged
through PR #23 (`fix/storage-flyway`); `./mvnw test` passes (102 tests).
The doc deltas below are applied.

- **Goal:** `V4__create_alert_notes.sql` is renamed to
  `V5__create_alert_notes.sql`, contents unchanged. `background_metadata`
  keeps V4: it was merged first (PR #19, before #21), and Amir's
  `feat/storage-snapshot-endpoints` builds on it. `./mvnw test` passes. A
  local database that already ran alert notes as V4 fails Flyway's
  validation and has to be reset.
- **Files:** `storage/src/main/resources/db/migration/V5__create_alert_notes.sql` (renamed)
- **Verification:** `./mvnw test` is green (it's red now, from the
  context-load test on); migration versions are unique.
- **Doc deltas:**
  - **DECISIONS:** a 2026-09-27 entry: alert notes moved to V5, why not
    `background_metadata`, and the reset needed.
  - **LOCAL_STORAGE_DB:** a note under "Resetting your local database".
  - **EVALUATION-REVIEW-CHANGES** and any other doc naming
    `V4__create_alert_notes.sql`: the new name.

### S1: Storage Management stores reports and their documents

**Status: done, verified (PASS).** One change from the plan, in
DECISIONS.md ("Reports group a nudge's new alerts…", Also settled):
`Alert.reportId` is mapped read-only, since a status change saved after
the grouping would otherwise write `report_id` back to null.

- **Goal:** `V7__create_reports.sql` creates `reports` (with the reserved
  evaluation columns) and `report_documents`, and adds
  `alerts.report_id`; the three report endpoints and `POST
  /internal/documents` work as in "Design", except that the `POST` doesn't
  download PDFs yet (S2 adds that): a new document stays `skipped`
  (notice) or `pending`. Opening a report groups the paper's unreported
  alerts in one transaction. The stub Storage Management gets the same
  endpoints. V7, because V6 is `V6__create_research_papers.sql` (now on
  `main`).
- **Files:**
  - `storage/src/main/resources/db/migration/V7__create_reports.sql`
  - `storage/src/main/java/com/g4t1/storage/report/`: `Report`,
    `ReportDocument`, `ReportRepository`,
    `ReportDocumentRepository`,
    `ReportService`, `ReportDocumentService`, `InternalReportController`,
    `InternalDocumentController`, the request and response records,
    `ReportStatus`, `DocumentKind` and the document status enums
  - `storage/src/main/java/com/g4t1/storage/alert/Alert.java`,
    `AlertRepository.java` (`report_id`, and finding a paper's unreported
    alerts)
  - `storage/src/test/java/com/g4t1/storage/report/InternalReportTest.java`
  - `backend/dev/stub_storage.py`, and a stub test
- **Verification:**
  - opening a report groups exactly the paper's unreported alerts, not
    other papers'; a second open with nothing new gives `204`; an alert is
    never in two reports, including two opens racing (as
    `AlertServiceRaceTest` does);
  - `POST /internal/documents` returns the entire stored row (every
    column), matching the request, with `crossref_record` as sent; the same
    DOI again in that report returns `200` with the stored row, unchanged
    even when the new body differs, and adds no row; the same DOI in
    another report is a separate row; an unknown `report_id` gives `404`
    (`No report <id>`);
  - reading one report with its alerts, documents and
    evaluation fields (null), and the status change; `assessed` can't be
    set through the `PATCH`;
  - deleting the paper deletes its reports and their documents;
  - the existing alert tests still pass;
  - `404` unknown paper or report, `401`, `403`;
  - the stub behaves the same.
- **Doc deltas:**
  - **CONTRACTS:** the endpoints, in "Storage Management ↔ Research
    Evaluation / Updating".
  - **ARCHITECTURE §2:** `reports`, `report_documents` and
    `alerts.report_id`, and the endpoints.
  - **DECISIONS:** a report per paper per nudge that stored new alerts,
    grouped by Storage Management, holding its documents and the LLM's
    evaluation, and why (impact judges alerts together; cross-alert
    conclusions need a home; one current copy per report; Research
    Evaluation keeps no database). The evaluation columns are reserved now,
    before impact exists. Also that researcher status stays on alerts, and
    that documents match alerts by DOI, with no link table (the user's
    call).
  - **README:** a row for this doc and for RE-changes-explained.md.

### S2: Storage Management downloads a document's PDF when it's stored

**Status: done, verified (PASS).**

- **Goal:**
  - `POST /internal/documents` downloads the PDF as in "Design", **only
    when it creates the row**: a new `new_version` or `current_version`
    row is saved as `pending`, then downloaded, and `ok` or `not_found` is
    recorded and the full row returned. A `notice` is never downloaded.
    **A row that's already there is never downloaded**, whatever its
    `pdf_status` (`ok`, `not_found`, or `pending` after a crash): it's
    returned as stored.
  - `GET /internal/documents/{documentId}/pdf` sends the stored PDF and
    never downloads.
  - `OpenAccessPdfClient` gains a method that also returns the link the
    PDF came from; `download(doi)` keeps its signature, so `PaperService`
    is unchanged. The file is saved and read with `LocalFileStore`'s
    existing `save` and `read`.
  - The stub gets both with a scripted download result (no real download).
- **Files:** `report/` (document service and controller),
  `metadata/OpenAccessPdfClient.java`; a test class with a mocked
  `OpenAccessPdfClient`; `OpenAccessPdfClientTest`
  (the source URL); `backend/dev/stub_storage.py`
- **Verification:**
  - a new `current_version` or `new_version` is downloaded once and
    returned `ok` with `sha256`, `pdf_source_url` and `pdf_fetched_at`;
    no downloadable PDF returns `not_found`;
  - a `notice` is never downloaded (`skipped`);
  - sending the same DOI again for a stored row downloads nothing, for
    each `pdf_status` (`ok`, `not_found`, `pending`), and returns the row
    unchanged (the download client is called zero times);
  - a failing download leaves the saved row (`pending` or `not_found`), not
    a half-written one;
  - `GET .../pdf` sends the stored bytes with no download; `404` for a
    `pending`, `not_found` or notice document and for an unknown id
    (`No document <id>`); a non-numeric id; auth outcomes;
  - `PaperTrackDoiTest` still passes.
- **Doc deltas:**
  - **CONTRACTS:** the download in `POST /internal/documents`, and
    `GET /internal/documents/{id}/pdf`.
  - **DECISIONS:** Storage Management downloads the PDF when Research
    Evaluation stores the document and returns the row (the user's call,
    2026-09-27), reusing `OpenAccessPdfClient`, and only when it creates
    the row: an existing row is never downloaded again (the user's call);
    the read endpoint never downloads; no "is it new" verdict, and why.
  - **ARCHITECTURE §2:** file storage now holds report documents' PDFs too.

### S3: Crossref records by DOI (Research Evaluation)

**Status: done, verified (PASS).** One correction to the plan, in
DECISIONS.md ("Investigation fetches notices itself…", Also settled): the
recorded `…31528-2` record also names the Lancet paper, as an `erratum`.

- **Goal:** `investigation/crossref.py`: `fetch_record(http, doi, mailto)`
  returns a `CrossrefRecord` (title, published date, journal, `update_to`
  entries, `relation`) or `NOT_FOUND` / `ERROR`, never an exception. Log
  lines carry only the DOI and a status or exception class.
  `investigation/http.py` holds the status enum shared with S4. Settings
  gain `crossref_mailto` from `CROSSREF_MAILTO`, default empty.
- **Files:** `backend/src/research_evaluation/investigation/__init__.py`,
  `http.py`, `crossref.py`; `research_evaluation/config.py`;
  `backend/tests/research_evaluation/investigation/test_crossref.py`,
  `test_config.py`; `backend/tests/fixtures/crossref/` (notice records:
  IJAA `10.1016/j.ijantimicag.2024.107416`, the Lancet EoC `…31290-3`, the
  Lancet retraction-and-republication `…31528-2`, F1000Research v2
  `10.12688/f1000research.187739.2`)
- **Verification:** IJAA's notice has its title and an `update_to` naming
  the paper; `…31528-2` names the Lancet commentary (`…31174-0`) as
  `retraction` and `erratum`, and the Lancet paper (`…31180-6`) only as
  `erratum` (corrected from the recorded response: the plan first said it
  didn't name the Lancet paper at all);
  F1000Research v2 has a `new_version` `update_to`; `404` → `NOT_FOUND`;
  `500`, timeout, bad JSON and a `200` that isn't a work → `ERROR`; DOIs
  with parentheses encoded like Updating's; `mailto` only when set; no
  `Authorization` header; the setting's default and env var; one `live`
  test.
- **Doc deltas:**
  - **SETUP:** Research Evaluation also reads `CROSSREF_MAILTO`.
  - **DECISIONS:** Research Evaluation's own fetcher rather than importing
    Updating's; investigation fetches deterministically, not through LLM
    tool calls.

### S4: Open-access text from Europe PMC (Research Evaluation)

**Status: done, verified (PASS).** One fixture added to the plan's list:
the search for the PLOS ONE EoC (`search_plos_eoc.json`), so the EoC is
tested end to end.

- **Goal:** `investigation/europepmc.py`: `fetch_text(http, doi)` searches
  Europe PMC by DOI, keeps only the result whose DOI matches, and for an
  open-access result with a PMCID fetches `{PMCID}/fullTextXML` and returns
  plain text (title, abstract, body, captions; no reference list), capped at
  60,000 characters with a `truncated` flag. Otherwise the reason:
  `not_indexed`, `not_open_access` or `error`.
- **Files:** `investigation/europepmc.py`; `test_europepmc.py`;
  `backend/tests/support.py` (an XML fixture loader);
  `backend/tests/fixtures/europepmc/` (search results for the Scientific
  Reports Author Correction, IJAA's notice and a no-match search; the full
  text of PMC11906582 and PMC10836678)
- **Verification:** known sentences present and the reference list absent;
  each reason; the cap; the DOI quoted safely in the query; no
  `Authorization` header; one `live` test.
- **Doc deltas:** **DECISIONS:** Europe PMC is the only text source, and
  fetched text is data for impact, never instructions.

### S5: Planning and fetching a report's documents (Research Evaluation)

**Status: done, verified (PASS).** One correction to the plan, in
DECISIONS.md ("Investigation fetches notices itself…", Also settled):
`update_to_includes_paper` is true for `…31174-0` on the Lancet paper, so
it doesn't catch R5. `investigation/__init__.py` wasn't changed: the
functions are imported from `investigate.py` directly.

- **Goal:** `investigation/investigate.py`:
  - `plan_documents(changes, report_keys, paper_doi)` turns the detected
    changes whose key is one of the report's alerts into planned documents
    (kind, DOI), following "What gets fetched for each alert in a report":
    each DOI once, so one current copy per report, planned when any of the
    report's changes needs it; no notice for a self-referencing one; no
    current copy without a paper DOI; both changes used when two share a
    key.
  - `fetch_document(http, planned, paper_doi, mailto)` runs S3 and S4
    concurrently and returns the body for `POST /internal/documents`, including
    `update_to_includes_paper` for a notice.
  - RE's `Snapshot` keeps `doi`.
- **Files:** `investigation/investigate.py`, `__init__.py`;
  `research_evaluation/changes.py` (`Snapshot.doi`);
  `tests/research_evaluation/investigation/test_investigate.py`
- **Verification:**
  - every row of the table;
  - a report of three alerts (retraction, EoC, correction) plans three
    notices and one current copy; a report of only an EoC plans no current
    copy;
  - a flag-only retraction and its notice in later pairs of one window plan
    the notice;
  - IJAA's self-referencing entry plans no notice;
  - changes whose key isn't in the report plan nothing;
  - `update_to_includes_paper` is true for IJAA's notice on IJAA, false for
    a notice checked against a paper it doesn't name, and **true** for
    `…31174-0` on the Lancet paper (corrected from the recorded response:
    the plan first said false; see "Real cases", R5);
  - a Crossref failure still tries Europe PMC and the other way round; a
    total outage gives statuses and no exception;
  - the existing detection tests still pass.
- **Doc deltas:** this doc's codebase context only.

### S6: Investigation runs after each nudge

**Status: done, verified (PASS).** Only papers with changes in their window
are investigated (as CONTRACTS.md says), so alerts a crash left ungrouped
wait for the paper's next change. `test_evaluate.py` wasn't changed: the
new behaviour is tested in `investigation/test_run.py`.

- **Goal:** the same per-paper loop as today, in two halves, because the
  `202` doesn't wait for investigation (the user's call):
  ```
  for each paper in the nudge:          (before the 202, as today)
      detect changes → store alerts
  reply 202
  for each paper, in the background:    investigate_paper(paper)
      1. open a report       → Storage Management groups the paper's new alerts
                               (204: nothing new, stop)
      2. plan its documents  → from those alerts' changes (S5)
      3. for each document:  S3 + S4 → POST /internal/documents
                               (Storage Management downloads the PDF)
      4. mark the report investigated → return its id
  ```
  - `evaluate_papers` also keeps, for each paper evaluated without failure,
    its detected changes (all of them, before the key check) and its DOI,
    for the second half. `POST /evaluate/changes` replies exactly as today.
  - `investigate_paper` returns the report's id, or nothing when there was
    nothing new or the report couldn't be finished. The background task
    collects the ids; the impact plan adds the call to impact on each. No
    impact placeholder is added in the meantime (DECISIONS.md, 2026-09-27,
    "No separate LLM step for `other` changes": a placeholder that does
    nothing was removed once already).
  - The `POST`s use a longer per-request timeout
    (`INVESTIGATION_PDF_TIMEOUT_SECONDS`, default 120), since they wait for
    the download. Research Evaluation takes the row each `POST` returns as
    the document, whether or not it differs from what was sent (see "Later
    sprints").
  - Any failure is logged with the paper and report ids and the cause,
    leaves the report `investigating`, and never affects the reply or the
    other papers. Nothing resumes it (see "Later sprints").
  - External calls use their own `httpx.AsyncClient`, created at startup,
    with no auth.
- **Files:** `research_evaluation/evaluate.py`, `main.py`, `storage.py`,
  `config.py`, `investigation/run.py`; `backend/.env.example`;
  `tests/research_evaluation/conftest.py`, `test_evaluate.py`,
  `test_config.py`, `investigation/test_run.py`
- **Verification:** against the stub, with external calls mocked:
  - a nudge with new alerts gives one report per paper, grouping all of
    them, with one `POST /internal/documents` per planned document, marked
    `investigated`;
  - when the stored row differs from what was just fetched, the returned
    (stored) row is the one used, and nothing is overwritten;
  - a re-nudge with nothing new opens no report and makes no external
    calls;
  - alerts stored by a nudge whose report never opened (a crash before
    step 1) join the next report;
  - a failure midway leaves the report `investigating`, returns no id for
    it, and the next nudge doesn't touch it;
  - the returned ids are exactly the reports finished in this run;
  - Crossref, Europe PMC or Storage Management failing during investigation
    still gives the same `202` and stored alerts;
  - external requests carry no `Authorization`; Storage Management requests
    still do;
  - the timeout setting's default, override, and rejection of 0 or less;
  - every existing test passes.
- **Doc deltas:**
  - **CONTRACTS:** the `/evaluate/changes` flow: a report opened and
    investigated after the reply.
  - **ARCHITECTURE §3:** the stages now include investigation and reports,
    and the handoff to impact is a report id.
  - **SETUP:** `INVESTIGATION_PDF_TIMEOUT_SECONDS`.
  - **DECISIONS:** investigation runs after the `202`, per paper; a report
    left `investigating` isn't resumed and a failed fetch isn't retried
    (both for later sprints); investigation returns report ids
    and the conductor will hand them to impact, which reads everything from
    Storage Management (the user's call, 2026-09-27), so the two stay
    independent.
  - **EVALUATION-REVIEW-CHANGES:** "Later stories" points here.

### S7: A late retraction notice replaces a notice-less retraction alert

A retraction's change key is always `retraction` (one retraction alert per
paper), unlike every other type, which is keyed by notice DOI. So when
OpenAlex's flag stored the `retraction` alert first, the real notice
arriving on a later poll has the same key, the key check skips it, and it
never reaches a report. It's the only change type with this gap.

- **Goal:**
  - **Research Evaluation:** the key check lets through a `retraction`
    change that has a notice DOI, even when `retraction` is already stored,
    and stores it as usual (rules, then `POST /internal/papers/{id}/alerts`).
    Within one history, a `retraction` change with a notice DOI is used
    over one without (the earliest such), so a flag and its notice in one
    window give one `POST`, with the notice.
  - **Storage Management** (`POST /internal/papers/{id}/alerts`), for a
    stored `retraction` alert:
    - **it has no notice DOI and the request has one:** the row is
      replaced with the request's fields (severity, description,
      recommendation, `notice_doi`, `detected_at`, `snapshot_id`,
      `previous_snapshot_id`) and treated as a new alert: `status` back to
      `new`, `status_changed_at` cleared, `report_id` cleared so the next
      report takes it. The id stays, so its notes stay. `201`, like a new
      alert.
    - **anything else:** `200` with the stored alert, unchanged, as today.
  - So S6 opens a report for it and fetches its notice. The earlier report
    no longer lists it; the documents fetched for it then (e.g. the current
    copy) stay in that report.
  - Every later nudge whose window still has that notice sends it again and
    gets `200`.
  - The stub Storage Management does the same.
- **Files:**
  - `storage/src/main/java/com/g4t1/storage/alert/AlertService.java`,
    `Alert.java` (replacing a row)
  - `storage/src/test/java/com/g4t1/storage/alert/InternalAlertTest.java`
  - `backend/src/research_evaluation/evaluate.py`,
    `backend/dev/stub_storage.py`
  - `backend/tests/research_evaluation/test_evaluate.py`,
    `investigation/test_run.py`
- **Verification:**
  - Storage Management: a notice replaces a notice-less `retraction` alert
    (`201`, same id, new fields, `status` `new`, `report_id` null, notes
    kept); a `retraction` alert that already has a notice is unchanged
    (`200`), even for a different notice; a request with no notice on a
    notice-less alert is unchanged (`200`); other change keys behave as
    before;
  - Research Evaluation, against the stub: the flag on one nudge and its
    notice on a later one give an alert with the notice, back to `new`, in
    a new report, with the notice fetched; a later nudge re-sends it and
    creates no report; a flag and its notice in one window make one `POST`,
    with the notice; the existing S5/S8 tests of the key check still pass.
- **Doc deltas:**
  - **CONTRACTS:** `POST /internal/papers/{id}/alerts`: the exception for a
    notice-less `retraction` alert; the `/evaluate/changes` key check lets
    a retraction with a notice through.
  - **ARCHITECTURE §3:** the key check's exception.
  - **DECISIONS:** a late retraction notice replaces a notice-less
    retraction alert and makes it new again (the user's call, 2026-09-27),
    and why: the notice is the evidence the flag lacked, and it's the only
    type whose key doesn't change with a new notice. A wrong earlier notice
    isn't replaced (left for later).
  - **EVALUATION-REVIEW-CHANGES:** S1 ("an existing one gets `200` …
    unchanged") and S8 (the key check) note the exception.

## Codebase context

Filled in as each subtask lands: where each piece lives, how they fit
together, and the gotchas.

### Storage Management: reports and documents (S1)

Everything is in `storage/src/main/java/com/g4t1/storage/report/`, next
to `alert/` and following its layout:

| Class | Job |
|---|---|
| `InternalReportController` | `POST /internal/papers/{id}/reports` (201 or 204), `GET` and `PATCH .../reports/{reportId}` |
| `InternalDocumentController` | `POST /internal/documents` (201 or 200) |
| `ReportService` | opens a report (groups the paper's unreported alerts), reads one, marks it `investigated`; `No paper` / `No report` 404s |
| `ReportDocumentService` | stores a document once per (`report_id`, `doi`), returns the stored row on a repeat, recovers from a lost insert race like `AlertService`; turns `crossref_record` to and from text |
| `Report`, `ReportDocument` | the entities; `ReportDocument` sets `pdf_status` from `kind` (`skipped` for a notice, `pending` otherwise) |
| `ReportResponse`, `ReportAlertResponse`, `ReportDocumentResponse` | the JSON, nulls always written out; a report's alerts include `change_key` |
| `NewReportDocumentRequest`, `ReportStatusChangeRequest` | request bodies, shape-checked before the service runs |
| `ReportStatus`, `DocumentKind`, `CrossrefStatus`, `TextStatus`, `PdfStatus` | enums, stored lowercase with `alert/LowercaseEnumConverter` (now public) |

- **The migration** is `V7__create_reports.sql`: `reports`,
  `report_documents`, and `alerts.report_id` (`on delete set null`).
- **Grouping** is `AlertRepository.assignUnreportedToReport`, one
  conditional bulk update (`where report_id is null`). `ReportService.open`
  checks for unreported alerts first (so an ordinary nudge with nothing
  new creates no row), inserts the report, runs the update, and deletes
  the report again if the update took nothing (another open won).
- **Gotcha: `Alert.reportId` is read-only in JPA**
  (`insertable = false, updatable = false`). Only the bulk update writes
  it. Mapped normally, any save of an alert loaded before the grouping (a
  researcher's status change) writes `report_id` back to null.
  `InternalReportTest.aStatusChangeSavedAfterAnOpenKeepsTheAlertInItsReport`
  covers it.
- **The alert API still hides `report_id`** (`AlertResponse` has no such
  field); only a report shows its alerts, with their change keys.
- **Timestamps** (`created_at`, `investigated_at`) are truncated to
  microseconds when set, so the first response matches later reads from
  Postgres.
- **DOIs are compared exactly**, so Research Evaluation must send them
  normalised (`common/doi.py`), or `10.1/X` and `10.1/x` become two rows.
- **Tests:** `storage/src/test/java/com/g4t1/storage/report/`:
  `InternalReportTest` and `InternalDocumentTest` (MockMvc on H2), and
  `ReportServiceRaceTest` and `ReportDocumentServiceRaceTest` (scripted
  repositories for the races). Nothing runs on real Postgres; the race
  safety of the grouping relies on Postgres re-checking `report_id is
  null` after the row lock (READ COMMITTED).
- **The stub** (`backend/dev/stub_storage.py`) has the same endpoints, in
  memory: alerts carry a hidden `report_id`, and `store.reports` and
  `store.documents` hold the rest. Differences from the real service: `422`
  where it answers `400` for a bad body, timestamps ending `+00:00` rather
  than `Z`, and no locking between concurrent opens. Its tests are
  `backend/tests/research_evaluation/test_stub_reports.py`.

### Storage Management: downloading a document's PDF (S2)

- **Where:** `ReportDocumentService.store` saves the row, then, if it's
  `pending` (a new version or the current copy), calls `downloadPdf`:
  `OpenAccessPdfClient.downloadWithSource(doi)` → `LocalFileStore.save` →
  `ReportDocument.recordPdf` (or `recordNoPdf`) → save. `storedPdf` backs
  `GET /internal/documents/{id}/pdf` in `InternalDocumentController`.
- **`OpenAccessPdfClient.downloadWithSource`** returns a `DownloadedPdf`
  (bytes + the link that worked). `download(doi)` delegates to it, so
  DOI tracking (`PaperService`) is unchanged.
- **Gotcha: only the request that creates the row downloads.** A stored
  row, whatever its `pdf_status`, is returned untouched. So a row left
  `pending` by a crash stays `pending`, and a request that loses the
  insert race can get the winner's row while it's still `pending`
  (mid-download). Research Evaluation should treat `pending` as "no PDF".
- **`pdf_fetched_at`** is set only when a PDF is stored (`ok`), not for
  `not_found`.
- **`store` isn't `@Transactional`:** each repository call is its own
  transaction, so the download holds none open. If the row update fails
  after the file is written, the file is deleted.
- **Tests:** `ReportDocumentPdfTest` (download once, not found, notice
  never, no re-download for `ok` / `not_found` / `pending`, a throwing
  download leaves the row `pending`, the read endpoint and its three
  404s); `InternalDocumentTest` and `ReportDocumentPdfTest` mock
  `OpenAccessPdfClient` with `@MockitoBean`, so no test touches the
  network; `ReportDocumentServiceRaceTest` checks the race loser downloads
  nothing.
- **The stub:** `POST /dev/pdfs {"doi", "source_url"}` makes a DOI
  downloadable (anything else is `not_found`); `GET /dev/pdf-downloads`
  lists every download attempted; `GET /internal/documents/{id}/pdf`
  serves `stub_pdf(doi)`. It answers `422` for a non-numeric id where the
  real service answers `400`.

### Research Evaluation: Crossref records (S3)

- **Where:** `backend/src/research_evaluation/investigation/`.
  `http.py` has `FetchStatus` (`ok` / `not_found` / `error`, the values
  Storage Management stores as `crossref_status`) and `get()`, the one GET
  every fetcher makes: 200 → the response, 404 → `NOT_FOUND`, anything else
  or a transport failure → `ERROR`, logged with the source, DOI and status
  or exception class only. `crossref.py` has `fetch_record(http, doi,
  mailto)` → `CrossrefRecord` or a `FetchStatus`.
- **`CrossrefRecord`** keeps `doi`, `title`, `published` (partial dates
  stay partial, e.g. `2020-06`, as in Updating's snapshots), `journal`,
  `update_to` (DOI normalised, `type`, `label`, `source`, `date`) and
  `relation` (type → DOIs). `model_dump(mode="json")` is what goes to
  Storage Management as `crossref_record`. `record.updates(doi)` says
  whether `update_to` names a DOI (normalised); S5 uses it for
  `update_to_includes_paper`.
- **Gotcha: it never raises.** Everything read from the body is inside one
  `try`: a body that isn't a work, a blank DOI or an odd shape is `ERROR`;
  a relation item that isn't a text DOI is skipped. S5 runs S3 and S4 side
  by side and relies on this. Known gaps, shared with Updating's
  `fetch_crossref`: a body nested absurdly deep makes `response.json()`
  raise `RecursionError`, and a DOI with a lone surrogate makes `quote()`
  raise; neither happens with real Crossref data.
- **Same request as Updating's `fetch_crossref`:** the DOI is
  percent-encoded with `quote(doi, safe="/")` (so `(20)` becomes `%2820%29`),
  `mailto` is sent only when `CROSSREF_MAILTO` is set, and no
  `Authorization` header is ever sent.
- **Setting:** `ResearchEvaluationSettings.crossref_mailto`, from
  `CROSSREF_MAILTO`, default empty.
- **Tests:** `backend/tests/research_evaluation/investigation/test_crossref.py`,
  against four recorded records in `backend/tests/fixtures/crossref/`
  (`ijaa_notice`, `lancet_eoc`, `lancet_republication`, `f1000_v2`); one
  `live` test fetches IJAA's notice (`pytest -m live`).

### Research Evaluation: open-access text (S4)

- **Where:** `backend/src/research_evaluation/investigation/europepmc.py`:
  `fetch_text(http, doi)` → `DocumentText(status, text, truncated)`, with
  `TextStatus` `ok` / `not_indexed` / `not_open_access` / `error` (the
  values Storage Management stores as `text_status`). Never raises.
- **Two calls,** both through `http.get()`:
  1. `GET .../europepmc/webservices/rest/search?query=DOI:"<doi>"&resultType=lite&format=json`.
     The DOI is escaped inside the quotes (`\` and `"`). The result whose
     `doi` matches (normalised) is used; none is `not_indexed`.
  2. Only if it has a `pmcid` and `isOpenAccess` is `Y`:
     `GET .../rest/<PMCID>/fullTextXML`. A `404` there is
     `not_open_access`; unreadable XML or no text is `error`.
- **`plain_text(xml)`** walks the JATS XML: `front/.../article-title`, each
  `abstract`, then `body` and `floats-group`, one block per `title` / `p`
  (blocks aren't walked inside, so a nested `p` isn't doubled), whitespace
  collapsed, blocks joined by a blank line. `back` (the reference list) is
  never read, and table cells aren't `p`s, so only table captions come
  through. Parsed with the standard library's `ElementTree`.
- **The cap** is `MAX_TEXT_CHARS` (60,000); longer text is cut and
  `truncated` is set. Notices are far shorter; it bites on full articles.
- **Gotcha:** "indexed" isn't "readable". IJAA's notice has a PMCID
  (`PMC11718083`) but `isOpenAccess: N`, so it's `not_open_access`, and no
  full-text request is made.
- **Callers pass a normalised, non-blank DOI** (S5 does). The query sends
  the DOI as given while the match compares it normalised, so a resolver
  URL (`https://doi.org/…`) finds nothing; and a blank DOI would match a
  search result that has no `doi`.
- **Known gaps:** XML nested past about 1,000 levels makes `_blocks` raise
  `RecursionError` (real JATS is far shallower; S3 has the same kind of
  gap); the first result with a matching DOI is used, so a duplicate
  record without a PMCID listed first would hide a readable one (none
  seen).
- **Tests:** `backend/tests/research_evaluation/investigation/test_europepmc.py`,
  against recorded responses in `backend/tests/fixtures/europepmc/`
  (searches for the Scientific Reports Author Correction, the PLOS ONE EoC,
  IJAA's notice and a no-match DOI; full text of PMC11906582 and
  PMC10836678), loaded with `support.load_xml_fixture`; one `live` test.

### Research Evaluation: planning and fetching a report's documents (S5)

- **Where:** `backend/src/research_evaluation/investigation/investigate.py`.
  `plan_documents(changes, report_keys, paper_doi)` → `PlannedDocument`s
  (`kind`, `doi`); `fetch_document(http, planned, paper_doi, mailto)` →
  `FetchedDocument`, whose `body(report_id)` is exactly what
  `POST /internal/documents` takes. `DocumentKind` has the same values as
  Storage Management's `kind`.
- **The plan** follows "What gets fetched for each alert in a report":
  - only changes whose `change_key` is one of the report's alerts count,
    and all of them do, so a retraction flag and its notice in one window
    (two changes, one key) give the notice;
  - a notice per change with a `notice_doi`, or a `new_version` for an
    `other` of type `new_version` / `new_edition`; each DOI once
    (normalised), in the order first seen;
  - then one `current_version` (the paper's DOI) if any change is a
    retraction, correction, erratum or other `other`, or if a notice's DOI
    is the paper's own (IJAA's self-referencing retraction entry, R3; or a
    small publisher's self-referencing new edition), which then gets no
    notice document of its own;
  - no current copy without a paper DOI; nothing for a DOAJ delisting or
    an EoC on its own.
- **The paper's DOI** comes from the snapshots: RE's `Snapshot` now keeps
  `doi` (Updating's normalised DOI, already in Storage Management's rows).
- **`fetch_document`** runs `fetch_record` and `fetch_text` with
  `asyncio.gather(..., return_exceptions=True)`: one failing never stops
  the other, and a fetcher that raises anyway (the known gaps in S3 and S4)
  counts as that source's `error`; cancellation still propagates.
  `update_to_includes_paper` is set only for a notice with a Crossref
  record and a paper DOI (`CrossrefRecord.updates`), otherwise null.
- **Gotcha: `update_to_includes_paper` doesn't catch R5.** The recorded
  `…31174-0` (a retracted Lancet commentary, which Elsevier links onto the
  Lancet paper) lists the Lancet paper in its own `update-to`, so the flag
  is true; its title, "RETRACTED: …", is what tells it apart.
- **Tests:** `backend/tests/research_evaluation/investigation/test_investigate.py`
  (every row of the table, the Lancet's first report, flag plus notice,
  IJAA's self-reference, keys outside the report, no paper DOI, one DOI
  once, the real IJAA and Lancet records, failures on either side, a total
  outage, a raising fetcher, and the exact body); the Lancet commentary's
  record is `backend/tests/fixtures/crossref/lancet_commentary.json`.

### Research Evaluation: investigation after each nudge (S6)

- **Where:** `backend/src/research_evaluation/investigation/run.py`:
  `investigate_papers(sm, context, papers)` → ids of the reports finished;
  `investigate_paper` (open, investigate, mark investigated, or `None`);
  `investigate_report` (plan, then fetch and store each document, returning
  the rows Storage Management stored). `InvestigationContext` carries the
  external client, `crossref_mailto` and `pdf_timeout`; `PaperChanges`
  carries a paper's id, DOI and detected changes.
- **Wiring:** `evaluate.evaluate_papers` now returns an `Evaluation`: the
  reply (`result`, unchanged) and `to_investigate` (each paper evaluated
  without failure that had changes; its DOI is the newest snapshot's).
  `main.evaluate_changes` adds `investigate_papers` as a FastAPI background
  task when there's anything to investigate, then replies `202` or `503` as
  before; FastAPI attaches the task to a returned `JSONResponse` too, so a
  `503` still investigates the papers that succeeded.
- **Clients:** the lifespan makes a second `httpx.AsyncClient` for
  Crossref and Europe PMC (no base URL, no auth) and stores the context in
  `app.state.investigation`, read by the `investigation_context`
  dependency. `store_document` passes `timeout=pdf_timeout` per request;
  every other Storage Management call keeps the client's 10 s.
- **Storage Management calls** (`storage.py`): `open_report` (201 →
  `OpenedReport` with its alerts' change keys, 204 → `None`),
  `store_document` (the stored row), `mark_investigated`. A `No paper`
  404 skips the paper, like the other calls.
- **Gotcha: a background task must never raise.** `investigate_paper`
  catches everything, logs the paper and report ids and the cause, and
  leaves the report `investigating`. Nothing resumes it: the next nudge
  opens a report only for new alerts (see "Later sprints"). Known gap:
  `run._cause` has no `ValidationError` case (unlike `evaluate._cause`), so
  an unreadable report from Storage Management is logged with pydantic's
  message, which quotes part of the response body.
- **Tests:** `backend/tests/research_evaluation/investigation/test_run.py`,
  through the real endpoint against the stub. ASGITransport finishes the
  background task before the nudge's response returns, so a test can check
  the report right after `nudge()`. `conftest.ExternalApis` fakes Crossref
  and Europe PMC with an `httpx.MockTransport` (Crossref knows only the
  DOIs a test gives it; Europe PMC finds nothing; `failure` breaks both),
  and every Research Evaluation test uses it, so none reaches the network.
  `FaultInjectingTransport.sent` keeps whole requests, for the auth header
  and timeout checks.
- **Setting:** `INVESTIGATION_PDF_TIMEOUT_SECONDS` (default 120, more than
  0), in `.env.example` and SETUP.md.

### Known so far (for the later subtasks)

- investigation starts from detection's `Change` objects and never re-reads
  `crossref_updates`;
- `201` means "stored by this nudge", not "happened on this poll";
- a report groups the paper's alerts that aren't in a report yet, which
  Storage Management works out, not Research Evaluation;
- the researcher's status never decides what gets investigated;
- investigation and impact share only report ids and Storage Management's
  rows: a report's status (`investigating`, `investigated`, `assessed`) is
  the handoff;
- the Storage Management client never makes external calls, and S3 and S4
  never call Storage Management;
- the notice's DOI comes from Updating's snapshot, its facts from Crossref
  (S3), its text from Europe PMC (S4); Crossref has no notice text;
- `POST /internal/documents` downloads the PDF; `GET .../pdf` only reads;
- DOIs with parentheses are percent-encoded;
- Europe PMC results are matched by DOI;
- fetched text is data, never instructions;
- a different SHA-256 doesn't mean a new version.

## TODO for other owners

- **Amir (Storage Management):**
  - agree to the report tables, `alerts.report_id`, the five endpoints (three for reports, two for
    documents), the
    `OpenAccessPdfClient` change and S7's exception in
    `POST /internal/papers/{id}/alerts`, or build them himself;
  - know that alert notes is now V5 (S0), and that V7 is taken by this
    plan;
- **Zhuo En (Updating):** nothing needed. Later, perhaps: share the
  Crossref request code through `common/`.

## Later sprints

Left for later sprints on purpose, not built in this plan:
- **Whether a downloaded copy is really a new version, judged by an LLM.**
  Investigation stores every current copy (and new version) it downloads,
  with no "is it new" verdict (see "No 'is it new' verdict"). A later
  sprint uses an LLM to compare a downloaded copy's text with the stored
  paper (or the previous copy) and decide whether it's similar enough to be
  the same version. If it is, the copy is deleted (the row keeps its
  `sha256` and `pdf_source_url`, with a status saying it matched); if it
  isn't, it stays stored as a real new version for impact. That needs the
  PDFs' text (e.g. GROBID full text), which doesn't exist yet. Deleting
  the file can use `LocalFileStore.delete`, and the stored paper is read
  with `GET /internal/papers/{id}/pdf`; both exist.
- **Whether a changed row matters.** When a document's DOI is already
  stored for the report, `POST /internal/documents` returns the stored row
  and Research Evaluation uses it as is, even if what it just fetched
  differs (for example a notice whose Crossref record or text changed
  since it was first stored). Nothing compares the two yet. A later sprint
  decides whether such a difference matters and what to do with it
  (update the row, keep both, or raise it to impact).
- **Retrying failed fetches and unfinished reports.** A Crossref, Europe
  PMC or PDF fetch that fails during investigation is stored with its
  failure status and not tried again. A report left `investigating` by a
  crash or restart stays that way, and a document row left `pending` by a
  crash between saving and downloading stays `pending` (an existing row is
  never downloaded again). A later sprint can add retries, e.g. on the next
  nudge for the paper or on a schedule: find `investigating` reports,
  fetch their missing documents, and download `pending` rows through a
  separate call.
- **Duplicate documents across users.** Two users tracking the same DOI
  have two `paper_id`s, so two reports, and the same notice and current
  copy are fetched, downloaded and stored once per user (like snapshots
  and alerts, which are also per `paper_id`; only Updating's fetch is per
  DOI). It's negligible with a handful of users. If it starts to cost:
  - fetch once per DOI per nudge: Updating sends both users' papers in the
    same nudge, so the background task can fetch a DOI once and store the
    result in each report;
  - store each PDF file once: Storage Management reuses a file already on
    disk when a download has the same `sha256`, so two rows point at one
    file.
  The documents are public data (notices, published papers), so sharing
  them is safe, unlike the researcher's draft.
- **A wrong retraction notice stored first** (R3's self-referencing entry,
  R5's notice about another article). S7 only replaces a `retraction`
  alert with no notice, so a real notice arriving after a wrong one still
  makes no new alert. A later sprint could replace it too, e.g. when the
  stored notice's `update_to_includes_paper` is false or its title shows
  it's another article (the flag alone misses R5; see "Real cases").
- **DOIs named in a notice's text** (R4's replacement: "Retraction and
  replacement of: …"): pull them out with a DOI pattern when there's text,
  and fetch them as new versions. Only open-access notices have text, so it
  wouldn't help JAMA or Elsevier.

## Open questions

None left.

Settled (the user's calls, 2026-09-27):
- **A retraction notice that arrives after a notice-less `retraction`
  alert replaces it** (S7): Storage Management replaces the row with the
  notice's details and treats it as a new alert, so the next report takes
  it and its notice is fetched. Only retractions need this: every other
  type is keyed by notice DOI, so a new notice is always a new alert. (A
  notice relabelled later, e.g. `erratum` to `correction`, makes no new
  alert either, but its DOI was already fetched with its first alert.)
- **Research Evaluation replies `202` once detection has run and the
  alerts are stored, without waiting for investigation.** The `202` is its
  reply to Updating's nudge (`POST /evaluate/changes`); a `503` makes
  Updating re-send the nudge on its next poll. Investigation runs in the
  background afterwards, so its PDF downloads never count against
  Updating's 20 s timeout, and nothing it does changes the reply.
- A 60,000-character cap on fetched text (S4) and a 120 s timeout on each
  `POST /internal/documents` (S6).
