# Investigating a detected change (plan)

**Status: plan, awaiting approval.** Work happens on
`feat/eval-investigation`. The research behind it (every way a change shows
up, with real examples) is `docs/RE-changes-explained.md` on
`feat/eval-impact`, which isn't merged yet.

## The goal

When Research Evaluation detects a new change on a paper, fetch what the
change actually is, so a later LLM step can judge it:

- **the notice, if there is one:** the notice's own Crossref record (title,
  type, date, which articles it says it updates) and, when it's open access,
  its text;
- **the new version of the paper, if there is one:** either a newer version
  under another DOI (Crossref `new_version` / `new_edition`), or the paper's
  own DOI as it reads now, for changes made in place (a correction, or
  JAMA's retract-and-replace at the same DOI).

Investigation only fetches and reports facts. Judging them (is this notice
really about the paper, what changed, does it matter) is the job of
**insight**, the LLM step, which is a later plan.

## Where it fits

```
nudge ─▶ 1 detection ─▶ key check ─▶ 2 rules ─▶ 3 investigation ─▶ 4 insight ─▶ store alert
```

| Stage | Job | Where | LLM? |
|---|---|---|---|
| 1. Detection | compare snapshots, list what changed | `changes.py` | no |
| 2. Rules | a severity, description and recommendation from fixed templates | `rules.py` | no |
| 3. Investigation | pull the notice and the new version of the paper, if any | `investigation/` (this plan) | no |
| 4. Insight | judge what the change means and how it affects the researcher, revising stage 2's assessment | `insight/` (a later plan) | yes |

**Naming.** Until now the docs called the LLM step "stage 3, LLM
investigation": `llm.py`'s `investigate()`, a placeholder that returns the
stage-2 assessment unchanged and runs only for `other` changes. That
clashes with investigation as fetching, and the LLM step will cover every
kind of change, not only `other`. S0 removes the placeholder. An `other`
change keeps its rule-based assessment until insight exists.

## What already exists

### Updating's lookups by DOI

- `updating/sources.py` has `fetch_crossref` (`api.crossref.org/works/{doi}`,
  with the `mailto` polite pool), `fetch_openalex`
  (`api.openalex.org/works/doi:{doi}`, with the API key, following merged
  works) and `fetch_openalex_authors`. Each returns a parsed model, or
  `NOT_FOUND` / `ERROR`, and logs only the DOI and a status or exception
  class.
- **Overlap:** investigation calls the same Crossref endpoint, and follows
  the same ok / not-found / error pattern.
- **Why it isn't reused as is:**
  - `CrossrefWork` keeps only `DOI` and `updated-by`. Investigation needs
    the title, type, date, `update-to` and `relation`.
  - Research Evaluation doesn't import from `updating`: they're separate
    services that share an image.
  - So investigation gets its own small fetchers, with the same pattern and
    the same env var (`CROSSREF_MAILTO`). Moving the shared part into
    `common/` is a later refactor with Updating's owner, as with
    `ServiceTokenAuth`.
- `common/doi.py`'s `normalize_doi` is reused.
- Updating's snapshots already give Research Evaluation each notice's DOI,
  type, label, date, source and Retraction Watch `record_id`. RE's
  `Snapshot` model drops the paper's own `doi`, the `source` and the
  `record_id` (`extra="ignore"`). S3 brings back `doi`.
- Storage Management's `MetadataClient` (Java) also calls Crossref and
  OpenAlex by DOI, at ingest, for the title, journal, ISSN, year and OpenAlex
  id. Research Evaluation can't call it.

### Storage Management and paper files

- **Every newly tracked paper has a stored PDF.**
  - An upload is written by `LocalFileStore.save` to `UPLOAD_DIR` as
    `<uuid>.pdf`, and `papers.file_key` holds the key.
  - Tracking by DOI now downloads an open-access copy with
    `OpenAccessPdfClient`: every OpenAlex PDF link, then Semantic Scholar's.
    It sends a browser-like user agent, follows redirects, and keeps only
    real PDFs under 25 MB. A DOI with no downloadable copy isn't tracked
    (`422`).
  - LOCAL_STORAGE_DB.md's "PDFs: Not built yet" is out of date.
- **Overlap with "the new version":** `OpenAccessPdfClient.download(doi)`
  does exactly what a current-version PDF fetch would do. It's Java, inside
  Storage Management, and has no endpoint, so Research Evaluation can't
  call it.
- **There's no `GET /internal/papers/{id}/pdf`**, in the Java service or the
  stub, although CONTRACTS.md specifies it. Research Evaluation can't read
  the stored (old) paper yet.
- **There's one file per paper**, with no versions, and no endpoint for
  another service to store a file. A fetched new version can't be stored in
  Storage Management, and Research Evaluation keeps no database.
- So investigation's results live in memory, for the evaluation that
  fetched them. Storing them is out of scope.

## Sources

| Source | Used for |
|---|---|
| Crossref `api.crossref.org/works/{doi}` | Any DOI's record: title, Crossref type, published date, journal, `update-to`, `relation`, abstract (rarely present) |
| Europe PMC `www.ebi.ac.uk/europepmc/webservices/rest/` (`search`, then `{PMCID}/fullTextXML`) | The text of open-access notices and papers in PubMed Central. On 26 Sep 2026 it returned full text for PLOS, Scientific Reports, RSC and PNAS notices, and nothing for the paywalled Elsevier ones |

Not used, and why:
- **Publisher pages and PDF downloads.** They're bot-blocked: the Lancet PDF
  gave `403` and the PMC PDF a bot-check page (DEMO.md,
  RE-changes-explained.md 5.3). PDFs would also need parsing.
- **OpenAlex and Semantic Scholar PDF links.** Storage Management already
  follows them at ingest. From Research Evaluation they'd only add PDF URLs,
  which run into the same blocking and would need parsing.
- **Crossref Labs (Retraction Watch reasons).** It needs a `mailto`, and the
  reasons can come later.

**Allowlist.** Investigation calls only `api.crossref.org` and
`www.ebi.ac.uk`, with fixed URLs whose only input is a DOI. It never uses
the Storage Management client, which carries the service token.

## What gets fetched for each change

| Change | Notice | New version (another DOI) | Current version (the paper's own DOI) |
|---|---|---|---|
| Retraction with a notice DOI | ✓ | — | ✓ (JAMA replaces at the same DOI) |
| Retraction from OpenAlex's flag only (no notice DOI) | — | — | ✓ |
| Expression of concern | ✓ | — | — (the content doesn't change) |
| Correction, erratum | ✓ | — | ✓ (often corrected in place) |
| `other` of type `new_version` or `new_edition` | the notice **is** the new version | ✓ (the notice) | — |
| Any other `other` type (withdrawal, removal, addendum, …) | ✓ | — | ✓ |
| DOAJ delisting | — | — | — |

- **A self-referencing notice** (notice DOI = paper DOI, e.g. IJAA's
  publisher `retraction` entry) isn't fetched twice. It's flagged, and the
  current version stands in for it.
- **A replacement under a new DOI that only the notice's text names**
  ("Retraction and replacement of: …") isn't resolved here. Crossref has no
  link to it (checked on the Evolution and JAMA cases), so the text goes to
  insight.
- A lookup that fails is recorded in the findings and never raised.

## Scope

In scope: removing the `llm.py` placeholder (S0), the `investigation/`
package (S1–S3), and running it for each new change during an evaluation
(S4).

Out of scope:
- **Judging the findings** (insight, a later plan).
- **Storing the findings.**
- **Reading the stored old paper.** That needs Storage Management's
  `GET /internal/papers/{id}/pdf`.
- **Downloading PDFs, OpenAlex, and Retraction Watch reasons.**
- **Changes Updating doesn't nudge on** (EVALUATION-REVIEW-CHANGES.md,
  "Later stories").

## Subtasks

Verified from `CSD-G4T1/backend`:
```
python -m uv run pytest
python -m uv run ruff check
python -m uv run pytest -m live   # the tests that hit the real APIs
```

Every fixture below is a real response, recorded from the live API.

### S0: Remove the `llm.py` placeholder

- **Goal:**
  - `llm.py` is deleted. `evaluate_paper` no longer builds a `Context` or
    calls `investigate`: every new change gets its rule-based assessment
    and is stored.
  - **Stored alerts don't change.** The placeholder returned the stage-2
    assessment unchanged, so an `other` change is stored exactly as before:
    `change_type` `other`, severity `medium`, the generic "Crossref
    recorded a '…' notice" text.
  - Comments and docstrings no longer promise a stage 3 for `other`:
    - `changes.py`'s `OTHER` comment and `evaluate.py`'s module docstring
      point to the later LLM step (insight) instead.
    - In `evaluate_paper`'s numbered list, only line 2a ("investigate
      changes unable to be classified") is removed, since that step no
      longer exists. Your line 3 already covers the later LLM step, and the
      rest keeps your wording.
- **Files:**
  - `backend/src/research_evaluation/llm.py` (deleted)
  - `backend/src/research_evaluation/evaluate.py`, `changes.py` (a comment)
  - `backend/tests/research_evaluation/test_evaluate.py`:
    - The two stage-3 tests ("called for `other` changes only", "the stage-3
      result is what gets stored"), the `investigated` fixture and "stage 3
      isn't called again for a stored `other` change" are removed.
    - Their `other`-specific assertions (the change key
      `other:withdrawal:10.1/w` and the "a 'Withdrawal' notice" text) move
      into one test of an `other` change stored with its rule-based text.
    - The second-nudge test keeps its stage-2 spy only.
- **Verification:**
  - The whole backend suite and `ruff check` pass.
  - An `other` change is stored with its change key, severity `medium` and
    the rule-based text.
  - A second nudge still assesses and stores nothing.
  - `grep` finds no `llm` import or `investigate` call left in
    `backend/src` or `backend/tests`.
- **Doc deltas:**
  - **DECISIONS:** a 2026-09-27 entry, "The stage-3 LLM placeholder is
    removed". Why:
    - it returned its input unchanged;
    - "investigation" now means fetching;
    - the LLM step (insight) will cover every kind of change, not only
      `other`, so it'll need a different hook anyway;
    - until then, `other` alerts keep their rule-based text.
  - **ARCHITECTURE §3:** the stages are detection, rules, investigation
    (this doc) and insight (later); no stage-3 placeholder.
  - **EVALUATION-REVIEW-CHANGES:** the stage table and the S5 lines naming
    `llm.py` / `investigate`, noting the removal, and "Later stories",
    "LLM investigation of unclassified changes (stage 3)", pointing to
    investigation (this doc) and insight.
  - **This doc:** mark S0 done.

### S1: Crossref records by DOI

- **Goal:**
  - `investigation/crossref.py`: `fetch_record(http, doi, mailto)` returns
    a `CrossrefRecord` or a fetch failure, for any DOI. A record has:
    - `title`, the Crossref `type`, the `published` date (a partial date
      stays partial, as in Updating's snapshots) and `journal`;
    - `update_to`: DOI, type, source and date for each entry;
    - `relation`: relation type → DOIs;
    - the abstract, when Crossref has one.
  - A `404` gives `NOT_FOUND`. Anything else gives `ERROR`: another status, a
    timeout, an unreadable body, or a `200` that isn't a work. No exception
    escapes. Log lines carry only the DOI and a status or exception class.
    `mailto` is sent only when it's set.
  - `investigation/http.py`: the fetch status enum and a small get-JSON
    helper, shared by S1 and S2.
  - Research Evaluation's settings gain `crossref_mailto`, read from
    `CROSSREF_MAILTO` (the variable Updating already reads), default empty.
- **Files:**
  - `backend/src/research_evaluation/investigation/__init__.py`, `http.py`,
    `crossref.py`
  - `backend/src/research_evaluation/config.py`
  - `backend/tests/research_evaluation/investigation/test_crossref.py`,
    `backend/tests/research_evaluation/test_config.py`
  - `backend/tests/fixtures/crossref/`: the notice records for IJAA
    (`10.1016/j.ijantimicag.2024.107416`), the Lancet EoC (`…31290-3`),
    the Lancet retraction-and-republication (`…31528-2`), and F1000Research
    version 2 (`10.12688/f1000research.187739.2`)
- **Verification:**
  - IJAA's notice: the title starts "Retraction notice to", and `update_to`
    has the IJAA paper as `retraction` (Retraction Watch) and `erratum`
    (publisher).
  - `…31528-2`: `update_to` lists both `…31174-0` and `…31180-6`, so the
    "about a different article" case shows in the data.
  - F1000Research version 2: `update_to` has version 1 as `new_version`, and
    `relation` has `has-version`.
  - `404` gives `NOT_FOUND`. `500`, a timeout, invalid JSON and a `200`
    without a work each give `ERROR`, with no exception.
  - A DOI with parentheses is percent-encoded like Updating's (`(` becomes
    `%28`). `mailto` is sent only when set. No request carries an
    `Authorization` header.
  - The setting defaults to empty and is read from `CROSSREF_MAILTO`.
  - A `live` test fetches IJAA's notice from the real API.
- **Doc deltas:**
  - **SETUP:** `CROSSREF_MAILTO` is also read by Research Evaluation.
  - **DECISIONS:**
    - Investigation fetches deterministically, before the LLM, instead of
      the LLM calling fetch tools (EVALUATION-REVIEW-CHANGES.md, "Later
      stories", planned tools). Why: predictable cost, time and tests, and
      nothing an LLM reads can steer what gets fetched.
    - Research Evaluation has its own fetchers instead of importing
      Updating's.
  - **README:** a row for this doc.
  - **This doc:** codebase context.

### S2: Open-access text from Europe PMC

- **Goal:**
  - `investigation/europepmc.py`: `fetch_text(http, doi)` returns the text,
    or the reason there isn't any.
  - It searches Europe PMC by DOI and keeps the result whose DOI matches.
  - If that result has a PMCID and is open access, it fetches
    `{PMCID}/fullTextXML` and returns the title, abstract and body as plain
    text: figure and table captions kept, the reference list dropped. The
    text is capped at 60,000 characters, with a `truncated` flag.
  - Otherwise it returns the reason: `not_indexed`, `not_open_access` or
    `error`.
- **Files:**
  - `backend/src/research_evaluation/investigation/europepmc.py`
  - `backend/tests/research_evaluation/investigation/test_europepmc.py`
  - `backend/tests/support.py` (a loader for XML fixtures)
  - `backend/tests/fixtures/europepmc/`:
    - the search results for the Scientific Reports Author Correction
      (open access), IJAA's notice (not open access), and a search with no
      match;
    - the full-text XML of that Author Correction (PMC11906582) and of the
      PLOS ONE EoC (PMC10836678)
- **Verification:**
  - The Author Correction's text contains "The original Article has been
    corrected".
  - The EoC's text contains "Fig. 7" and the result sentence it no longer
    supports, and not the reference list.
  - IJAA's notice gives `not_open_access`. No results, or no result with a
    matching DOI, gives `not_indexed`. `500`, a timeout and unreadable XML
    each give `error`, with no exception.
  - Text over the cap is cut and flagged.
  - The DOI is quoted safely in the search query. No request carries an
    `Authorization` header.
  - A `live` test fetches the Author Correction's text.
- **Doc deltas:**
  - **DECISIONS:**
    - Europe PMC is the only text source. There are no publisher pages and
      no PDF downloads (bot-blocked, and outside the allowlist).
    - Fetched text is data for insight, never instructions.
  - **This doc:** codebase context.

### S3: Investigating one change

- **Goal:**
  - `investigation/investigate.py`, exported from `investigation`:
    `investigate(http, change, paper_doi, mailto)` returns `Findings`,
    following the table in "What gets fetched for each change".
  - `Findings` has `notice`, `new_version` and `current_version`. Each is a
    document (its DOI, its Crossref record or a failure status, its text or
    the reason there's none), or `None` when it doesn't apply. It also has
    `self_referencing_notice`.
  - The fetches run concurrently. A failed lookup is recorded in the
    findings, never raised.
  - RE's `Snapshot` model keeps the paper's `doi` (it's already in Storage
    Management's snapshot JSON), so the caller can pass `paper_doi`.
- **Files:**
  - `backend/src/research_evaluation/investigation/investigate.py`,
    `__init__.py`
  - `backend/src/research_evaluation/changes.py` (`Snapshot.doi`)
  - `backend/tests/research_evaluation/investigation/test_investigate.py`
- **Verification:** with mocked HTTP, counting the calls to each host:
  - Each row of the table fetches exactly the documents it lists: a
    retraction with and without a notice DOI, an EoC, a correction, an
    erratum, `other` of type `new_version`, `other` of type `withdrawal`,
    and a DOAJ delisting.
  - A self-referencing notice gives one fetch of the paper's DOI, and is
    flagged.
  - If Crossref fails for a notice, its Europe PMC text is still tried, and
    the other way round. A total outage gives findings full of failure
    statuses and no exception.
  - With no paper DOI, the current version isn't fetched.
  - The existing detection tests still pass.
- **Doc deltas:** this doc (codebase context: the `Findings` model and the
  table).

### S4: Running investigation during an evaluation

- **Goal:**
  - `evaluate_paper` runs `investigate` for each change that isn't stored
    yet (after the change-key check), and logs a one-line summary of the
    findings for each change.
  - **Stored alerts don't change.** Insight (a later plan) will use the
    findings.
  - External calls use their own `httpx.AsyncClient`, created at startup,
    with no auth.
  - **Time-boxed and fail-open.** Each change's investigation stops after
    `INVESTIGATION_TIMEOUT_SECONDS` (default 6). A timeout or any error
    leaves the findings empty, logs the reason, and never changes the
    nudge's `202` or `503`.
- **Files:**
  - `backend/src/research_evaluation/evaluate.py`, `main.py`, `config.py`
  - `backend/.env.example`
  - `backend/tests/research_evaluation/conftest.py`, `test_evaluate.py`,
    `test_config.py`
- **Verification:**
  - Every existing test passes, with the external calls mocked.
  - A new change is investigated once. A change that's already stored makes
    no external calls.
  - If investigation times out, errors, or can't reach the external hosts,
    the alert is still stored with the rules text and the nudge answers
    `202`.
  - Requests to Crossref and Europe PMC carry no `Authorization` header;
    requests to Storage Management still do.
  - The setting defaults to 6, can be overridden by env, and rejects 0 or
    less.
- **Doc deltas:**
  - **CONTRACTS:** the `/evaluate/changes` flow now calls Crossref and
    Europe PMC for each new change, within the time limit, which adds to
    Updating's wait.
  - **ARCHITECTURE §3:** investigation now runs during the evaluation.
  - **SETUP:** `INVESTIGATION_TIMEOUT_SECONDS`.
  - **DECISIONS:** investigation runs before the `202`, time-boxed and
    fail-open, and why. The limit: several new changes in one nudge add up
    against Updating's 20 s timeout.
  - **This doc:** codebase context.

## Codebase context

Filled in as each subtask lands: where each piece lives, how they fit
together, and the gotchas. For example: never use the Storage Management
client for external calls, DOIs with parentheses, matching Europe PMC
results by DOI, and fetched text being data, not instructions.

## TODO for other owners

- **Amir (Storage Management):**
  - Build `GET /internal/papers/{id}/pdf` (already in CONTRACTS.md), so
    insight can compare the stored old paper with the current version.
  - Optional: an internal endpoint that runs `OpenAccessPdfClient` for a
    DOI, so Research Evaluation can get a paper's current open-access PDF
    without duplicating it.
  - Update LOCAL_STORAGE_DB.md's "PDFs: Not built yet": uploads and DOI
    tracking both keep a PDF now.
- **Zhuo En (Updating):** nothing needed. Later, perhaps: move the shared
  Crossref fetch pattern into `common/`.

## Open questions

- **DECISIONS.md lost six entries in merges.**
  - PR #15's merge (`a95d412`) dropped five Research Evaluation entries:
    "compares only the newest N snapshots", "only changes not stored yet
    are evaluated", "called only by Updating's nudge", "one alert per
    Crossref notice", and "Alerts: stored in Storage Management, evaluated
    in stages".
  - `54ee99a` ("merge conflicts w main") dropped `main`'s "Storage stores
    snapshots and open-access PDFs & migrations tidied", which ARCHITECTURE
    and CONTRACTS still cite as "DECISIONS.md, 2026-09-26".
  - All six can be restored verbatim from `3b8c9a7` and `a95d412`. That
    would be a docs-only step before S0, since S0's entry refers to the
    stage-3 design recorded in "evaluated in stages".
- **`RE-changes-explained.md`:** bring it over from `feat/eval-impact`, or
  leave it there?
- **S4 now, or with the insight plan?** S4 adds external calls to the nudge
  before anything uses their results.
- **The defaults:** a 60,000-character text cap and a 6-second time limit
  per change.
