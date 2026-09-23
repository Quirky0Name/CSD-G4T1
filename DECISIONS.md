# Decisions log

Dated log of decisions and why they were made, so settled questions stay
settled and anyone (including the TA) can see the reasoning. Newest first.

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
