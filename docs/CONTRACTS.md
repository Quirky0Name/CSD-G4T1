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
  real user.
- **Sprint 1:** there is no User Management yet, and everything runs
  locally. `JWT_SECRET` is a shared throwaway value (base64 of 32+ bytes like
  the real one, the same in every service's local config), and Updating still
  mints service tokens with it. Updating's own endpoints take no token; `POST /admin/run-poll`
  is gated by `X-Admin-Key` only.

## Frontend ↔ Storage Management

Owned by: Storage Management. Consumed by: the frontend.

### `POST /papers` (PDF upload)

User JWT required; the caller becomes the paper's owner. Multipart form
with `file` (a PDF, 25 MB max) and an optional `folder_id` (uuid).
Storage pulls the DOI out of the PDF with GROBID and fills in the rest
from CrossRef/OpenAlex. If GROBID finds no DOI the paper is still saved,
just without metadata. The PDF itself isn't kept.

**Response `201`:**
```json
{"id": "uuid", "folder_id": "uuid", "doi": "10.xxxx/...", "openalex_id": "W...", "title": "...", "journal": "...", "issn": "0000-0000", "publication_year": 2020, "created_at": "2026-09-24T08:00:00Z"}
```

Errors come back as problem details, with the reason in `detail`:
`400` not a PDF, `401` missing or bad token, `403` service token,
`409` you already track a paper with that DOI, `413` over 25 MB.

### `POST /papers` (track by DOI)

Same endpoint, sent as JSON instead of multipart, for a paper you don't
have a PDF for. User JWT required; the caller becomes the paper's owner.
The DOI is normalised (a `https://doi.org/` or `doi:` prefix is fine,
case doesn't matter) and looked up on CrossRef/OpenAlex before saving.

**Request:** `{"doi": "10.xxxx/...", "folder_id": "uuid"}`. `folder_id` is
optional; leaving it out or sending `""` means no folder.

**Response `201`:** same shape as the upload.

Errors come back as problem details, with the reason in `detail`:
`400` no DOI or not a DOI, `401` missing or bad token, `403` service
token, `409` you already track a paper with that DOI, `422` CrossRef has
no paper with that DOI, `503` CrossRef couldn't be reached (nothing is
saved, try again).

## Storage Management ↔ Research Evaluation / Updating

Owned by: Storage Management. Consumed by: Research Evaluation (reads
the snapshots and paper data of the papers Updating tells it about),
Updating (calls these on every poll).

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
Crossref or OpenAlex lookup errored that poll (see "Poll job").

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
    {"name": "...", "openalex_author_id": "A...", "position": "first", "institution": "...", "h_index": 12, "works_count": 40}
  ],
  "cited_by_count": 1252,
  "source_status": {"crossref": "ok", "openalex": "ok", "openalex_authors": "ok"}
}
```

**Response `201`:** the same body plus `snapshot_id` and `paper_id`.

### `GET /internal/papers/{id}/background-info/history?after_id=&limit=`

Ascending order by snapshot id. Used by Updating (to read a paper's
previous snapshot before deciding whether to nudge) and by Research
Evaluation (to read the snapshots it works out differences from).

```json
{"snapshots": [{"snapshot_id": 41, "fetched_at": "...", "...": "snapshot"}, {"snapshot_id": 42, "...": "..."}]}
```

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
- **`authors`:** `institution` is the author's affiliation on this paper
  (from the work's `authorships`), not `last_known_institutions`. The
  batched `/authors` response is unordered; re-order it by `authorships`.
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

Called by Updating (a nudge when papers changed, and stance checks if ever
needed for a manual comparison) and Storage Management (COI text / claims).
Not called directly by the frontend.

### `POST /evaluate/changes`

Called by Updating at the end of a poll in which a paper's new snapshot
differed from its previous one (see "When Updating nudges" below). It is a
nudge, not a payload: it carries only the ids of the changed papers, never
snapshot or change data. Updating records no changes, so **Research
Evaluation works out the differences itself**: it reads the papers'
snapshots from Storage Management
(`GET /internal/papers/{id}/background-info/history`) plus their
non-updatable data (notes, extracted text), compares the snapshots,
classifies each difference and evaluates it (severity, impact statement,
recommendation). Nothing is returned to Updating, and Updating never calls
this on a poll with no changes.

Classification, from the sprint's alert stories:

| Change | Rule on the snapshots |
|---|---|
| Retraction | `is_retracted` false → true, or a new `crossref_updates` entry of type `retraction` |
| Correction / erratum / expression of concern | a new `crossref_updates` entry of that type |
| DOAJ delisting | `in_doaj` true → false, with the same `journal_source_id` and a `journal_source_type` of `journal` |

Entries are identified by (`notice_doi`, `type`), and nulls are never
compared (see "Snapshot fields"). A paper's first snapshot is only a
baseline.

**Request:**

```json
{"paper_ids": ["uuid", "uuid"]}
```

**Response `202`:** accepted; Research Evaluation does the reading and
evaluating after responding, so Updating doesn't wait on it. Any other
status (or no response) means the nudge wasn't accepted and Updating will
re-send the same paper ids on its next poll, so this call must be safe to
receive more than once for the same paper.

How Research Evaluation stores the evaluation and how the frontend reads
it, and the change list and researcher actions (acknowledge, dismiss)
the frontend uses, are Research Evaluation's design and aren't specified
here yet.

### `POST /evaluate/background-info`

> **Sprint 1 ownership change:** Updating now fetches and snapshots the
> retraction, Crossref update, DOAJ, journal and author fields (see
> "Snapshot fields" above). Which of the fields below this endpoint keeps
> serving is for Research Evaluation to confirm; the DTO is left as is
> until then.

**Request:**

```json
{"doi": "10.xxxx/...", "openalex_id": "W...", "issn": "0000-0000", "pdf_url": "https://...", "include_llm": true}
```

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

Each poll (every `POLL_INTERVAL_HOURS`, or `POST /admin/run-poll`):

1. lists tracked papers from Storage Management, skipping and logging
   papers with no DOI;
2. fetches each DOI once from Crossref and OpenAlex;
3. stores one snapshot per tracked paper in Storage Management, even when
   nothing changed (its `fetched_at` is when the paper was last checked).
   If Crossref or OpenAlex returned `error` for a paper's DOI, it stores no
   snapshot for that paper, lists it under `source_errors` in the run summary
   and retries on the next poll; `not_found` is stored;
4. compares each new snapshot with the paper's previous one, read back from
   Storage Management (see below), and sets `nudge_pending` on the paper
   in its own `tracked_papers` table if they differ;
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

### `POST /admin/run-poll?paper_id=`

Requires header `X-Admin-Key`; a missing or wrong key is rejected.
Runs the poll job synchronously (used for the demo, since a real change
won't reliably land inside a 10-minute slot). Returns the run summary,
including which papers it stored snapshots for and which it nudged
Research Evaluation about.

## Env vars every service needs to agree on

| Var | Used by | Notes |
|---|---|---|
| `JWT_SECRET` | all | base64-encoded, 32+ bytes (`openssl rand -base64 32`); every service decodes before use. Sprint 1: a shared throwaway value in that format |
| `SM_BASE_URL` | Research Evaluation, Updating | Storage Management's base URL (`http://localhost:8081` locally) |
| `RE_BASE_URL` | Storage Management, Updating | Research Evaluation's base URL (Updating's nudge goes here) |
| `ADMIN_API_KEY` | Updating | for `/admin/run-poll` |

See [SETUP.md](SETUP.md) for the full env var list including the
third-party API keys.
