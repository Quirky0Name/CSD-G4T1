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

## Frontend ↔ Storage Management

Owned by: Storage Management. Consumed by: the frontend.

### `POST /papers` (PDF upload)

User JWT required; the caller becomes the paper's owner. Multipart form
with `file` (a PDF, 25 MB max) and an optional `folder_id` (uuid).
Storage pulls the DOI out of the PDF with GROBID and fills in the rest
from CrossRef/OpenAlex. If GROBID finds no DOI the paper is still saved,
just without metadata.

**Response `201`:**
```json
{"id": "uuid", "folder_id": "uuid", "doi": "10.xxxx/...", "openalex_id": "W...", "title": "...", "journal": "...", "issn": "0000-0000", "publication_year": 2020, "file_available": true, "created_at": "2026-09-24T08:00:00Z"}
```

Errors come back as problem details, with the reason in `detail`:
`400` not a PDF, `401` missing or bad token, `403` service token,
`409` you already track a paper with that DOI, `413` over 25 MB.

## Storage Management ↔ Research Evaluation / Updating

Owned by: Storage Management. Consumed by: Research Evaluation (reads
files), Updating (calls these on every poll).

### `GET /internal/papers`

Service-JWT only. Returns every tracked paper, for the poller to sync
into its own `tracked_papers` table.

```json
[{"id": "uuid", "owner_id": "uuid", "doi": "10.xxxx/...", "issn": "0000-0000", "file_available": true}]
```

### `POST /internal/papers/{id}/background-info?include_llm=true|false`

Triggers a fetch: Storage Management calls Research Evaluation's
`/evaluate/background-info`, persists the result as a new
`background_metadata`/`background_text` row (insert-only, never
overwrite), and returns the persisted snapshot including its id.

```json
{"snapshot_id": 42, "fetched_at": "2026-09-18T12:00:00Z", "...": "full BackgroundInfoDTO, see below"}
```

### `GET /internal/papers/{id}/background-info/history?after_id=&limit=`

Ascending order by snapshot id. Used by Updating to diff each
consecutive pair since its last watermark.

```json
{"snapshots": [{"snapshot_id": 41, "fetched_at": "...", "...": "BackgroundInfoDTO"}, {"snapshot_id": 42, "...": "..."}]}
```

### Storage Management schema changes needed (from the original vision doc)

- `background_metadata`:
  - replace the unverified `update_to` column with **`crossref_updates`**
    (JSON array — see `BackgroundInfoDTO.metadata.crossref_updates`
    below; sourced from Crossref's `updated-by` field, not `relation`)
  - add `openalex_id`, `citation_metrics` (JSON), `source_status` (JSON)
  - rename/relabel `journal_legitimate` → `in_doaj` (now sourced from
    OpenAlex's `primary_location.source.is_in_doaj`, not a separate DOAJ
    API call)
- `background_text`: add `claims_assessment` (JSON); **no** `snippet_text`
  column — Semantic Scholar snippets are fetched per query at stance
  time and cached inside Research Evaluation, not stored per paper.

### Internal file URL

Storage Management exposes an internal URL Research Evaluation can fetch
an uploaded PDF from (forwarding the caller's bearer token), passed to
Research Evaluation as `pdf_url`.

## Research Evaluation

Called by Storage Management (background-info fetches) and Updating
(stance checks, if ever needed for a manual comparison). Not called
directly by the frontend.

### `POST /evaluate/background-info`

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
    "crossref_retracted": true,
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
never a default `false` — so Updating can never mistake a source outage
for a real status change. Check `source_status` for each field's
provenance.

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

Called by the frontend directly.

### `GET /papers/{id}/changes`

User JWT required; `sub` must equal the paper's `owner_id`. Returns
newest first.

```json
[{
  "id": 7, "paper_id": "uuid", "changed_field": "is_retracted",
  "old_value": "false", "new_value": "true",
  "detected_at": "2026-09-18T12:00:00Z", "severity": "high",
  "impact_text": "OpenAlex now marks this paper as retracted.",
  "recommendation": "Read the retraction notice before citing it; review any notes that rely on this paper.",
  "details": {"...": "raw diff context, e.g. the crossref_updates entry"},
  "status": "new"
}]
```

### `PATCH /changes/{event_id}`

**Request:** `{"status": "acknowledged" | "dismissed"}`
The "researcher decides" step of the core loop.

### `POST /admin/run-poll?paper_id=`

Requires header `X-Admin-Key`. Runs the poll job synchronously (used for
the demo, since a real change won't reliably land inside a 10-minute
slot). Returns the run summary plus the events it created.

## Env vars every service needs to agree on

| Var | Used by | Notes |
|---|---|---|
| `JWT_SECRET` | all | base64-encoded, 32+ bytes; every service decodes before use |
| `SM_BASE_URL` | Research Evaluation, Updating | Storage Management's base URL |
| `RE_BASE_URL` | Storage Management, Updating | Research Evaluation's base URL |
| `ADMIN_API_KEY` | Updating | for `/admin/run-poll` |

See [SETUP.md](SETUP.md) for the full env var list including the
third-party API keys Research Evaluation needs.
