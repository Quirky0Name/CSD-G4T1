# Decisions log

Dated log of decisions and why they were made, so settled questions stay
settled and anyone (including the TA) can see the reasoning. Newest first.

---

## 2026-09-25 — Storage keeps every tracked paper's PDF

### Team decisions

- **Storage Management keeps the PDF of every tracked paper**, whether it
  was added by upload or by DOI. Research Evaluation needs the paper
  itself to evaluate a change: a snapshot only says that a paper was
  retracted or corrected, and judging what that means for the researcher
  needs the paper's text. GROBID's COI/funding extraction and the claims
  prompt, both in week-7 scope, also run on the PDF. This reverses commit
  `32fb3be` ("stop keeping uploaded PDFs"), which read an upload with
  GROBID and then discarded it. That change was never logged here, and it
  left Research Evaluation nothing to read.
- **Local disk for now.** PDFs go in a folder on Storage Management's
  host, never in Postgres; `papers` holds only the file's key. Deployed,
  the folder is a persistent volume on the VM (ARCHITECTURE.md, Section
  5). It needs no cloud account or credentials for anyone running Storage
  Management, and the whole stack runs on one VM anyway.
- **Research Evaluation reads PDFs only through
  `GET /internal/papers/{id}/pdf`** (service JWT), never from the folder.
  That keeps "Research Evaluation reads everything from Storage
  Management" true, and it keeps working if the two services end up on
  different hosts or the files move to S3. `pdf_url` in
  `/evaluate/background-info` now means that endpoint.
- **Pending: where a DOI-only paper's PDF comes from.** Amir decides, as
  part of DOI tracking. The options:
  - download the open-access PDF (OpenAlex `best_oa_location`) and, if
    there's none or the publisher blocks the download, track the paper
    without a file;
  - download the open-access PDF and refuse to track the paper when
    there's none;
  - require the user to upload the PDF for a DOI-only paper.

  Until it's settled a paper may have no stored PDF, so
  `GET /internal/papers/{id}/pdf` returns `404` for one, and no
  `file_available` flag is added to responses yet. Paywalls matter here:
  the three demo papers are on ScienceDirect, which usually blocks bots
  (see DEMO.md).

### Rejected

- **Discarding the PDF once GROBID has read the DOI** (the `32fb3be`
  design). It leaves Research Evaluation nothing to evaluate a change
  against and drops the week-7 COI text and claims input.
- **Keeping only the text GROBID extracts, not the PDF.** It avoids
  storing files, but fixes the extraction at ingest: a better GROBID
  model, a different extraction (such as passages for week-13
  highlighting), or a re-run after a GROBID outage would all need the
  original.
- **S3 now.** It needs an AWS account, credentials for everyone who runs
  Storage Management, and an SDK, for a stack that runs on one VM. Since
  PDFs are only read through the internal endpoint, moving to S3 later
  changes nothing outside Storage Management.
- **Research Evaluation reading the upload folder directly.** It ties
  both services to one disk and skips the service-token check.

### Consequences

- The storage code doesn't match this yet: uploads are still discarded
  (commit `747849b` only changed ARCHITECTURE.md). `LocalFileStore` and
  the `papers` file key have to come back and
  `GET /internal/papers/{id}/pdf` has to be built. Some local databases
  have already run `V2__drop_papers_file_key.sql`, so the column comes
  back in a new migration rather than by deleting V2.
- The DOI-tracking contract on `feat/storage-track-doi` needs the PDF
  rule once the pending decision is made.

---

## 2026-09-25 — Updating stores no snapshot when Crossref or OpenAlex errors

### Decision

- If Crossref or OpenAlex returns `error` (timeout, 429, 5xx, an unreadable
  body) for a paper's DOI, Updating stores **no snapshot** for that paper on
  that poll. It lists the paper under `source_errors` in the run summary and
  retries on the next poll.
- Only `error` gates. `not_found` (e.g. DataCite DOIs) is a stable answer and
  is stored. A failed author batch (`openalex_authors`) is stored too, since
  authors aren't compared.

### Why

