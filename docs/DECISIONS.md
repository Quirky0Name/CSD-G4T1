# Decisions log

Dated log of decisions and why they were made, so settled questions stay
settled and anyone (including the TA) can see the reasoning. Newest first.

---

## 2026-09-24 — Updating owns fetching and snapshots; Research Evaluation evaluates

### Team decisions

- **Ownership split.** Updating fetches Crossref/OpenAlex status, journal and
  author data, stores snapshots (via Storage Management), diffs them and
  records raw changes. Research Evaluation evaluates what a change means
  (severity, impact statement, recommendation) and owns the alert list and
  actions. This replaces the 2026-09-18 design where Research Evaluation
  built the background-info snapshot and Updating attached canned impact
  text from a lookup table.
- **Push, not pull, for the handoff.** Updating writes each change as
  `pending`, calls `POST /evaluate/change`, and stores the answer. The UI
  then reads one complete record from Updating. If Research Evaluation is
  down the row stays `pending` and is re-sent on the next poll. Rejected:
  Research Evaluation pulling `GET /changes?since=`, which needs its own
  watermark and storage and makes the UI join two sources.
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
  tracking. Keeping full history over only the latest snapshot: each change
  cites the two snapshots it came from, false alerts can be replayed from
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
