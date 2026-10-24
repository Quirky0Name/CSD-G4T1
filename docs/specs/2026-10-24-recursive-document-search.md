# Recursive document search in investigation

**Status:** approved

**Repo:** `D:\SMU\CS203\project\CSD-G4T1`

**Context file to update:** none named. The design is already drafted in
`docs/EVALUATION.md`, "What to fetch, by scenario", where the planned parts are marked
*(planned)*.

**Commands:**

| What | Command |
|---|---|
| Python full suite | from `backend/`: `uv run pytest` |
| Python single file | from `backend/`: `uv run pytest tests/research_evaluation/investigation/test_investigate.py` |
| Python typecheck | none |
| Python lint | from `backend/`: `uv run ruff check` |
| Java full suite | from `storage/`: `./mvnw test` (`.\mvnw.cmd test` on Windows) |
| Java single test | from `storage/`: `./mvnw test -Dtest=InternalDocumentTest` |
| Frontend typecheck | from `frontend/csd-frontend-vite/`: `npm run build` (runs `tsc -b`) |
| Frontend lint | from `frontend/csd-frontend-vite/`: `npm run lint` |

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 Storage Management: `related` kind and `links` | none |
| S2 Link extraction in investigation | S1 |
| S3 Following links | S2 |

Run order: S1, S2, S3.

External blockers: none.

Uncommitted changes: none besides this spec. The "What to fetch, by scenario" section of
`docs/EVALUATION.md` is already committed (`ceb601c`); S3's doc delta rewrites it as current
behaviour.

Branch: `feat/recursive-search` (already checked out).

## Suggested splits

No split: all subtasks are connected. S2 sends the `links` field S1 adds, and S3 sends the
`related` kind S1 adds and follows the links S2 extracts.

## Out of scope

- Following citations, reviews, preprints, supplements and data (`references`, `cites`,
  `has-review`, `has-preprint`, `is-supplemented-by`, `is-related-material`), and back-pointers
  naming another article. They are stored in `links`, never fetched. Recorded by S3's doc
  delta.