The docs never compare a field whose source wasn't `ok`, so a change
published during an outage would be lost for good. Mon ok; Tue Crossref down;
a correction is published; Wed ok. Tue's snapshot has null `crossref_updates`,
so Wed vs Tue is skipped, and Thu matches Wed: the correction is never
nudged. With the gate, Tue stores nothing, so Wed is compared with Mon and
nudges. Every stored snapshot is then comparable with the one before it, so
"compare with the previous snapshot" works unchanged for Updating and for
Research Evaluation.

### Rejected

Per-source "last ok" baselines (compare each source's fields against the last
snapshot where that source was `ok`). They need extra state per paper and
source, and Research Evaluation, which reads the snapshots itself, would have
to copy the same logic.

### Also settled while building it

- **`JWT_SECRET` must be base64 of 32+ bytes even in sprint 1.** The
  2026-09-24 entry below says "any shared value"; Storage Management
  base64-decodes the secret and its JWT library rejects keys under 32 bytes, so
  Updating checks the format at startup.
- **The first scheduled poll counts from the last scheduled poll**, not from
  startup, so redeploys and restarts can't keep pushing a 24-hour poll back.
- Live check: the publisher's `retraction` entry in Crossref's `updated-by` for
  `10.1016/j.ijantimicag.2020.105949` points at that paper's own DOI, and
  publisher entries have no `record-id` at all (Retraction Watch entries do).
  Both are stored as they come.

---

## 2026-09-24 — Updating owns fetching and snapshots; Research Evaluation evaluates

### Team decisions

- **Ownership split.** Updating fetches Crossref/OpenAlex status, journal and
  author data, stores snapshots (via Storage Management) and tells Research
  Evaluation when a snapshot changed. It records snapshots only, never
  changes. Research Evaluation works out the differences between snapshots,
  classifies them, evaluates what they mean (severity, impact statement,
  recommendation) and owns the alert list and actions. This replaces the
  2026-09-18 design where Research Evaluation built the background-info
  snapshot and Updating diffed it, kept `change_events` and attached canned
  impact text from a lookup table.
- **The handoff is a nudge with paper ids, not a payload.** Updating and
  Research Evaluation share data only through Storage Management. Per poll:
  fetch from the APIs → store the snapshot in Storage Management → compare
  it with the previous snapshot (read back from Storage Management) → if it
  differs in a field the alerts depend on, `POST /evaluate/changes` with just
  the changed `paper_ids`. Research Evaluation then reads the snapshots (the
  updatable data) and the paper, notes and extracted text (the non-updatable
  data) from Storage Management itself and works out the differences.
  Reasons: Research Evaluation would otherwise need Updating to hand it data
  it can already read, each side only has to talk to one other service, and
  Updating needs no change table of its own. A poll with no changes sends
  nothing, so Research Evaluation isn't woken on every check. Considered and
  rejected:
  - Pushing each change with its data and storing Research Evaluation's
    answer on Updating's row (the first version of this decision). It
    couples the two services' data shapes and needs Research Evaluation to
    reply.
  - A bare nudge with no ids. Research Evaluation would have to scan Storage
    Management for papers whose latest snapshot differs from the previous
    one and keep its own watermark.
  - Sending change ids and having Research Evaluation read change rows from
    Updating. It would need to call two services, and Updating would need to
    keep change rows.

  Updating's comparison is a plain field comparison to decide whether to
  nudge, not a classification. Because it keeps no change rows, it keeps a
  `nudge_pending` flag per paper in its own `tracked_papers` table, cleared
  once Research Evaluation accepted the nudge (`202`). This is needed
  because after a failed nudge the next snapshot would look unchanged. The
  endpoint must therefore tolerate receiving the same ids twice. Updating no
  longer serves a change list or `PATCH /changes/{id}`; how Research
  Evaluation stores and serves evaluations, changes and researcher actions is
  left to it.
- **Auth is a placeholder in sprint 1.** There's no User Management yet, so
  `JWT_SECRET` is any shared value and services run locally. Updating still
  mints service tokens; Storage Management only accepts those on
  `/internal/**`, which is why the background-info endpoints moved there.
  Nothing is hosted, so Supabase and the session-pooler note wait until
  deployment.
