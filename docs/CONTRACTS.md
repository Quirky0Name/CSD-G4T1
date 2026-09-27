# Cross-service contracts

This is the single source of truth for every interface between services.
**A PR that changes an interface updates this file in the same PR.**

## Auth

- JWT: HS256, `sub` = user id, plus `exp`. Issued by User Management at
  login.
- `JWT_SECRET` is shared as base64 of 32+ random bytes (`openssl rand -base64 32`;
  Storage Management's JWT library rejects shorter keys, and Updating checks at
  startup). **Every service
  base64-decodes it before use** — a mismatched encoding is the classic
  Java↔Python JWT bug, so check this first if signature validation fails
  across a language boundary.
- Every downstream service (Storage Management, Research Evaluation,
  Updating) validates the JWT signature itself. No service calls back to
  User Management per request.
- **Service tokens:** Updating and Research Evaluation each mint their own
  short-lived JWTs to call Storage Management, with `sub=svc:updating` /
  `sub=svc:research-evaluation` and `role=service`. Storage Management must
  accept these on its internal endpoints without owner-scoping them to a
  real user. Updating also sends its service token to Research
  Evaluation's `POST /evaluate/changes`, which accepts any service token
  (`role=service`) and rejects user tokens, the same way Storage
  Management treats `/internal/**`.
