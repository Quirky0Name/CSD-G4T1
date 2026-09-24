# Cross-service contracts

This is the single source of truth for every interface between services.
**A PR that changes an interface updates this file in the same PR.**

## Auth

- JWT: HS256, `sub` = user id, plus `exp`. Issued by User Management at
  login.
- `JWT_SECRET` is shared as base64 of 32+ random bytes. **Every service
  base64-decodes it before use** — a mismatched encoding is the classic
  Java↔Python JWT bug, so check this first if signature validation fails
  across a language boundary.
- Every downstream service (Storage Management, Research Evaluation,
  Updating) validates the JWT signature itself. No service calls back to
  User Management per request.
- **Service tokens:** Updating mints its own short-lived JWTs to call
  Storage Management, with `sub=svc:updating` and `role=service`.
  Storage Management must accept these on its internal endpoints without
  owner-scoping them to a real user.
- **Sprint 1:** there is no User Management yet, and everything runs
  locally. `JWT_SECRET` is a shared placeholder (any value, the same one in
  every service's local config), and Updating still mints service tokens
  with it. Updating's own endpoints take no token; `POST /admin/run-poll`
  is gated by `X-Admin-Key` only.

## Storage Management ↔ Research Evaluation / Updating

Owned by: Storage Management. Consumed by: Research Evaluation (reads
files), Updating (calls these on every poll).

### `GET /internal/papers`

Service-JWT only. Returns every tracked paper, for the poller to sync
into its own `tracked_papers` table.

```json
[{"id": "uuid", "owner_id": "uuid", "doi": "10.xxxx/...", "issn": "0000-0000", "file_available": true}]
```

### `POST /internal/papers/{id}/background-info`

Stores the snapshot in the request body as a new `background_metadata`
row (insert-only, never overwrite) and returns it with its id. Storage
Management doesn't fetch anything or call Research Evaluation here;
Updating fetched the data. Updating sends one snapshot per tracked paper
per poll, including polls where nothing changed.

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

Ascending order by snapshot id. Used by Updating to diff each
consecutive pair since its last watermark.

```json
{"snapshots": [{"snapshot_id": 41, "fetched_at": "...", "...": "snapshot"}, {"snapshot_id": 42, "...": "..."}]}
```

### Snapshot fields

Kept in full and per paper (insert-only). Every nullable field is
**null, never false**, when its source failed or didn't know the DOI
(see `source_status`); Updating never diffs a null.

| Field | Type | Source | Used for |
|---|---|---|---|
| `snapshot_id` | bigint, ascending | Storage Management | diff order, watermark |
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
  a new source for a known pair is not a new event. Types we don't alert
  on yet (`withdrawal`, `removal`, `partial_retraction`, ...) are stored
  anyway.
- **`in_doaj` belongs to the journal, not the paper.** Repository
  locations (e.g. PubMed) are always false. A delisting is only
  `in_doaj` true → false with the same `journal_source_id` and a
  `journal_source_type` of `journal`.
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

### Internal file URL

Storage Management exposes an internal URL Research Evaluation can fetch
an uploaded PDF from (forwarding the caller's bearer token), passed to
Research Evaluation as `pdf_url`.

## Research Evaluation

Called by Updating (change evaluation, and stance checks if ever needed
for a manual comparison) and Storage Management (COI text / claims).
Not called directly by the frontend.

### `POST /evaluate/change`

Called by Updating once per detected change. Research Evaluation says
what the change means; it fetches nothing and has no side effects, so the
same `change_id` always gives the same answer and re-sending is safe.

**Request:**

```json
{
  "change_id": 7, "paper_id": "uuid", "doi": "10.xxxx/...",
  "change_type": "retraction",
  "source": "openalex",
  "field": "is_retracted", "old_value": "false", "new_value": "true",
  "details": {"notice_doi": "10.xxxx/...", "label": "Retraction", "source": "publisher", "date": "2020-05-22"},
  "detected_at": "2026-09-24T12:00:00Z"
}
```

`change_type` is one of `retraction`, `correction`, `erratum`,
`expression_of_concern`, `doaj_delisted`. `source` is `openalex` or
`crossref`. An unknown `change_type` still gets a generic answer, so a
change is never stuck unevaluated.

**Response `200`:**

```json
{"severity": "high", "impact_text": "OpenAlex now marks this paper as retracted.", "recommendation": "Read the retraction notice before citing it; review any notes that rely on this paper."}
```

`severity` is `low`, `medium` or `high`.

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

Called by the frontend directly. Updating calls Storage Management
(`/internal/**`) and Research Evaluation (`/evaluate/change`).

### Poll job and diff rules

Each poll (every `POLL_INTERVAL_HOURS`, or `POST /admin/run-poll`):
lists tracked papers from Storage Management, skips and logs papers with
no DOI, fetches each DOI once from Crossref and OpenAlex, and stores one
snapshot per tracked paper. It then diffs each paper's snapshots after its
watermark, writes a change row per change, and pushes each to
`POST /evaluate/change`. Rows whose evaluation failed stay `pending` and
are re-sent first on the next poll.

| `change_type` | `source` | Rule |
|---|---|---|
| `retraction` | `openalex` | `is_retracted` false → true |
| `retraction` | `crossref` | new (`notice_doi`, `retraction`) entry in `crossref_updates` |
| `correction`, `erratum`, `expression_of_concern` | `crossref` | new (`notice_doi`, type) entry in `crossref_updates` |
| `doaj_delisted` | `openalex` | `in_doaj` true → false, same `journal_source_id`, type `journal` |

- No change produces no row, and re-running a poll creates no duplicates.
- A paper's first snapshot is its baseline and is never diffed.
- A null field, or a source whose `source_status` isn't `ok`, is never
  diffed.

### `GET /papers/{id}/changes`

`sub` must equal the paper's `owner_id` (not enforced in sprint 1, see
Auth). Returns newest first.

```json
[{
  "id": 7, "paper_id": "uuid", "doi": "10.xxxx/...",
  "change_type": "retraction", "source": "openalex",
  "changed_field": "is_retracted",
  "old_value": "false", "new_value": "true",
  "detected_at": "2026-09-18T12:00:00Z",
  "snapshot_ids": [41, 42],
  "evaluation_status": "done",
  "severity": "high",
  "impact_text": "OpenAlex now marks this paper as retracted.",
  "recommendation": "Read the retraction notice before citing it; review any notes that rely on this paper.",
  "details": {"...": "raw diff context, e.g. the crossref_updates entry"},
  "status": "new"
}]
```

`evaluation_status` is `pending` or `done`. While `pending`, `severity`,
`impact_text` and `recommendation` are null. `status` is the researcher's
decision (`new`, `acknowledged`, `dismissed`).

### `PATCH /changes/{event_id}`

**Request:** `{"status": "acknowledged" | "dismissed"}`
The "researcher decides" step of the core loop.

### `POST /admin/run-poll?paper_id=`

Requires header `X-Admin-Key`; a missing or wrong key is rejected.
Runs the poll job synchronously (used for the demo, since a real change
won't reliably land inside a 10-minute slot). Returns the run summary plus
the events it created.

## Env vars every service needs to agree on

| Var | Used by | Notes |
|---|---|---|
| `JWT_SECRET` | all | base64-encoded, 32+ bytes; every service decodes before use. Sprint 1: any shared placeholder value |
| `SM_BASE_URL` | Research Evaluation, Updating | Storage Management's base URL (`http://localhost:8081` locally) |
| `RE_BASE_URL` | Storage Management, Updating | Research Evaluation's base URL |
| `ADMIN_API_KEY` | Updating | for `/admin/run-poll` |

See [SETUP.md](SETUP.md) for the full env var list including the
third-party API keys.