- **Snapshots stay per paper, insert-only, full history.** Considered
  keying them by DOI (one snapshot per DOI per run, fanned out to every
  user tracking it) and rejected it: it saves a few near-identical rows but
  needs fan-out logic, and per-paper history gives each user their own
  baseline, so nobody gets alerted about changes from before they started
  tracking. Keeping full history over only the latest snapshot: since
  snapshots are the only record, Research Evaluation can work out the
  differences after the fact, a missed or false alert can be replayed from
  the stored pair, and new rules can be back-tested. The cost is small (a
  few KB per row; 100 papers polled daily is about 36k rows a year). If
  size ever matters, prune unchanged snapshots that no change references.
- **Snapshots are stored even when nothing changed**, so the poll's
  "new snapshot each run" is checkable. Storage Management's POST is now a
  plain store of the snapshot in the body; it no longer triggers a fetch.
- **Citation counts are stored but not alerted on this sprint.** The
  2026-09-18 week-7 scope listed "did citation count jump"; the sprint's
  alert stories are retraction, correction/erratum/expression of concern,
  and DOAJ delisting only.

### Facts found while checking live Crossref/OpenAlex responses

- **`updated-by` lists duplicates.** The same notice DOI appears once from
  `publisher` and once from `retraction-watch`, sometimes with different
  types (`10.1016/s0140-6736(20)31324-6` is both a Retraction Watch
  "retraction" and a publisher "erratum"). Entries are identified by
  (`notice_doi`, `type`); a new source for a known pair is not a new
  event.
- **DOAJ status belongs to the journal, not the paper.** OpenAlex's
  `is_in_doaj` is per source, and repository locations (PubMed) are always
  false. If OpenAlex switched a paper's `primary_location` from the journal
  to a repository, `in_doaj` would flip to false without any delisting. So a
  delisting needs `in_doaj` true → false with the same `journal_source_id`
  and a `journal` source type.
- **Citation counts disagree between sources** (OpenAlex 1252 vs Crossref
  416 for the same retracted Lancet paper). Only OpenAlex's is stored.
- **OpenAlex's batched `/authors` lookup returns authors in its own
  order.** Re-order by the work's `authorships`, and take `institution`
  from the authorship (the affiliation on that paper), not
  `last_known_institutions`.
- **Crossref uses more update types than we alert on** (`withdrawal`,
  `removal`, `partial_retraction`, ...). They're stored, not alerted on.
- **A paper's stored title goes stale.** Crossref later prefixes
  "RETRACTED:" to a title, but `papers.title` is set once at ingest, so
  the UI should show the latest snapshot's title.
- **DataCite DOIs (Zenodo, arXiv) and wrongly extracted DOIs aren't in
  Crossref.** They're recorded as `not_found`, not treated as a change.

### Consequences

- Research Evaluation's `POST /evaluate/background-info` no longer serves the
  retraction, Crossref update, DOAJ, journal and author fields; its owner
  decides what it keeps serving (see CONTRACTS.md).
- The derived `crossref_retracted` field is dropped: a retraction is an
  entry of type `retraction` in `crossref_updates`.

---

## 2026-09-18 — Research Evaluation + Updating scope and design

### Team decisions (via Q&A)

- **LLM provider: DeepSeek**, not Claude/OpenAI. Chosen for cost — under
  1¢ per stance/claims call — with demo reliability handled by
  pre-warming the cache rather than by provider choice.
- **Updating's stack: Python**, sharing one codebase/image with Research
  Evaluation (two FastAPI apps). Avoids duplicating the
  `BackgroundInfoDTO`, JWT validation and HTTP client code across a
  Python and a Java project, which the original "Updating: TBD" left
  unresolved.
- **Updating's change data (`change_events`, `tracked_papers`,
  `poll_runs`) lives in Updating's own Postgres schema**, not in Storage
  Management's database, even though Section 2 of the original doc says
  Storage Management owns "every background-info/change snapshot." That
  line is read as covering the background-info snapshots themselves
  (which Storage Management does own); the derived diff/event data is
  Updating's own bookkeeping and putting it in Updating's schema avoids
  making every Updating write depend on Storage Management's uptime.