- Reading cited works (user: confirmed). Recorded by S2's doc delta.
- Fetching any URL link. URLs are stored in `links` only, for impact (user: "just store them in
  case we want the AI to use it"). Recorded by S2's doc delta.
- Storing which document led to which (no `via_doi` column). Recorded by S3's doc delta.
- An LLM deciding which links to follow. Recorded by S3's doc delta.
- Resuming or retrying a report left `investigating`. Already in `docs/DECISIONS.md`, `transient`.
- Showing `links` in the frontend. `transient`.
- Checking DOIs already stored in the paper's earlier reports, or anyone's (user: "a").
  Recorded by S3's doc delta.

## Interfaces

### `Link` (S2 produces, S1 stores, S3 reads)

One DOI or URL a document points at. Exactly one of `doi` and `url` is set.

```json
{"doi": "10.1234/abcd", "url": null, "role": "is-replaced-by", "source": "crossref"}
{"doi": null, "url": "https://example.org/data", "role": null, "source": "europepmc_xml"}
```

| Field | Type | Values |
|---|---|---|
| `doi` | string or null | normalised with `common.doi.normalize_doi`, never blank. A `doi.org` / `dx.doi.org` URL is stored as its DOI |
| `url` | string or null | an `http` / `https` URL that isn't a DOI URL, as found (trailing `.,;)` stripped) |
| `role` | string or null | Crossref: `update-to`, `updated-by`, or the `relation` type as Crossref names it (`is-replaced-by`, `has-version`, …). JATS: the `related-article-type` value. `null` for untyped links |
| `source` | string | `crossref`, `europepmc_xml` or `europepmc_text` |

Python: `research_evaluation.investigation.links.Link`, a frozen pydantic model with these
four fields; it rejects both or neither of `doi` and `url`.

### `POST /internal/documents` (S1 changes, S2 and S3 call)

- Request gains `links`: optional JSON array of `Link`, stored and returned exactly as sent,
  never looked inside (like `crossref_record`). Absent or `null` is stored as `null`.
- `kind` accepts `related` as well as `notice`, `new_version`, `current_version`.
- A new `related` row is saved `pending` and its PDF downloaded, as for `new_version`.
- The `201`/`200` response row and `GET /internal/reports/{id}` documents include `links`.
  `GET /papers/{id}/reports` documents include `links` too.

### `FetchedDocument` (S2 changes)

Gains `links: list[Link]`, sent in the body of `POST /internal/documents`. A document whose
sources found nothing has `[]`.

## Subtasks

### S1 Storage Management: `related` kind and `links`

- **Goal:** Storage Management stores a `related` document and downloads its PDF to its local
  upload folder, and stores, returns and serves `links` on every document.
- **Blocked by:** none
- **Files:** `storage/src/main/java/com/g4t1/storage/report/DocumentKind.java`,
  `NewReportDocumentRequest.java`, `ReportDocument.java`, `ReportDocumentResponse.java`,
  `UserDocumentResponse.java`, `ReportDocumentService.java`,
  `storage/src/main/resources/db/migration/V10__add_document_links.sql`, tests under
  `storage/src/test/java/com/g4t1/storage/report/`,
  `frontend/csd-frontend-vite/src/api.ts`,
  `frontend/csd-frontend-vite/src/components/ReportList.tsx`.
- **Seams:** `POST /internal/documents`, `GET /internal/reports/{id}`,
  `GET /papers/{id}/reports`, `GET /internal/documents/{id}/pdf`.
- **Decisions:**
  - The PDF of a followed document is stored locally (user: "storage of pdf should be local",
    confirmed as: Storage Management downloads it, Research Evaluation never stores a PDF).
    Storage Management downloads it with `OpenAccessPdfClient` into
    `LocalFileStore`, as for every other document PDF (docs: `DECISIONS.md`, 2026-09-27
    "Storage Management downloads a document's PDF when it's stored").
  - `links` is stored as text and returned as JSON exactly as sent, like `crossref_record`
    (code: `NewReportDocumentRequest.java`).
  - `DocumentKind.RELATED.hasPdf()` is true.
  - Migration number V10 (user: confirmed). A migration on `main` is never edited (docs:
    `STORAGE.md`, Gotchas).
  - Frontend label "Related document" (user: confirmed).
- **Acceptance criteria:**
  - [x] `POST /internal/documents` with `kind` `related` answers `201` with `pdf_status`
        `ok` and a `file_key` when the mocked download succeeds, and `not_found` when it finds
        nothing (test in `InternalDocumentTest`).
  - [x] `GET /internal/documents/{id}/pdf` serves a stored `related` PDF (test in
        `ReportDocumentPdfTest`).
  - [x] A `links` array sent to `POST /internal/documents` comes back unchanged in the `201`
        row, in `GET /internal/reports/{id}` and in `GET /papers/{id}/reports`; omitted `links`
        comes back `null` (tests in `InternalDocumentTest`, `ReportListTest`).
  - [x] An unknown `kind` still answers `400` (existing test passes).
  - [x] `./mvnw test` passes.
  - [x] `npm run build` and `npm run lint` pass in `frontend/csd-frontend-vite/`.
- **Doc deltas:**
  - `docs/CONTRACTS.md`, `POST /internal/documents`: `kind` `related`; the `links` field and
    the `Link` shape; `related` gets a PDF; `links` in every document response.
  - `docs/STORAGE.md`, "Reports and documents": `related` is downloaded like `new_version`;
    `links` stored as-is.
  - `GLOSSARY.md`: **Related document**: a document investigation reached by following a typed
    link from a notice or new version, not from the paper's own notices.
  - Decisions made during S1:
    - `links` is any JSON value (`JsonNode`), not checked to be an array, since storage never
      looks inside it (like `crossref_record`): `docs/CONTRACTS.md`, `POST /internal/documents`.
    - A JSON `null` `links` is stored as SQL null, not the text `"null"`, same as
      `crossref_record`: `docs/CONTRACTS.md`.
    - The frontend gets a `DocumentLink` type in `api.ts` and `links` on `UserDocument`, but
      shows nothing of it (out of scope): no doc needed, the type comment points at
      `docs/CONTRACTS.md`.

### S2 Link extraction in investigation

- **Goal:** every fetched document carries the DOIs and URLs its Crossref record and Europe
  PMC full text point at, and investigation sends them as `links`. Nothing new is fetched yet.
- **Blocked by:** S1
- **Files:** `backend/src/research_evaluation/investigation/links.py` (new),
  `crossref.py`, `europepmc.py`, `investigate.py`, `backend/dev/stub_storage.py`,
  `backend/src/research_evaluation/storage.py` (`DocumentInReport.links`), tests under
  `backend/tests/research_evaluation/investigation/`, new fixtures recorded from
  the live APIs: `backend/tests/fixtures/crossref/f1000_v1.json`
  (`10.12688/f1000research.187739.1`), `plos_retraction_notice.json`
  (`10.1371/journal.pone.0322065`), `evolution_eoc.json` (`10.1093/evolut/qpad094`),
  `backend/tests/fixtures/europepmc/fulltext_PMC12017829.xml` (the PLOS R1 retraction notice).
- **Seams:** `crossref.fetch_record`, `europepmc.fetch_text`, `europepmc.plain_text`,
  `investigate.fetch_document`, `run.investigate_papers` (through `ExternalApis` and the stub).
- **Decisions:**
  - Crossref links come from `update-to[].DOI` (role `update-to`), `updated-by[].DOI` (role
    `updated-by`) and `relation` (role = the relation type). `CrossrefRecord` also keeps
    `updated_by` (same shape as `update_to`), so it is stored in `crossref_record` too.
  - Europe PMC XML links come from `<related-article>` (role = `related-article-type`; DOI
    from its own `xlink:href` when `ext-link-type` is `doi`, else from a nested
    `<ext-link ext-link-type="doi">`, as in the PLOS R1 notice `PMC12017829`), and from `<ext-link>` and
    `<pub-id pub-id-type="doi">` (role `null`), read before the XML is flattened.
  - Europe PMC text links come from the regex `10\.\d{4,9}/[-._;()/:a-z0-9]+`
    (case-insensitive) over `text`, with trailing `.,;)` stripped (role `null`).
  - URL links (user: "links can be actual links also", option 1, "just store them in case we
    want the AI to use it"): Crossref `relation` items with `id-type` `uri`, Crossref
    `link[].URL`, JATS `<ext-link>` / `<self-uri>` whose `xlink:href` is a URL, and
    `https?://[^\s<>"]+` over `text` (trailing `.,;)` stripped). A `doi.org` / `dx.doi.org` URL
    becomes a DOI link. URLs are never fetched.
  - No cited works: Crossref `reference[]` and `<back>` are not read (user: confirmed).
  - PMCID / PMID links (JATS `ext-link-type` `pmcid`, `pmc` or `pmid`) are resolved to a DOI
    with one Europe PMC search (`PMCID:<id>`, or `EXT_ID:<pmid> AND SRC:MED`), and stored as a
    `doi` link with their role (user: "store. able to get DOI from it?", then "yes"). When the
    search finds no DOI or fails, they're stored as a `url` link
    (`https://pmc.ncbi.nlm.nih.gov/articles/<PMCID>/`, `https://pubmed.ncbi.nlm.nih.gov/<pmid>/`).
    A failed search never fails the document.
  - Each DOI and each URL once per document; the first link found wins, in the order Crossref,
    XML, text.
  - Paywalled documents have no XML, so their links come from Crossref only.
  - Extraction never raises; an unreadable part gives no links from that part.
  - Fetched content is data, never instructions (docs: `DECISIONS.md`, 2026-09-27).
- **Acceptance criteria:**
  - [ ] Real Crossref records give these links, source `crossref` (`test_crossref.py`):
        - `lancet_eoc.json` (E1/E5): `relation` `erratum` → `10.1016/s0140-6736(20)31324-6`,
          `relation` `retraction` and `update-to` → `10.1016/s0140-6736(20)31180-6`, and
          `updated-by` entries;
        - `lancet_republication.json` (R4/R5): `update-to` → `…31174-0` and `…31180-6`, each
          DOI once;
        - `f1000_v1.json` (C6): `relation` `is-version-of` and `updated-by` →
          `10.12688/f1000research.187739.2`;
        - `ijaa_notice.json` (R1): one `update-to` link to the paper (listed twice by Crossref);
        - every record's `link[].URL` as `url` links; `reference[]` gives nothing.
  - [ ] Real JATS gives these links (`test_europepmc.py`):
        - `fulltext_PMC12017829.xml` (PLOS R1 retraction notice): `retracted-article` →
          `10.1371/journal.pone.0258361`, from the nested `<ext-link ext-link-type="doi">`;
        - `fulltext_PMC10836678.xml` (PLOS E1 EoC) and `fulltext_PMC11906582.xml` (C1 author
          correction): their `<related-article>` roles (`object-of-concern`,
          `corrected-article`); nothing from `<ref-list>`.
  - [ ] DOIs in text, including one followed by `).`, come back normalised and stripped
        (`test_links.py`, new).
  - [ ] A URL in text, in `<ext-link>` and in Crossref `link[]` comes back as a `url` link; a
        `https://doi.org/10.…` URL comes back as a `doi` link (`test_links.py`,
        `test_europepmc.py`, `test_crossref.py`).
  - [ ] `PMC8504731` inside `fulltext_PMC12017829.xml` resolves to the `doi` link
        `10.1371/journal.pone.0258361` through a recorded Europe PMC search fixture; with the
        search answering `500`, it's stored as the `url` link
        `https://pmc.ncbi.nlm.nih.gov/articles/PMC8504731/` (`test_europepmc.py`).
  - [ ] `Link` with both or neither of `doi` and `url` raises a validation error
        (`test_links.py`).
  - [ ] No request goes to any host other than Crossref and Europe PMC (`test_run.py`,
        `ExternalApis` requests checked).
  - [ ] A malformed `relation` item or `<related-article>` without `xlink:href` gives no link
        and no exception (`test_crossref.py`, `test_europepmc.py`).
  - [ ] The body sent to `POST /internal/documents` has `links`, and the stub stores them
        (`test_run.py`).
  - [ ] The existing investigation tests pass unchanged except for the added `links` field.
  - [ ] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:**
  - `docs/EVALUATION.md`, Investigation step 3: each document's links and where they come from
    (the "Where links come from" table, current behaviour, with the URL sources); cited works
    are never read; URLs are stored for impact and never fetched.

### S3 Following links

- **Goal:** after the planned documents, investigation follows the links that lead to the
  paper's own later notices, newer versions and replacements, stores what it reaches as
  `related` documents, and impact sees them.
- **Blocked by:** S2
- **Files:** `backend/src/research_evaluation/investigation/investigate.py`, `run.py`,
  `backend/src/research_evaluation/impact/inputs.py`, `impact/prompts.py`,
  `backend/dev/stub_storage.py`, tests under `backend/tests/research_evaluation/investigation/`
  and `backend/tests/research_evaluation/impact/`, fixtures under `backend/tests/fixtures/`,
  `docs/EVALUATION.md`, `docs/DECISIONS.md`.
- **Seams:** `run.investigate_papers`, `investigate.plan_documents`,
  `investigate.classify_link` (new; pure: a `Link`, the paper's DOI and the DOIs seen →
  `follow`, `check` or `keep`), `investigate.lineage_match` (new; pure: a candidate's
  `CrossrefRecord`, the paper's DOI and the report's DOIs → bool), `impact` prompt builders
  `prompts.change_prompt` and `prompts.document_block`.
- **Decisions:**
  - Recursion is relative: it only fetches documents in the paper's own history, its later
    notices, newer versions and replacements (user: "would this recursive search be relative";
    then "i don't think the rules need to be this strict, search the RE-changes-explained.md for
    better rules", and "yes" to the rule below). One rule for every change type; no per-change
    table. The cases it serves (docs: `RE-changes-explained.md`): R4/C7 replacement article,
    C6 newer version, E5 retraction or correction after an EoC, E4 notice updating or lifting an
    EoC, R8 reinstatement. R5 (a notice about another article) is kept, not chased.
  - `classify_link` returns, in this order:
    1. `keep`: a `url` link; the paper's DOI; a DOI already seen.
    2. `keep`: role `references`, `cites`, `has-review`, `has-preprint`, `is-supplemented-by`,
       `is-related-material`; and back-pointers `update-to`, `retracted-article`,
       `corrected-article`, `object-of-concern` (the paper itself is already caught by 1, so
       what reaches here names another article, R5).
    3. `follow`: role `updated-by`; `relation` `is-replaced-by`, `has-version`, `is-version-of`;
       Elsevier's notice-named `relation` types `retraction`, `correction`, `erratum`,
       `expression_of_concern`, `addendum`; JATS `retraction-forward`, `correction-forward`,
       `concern-forward`, `update`.
    4. `check`: an untyped DOI (role `null`) from a document's XML or text.
    5. `keep`: any other role.
  - `check`: fetch the candidate's Crossref record (one request, no Europe PMC). If
    `lineage_match` is true (its `update-to`, `updated-by` or `relation` names the paper's DOI or
    a DOI already in the report), it's followed like `follow`; otherwise kept. At most
    `MAX_CHECKS` 20 checks per report (user: confirmed). A failed check counts as no match.
  - Every link stays in its document's `links`, whatever the outcome.
  - URL links are never followed and never count toward any cap (user: "don't count towards the
    cap. just store them in case we want the AI to use it").
  - Each DOI is fetched at most once per report: a `seen` set seeded with the paper's DOI and
    every planned DOI (user: "should also check whether the link/DOI has been searched before";
    scope settled as "within this report only"). DOIs in the paper's earlier reports are not
    checked.
  - Breadth first. Planned documents are depth 0; `MAX_DEPTH` 2: a document at depth 2 is
    stored but its links aren't classified. At most `MAX_RELATED` 10 `related` documents per
    report; planned documents don't count (user: confirmed).
  - The walk starts from every planned document except `current_version` (user: confirmed).
  - A `related` document is fetched with `fetch_document` and stored with `kind` `related`;
    `update_to_includes_paper` is `null` for it. Stored even when Crossref answers `not_found`;
    a store failure fails the report as for a planned document (user: confirmed).
  - Storage Management downloads its PDF locally, into `UPLOAD_DIR` (user; see S1).
  - Fetched content can choose further DOIs, only through these fixed rules and caps; no LLM
    takes part (user: accepted as an amendment to `docs/DECISIONS.md`, 2026-09-27
    "Investigation fetches notices itself, deterministically").
  - Impact: `<document>` gets a `links:` line (JSON, defused like the record); a `related` PDF
    is attached after the new versions and labelled `RELATED, <doi>.` (user: confirmed).
- **Acceptance criteria:**
  - [ ] `classify_link` gives the outcome above for every listed role, for `url` links, for the
        paper's DOI and for a seen DOI (`test_investigate.py`, parametrised).
  - [ ] `lineage_match` is true for `lancet_republication.json` against the paper
        `10.1016/s0140-6736(20)31180-6` and false against an unrelated DOI
        (`test_investigate.py`).
  - [ ] Real cases end with exactly these documents (`test_run.py`, `ExternalApis` requests
        checked):
        - R1 PLOS (`10.1371/journal.pone.0258361`, notice `…0322065`): notice and current copy,
          no `related` (the notice links only back to the paper);
        - R5 Lancet (`…31180-6`, erratum notice `…31528-2`): `…31174-0` stays in `links` and
          isn't fetched;
        - C6 F1000 (`…187739.1`, new version `…187739.2`): new version only, no `related`
          (`has-version` → v1 is the paper);
        - E5 Lancet: a report holding only the EoC alert (notice `…31290-3`) stores the
          retraction notice `…31324-6` as `related`, reached through `relation` `erratum`.
  - [ ] An untyped DOI in a notice's text whose Crossref record has `update-to` → the paper is
        fetched as `related`; one whose record names neither the paper nor a report document
        isn't (synthetic records on real DOIs, `test_run.py`).
  - [ ] Depth (synthetic records on real DOIs; no real case is three deep): notice → B → C → D
        by `follow` roles ends with B and C as `related` and no request for D (`test_run.py`).
  - [ ] A cycle (B's record points back to the notice) fetches each DOI once (`test_run.py`).
  - [ ] With 12 `follow` links, exactly 10 `related` documents are stored; with 25 untyped DOIs,
        exactly 20 Crossref checks are made (`test_run.py`).
  - [ ] A notice with 10 `follow` links and 5 URL links stores 10 `related` documents, and no
        request goes to any URL host (`test_run.py`).
  - [ ] A `current_version` document's links are stored but not classified (`test_run.py`).
  - [ ] A report with no followable links stores exactly the documents it stores today
        (existing `test_run.py` tests pass unchanged).
  - [ ] The step 1 prompt includes a `related` document's block with its `links:` line, and
        its PDF labelled `RELATED, <doi>.` (`tests/research_evaluation/impact/`).
  - [ ] `uv run pytest` and `uv run ruff check` pass.
- **Doc deltas:**
  - `docs/EVALUATION.md`, "What to fetch, by scenario": rewrite as current behaviour (remove
    *(planned)*, replace the per-change "What to follow" table) with `classify_link`'s outcomes,
    the check step, limits, the `related` kind, the walk skipping the current copy, no
    provenance stored.
  - `docs/EVALUATION.md`, Impact step 1: `related` documents, their `links:` line and PDF label.
  - `docs/DECISIONS.md`: amend 2026-09-27 "Investigation fetches notices itself,
    deterministically": fetches can follow DOIs from a fetched record or text, only by fixed
    rules and caps. New entry: following links into the paper's own history, each DOI once per
    report, with the rejected options: a per-change follow table, following everything fetched,
    an LLM choosing links, a `via_doi` column, checking earlier reports' documents.

## Human checks

- [ ] **S3**, live run (E5). Steps: track the Lancet paper `10.1016/s0140-6736(20)31180-6`,
      poll once, use the demo tool `undoChange` (`/dev/papers/{id}/...`) for
      `expression_of_concern`, poll again, then open the paper's newest report in the frontend.
      Expected: the EoC notice `…31290-3` and, as "Related document", the retraction notice
      `…31324-6`; any `related` PDF with `pdf_status` `ok` is in Storage Management's
      `UPLOAD_DIR`.