- **Sprint 1:** there is no User Management yet, and everything runs
  locally. `JWT_SECRET` is a shared throwaway value (base64 of 32+ bytes like
  the real one, the same in every service's local config), and Updating still
  mints service tokens with it. Updating's own endpoints take no token or key;
  `POST /run-poll` will take the user's JWT once User Management exists
  (CG-99).

## Folders and projects

A folder is one research project. Folders belong to User Management; other
services hold only a bare `folder_id` (uuid), with no FK and no check that
the folder exists or belongs to the caller.

- **`folder_id` null means no folder**, and everything a user keeps
  outside folders is one more project of theirs: their "no folder"
  project. Stored rows and responses always show no folder as `null`,
  never as a sentinel id. As input, leaving `folder_id` out means no
  folder, and so does `""`.
- **Storage Management keys a project by owner and `folder_id`**, since it
  can't check who owns a folder id. The same `folder_id` sent by two users
  is two projects.
- **A tracked paper is in exactly one project:** its owner plus its
  `folder_id`.

See DECISIONS.md, "2026-09-27 — The researcher's own paper, one per
project".

## Frontend ↔ Storage Management

Owned by: Storage Management. Consumed by: the frontend.

### `POST /papers` (PDF upload)

User JWT required; the caller becomes the paper's owner. Multipart form
with `file` (a PDF, 25 MB max) and an optional `folder_id` (uuid).
Storage pulls the DOI out of the PDF with GROBID and fills in the rest
from CrossRef/OpenAlex. If GROBID finds no DOI the paper is still saved,
just without metadata. The PDF is kept on Storage Management's local
disk, so Research Evaluation can read it later through
`GET /internal/papers/{id}/pdf`.

**Response `201`:**
```json
{"id": "uuid", "folder_id": "uuid", "doi": "10.xxxx/...", "openalex_id": "W...", "title": "...", "journal": "...", "issn": "0000-0000", "publication_year": 2020, "created_at": "2026-09-24T08:00:00Z"}
```

Errors come back as problem details, with the reason in `detail`:
`400` not a PDF, `401` missing or bad token, `403` service token,
`409` you already track a paper with that DOI, `413` over 25 MB.

### `GET /papers/{id}/alerts?include_dismissed=false`

User JWT required, and only the paper's owner sees its alerts. Returns
the changes Research Evaluation detected on the paper, each with its
assessment, **newest first**: by `detected_at` descending, with ties
(changes found in the same pair of snapshots) broken by severity (`high`,
then `medium`, then `low`), then by `id` descending.

Dismissed alerts are left out unless `include_dismissed=true`.
Acknowledged alerts are always listed, with their status.

**Response `200`:**

```json
{"alerts": [
  {
    "id": 7, "paper_id": "uuid",
    "change_type": "retraction", "severity": "high",
    "description": "...", "recommendation": "...",
    "notice_doi": "10.xxxx/...", "detected_at": "2026-09-24T12:00:00Z",
    "status": "new", "status_changed_at": null
  }
]}
```

A paper with no alerts gives `{"alerts": []}`.

| Field | Meaning |
|---|---|
| `change_type` | `retraction`, `correction`, `erratum`, `expression_of_concern`, `doaj_delisting` or `other` |
| `severity` | `high`, `medium` or `low` |
| `description` | what changed and how it affects the researcher |
| `recommendation` | what to do about it |
| `notice_doi` | the Crossref notice, or null when there isn't one |
| `detected_at` | the `fetched_at` of the first snapshot the change shows up in, i.e. when a poll first saw it |
| `status` | `new`, `acknowledged` or `dismissed` |
| `status_changed_at` | when the researcher last changed the status, or null |

Errors, as problem details: `400` an `include_dismissed` that isn't
`true`/`false`, or an id that isn't a UUID; `401` missing or bad token;
`403` a service token; `404` no paper with that id **or** a paper that
belongs to another user (both give the same `detail`, so the response
never reveals whether someone else's paper exists).

### `PATCH /alerts/{id}`

User JWT required, and only the owner of the alert's paper can change
it. Records the researcher's response to an alert.

**Request:**

```json
{"status": "acknowledged"}
```

`status` is `acknowledged` or `dismissed`:
- Either can replace any other status, including each other. Acknowledging
  a dismissed alert brings it back into the default list.
- Setting the status the alert already has changes nothing, and
  `status_changed_at` keeps the time of the real change.
- An alert can't be set back to `new`.

**Response `200`:** the alert, in the same shape as in the list, with the
new `status` and `status_changed_at`.

Errors, as problem details: `400` a `status` of `new` ("status must be
acknowledged or dismissed"), a missing or unknown status, a body that
isn't JSON, or an id that isn't a number; `401` missing or bad token;
`403` a service token; `404` no alert with that id **or** an alert on
another user's paper (the same `detail` for both).

### `POST /alerts/{id}/notes`

User JWT required, and only the owner of the alert's paper can add a
note. Adds a note to the researcher's log on an alert, e.g. what they did
about it. These aren't the paper's own notes (`PUT /papers/{id}/notes`).
Adding a note never changes the alert's `status` or `status_changed_at`,
and any alert can get one, dismissed or not. Notes can't be edited or
deleted.

**Request:**

```json
{"text": "Removed the citation from my draft."}
```

`text` is required: 1 to 2000 characters, not blank. The limit counts
UTF-16 code units, like JavaScript's `length`, so an emoji counts as 2.

**Response `201`:**

```json
{"text": "Removed the citation from my draft.", "created_at": "2026-09-26T10:00:00Z"}
```

Errors, as problem details: `400` a missing or blank `text`, one over
2000 characters, a body that isn't JSON, or an id that isn't a number;
`401` missing or bad token; `403` a service token; `404` no alert with
that id **or** an alert on another user's paper (the same `detail` as
`PATCH /alerts/{id}`). A refused request stores nothing.

### `GET /alerts/{id}/notes`

User JWT required, and only the owner of the alert's paper can read its
notes. Returns the alert's notes, **newest first** (by `created_at`, then
the newer note).

**Response `200`:**

```json
{"notes": [
  {"text": "Emailed my co-author.", "created_at": "2026-09-27T09:00:00Z"},
  {"text": "Removed the citation from my draft.", "created_at": "2026-09-26T10:00:00Z"}
]}
```

An alert with no notes gives `{"notes": []}`. The alert list
(`GET /papers/{id}/alerts`) doesn't include notes; they're read per alert.

Errors, as problem details: `400` an id that isn't a number; `401`
missing or bad token; `403` a service token; `404` as for
`POST /alerts/{id}/notes`.

### `POST /papers` (track by DOI)

Same endpoint, sent as JSON instead of multipart, for a paper you don't
have a PDF for. User JWT required; the caller becomes the paper's owner.
The DOI is normalised (a `https://doi.org/` or `doi:` prefix is fine,
case doesn't matter) and looked up on CrossRef/OpenAlex. Storage then
downloads an open-access copy (every OpenAlex PDF link, then Semantic
Scholar's) and keeps it like an upload. A DOI with no downloadable
open-access PDF isn't tracked (see DECISIONS.md, 2026-09-26).

**Request:** `{"doi": "10.xxxx/...", "folder_id": "uuid"}`. `folder_id` is
optional; leaving it out or sending `""` means no folder.

**Response `201`:** same shape as the upload.

Errors come back as problem details, with the reason in `detail`:
`400` no DOI or not a DOI, `401` missing or bad token, `403` service
token, `409` you already track a paper with that DOI, `422` CrossRef has
no paper with that DOI, or no open-access PDF could be downloaded for it
(pick a different paper), `503` CrossRef couldn't be reached (nothing is
saved, try again).

### `POST /research-paper`

User JWT required; the caller is the owner. Uploads the researcher's own
paper (the draft they're writing) for one of their projects, so Research
Evaluation can judge a change in a tracked paper against it. Multipart form
with `file` (a PDF, 25 MB max) and an optional `folder_id` (uuid; left out
or `""` means the "no folder" project, see "Folders and projects"). The PDF
is kept on Storage Management's local disk like a tracked paper's. Nothing
is read out of it (no GROBID or CrossRef).

**A project has one research paper.** Uploading to a project that already
has one replaces it: the `id` stays the same, `filename` and `uploaded_at`
become the new upload's, and the old PDF is deleted.

**Response `201`** for the project's first research paper, **`200`** when
it replaced one:

```json
{"id": "uuid", "folder_id": "uuid", "filename": "my-draft.pdf", "uploaded_at": "2026-09-27T08:00:00Z"}
```

`folder_id` is `null` for the "no folder" project. `filename` is the name
the file was uploaded with.

Errors, as problem details: `400` not a PDF, no `file`, or a `folder_id`
that isn't a UUID; `401` missing or bad token; `403` a service token; `413`
over 25 MB. A refused upload changes nothing: the project keeps the
research paper it had.

### `GET /research-paper?folder_id=`

User JWT required. The caller's research paper for one of their projects;
leave `folder_id` out (or send `""`) for the "no folder" project.

**Response `200`:** the same body as the upload's, for the current
upload.

Errors, as problem details: `400` a `folder_id` that isn't a UUID; `401`
missing or bad token; `403` a service token; `404` the project has no
research paper (`detail`: `No research paper in folder <id>`, or `No
research paper outside folders`). Another user's research paper is never
returned: the same `folder_id` under another owner is another project.

### `DELETE /research-paper?folder_id=`

User JWT required. Deletes the caller's research paper for one of their
projects and its PDF; leave `folder_id` out (or send `""`) for the "no
folder" project. Uploading to the project again afterwards starts a new
research paper, with a new `id`.

**Response `204`**, no body.

Errors: as for `GET /research-paper`. A `404` deletes nothing.

## Storage Management ↔ Research Evaluation / Updating

Owned by: Storage Management. Consumed by: Research Evaluation (reads
the snapshots, paper data and stored PDFs of the papers Updating tells
it about, stores the alerts it evaluates, and the reports and documents
its investigation fetches), Updating (calls these on every poll).

### `GET /internal/papers`

Service-JWT only. Returns every tracked paper, for the poller to sync
into its own `tracked_papers` table.

```json
[{"id": "uuid", "owner_id": "uuid", "doi": "10.xxxx/...", "issn": "0000-0000"}]
```

### `POST /internal/papers/{id}/background-info`

Stores the snapshot in the request body as a new `background_metadata`
row (insert-only, never overwrite) and returns it with its id. Storage
Management doesn't fetch anything or call Research Evaluation here;
Updating fetched the data. Updating sends one snapshot per tracked paper
per poll, including polls where nothing changed, except for a paper whose
Crossref or OpenAlex lookup, or whose previous-snapshot read, failed that
poll (see "Poll job").

**Request — snapshot (fields below):**

```json
{
  "doi": "10.xxxx/...", "fetched_at": "2026-09-24T12:00:00Z",
  "openalex_id": "W3027680906", "title": "...", "publication_year": 2020,
  "is_retracted": true,
  "crossref_updates": [
    {"notice_doi": "10.xxxx/...", "type": "retraction", "label": "Retraction", "source": "publisher", "date": "2020-05-22", "record_id": null}
  ],
  "in_doaj": false, "journal_source_id": "S49861241", "journal_source_type": "journal",
  "journal": "...", "issn_l": "0000-0000", "publisher": "...",
  "authors": [
    {"name": "...", "openalex_author_id": "A...", "position": "first", "institutions": ["..."], "h_index": 12, "works_count": 40}
  ],
  "cited_by_count": 1252,
  "source_status": {"crossref": "ok", "openalex": "ok", "openalex_authors": "ok"}
}
```

**Response `201`:** the same body plus `snapshot_id` and `paper_id`.
`crossref_updates`, `authors` and `source_status` come back exactly as
sent; Storage Management doesn't look inside them.

Errors: `400` a missing `doi`, `fetched_at` or `source_status`, `401`
missing or bad token, `403` a user token, `404` an unknown paper, with
`detail` exactly `No paper <id>`.

### `GET /internal/papers/{id}/background-info/history?after_id=&last=&limit=`

Ascending order by snapshot id. Used by Updating (to read a paper's
previous snapshot before deciding whether to nudge) and by Research
Evaluation (to read the snapshots it works out differences from).

```json
{"snapshots": [{"snapshot_id": 41, "fetched_at": "...", "...": "snapshot"}, {"snapshot_id": 42, "...": "..."}]}
```

Query parameters, all optional and applied in this order:
- `after_id`: only snapshots with a higher id;
- `last=N` (1 or more): only the newest N of those, **still oldest
  first**. Research Evaluation sends `last=<EVALUATION_SNAPSHOT_WINDOW>`
  (default 5) and compares only those snapshots;
- `limit`: at most this many, from the oldest.

With none of them it returns the whole history; there's no default page
size, which would silently hide snapshots. Each row has every snapshot
field, nulls written out. A `last` or `limit` below 1 is a `400`. A paper Storage Management doesn't know gets `404`
with `detail` exactly `No paper <id>`, the same as
`POST /internal/papers/{id}/alerts`. Research Evaluation relies on that
text to tell a missing paper (skipped) from a missing route (a failure).

### `GET /internal/papers/{id}/pdf`

Service-JWT only. Returns the paper's stored PDF as `application/pdf`.
Used by Research Evaluation, which reads the paper itself when it
evaluates a change and runs GROBID on it for COI/funding text. Research
Evaluation always reads PDFs through this endpoint, never from Storage
Management's disk.

Any tracked paper, whoever owns it: uploads return the file as it was
uploaded, DOI-tracked papers the open-access copy Storage Management
downloaded when the paper was tracked. A tracked paper's PDF never
changes.

Errors, as problem details (also when the request asks for
`Accept: application/pdf`): `400` an id that isn't a UUID; `401` missing
or bad token; `403` a user token; `404` with one of:

| `detail` | Meaning |
|---|---|
| `No paper <id>` | no tracked paper with that id, exactly as on the other internal endpoints |
| `Paper <id> has no stored PDF` | the paper has no file. Every paper tracked since PDFs are kept has one (uploads keep theirs, DOI tracking downloads an open-access copy or refuses the paper; see DECISIONS.md, 2026-09-26), so this is only for papers added before that |
| `The PDF for paper <id> is missing from disk` | the paper's row points at a file that isn't there (e.g. a local database and upload folder reset separately) |

Only the first means the paper is gone.

### `GET /internal/papers/{id}/research-paper`

Service-JWT only. Returns, as `application/pdf`, the researcher's own
paper (the draft they uploaded with `POST /research-paper`) for the
project the tracked paper is in: the paper's owner plus its `folder_id`,
or the owner's "no folder" project when the paper has no folder (see
"Folders and projects"). Research Evaluation evaluates one user's paper at
a time, so this is the draft whose author gets the alert. It's always the
project's current upload.

Errors, as problem details (also when the request asks for
`Accept: application/pdf`): `400` an id that isn't a UUID; `401` missing
or bad token; `403` a user token; `404` with one of:

| `detail` | Meaning |
|---|---|
| `No paper <id>` | no tracked paper with that id, exactly as on the other internal endpoints |
| `No research paper in the project of paper <id>` | the paper exists, but its project has no research paper (yet) |
| `The research paper for paper <id> is missing from disk` | the row points at a file that isn't there (e.g. a local database and upload folder reset separately) |

Only the first means the paper is gone; the other two mean there's no
draft to read.

### `POST /internal/papers/{id}/alerts`

Service-JWT only. Stores one alert: a change Research Evaluation detected
on the paper, with its assessment. Storage Management doesn't evaluate
anything here. Storing is idempotent on (`paper_id`, `change_key`), so a
re-sent nudge can't store the same change twice.

**Request:**

```json
{
  "change_type": "retraction", "change_key": "retraction",
  "severity": "high",
  "description": "...", "recommendation": "...",
  "notice_doi": "10.xxxx/...",
  "detected_at": "2026-09-24T12:00:00Z",
  "snapshot_id": 42, "previous_snapshot_id": 41
}
```

| Field | Rules |
|---|---|
| `change_type` | required: `retraction`, `correction`, `erratum`, `expression_of_concern`, `doaj_delisting` or `other` (a Crossref notice type Research Evaluation doesn't classify yet), exact lowercase |
| `change_key` | required, at most 512 characters. Identifies the change within the paper (e.g. `retraction`, `correction:<notice_doi>`) |
| `severity` | required: `high`, `medium` or `low`, exact lowercase |
| `description`, `recommendation` | required, not blank |
| `notice_doi` | optional, at most 255 characters |
| `detected_at` | required: the `fetched_at` of the first snapshot the change shows up in |
| `snapshot_id`, `previous_snapshot_id` | required: the snapshot the change first appeared in, and the one it was compared against |

There's no `status` field: a new alert always starts as `new`, and a
`status` sent here is ignored.

**Response `201`** for a new alert, or **`200`** with the alert already
stored under that `change_key`, **unchanged**: the first description is
kept, and so is the researcher's status. The body is the alert as the API
shows it:

```json
{
  "id": 7, "paper_id": "uuid",
  "change_type": "retraction", "severity": "high",
  "description": "...", "recommendation": "...",
  "notice_doi": "10.xxxx/...", "detected_at": "2026-09-24T12:00:00Z",
  "status": "new", "status_changed_at": null
}
```

`change_key`, the snapshot ids and the row's `created_at` are stored but
never returned.

Errors, as problem details: `400` a missing or blank required field, an
unknown `change_type` or `severity`, or a `detected_at` that isn't a
timestamp; `401` missing or bad token; `403` a user token; `404` no paper
with that id.

### `GET /internal/papers/{id}/alerts/change-keys`

Service-JWT only. The change key of every alert stored for the paper,
whatever its status (a dismissed alert is still a change that was
evaluated). Research Evaluation calls this after detecting a paper's
changes and evaluates and stores only those whose key isn't listed, so a
change is never evaluated twice.

**Response `200`:**

```json
{"change_keys": ["correction:10.xxxx/...", "retraction"]}
```

A paper with no alerts gives `{"change_keys": []}`. The keys come back
sorted, but callers should treat the list as a set.

Errors, as problem details: `400` an id that isn't a UUID; `401` missing
or bad token; `403` a user token; `404` no paper with that id, with
`detail` exactly `No paper <id>`, as on the other internal endpoints.

### Reports (investigation)

A report groups the alerts one nudge stored for a paper, and holds what
Research Evaluation's investigation fetched for them (its **documents**)
and, later, impact's evaluation. The plan is in
[EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md). Every
endpoint below is service-JWT only (`401` missing or bad token, `403` a
user token). A report's owner and project are its paper's.

#### `POST /internal/papers/{id}/reports`

Opens a report grouping **every alert of the paper that isn't in a report
yet**: in practice the ones the latest nudge stored, plus any left
ungrouped by a crash. Storage Management does the grouping in one
transaction, so an alert is never in two reports, even when two opens
race. No body.

**Response `201`:** the new report, as `GET` below returns it, with
`documents` empty. **`204`**, no body, when the paper has no alert
outside a report.

Errors, as problem details: `400` an id that isn't a UUID; `404` with
`detail` exactly `No paper <id>`.

#### `GET /internal/papers/{id}/reports/{reportId}`

**Response `200`:**

```json
{
  "id": 3, "paper_id": "uuid", "status": "investigating",
  "created_at": "2026-09-27T08:00:00Z", "investigated_at": null,
  "evaluation": null, "recommendation": null, "evaluated_at": null,
  "alerts": [
    {"id": 7, "change_type": "retraction", "change_key": "retraction", "severity": "high",
     "description": "...", "recommendation": "...", "notice_doi": "10.xxxx/...",
     "detected_at": "2026-09-24T12:00:00Z", "status": "new"}
  ],
  "documents": [{"id": 12, "report_id": 3, "kind": "notice", "...": "the document row, see below"}]
}
```

- `status`: `investigating` (just opened), `investigated` (investigation
  done) or `assessed` (impact done; nothing sets it yet).
- `evaluation`, `recommendation`, `evaluated_at`: impact's, null until a
  later plan writes them.
- `alerts`, oldest first, carry their `change_key` (unlike the frontend's
  alert API): Research Evaluation matches them to its detected changes by
  it.
- `documents`, oldest first. They aren't linked to alerts: a `notice` or
  `new_version` belongs to the alert whose `notice_doi` is its `doi`, and
  the `current_version` is for the whole report.

Errors, as problem details: `400` an id that isn't a UUID or a report id
that isn't a number; `404` with `detail` `No paper <id>`, or
`No report <reportId>` for an unknown report or one of another paper.

#### `PATCH /internal/papers/{id}/reports/{reportId}`

**Request:** `{"status": "investigated"}`. Only `investigated` can be set
here. Setting it again changes nothing, so `investigated_at` keeps the
first time.

**Response `200`:** the report, as `GET` returns it.

Errors, as problem details: `400` any other or a missing `status`; `404`
as for `GET`; `409` a report that's already `assessed`.

### `POST /internal/documents`

Stores what investigation fetched for **one DOI of a report**: its
Crossref record and its open-access text. Flat, since `report_id` already
names the report and, through it, the paper. A DOI is stored once per
report (unique on `report_id`, `doi`).

**Request:**

```json
{
  "report_id": 3, "kind": "notice", "doi": "10.xxxx/...",
  "crossref_status": "ok", "crossref_record": {"title": "Retraction notice to ...", "...": "..."},
  "update_to_includes_paper": true,
  "text_status": "not_open_access", "text": null, "text_truncated": false
}
```

| Field | Rules |
|---|---|
| `report_id` | required |
| `kind` | required: `notice`, `new_version` or `current_version`, exact lowercase |
| `doi` | required, not blank, at most 255 characters. Research Evaluation sends it normalised; Storage Management compares it exactly |
| `crossref_status` | required: `ok`, `not_found` or `error` |
| `crossref_record` | optional JSON, stored and returned exactly as sent; Storage Management never looks inside |
| `update_to_includes_paper` | optional: for a notice, whether its Crossref `update-to` names the paper |
| `text_status` | required: `ok`, `not_indexed`, `not_open_access` or `error` |
| `text` | optional |
| `text_truncated` | required |

**Response `201`** with the new row, or **`200`** with the row already
stored for that DOI in the report, **unchanged whatever the new body
says**. Both are the entire row as stored, nulls written out:

```json
{
  "id": 12, "report_id": 3, "kind": "notice", "doi": "10.xxxx/...",
  "crossref_status": "ok", "crossref_record": {"...": "..."}, "update_to_includes_paper": true,
  "text_status": "not_open_access", "text": null, "text_truncated": false,
  "pdf_status": "skipped", "file_key": null, "sha256": null, "pdf_source_url": null,
  "created_at": "2026-09-27T08:00:01Z", "pdf_fetched_at": null
}
```

**The PDF is downloaded only when the row is created.** A notice is
stored with `pdf_status` `skipped` (notices get no PDF). A `new_version`
or `current_version` is saved as `pending`, then Storage Management
downloads the DOI's open-access PDF (every OpenAlex PDF link, then
Semantic Scholar's, the first that is really a PDF under 25 MB) and
records either `ok`, with `file_key`, `sha256` (hex), `pdf_source_url`
(the link that worked) and `pdf_fetched_at`, or `not_found`. The `201`
answers after the download, so the request can take as long as it
(over 30 s per link); Research Evaluation calls it with a long timeout.

**A row that's already there is never downloaded again**, whatever its
`pdf_status`: `ok`, `not_found`, or `pending` (left by a crash between
saving and downloading, or still downloading for a request that's racing
this one). It comes back as stored. Research Evaluation uses the returned
row as the document, not what it sent.

Errors, as problem details: `400` a missing or blank required field or an
unknown enum value; `404` with `detail` `No report <report_id>`.

### `GET /internal/documents/{id}/pdf`

Service-JWT only. The document's stored PDF, as `application/pdf`, for
impact later. **It only reads; it never downloads.**

Errors, as problem details: `400` an id that isn't a number; `401`
missing or bad token; `403` a user token; `404` with one of:

| `detail` | Meaning |
|---|---|
| `No document <id>` | no document with that id |
| `Document <id> has no stored PDF` | a notice, or a PDF that's `pending` or `not_found` |
| `The PDF for document <id> is missing from disk` | the row points at a file that isn't there (e.g. a local database and upload folder reset separately) |

It isn't keyed by DOI: one DOI can have several stored copies (one per
report for the current copy, and one per user tracking the paper).

### Snapshot fields

Kept in full and per paper (insert-only). Every nullable field is
**null, never false**, when its source failed or didn't know the DOI
(see `source_status`); nothing that compares snapshots may treat a null,
or a field whose source wasn't `ok`, as a change.

Stored snapshots never have `error` for `crossref` or `openalex`: Updating
stores nothing for a paper on a poll where either failed (see "Poll job"). So
for those two sources a null field means the source didn't know the DOI
(`not_found`), and only `openalex_authors` can be `error`.

| Field | Type | Source | Used for |
|---|---|---|---|
| `snapshot_id` | bigint, ascending | Storage Management | ordering, watermark |
| `paper_id` | uuid | Storage Management | key |
| `doi` | text, normalised | Updating | fetch key |
| `fetched_at` | timestamptz | Updating | audit |
| `openalex_id` | text, short form (`W…`) | OpenAlex | work id |
| `is_retracted` | bool, nullable | OpenAlex `is_retracted` | retraction alert |
| `crossref_updates` | JSON array of `{notice_doi, type, label, source, date, record_id}` | Crossref `updated-by` (not `relation`, not `update-to`) | retraction, correction, erratum, expression-of-concern alerts |
| `in_doaj` | bool, nullable | OpenAlex `primary_location.source.is_in_doaj` (no separate DOAJ call) | DOAJ delisting alert, journal context |
| `journal_source_id`, `journal_source_type` | text | OpenAlex `primary_location.source.id` / `.type` | guard for the DOAJ alert, journal context |
| `journal`, `issn_l`, `publisher` | text | OpenAlex source `display_name` / `issn_l` / `host_organization_name` | journal context |
| `authors` | JSON array, first 10, in authorship order | OpenAlex `authorships` plus one batched `/authors` lookup | author context |
| `cited_by_count` | int, nullable | OpenAlex only (Crossref's count differs) | stored, no alert |
| `title`, `publication_year` | text, int | OpenAlex | display |
| `source_status` | JSON `{crossref, openalex, openalex_authors}`, each `ok` / `not_found` / `error` | Updating | real change vs outage |

Rules:
- **`crossref_updates` entries are identified by (`notice_doi`, `type`).**
  Crossref lists the same notice once per source (`publisher`,
  `retraction-watch`), sometimes under different types. Keep every entry;
  a new source for a known pair is not a new entry. Types we don't alert
  on yet (`withdrawal`, `removal`, `partial_retraction`, ...) are stored
  anyway.
- **`in_doaj` belongs to the journal, not the paper.** Repository
  locations (e.g. PubMed) are always false, so an OpenAlex switch of
  `primary_location` from the journal to a repository flips `in_doaj` with
  no delisting. Whoever classifies a difference must only call
  `in_doaj` true → false a delisting when `journal_source_id` is unchanged
  and `journal_source_type` is `journal`.
- **`authors`:** `institutions` is the author's affiliations on this paper
  (every institution OpenAlex matched on the work's `authorships`, `[]` if
  none), not `last_known_institutions`.
  The batched `/authors` response is unordered; re-order it by `authorships`.
  `authors` is null when OpenAlex doesn't know the DOI
  (`openalex_authors` is `not_found`) and `[]` when the work lists none.
  An author's `h_index` and `works_count` are null when the batch failed
  (`openalex_authors` is `error`, and the author list is kept) or the
  author has no OpenAlex id.
- **DOIs are normalised the way Storage Management's `MetadataClient`
  does:** trim, strip `https?://(dx.)?doi.org/` or `doi:`
  (case-insensitive), lowercase; empty becomes null. Papers with no DOI
  are skipped by Updating.
- JSON is snake_case both ways.
- `background_text` (COI text, `claims_assessment`) is unchanged and
  written by the Research Evaluation flow, not by Updating. There's no
  `snippet_text` column; Semantic Scholar snippets are cached inside
  Research Evaluation, not stored per paper.

## Research Evaluation

Called only by Updating, with the nudge (`POST /evaluate/changes`) when
papers changed. Research Evaluation in turn calls Storage Management: it
reads snapshots (and later the PDF, notes and text) and stores alerts.
Storage Management never calls Research Evaluation, and neither does the
frontend: alerts reach the frontend through Storage Management. See
DECISIONS.md, "2026-09-26 — Research Evaluation is called only by
Updating's nudge".

The other three endpoints below (`/evaluate/background-info`,
`/evaluate/citation-neighbourhood`, `/evaluate/stance`) are **under
review**. They're from the 2026-09-18 design, when Storage Management and
Updating called Research Evaluation for them; that no longer holds. They
are likely to become steps inside Research Evaluation's own evaluation
rather than endpoints other services call, to be settled in the stance and
claims stories. None of them is built.

### `POST /evaluate/changes`

Called by Updating at the end of a poll in which a paper's new snapshot
differed from its previous one (see "When Updating nudges" below). It is a
nudge, not a payload: it carries only the ids of the changed papers, never
snapshot or change data. Updating records no changes, so **Research
Evaluation works out the differences itself**: it reads each paper's
newest N snapshots from Storage Management
(`GET /internal/papers/{id}/background-info/history?last=N`, N =
`EVALUATION_SNAPSHOT_WINDOW`, default 5), compares every consecutive pair
of those and classifies each difference. It then asks which
changes already have an alert (`GET /internal/papers/{id}/alerts/change-keys`,
skipped when nothing was detected) and evaluates only the new ones
(severity, description, recommendation), storing each as an alert in
Storage Management. A change already stored, or one that appears in two
pairs of the same history (e.g. the retraction flag on one poll and the
retraction notice on a later one), is evaluated once. Reading the paper's non-updatable data (notes,
extracted text, and the stored PDF from `GET /internal/papers/{id}/pdf`)
is for later stories. Updating never calls this on a poll with no changes.

Service JWT only: `401` for a missing, bad or expired token, `403` for a
user token. A body that isn't `{"paper_ids": [uuid, ...]}` gets `422`.

Classification, from the sprint's alert stories:

| Change | Rule on the snapshots | Severity | `change_key` |
|---|---|---|---|
| Retraction | `is_retracted` false → true, or a new `crossref_updates` entry of type `retraction` | `high` | `retraction` |
| Expression of concern | a new `crossref_updates` entry of type `expression_of_concern` | `medium` | `expression_of_concern:<notice_doi>` |
| Correction | a new `crossref_updates` entry of type `correction` | `medium` | `correction:<notice_doi>` |
| Erratum | a new `crossref_updates` entry of type `erratum` | `low` | `erratum:<notice_doi>` |
| DOAJ delisting | `in_doaj` true → false, with the same `journal_source_id` and a `journal_source_type` of `journal` | `low` | `doaj_delisting:<snapshot_id>` |
| Other | a new `crossref_updates` entry of any other type (`withdrawal`, `removal`, `partial_retraction`, ...) | `medium` | `other:<type>:<notice_doi>` |

Entries are identified by (`notice_doi`, `type`), and nulls are never
compared (see "Snapshot fields"). A paper's first snapshot is only a
baseline. Also:
- **One retraction per paper.** The flag and a `retraction` entry are the
  same event, so they share the change key `retraction`; if they arrive on
  different polls, Storage Management keeps the first alert.
- **One alert per notice (temporary).** A notice Crossref lists under
  several types gives one alert, of the most severe type: `retraction`,
  `expression_of_concern`, `correction`, any unclassified type, `erratum`
  (IJAA's retraction notice is also a publisher `erratum`; a Lancet
  correction notice is also an `erratum`). A notice already seen on an
  earlier poll gives no new alert under a new type, unless it's now a
  `retraction`. Notices without a DOI can't be matched up and count per
  type. See DECISIONS.md, "2026-09-26 — One alert per Crossref notice,
  for now".
- An entry with no `type` is skipped. A missing `notice_doi` leaves the
  key's DOI part empty (`correction:`).
- The change key identifies the change within the paper, so a re-sent
  nudge stores nothing new (see `POST /internal/papers/{id}/alerts`).

The severity, description and recommendation are rule-based templates per
change type for now (see ARCHITECTURE.md, Section 3).

**Request:**

```json
{"paper_ids": ["uuid", "uuid"]}
```

Research Evaluation evaluates **before** it answers, so the reply says
whether the alerts were stored:

**Response `202`:** every paper was evaluated and its alerts are stored.
A paper Storage Management doesn't know is skipped, not a failure.

```json
{"alerts_created": 2, "skipped_paper_ids": [], "failed_paper_ids": []}
```

**Response `503`:** at least one paper failed, whatever the cause
(Storage Management unreachable, slow or answering an error, a
`JWT_SECRET` mismatch, a snapshot Research Evaluation can't read, a bug).
The body is the same summary plus `detail`; each failure is logged by
Research Evaluation with the paper id and the cause. The other papers are
still evaluated and their alerts stored.

Any status but `202` (or no response) means the nudge wasn't accepted,
and Updating re-sends the same paper ids on its next poll. That's safe:
Research Evaluation re-reads the newest N snapshots each time, and Storage
Management stores each change once. A change is still found as long as a
nudge gets through within N − 2 failed nudges in a row (N = 5: 3 failed
nudges, about 3 days at the default 24-hour poll); after more, it has left
the window and is lost. Updating waits for the evaluation, so
its HTTP timeout must allow for it: each Storage Management call has a
10-second timeout on Research Evaluation's side, and papers are evaluated
one after another. A timed-out nudge is simply re-sent.

Research Evaluation stores each change it evaluates as an alert in
Storage Management (`POST /internal/papers/{id}/alerts`, above), not in a
database of its own. The frontend reads alerts and acknowledges or
dismisses them through Storage Management, never by calling Research
Evaluation (see DECISIONS.md, "2026-09-25 — Alerts: stored in Storage
Management, evaluated in stages").

**Investigation runs after the reply**
([EVALUATION-INVESTIGATION.md](EVALUATION-INVESTIGATION.md)). Once the
`202` (or `503`) has been sent, Research Evaluation goes through each
paper evaluated without failure that had changes in its window, in the
background:

1. `POST /internal/papers/{id}/reports`: open a report for the paper's
   alerts not in one yet (`204`: nothing new, stop);
2. plan the documents those alerts need, and for each, fetch its Crossref
   record (`api.crossref.org`) and open-access text (Europe PMC), then
   `POST /internal/documents` (Storage Management downloads the PDF of a
   new version or current copy before answering, so this call has a long
   timeout, `INVESTIGATION_PDF_TIMEOUT_SECONDS`);
3. `PATCH /internal/papers/{id}/reports/{reportId}` to `investigated`.

Nothing in it changes the reply, and Updating never waits for it. A
failure is logged and leaves the report `investigating`; nothing retries
it yet. The finished reports' ids are what impact (a later plan) will
evaluate.

### `POST /evaluate/background-info`

> **Sprint 1 ownership change:** Updating now fetches and snapshots the
> retraction, Crossref update, DOAJ, journal and author fields (see
> "Snapshot fields" above). Which of the fields below this endpoint keeps
> serving is for Research Evaluation to confirm; the DTO is left as is
> until then.

**Request:**

```json
{"doi": "10.xxxx/...", "openalex_id": "W...", "issn": "0000-0000", "pdf_url": "http://localhost:8081/internal/papers/{id}/pdf", "include_llm": true}
```

`pdf_url` is the paper's `GET /internal/papers/{id}/pdf` on Storage
Management, which Research Evaluation fetches with its own service
token. It's left out when the paper has no stored PDF. Research
Evaluation only fetches a `pdf_url` under `SM_BASE_URL`, so its service
token is never sent anywhere else.

**Response — `BackgroundInfoDTO`:**

```json
{
  "identity": {
    "doi": "10.xxxx/...", "openalex_id": "W...", "title": "...",
    "journal": "...", "issns": ["0000-0000"], "publication_year": 2020,
    "fetched_at": "2026-09-18T12:00:00Z"
  },
  "metadata": {
    "is_retracted": true,
    "crossref_updates": [
      {"type": "retraction", "label": "Retraction", "source": "publisher", "notice_doi": "10.xxxx/...", "date": "2020-05-22"}
    ],
    "cited_by_count": 4965,
    "in_doaj": false
  },
  "authors": [
    {"name": "...", "openalex_author_id": "A...", "h_index": 12, "institution": "...", "works_count": 40}
  ],
  "citation_metrics": {
    "self_citation_ratio": 0.05, "retracted_references": ["W..."],
    "retracted_reference_count": 1, "citing_institution_count": 120,
    "citing_source_count": 80, "citations_per_year": 827.5,
    "sample_size": 1000, "truncated": true
  },
  "text": {"abstract": "...", "tldr": "...", "coi_text": "..."},
  "claims_assessment": {
    "key_claims": [{"claim": "...", "evidence_quote": "..."}],
    "study_design": "...", "sample_size": "...",
    "methodology_flags": [{"flag": "...", "severity": "low", "evidence_quote": "..."}],
    "limitations_acknowledged": true
  },
  "source_status": {"crossref": "ok", "openalex": "ok", "s2": "ok", "grobid": "ok", "llm": "ok"}
}
```

**Null-not-false rule:** a failed source produces `null` for its fields,
never a default `false` — so a source outage is never mistaken for a
real status change. Check `source_status` for each field's provenance.
The same rule applies to Updating's snapshots.

### `POST /evaluate/citation-neighbourhood`

**Request:** `{"openalex_id": "W..."}` or `{"doi": "10.xxxx/..."}`
**Response:** the `citation_metrics` object shown above (also embedded in
background-info).

### `POST /evaluate/stance`

**Request:**

```json
{"tracked": {"doi": "10.xxxx/..."}, "candidate": {"doi": "10.xxxx/..."}, "refresh": false}
```

Note: this takes **DOIs, not Storage Management paper ids** — a
candidate paper needn't exist in Storage Management, and this keeps
Research Evaluation free of runtime calls to Storage Management.
`refresh=true` forces fresh Semantic Scholar snippets and a fresh LLM
call instead of reusing cached ones (see DECISIONS.md on snippet reuse).

**Response:**

```json
{
  "stance": "contradicts",
  "confidence": 0.7,
  "rationale": "...",
  "per_claim": [{"claim": "...", "stance": "contradicts", "evidence_quote": "..."}],
  "insufficient_text": false,
  "quote_verified": true
}
```

`confidence` is model-reported and uncalibrated — label it as such in
the UI, don't present it as a probability.

## Updating

Not called by the frontend. Updating records snapshots only, never
changes: it has no change list or researcher actions (those are Research
Evaluation's). It calls Storage Management (`/internal/**`) and Research
Evaluation (`/evaluate/changes`).

### Poll job

Each poll (every `POLL_INTERVAL_HOURS`, or `POST /run-poll`):

1. lists tracked papers from Storage Management, skipping and logging
   papers with no DOI;
2. fetches each DOI once from Crossref and OpenAlex;
3. stores one snapshot per tracked paper in Storage Management, even when
   nothing changed (its `fetched_at` is when the paper was last checked).
   If Crossref or OpenAlex returned `error` for a paper's DOI, it stores no
   snapshot for that paper, lists it under `source_errors` in the run summary
   and retries on the next poll; `not_found` is stored;
4. compares each new snapshot with the paper's previous one, read back from
   Storage Management before the new one is stored (see below), and sets
   `nudge_pending` on the paper in its own `tracked_papers` table if they
   differ. If that read fails, it stores no snapshot for the paper, lists it
   under `store_errors` and retries on the next poll: storing anyway would
   make the next poll compare against this snapshot and miss the change;
5. sends the ids of all papers with `nudge_pending` to
   `POST /evaluate/changes`. On a `202` it clears the flag. A poll with no
   changes sends nothing; after a failed nudge the flag stays set and the
   next poll re-sends those ids (the next snapshot would otherwise look
   unchanged, so Research Evaluation would never hear about the change).

### When Updating nudges

Updating does a plain comparison and doesn't classify what changed; it
records no change, only the flag above. It nudges when the new snapshot
differs from the previous one in any of these fields (nulls, and fields
whose source wasn't `ok`, are never compared):

| Field | Nudge when |
|---|---|
| `is_retracted` | false → true |
| `crossref_updates` | a new (`notice_doi`, `type`) entry of type `retraction`, `correction`, `erratum` or `expression_of_concern` |
| `in_doaj` | true → false |

- A paper's first snapshot is its baseline and never nudges.
- Re-running a poll doesn't nudge again for the same difference: the next
  comparison is against the snapshot the previous run stored.
- Citation counts, authors, titles and other stored fields don't nudge.

### `POST /run-poll?paper_id=`

No auth in sprint 1 (see "Auth"). Runs the poll job synchronously (used for
the demo and for testing, since a real change won't reliably land inside a
10-minute slot) and returns the run summary. It is recorded in `poll_runs`
with trigger `manual`, so it never moves the schedule of the automatic polls.

`paper_id` (optional) limits fetching and storing to that paper. Everything
else in "Poll job" is unchanged: the other papers stay tracked, and step 5
still sends every paper with `nudge_pending`, not just this one. Other papers
that share its DOI aren't stored until the next full poll. A paper Storage
Management knows but that has no DOI comes back in `skipped_no_doi`, not as an
error.

| Status | When |
|---|---|
| `200` | the run summary, below |
| `404` | `paper_id` isn't a paper Storage Management lists |
| `409` | a poll is already running. Only one runs at a time: a scheduled poll that comes due meanwhile waits for it, and a manual call that arrives at the very moment it is handed the lock waits too |
| `422` | `paper_id` isn't a uuid |
| `502` | Storage Management's paper list was unavailable |

```json
{
  "run_id": 7, "trigger": "manual", "paper_id": "uuid",
  "started_at": "2026-09-25T08:00:00Z", "finished_at": "2026-09-25T08:00:03Z",
  "stored": [{"paper_id": "uuid", "doi": "10.xxxx/...", "snapshot_id": 42}],
  "skipped_no_doi": [],
  "source_errors": [{"paper_id": "uuid", "doi": "10.xxxx/...", "crossref": "ok", "openalex": "error"}],
  "store_errors": [{"paper_id": "uuid", "doi": "10.xxxx/...", "reason": "reading history: HTTP 503"}],
  "nudged": ["uuid"],
  "nudge_error": null
}
```

| Field | Meaning |
|---|---|
| `paper_id` | the paper the run was limited to; null for a full run |
| `stored` | papers a snapshot was stored for, with its `snapshot_id` |
| `skipped_no_doi` | papers with no DOI, which aren't polled |
| `source_errors` | papers with no snapshot this run because Crossref or OpenAlex returned `error` for their DOI (the outage gate); retried next poll |
| `store_errors` | papers with no snapshot this run because Storage Management failed: the previous-snapshot read (`reading history: …`) or the store itself; retried next poll |
| `nudged` | every paper id sent to `POST /evaluate/changes` and accepted with `202`, including ones left over from an earlier failed nudge |
| `nudge_error` | `{paper_ids, reason}` when the nudge wasn't accepted; those papers stay pending and are re-sent next poll, otherwise null |

The summary lists ids only. Why a paper was nudged (which field changed) is
logged by Updating, not returned.

## Env vars every service needs to agree on

| Var | Used by | Notes |
|---|---|---|
| `JWT_SECRET` | all | base64-encoded, 32+ bytes (`openssl rand -base64 32`); every service decodes before use. Sprint 1: a shared throwaway value in that format |
| `SM_BASE_URL` | Research Evaluation, Updating | Storage Management's base URL (`http://localhost:8081` locally) |
| `RE_BASE_URL` | Storage Management, Updating | Research Evaluation's base URL (Updating's nudge goes here) |

See [SETUP.md](SETUP.md) for the full env var list including the
third-party API keys.