- **New-related-paper detection is out of scope for week 7.** The
  original brief listed two independent detection sources (status diff
  on a tracked paper, and new papers appearing that relate to it). Only
  the first is built for the midterm; the second moves to week 13
  alongside topic-based discovery, which needs similar infrastructure
  (OpenAlex topic/citation queries) anyway.
- **Repo layout:** Research Evaluation + Updating live in `backend/`;
  `frontend/` and `storage/` start as empty placeholder folders for the
  other two owners.

### Deviations from the original brief, found while verifying API facts live

- **Crossref retraction signal comes from `updated-by`, not `relation`.**
  The original brief marked this "UNVERIFIED — needs confirming against
  a live retracted DOI." Checked live against two retracted papers
  (`10.1016/S0140-6736(20)31180-6` and
  `10.1016/j.ijantimicag.2020.105949`): the `relation` object only ever
  held unrelated things like `has-review`. The real signal is the
  `updated-by` array, where each entry has `type` (`retraction`,
  `expression_of_concern`, `correction`, `erratum`, ...), `label`,
  `source` (`publisher` / `retraction-watch`), the notice's own `DOI`,
  and an `updated` date. Column renamed from `update_to` to
  `crossref_updates` to match.
- **`journal_legitimate` (DOAJ) dropped as a separate API call.**
  OpenAlex's work record already carries
  `primary_location.source.is_in_doaj`, sourced from DOAJ's own journal
  list, for free alongside data Research Evaluation fetches anyway. A
  separate DOAJ client would add a call, a 2 req/s rate limit, and a new
  failure mode for no new information. Caveat carried forward: DOAJ
  lists only fully open-access journals, so `in_doaj=false` for a
  subscription journal (e.g. The Lancet) is not a legitimacy signal —
  only a **true→false flip (delisting)** is treated as a change event.
- **Semantic Scholar snippets are stored and reused, not fetched fresh
  every time.** Section 2 said snippets are query-driven and fetched at
  comparison time, not stored per paper — confirmed correct. But
  re-fetching on every stance call would waste calls when nothing
  relevant changed. Research Evaluation now caches snippet results keyed
  by (candidate DOI, claim-text hash), and re-pulls only when: the claim
  text changed, the candidate paper's Crossref `updated-by` list changed
  (checked via one free Crossref lookup), or the caller passes
  `refresh=true`. Snippets still never land in Storage Management's
  per-paper `background_text` row — they belong to a (claim, candidate)
  pair, not to one paper.
- **`/evaluate/stance` takes DOIs, not Storage Management paper ids.** A
  candidate paper a new snippet/citation points at may not exist in
  Storage Management at all, and this keeps Research Evaluation free of
  runtime calls back into Storage Management just to resolve an id.
- **OpenAlex now requires an API key** for its full $1/day free usage
  budget (keyless calls get 1/10 of that). This wasn't true when the
  original brief was written. See SETUP.md.
- **No `GET /health` endpoints.** Considered and dropped — not needed
  for this project's scope; Docker Compose's own container health can
  stand in if ever needed.
- **DeepSeek `reasoning_effort` defaults to `low`**, not `high`. Chosen
  for latency and cost in the common case; raise per-call only if a demo
  pair's stance/claims output comes out wrong under low effort.
- **Research Evaluation's local sqlite cache (`cache.py`) is capped** at
  `CACHE_MAX_ENTRIES` (default 5000) with least-recently-used eviction,
  so it can't grow unbounded across a semester of development and demo
  runs. It was never a source of truth (Storage Management owns
  persistence), so evicting an entry only costs a re-fetch, not data
  loss.

### Rejected / not pursued

- A dedicated DOAJ API client — see above.
- Storing Semantic Scholar snippets in Storage Management's schema — see
  above.
- Giving Updating a hard dependency on Storage Management's database
  (shared schema) instead of its own — rejected for the reason given
  above under "Updating's change data."
