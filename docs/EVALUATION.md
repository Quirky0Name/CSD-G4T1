# Research Evaluation

Works out what changed in a tracked paper and what it means for the
researcher. Python, `backend/src/research_evaluation/`. It keeps no
database: it reads everything from Storage Management and writes alerts,
report documents and evaluations back to it. Endpoint shapes are in
CONTRACTS.md ("Research Evaluation", "Reports"); the reasoning is in
DECISIONS.md; the real-world cases behind the design (R1, C6, E5, …) are
in [RE-changes-explained.md](RE-changes-explained.md).

```
POST /evaluate/changes (paper ids, from Updating)
  ├─ 1. detection   changes.py     newest N snapshots → changes (deterministic)
  ├─    key check   storage.py     skip changes whose change_key already has an alert
  ├─ 2. rules       rules.py       alert severity, description, recommendation → POST alert
  └─ reply 202 / 503
       └─ background task, per paper evaluated without failure that had changes:
          3. investigation  investigation/   open a report, post its alerts to User Management, fetch its documents → report ids
          4. impact         impact/          read the paper's past, three model steps → PUT evaluation → assessed → post it to User Management → store the report's chunks
POST /evaluate/reports (report ids, by hand) ─▶ 4 only
```

## The nudge: detection and rules

`main.evaluate_changes` → `evaluate.evaluate_papers`, one paper after
another. For each paper:

1. Read the newest N snapshots (`snapshots?last=N`, N =
   `EVALUATION_SNAPSHOT_WINDOW`, default 5, minimum 2) and run detection
   on every consecutive pair. The first snapshot is only a baseline.
2. If anything was detected, read the paper's stored change keys
   (`/alerts/change-keys`) and keep only new ones.
3. Give each a rule-based assessment and store it
   (`POST /internal/papers/{id}/alerts`).

**Detection** (`changes.py`), by the classification table in CONTRACTS.md:

- Nulls and fields whose source wasn't `ok` are never compared.
- A Crossref `updated-by` entry is identified by (`notice_doi`, `type`); a
  new source (publisher vs Retraction Watch) for a known pair isn't new.
- **One retraction per paper:** OpenAlex's `is_retracted` flip and a
  Crossref `retraction` entry share the key `retraction`.
- **One alert per notice (temporary):** a notice listed under several
  types gives one alert, of the most severe type (`retraction`,
  `expression_of_concern`, `correction`, unclassified, `erratum`). A known
  notice relabelled later gives nothing new, unless it's now a retraction.
- An unclassified Crossref type (`withdrawal`, `removal`, …) becomes an
  `other` alert with its raw type, label and DOI, never dropped.
- A DOAJ delisting needs `in_doaj` true → false with the same
  `journal_source_id` and a `journal` source type (a switch to a
  repository location also flips `in_doaj`).
- A citation jump is a rise in `cited_by_count` of at least 10 and at
  least 20 % of the previous count, between two consecutive snapshots
  (`common/citations.py`, the same rule Updating nudges on). A drop never
  counts, and neither does a slow climb of small rises.
- The change key is set here: `retraction`, `<type>:<notice_doi>`,
  `other:<type>:<notice_doi>`, `doaj_delisting:<snapshot_id>`,
  `citation_jump:<snapshot_id>`.
- `detected_at` is the `fetched_at` of the first snapshot the change shows
  up in.

**Which detected change gets stored** (`evaluate._to_store`): keys not yet
stored, one change per key (the earlier pair's). The exception is a
retraction **with** a notice: it's sent even when `retraction` is stored,
and within one window it wins over the flag's notice-less change. Storage
Management then replaces a notice-less retraction alert with it (`201`) or
changes nothing (`200`), so a notice that arrives after OpenAlex's flag
still gets investigated.

**Rules** (`rules.py`): fixed alert severity per type (retraction `high`; EoC,
correction, `other` `medium`; erratum, DOAJ delisting, citation jump `low`)
and template text; a citation jump's description names both counts. Every alert is complete before any LLM runs.

**The reply:** `202` once every paper's alerts are stored; `503` if any
paper failed (the others are still stored), with a summary
(`alerts_created`, `skipped_paper_ids`, `failed_paper_ids`). Updating
keeps its `nudge_pending` flag on anything but `202` and re-sends next
poll, which is safe: the window is re-read and alerts are idempotent. A
change survives up to N − 2 failed nudges in a row, then leaves the window
and is lost. A paper is skipped (not failed) only on Storage Management's
`404` with `detail` exactly `No paper <id>`; any other `404` (a missing
route) is a failure.

## Investigation

`investigation/run.py` `investigate_papers` → the ids of the reports it
finished. Per paper:

1. `POST /internal/papers/{id}/reports`: Storage Management opens a report
   grouping every alert of the paper not in a report yet (`204`: none,
   stop). Its alerts come back with their change keys.
2. Plan the documents (`investigate.plan_documents`) from the detected
   changes whose key is one of the report's alerts:

   | Change | `notice` | `new_version` | `current_version` (once per report) |
   |---|---|---|---|
   | Retraction with a notice DOI | ✓ | — | ✓ |
   | Retraction from OpenAlex's flag only | — | — | ✓ |
   | Expression of concern | ✓ | — | — |
   | Correction, erratum | ✓ | — | ✓ |
   | `other` of type `new_version` / `new_edition` | — | ✓ (the notice DOI) | — |
   | `other`, any other type | ✓ | — | ✓ |
   | DOAJ delisting | — | — | — |
   | Citation jump | — | — | — |

   Each DOI once, normalised. A notice whose DOI is the paper's own (R3)
   gets no notice document; the current copy covers it. No paper DOI, no
   current copy.
3. For each document, fetch side by side (`asyncio.gather`, one failing
   never stops the other):
   - its Crossref record (`crossref.py`: title, published, journal,
     `update_to`, `updated_by`, `relation`);
   - its open-access text (`europepmc.py`: search by DOI, keep the result
     whose DOI matches, then `fullTextXML` only if it has a PMCID and
     `isOpenAccess: Y`; title, abstract, body and captions, no references,
     capped at 60,000 characters);
   - its links (`links.py`): the DOIs and URLs the record, the XML and the
     text point at, sent as the document's `links` (below).
   - For a notice, `update_to_includes_paper`: whether its `update-to`
     names the paper.
4. `POST /internal/documents` with each; Storage Management downloads the
   PDF of a new version, a related document or the current copy before answering (timeout
   `INVESTIGATION_PDF_TIMEOUT_SECONDS`, default 120). The returned row is
   used as the document, not what was sent.
5. Follow links (`run.follow_links`, below): documents reached are stored
   as `related`.
6. `PATCH` the report to `investigated`.

**The alerts post.** Right after step 1 and before any document is fetched,
investigation posts an `alerts` event for the report's owner (`POST
/internal/users/{id}/notifications`, CONTRACTS.md, "Research Evaluation ↔
User Management"), through the User Management client both stages share
(`InvestigationContext.um`, optional: `None` posts nothing, as in the evals).
It isn't posted as each alert is stored: alerts are stored before the nudge's
reply, which Updating waits for, and Research Evaluation doesn't know the
paper's owner then. The post reads the report again (`GET
/internal/reports/{id}`) for `owner_id` and the alerts' ids, types, severities
and descriptions, because the opened report only carries change keys, and
reads the newest snapshot (`paper_details`) for the title and DOI. It lists
every alert of the report; User Management filters per channel. A failed post
is logged and changes nothing: the report still ends `investigated`. So does
failing to read the report or paper for the post (logged as `reading report
<id> for its notification failed: <cause>`), since those reads exist only for
the post and a Storage Management blip there must not leave a report
`investigating`. A report whose owner is unknown skips the post, logged at INFO
(`report <id> has no owner: its alerts are not posted`), not WARNING: it is a
skip, not a failure.

Investigation fetches facts and judges nothing. In particular it doesn't
decide whether the current copy differs from the stored paper: hashes
can't tell (different sources, download stamps), so `sha256` and
`pdf_source_url` are stored as facts and the judging is impact's.

**What real cases get** (RE-changes-explained.md): only open-access
notices in PubMed Central have text (PLOS, Scientific Reports, RSC, PNAS);
Elsevier, The Lancet and JAMA notices get their Crossref record only.
Notice PDFs aren't fetched (bot-blocked). A notice about another article
(R5, the Lancet's `…31174-0`) still has `update_to_includes_paper` true;
only its title ("RETRACTED: <another article>") shows it, which impact
judges.

### What to fetch, by scenario

Steps 2–4 as one flow, then the link walk (`run.follow_links`), which runs
after the planned documents are stored.

```
for each change behind one of the report's alerts:
  doaj_delisting ────────────────────────────▶ nothing (it's about the journal)
  citation_jump ─────────────────────────────▶ nothing (it's about who cites the paper)
  retraction from OpenAlex's flag only ──────▶ current_version
  no notice DOI ─────────────────────────────▶ current_version if the type can alter the paper
  notice DOI is the paper's own (R3) ────────▶ current_version, no notice
  other: new_version / new_edition ──────────▶ new_version (the notice DOI)
  retraction, correction, erratum, other ────▶ notice + current_version
  expression_of_concern ─────────────────────▶ notice only (it doesn't change the paper)

for each planned DOI (each once per report):
  ├─ Crossref /works/{doi}     → crossref_record (metadata, never body text)
  ├─ Europe PMC search by DOI
  │    ├─ no result with that DOI   → text_status not_indexed
  │    ├─ no PMCID, or not OA       → text_status not_open_access
  │    └─ fullTextXML               → text (≤ 60,000 chars) + links, read before flattening
  ├─ notice only: update_to_includes_paper
  └─ POST /internal/documents  → Storage Management downloads the PDF
                                 (new_version, current_version, related; never a notice)

walk (breadth first), from every planned document except current_version:
  each link of a document at depth < MAX_DEPTH (2), through classify_link
    ├─ keep   → stays in `links`, nothing fetched
    ├─ follow → fetch as `related`, depth + 1
    └─ check  → fetch its Crossref record; lineage_match true → follow, else keep
```

**Where links come from.** Every document has `links` (the `Link` shape in
CONTRACTS.md): each DOI and each URL once, the first found winning, in the
order Crossref, XML, text. Extraction never raises; an unreadable part gives
no links from that part.

| Source | Field | Role | Kept as |
|---|---|---|---|
| Crossref | `update-to[].DOI`: the articles the notice updates | `update-to` | DOI |
| Crossref | `relation`: `is-replaced-by`, `has-version`, `is-version-of`, `is-supplemented-by`, `references`, `erratum`, `retraction`, … | the relation type as Crossref names it | DOI; an `id-type` `uri` item is a URL |
| Crossref | `updated-by[].DOI`: notices on this document (a correction of a retraction) | `updated-by` | DOI |
| Crossref | `link[].URL` | none | URL |
| Europe PMC XML | `<related-article related-article-type="…">`: `retracted-article`, `corrected-article`, `object-of-concern`, … | the `related-article-type` | DOI or URL |
| Europe PMC XML | `<ext-link>`, `<self-uri>`, `<pub-id pub-id-type="doi">` | none | DOI or URL |
| Europe PMC text | DOI regex `10\.\d{4,9}/[-._;()/:a-z0-9]+`, case-insensitive, and `https?://[^\s<>"]+`, trailing `.,;)` stripped | none | DOI or URL |

- Crossref is read in the order `update-to`, `relation`, `updated-by`,
  `link`, so a DOI in both a relation and `updated-by` keeps its relation
  type (the Lancet EoC's `erratum` → `…31324-6`).
- A `doi.org` / `dx.doi.org` URL becomes a DOI link. URLs are stored for
  impact and **never fetched**.
- Cited works are never read: Crossref `reference[]`, `<back>` and anything
  inside any `<ref-list>` (the PLOS notice keeps its reference list in
  `<body>`).
- A `<related-article>` often holds only a PMCID (the PLOS R1 notice
  `PMC12017829`). Its target is, first found: its own DOI `xlink:href`, a
  nested DOI (`<ext-link ext-link-type="doi">` or `<pub-id pub-id-type="doi">`),
  its own PMCID/PMID, a nested PMCID/PMID ext-link, its own URL. Elements
  inside a `<related-article>` give no links of their own.
- A PMCID or PMID is resolved to a DOI with one Europe PMC search
  (`PMCID:<id>`, or `EXT_ID:<pmid> AND SRC:MED`; a result counts only when
  its `pmcid`/`pmid` is the one asked), once per document, and stored as a
  `doi` link with its role. When the search finds no DOI or fails, it's a
  `url` link (`https://pmc.ncbi.nlm.nih.gov/articles/<PMCID>/`,
  `https://pubmed.ncbi.nlm.nih.gov/<pmid>/`); a failed search never fails
  the document.
- `<article-id pub-id-type="doi">` (the document's own DOI) is not read, only
  `<pub-id>`. XML links keep document order.
- Text links are read from the whole plain text, before it's cut to
  `MAX_TEXT_CHARS`. The DOI and URL regexes run as separate scans, merged in
  text order, so a DOI inside a publisher's URL is a DOI link as well as the
  URL.
- A paywalled document has no XML, so its links come from Crossref only.

**What the walk does with a link** (`investigate.classify_link`, pure), in
this order:

1. `keep`: a `url` link; the paper's DOI; a DOI already seen.
2. `keep`: role `references`, `cites`, `has-review`, `has-preprint`,
   `is-supplemented-by`, `is-related-material`; and the back-pointers
   `update-to`, `retracted-article`, `corrected-article`, `object-of-concern`
   (the paper itself is caught by 1, so what reaches here names another
   article, R5).
3. `follow`: role `updated-by`; `relation` `is-replaced-by`, `has-version`,
   `is-version-of`; Elsevier's notice-named `relation` types `retraction`,
   `correction`, `erratum`, `expression_of_concern`, `addendum`; JATS
   `retraction-forward`, `correction-forward`, `concern-forward`, `update`.
4. `check`: an untyped DOI (role `null`) from a document's XML or text. An
   untyped DOI from Crossref (a `doi.org` URL in `link[]`) is kept, not
   checked.
5. `keep`: any other role.

One rule for every change type, no per-change table: the walk only fetches
documents in the paper's own history, its later notices, newer versions and
replacements (R4/C7 replacement article, C6 newer version, E5 retraction or
correction after an EoC, E4 notice updating or lifting an EoC, R8
reinstatement). R5, a notice about another article, is kept, not chased.
Every link stays in its document's `links`, whatever the outcome.

**The check.** One Crossref request for the candidate, no Europe PMC. If
`lineage_match` is true (its `update-to`, `updated-by` or `relation` names
the paper's DOI or a DOI already in the report) it's followed like `follow`,
stored from the record the check fetched (`fetch_document`'s optional
Crossref result), so each DOI is fetched once; otherwise kept. A failed
check counts as no match. A checked DOI joins `seen` whether or not it
matched. "DOIs already in the report" are the paper's, the planned ones and
the related ones stored so far, not DOIs that failed a check.

**Limits.**

- Each DOI is fetched at most once per report: a `seen` set seeded with the
  paper's DOI and every planned DOI. DOIs in the paper's earlier reports
  (or anyone's) are not checked. Storage Management's
  `unique (report_id, doi)` backs this up across retries.
- Breadth first. Planned documents are depth 0; `MAX_DEPTH` 2: a document at
  depth 2 is stored but its links aren't classified.
- `MAX_RELATED` 10 `related` documents per report; planned documents don't
  count. Reaching it ends the walk.
- `MAX_CHECKS` 20 checks per report. Reaching it only skips further checks;
  `follow` links are still taken.
- URL links never count toward any cap.
- The `current_version` is not a starting point: its links are stored but
  not classified.

**A `related` document** is fetched with `fetch_document` and stored with
kind `related` (`update_to_includes_paper` null); stored even when Crossref
answers `not_found`. A store failure fails the report, as for a planned
document. Storage Management downloads its PDF into its own `UPLOAD_DIR`;
Research Evaluation never stores a PDF. Which document led to which isn't
stored (no `via_doi`): the parent's `links` names the child.

**Not followed, by design:** citations, reviews, preprints, supplements and
data (`references`, `cites`, `has-review`, `has-preprint`,
`is-supplemented-by`, `is-related-material`) and back-pointers naming
another article: stored in `links`, never fetched. Cited works are not read.
URLs are not fetched. No LLM chooses links: fetched content can pick further
DOIs only through these fixed rules and caps.

## Impact

`impact/run.py` `assess_reports(sm, context, report_ids)`, one report at
a time, called with investigation's ids after each nudge or by
`POST /evaluate/reports`. Without `ANTHROPIC_API_KEY` or `GEMINI_API_KEY` it
doesn't run (one log line) and reports stay `investigated`.

Per report:

1. `GET /internal/reports/{id}`; skip unless `investigated` (before any PDF
   or model call, so a wrong or repeated id costs one read).
   Then `impact/history.py` reads the paper's past (below).
2. `inputs.gather_inputs`: the paper's details (newest snapshot), the
   paper's stored PDF, each document whose `pdf_status` is `ok`. Anything
   missing is recorded, not a failure.
3. `assess.assess`, three model calls in a row (`prompts.py`, structured
   output against the schemas in `schemas.py`, no tools):

   | Step | Question | Reads | Answer |
   |---|---|---|---|
   | 1. Change | What changed, is it about this paper, how severe? | paper details, alerts, documents (`<document>` blocks, each with a `links:` line, JSON, or `links: none`), PDFs: stored paper, new versions, related documents (labelled `RELATED, <doi>.`), current copy; the **feedback blocks**, when there is feedback | `ChangeAssessment`: summary, change severity `none`/`low`/`medium`/`high`, `claims` |
   | gate | change severity `none` = not meaningful: stop, store summary and change severity only | | |
   | 2. Impact | How does the draft use the paper; is each use affected; how much? | step 1's answer; PDFs: the **draft**, read only now (`storage.draft_pdf`), then the stored paper if both fit the budget (below); the **profile block**, when there is a profile; the **feedback blocks**, when there is feedback | `ImpactAssessment`: uses, impact level, explanation, `claims` |
   | 3. Actions | What should the researcher do? | steps 1 and 2 as JSON, no PDFs; the profile block, when there is a profile; the feedback blocks, when there is feedback | `RecommendedActions`: actions, recommendation, `claims` |

   No draft: step 2 judges for a typical researcher citing the paper as key
   evidence, and step 3 adds "upload your draft". The impact level follows
   a fixed table in the prompt (change severity × how the draft relies on
   the paper; RE-changes-explained.md §6). Step 1's PDFs are attached in
   that order up to `MAX_INLINE_PDF_BYTES` (40 MB); one that doesn't fit is
   left out and recorded. Step 2 attaches the draft first, then the stored
   paper if the two fit `MAX_INLINE_PDF_BYTES`; with no draft the stored
   paper isn't attached. What was attached or left out is recorded in
   `assessment.impact_pdfs`, shaped like `assessment.pdfs`. Step 1's task says what a RELATED document is
   (reached by following a notice's or new version's links; no alert of its
   own). A notice's alert line carries its notice date when the rule's
   description has one (`rules.notice_date`; Storage Management keeps no
   separate date), and step 1's task says that for a notice whose DOI is the
   paper's own it is the paper's date, not a retraction's. A citation jump has no document: its alert line carries the rule's
   description (the counts), and step 1's task says to judge it as new
   attention, scope `citations_only`, severity `low`, so the gate lets it
   through to steps 2 and 3. `PROMPT_VERSION` is 18 since feedback went to step 1 too and may guide both levels (17: the `RESEARCHER FEEDBACK` section (from the
   feedback line of work, which ended at 8) joined them; 16: the past timeline and the researcher's
   profile (from the other line of work, which ended at 7) joined the claims prompts (15: a derived claim may name an earlier
   step's claims (`from_change` in steps 2 and 3, `from_impact` in step 3) to build on that
   step's conclusions (14: the alert line carries the notice date and a
   derived claim may use only facts its named claims state; 13: a conclusion drawn from other claims
   is a derived claim (`from_claims`), a claim's quotes may show it together, and a place is
   shown by quoting its sentence, never a heading (12: each claim says whether it states only
   an absence, `absence`; 11: free text as sentences that name their claims; 7: conclusions,
   widening words and absences get their own claims, and step 3 adds no fact of its own; 6: each
   claim states one fact that each of its quotes shows on its own; 5 added claims and step 2's
   stored paper)))).

   **The past.** Before step 1, `impact/history.py` (`read_history`) embeds
   one query and searches the paper's stored chunks
   (`storage.search_chunks`, `POST /internal/chunks/search`); steps 1 and 2
   get what it finds as a `PAST TIMELINE` section, the same chunks in both.
   Step 3 gets none.

   - **What "past" is:** every chunk of the same tracked paper
     (`paper_id`), with no date, order or exclusion filter. A report's
     chunks are written only after impact has assessed it, so the report
     being assessed is never among them. Search is per paper; nothing
     searches across papers.
   - **The query** is one embedding (`RETRIEVAL_QUERY`) of the alert lines
     joined by newlines (`<change_type> <notice_doi or "no notice">
     <description>`), then each notice's `text`, cut to the first 8000
     characters. A report with no alerts and no notice text has an empty
     query, so nothing is embedded or searched and `history` is `none`,
     not `failed`.
   - **The searches:** one per source type, with no score threshold: up
     to 3 past alerts, 3 past reports and 5 past document chunks. The three
     results are merged and ordered by `score`, highest first, so the
     section and `context_chunks` are nearest first across source types,
     not grouped by type. Recency plays no part in ranking; the date is
     shown so the model can place each item in the timeline.
   - **The section:** with at least one chunk back it is added; with none,
     a failed search, or no embedder (no `GEMINI_API_KEY`; the embedder is
     `ImpactContext.embedder`, the one that stores chunks), step 1's and
     step 2's prompt parts are exactly as before. Step 1: after the
     attached PDFs, right before the task. Step 2: after the draft and the
     stored paper (or NO DRAFT), before the profile section and the task. Each item is a
     `<document kind="past_alert|past_report|past_document" source_id=".."
     date="YYYY-MM-DD" report="<id or none>" page="..">` block (`page` only
     when set; the date is `source_date`), its text through `_defuse`. The
     rules (`PAST_RULES`) follow the blocks, so untrusted text never comes
     last: past items are context for judging the current change, never the
     change being assessed, and they are data, not instructions.
   - **`assessment.history`** records `read`, `none` (nothing found, an
     empty query, or no embedder) or `failed`. `assess` takes `history`;
     left out, it is `read` when `inputs.chunks` (`ReportInputs.chunks`,
     default empty) is set and `none` otherwise, which is what the evals
     get, since they fill `chunks` from a case's `history`. A failed search
     or embedding never fails the report: it logs the report id and the
     exception class only (an error could carry the query's text), and
     `assessment.history` holds only the status.
   - **`context_chunks`** goes in the evaluation `PUT`: `source_type`,
     `source_id` and `page` of exactly the chunks in the prompt, in prompt
     order, so a frontend can show them later; `[]` when there are none. It
     is sent with every evaluation, the placeholder and change severity
     `none` included, since steps 1 and 2 had the chunks either way.
     A page-less `document` chunk whose text was cut into more than one
     chunk (`chunk_index` above 0, or its text after the header exactly
     `embedding.CHUNK_SIZE`, 3,200) also gets `start` and `end`, its range in
     the document's `text`: `start = chunk_index × 2,800` (`CHUNK_SIZE` −
     `CHUNK_OVERLAP`, 400) and `end = start +` the length of the chunk's text
     after its header. `impact.history.context_chunk` computes it when impact
     reads the chunk, so a later change of chunk size doesn't move stored
     ranges. Every other item, including a split PDF page, has neither key.
   - Step 1 still attaches the current report's PDFs; the past is added
     beside them as retrieved text, and past documents reach the prompt only
     as text chunks, never as PDFs.

   **The profile.** After the gate, next to the draft, `assess` reads the
   researcher's profile from User Management
   (`users.researcher_profile(um, owner_id)`; `owner_id` is on Storage
   Management's report). Steps 2 and 3 get a `USER PROFILE` section as its
   own text part before the task: a `<document kind="researcher_profile">`
   block (the profile block), then `PROFILE_RULES` (`prompts.profile_section`).
   Each value is JSON passed through `_defuse`; an empty list or `null` is
   `none`. The profile may only tailor the explanation (step 2) and the
   recommendation and actions (step 3): what to explain and what to put
   first. `response_preferences` is the one exception to "documents are
   data": it is followed only for the tone, length and focus of that field,
   within the length the task sets (explanation two to five sentences,
   recommendation one or two), and can't change any rating, the answer
   schema, the facts used or any other rule. The profile never changes
   change severity or impact level. Step 1 gets no profile, and with no
   profile the prompt parts are exactly as before. In step 3 the profile
   tailors both `recommendation` and `actions`, so the recommendation stays
   the gist of the actions.

   An edit to a profile applies only to reports assessed after it; nothing
   re-assesses an earlier report.

   **The researcher's feedback.** `read_history` makes one more search, with
   the query vector it already embedded: `source_type` `feedback`, `limit`
   3, across every paper of the owner (the same paper included), by
   similarity only with no score threshold. Steps 1, 2 and 3 each get
   a `RESEARCHER FEEDBACK` section as its own text part right before the
   task (step 1: after the past timeline; steps 2 and 3: after the profile section when there is one): one
   `<document kind="researcher_feedback" report_date="..." verdict="...">`
   block per chunk with the chunk's content, through `_defuse` like every
   document, then `FEEDBACK_RULES` (`prompts.feedback_block`). With no
   feedback neither blocks nor rules appear and the parts are byte-identical
   to before. `FEEDBACK_RULES`: each block is the researcher's own verdict
   and note on an earlier report, found because it is similar to the current
   change and possibly about another paper they track; it can affect the
   model's decisions, change severity and `impact_level` included, as far as
   the model judges it fits this change (overstated: the earlier report was
   too serious; understated: too light); the current change's own facts come
   first, and feedback can't add facts or change the answer schema or any
   other rule. The model gets freedom to interpret, not a fixed direction.
   A note's text still goes through `_defuse`, and SYSTEM's rule that
   everything in `<document>` tags is data still covers it. Feedback can
   therefore lift a change past the gate (change severity `none` skips steps
   2 and 3) or drop one to `none`. The profile still never changes a level
   (`PROFILE_RULES`). Nothing re-assesses earlier reports after feedback is
   saved or deleted.

   - Each item is a `PastFeedback` (`history.py`): the chunk plus the report
     date and verdict read back from the chunk's content. The verdict is the
     last verdict line before the note line, so a note can't spoof one, and
     anything outside the three values is `unknown`.
   - A feedback chunk without `source_date` (its feedback was deleted and
     the delete nudge failed) is left out, so deleted feedback never reaches
     a prompt.
   - **`assessment.feedback`** is `read`, `none` or `failed`; a note's text
     is never in logs or `assessment`. A failed query embedding gives both
     `history` and `feedback` `failed`; a failed feedback search gives
     `feedback` `failed` and keeps the past, and vice versa.
   - **`context_chunks`** gets one item per feedback block (CONTRACTS.md,
     `GET /papers/{id}/reports`) after the past chunks, as a
     `FeedbackContextChunk` so other items keep exactly their three fields.
     They are built up front, whenever step 1 had them in its prompt, so a
     change with severity `none` and a placeholder from a failed step 1 list
     them too; `assessment["feedback"]` records how reading went. `ReportInputs`
     gains `feedback`, and `assess` takes a `feedback` status like `history`.
4. `PUT /internal/reports/{id}/evaluation`, once; the report becomes
   `assessed`. Nothing is written between steps, so an `assessed` report is
   always complete.
5. Store the report's chunks (see "Embeddings"), inline right after the
   evaluation `PUT`.

**Three levels mean three things:** the alert's `alert_severity` is the rule's,
by change type, and never changes; the report's `change_severity` is how
serious the change really is for anyone relying on the paper; its
`impact_level` is how much this researcher's draft is affected. A
retract-and-replace can be a `high` alert, a `medium` change and a `low`
impact.

**Claims.** Each step's answer carries `claims: list[Claim]` with
`min_length=1`: an empty list is a schema misfit, which `FallbackLlm` handles
like any other. A model claim is `{text, passages: list[Evidence]}`, where an
`Evidence` is `{source, quote}`. `passages` has no minimum: a statement with
nothing to quote has none.

- The prompt requires one claim per factual statement in that step's
  `summary`, `explanation` or `recommendation`. A claim can be about anything
  the step says: it isn't derived from, or tied to, an alert judgment, a use
  or an action. `evidence` and `Use.quote` stay as they were, because the evals
  score them.
- A passage's source can be any material the step was given: `stored paper`,
  a notice's DOI, `new version`, `current copy`, `draft` or `alert <id>`.
  Quotes must be contiguous: no `...`.
- A conclusion is a claim too, worded no wider than the quote closest to it.
  A statement that something is missing is a claim of its own with no passages,
  saying only that; a comparison of two documents is one claim per side.
- The draft stands in for the research topic (no topic or question field
  exists). With the stored paper attached, step 2 writes two `impact` claims per
  use: one quoting the draft's sentence, one quoting the tracked paper's passage
  behind it.
- A citation jump's and a flag-only retraction's `change` claims quote the
  alert's line as `alert <id>`. `action` claims quote what the `change` and
  `impact` claims they rest on quote, including "upload your draft".
- `assessment.impact_pdfs` is null until step 2 runs (change severity `none`,
  or a placeholder before step 2). With no draft it is
  `{"attached": [], "left_out": []}`: the stored paper isn't recorded as left
  out, since step 2 never offers it without a draft. A stored paper Storage
  Management doesn't have is left out with `why` `not stored`; one over the
  budget with `over the size budget`, as in step 1.

**How quotes are verified** (`impact/claims.py`, `build_claims`, after the
steps, in code: no runtime call asks whether a quote backs its claim, and
there is no fourth Gemini call). The `Evaluation` carries `claims`
(`list[StoredClaim]`, the `Claim` of CONTRACTS.md): every finished step's
claims in order, ids `<kind>-<index>`. A claim is never dropped; `supported`
means only that at least one passage was found verbatim, and is false with no
passages. Whether quotes back their claims is measured offline in the evals.

- A quote is matched by `impact/quotes.py` (`quoted_in`, after `norm` and
  `join_hyphens`), the same matcher the evals use.
- PDF text is read with `pdf_pages` in `asyncio.to_thread`, lazily the first
  time a quote is searched in that PDF, and at most once per PDF per report. A
  PDF's pages are searched as one text, so a quote across a page break is
  found; `page` is the 1-based page where it starts. A match outside a PDF
  gives `page: null`.
- No OCR: a PDF with no text layer gives `verified: "no_text"`.
- No character ranges: claims are a separate list covering the statements,
  not offsets into `change_summary`, `evaluation` or `recommendation`.
- Claims quote only source material, never the model's own earlier answers.
  A later step builds on an earlier step's conclusion (the change's severity,
  that a use is touched) as a derived claim naming that step's claims
  (`from_change`, `from_impact`, numbered from 1 in that step's claims), so
  each link of the chain is judged on whether it follows.

**Source resolution.** A passage's `source` label resolves in this order: a
report document's DOI to that `document` (checked before the paper's own DOI,
so the paper's DOI names a `current_version` document when the report has
one); the paper's own DOI or `stored paper` to `paper`; `alert <id>` to
`alert`; `current copy` to the `current_version` document; `new version` to
the `new_version` document; `draft` to `draft`. When two documents fit a
label, the first in the report's order is used. `draft` always resolves, so a
draft quote on a run with no draft gives `target: "draft"`, `no_text`.

- A document is checked against its PDF when it has one, its `text`, and each
  scalar value of its `crossref_record` and `links`, searched one value at a
  time, so a notice with no open-access text can be quoted from its record. A
  match in the record or links gives `page: null`.
- Anything else the prompt shows for a document is quotable too, as details
  that don't count as text to check: its DOI, a notice's
  `update_to_includes_paper` line (`prompts.update_to_line`) and, when its
  PDF is attached, the PDF's label (`prompts.pdf_label`: the download date
  and source URL). The model may quote everything it is given; only an
  earlier step's own wording is never a source.
- `paper` also covers the paper's details as the prompt lays them out
  (`prompts.paper_block`); a match there gives `page: null`. The details are
  searched but don't count as text to check.
- An alert is its line as the prompt shows it.

**Re-targeting.** A quote is searched first in the source it names. When not
found there, it is searched in the paper, the draft, the documents in the
report's order, then the alerts; a match there re-targets the passage to
where it was found and counts as `verbatim`, so the highlight lands in the
right place. The raw answer in `assessment` stays as it was, and the evals
still count the quote as misattributed. An empty quote, or one that is only
`...`, is found nowhere.

**What `verified` means.** `verbatim`: found. `not_found`: the text was read
and the quote isn't in it. `no_text`: no text to check (no PDF, unreadable, or
no text layer), including a `stored paper` quote with no readable PDF and not
in the details. A source naming no text, with the quote found nowhere, gives
`target: null` and `not_found`. A placeholder evaluation carries the claims of
the steps that finished; a change severity `none` report carries its `change`
claims.

**What's stored:** `change_summary`, `change_severity`, `impact_level`,
`evaluation` (step 2's explanation), `recommendation` (step 3's), and
`assessment`, JSON with everything else: `prompt_version`, `model`,
`model_version`, `answered_by` per step, `draft` (`read` / `none` /
`not_needed`), `profile` (below), `history` (`read` / `none` / `failed`, "The past"), `pdfs` attached and left out, `impact_pdfs` (step 2's, above), each step's
full answer, `placeholder`. The claims are stored apart, in the evaluation's
`claims`, so Storage Management never looks inside `assessment`.

`assessment.profile` is one of four values: `not_needed` (step 1 failed, or
change severity `none`: User Management isn't called); `read` (a profile was
read and given to steps 2 and 3); `none` (no `um` client, User Management
answered `404` `No profile <id>`, or the profile has every answer empty);
`failed` (anything else: no `owner_id`, a transport error or timeout, any
other status including a `404` with another `detail`, or a body that doesn't
fit `ResearcherProfile`). `um` is checked first, so the evals, which pass
none, record `none`. `assessment` records only the status, never the
profile's values.

**Model fallback** (`llm.FallbackLlm`): each call tries `CLAUDE_MODEL`
(`claude-sonnet-5-5`, only when `ANTHROPIC_API_KEY` is set), then
`GEMINI_MODEL`, then `GEMINI_FALLBACK_MODELS` in order (duplicates
removed), starting from the first model again for every step. Any
model-side failure moves on: an API error of any code, a transport error or
timeout (`IMPACT_LLM_TIMEOUT_SECONDS`), a Claude refusal (`stop_reason`
`refusal`), no text, an answer that doesn't fit the schema. Anything else
raises. Neither SDK retries (the Anthropic client has `max_retries=0`), so
every retry is a fallback, and an overloaded Sonnet moves straight on to
Gemini.

**Claude's call** (`llm.ClaudeLlm`): the same system prompt, text and PDFs
(as base64 `document` blocks) as Gemini gets, the schema through
`messages.parse`, adaptive thinking at effort `low`, since a researcher
waits on all three steps. With only `ANTHROPIC_API_KEY` set, impact runs on
Claude alone and has no embedder, so the paper's past is neither searched
nor stored (below).

**When every model fails a step**, `assess` returns a placeholder
evaluation: both levels `low`, all three texts `PLACEHOLDER_TEXT`
(`schemas.py`), `assessment.placeholder` `true` with `failed_step` and each
model's `failures`. It's stored like any evaluation.

**Other failures** (Storage Management unreachable, a `409`, a blank answer
that fails the `PUT`): logged with the report id and cause, nothing
stored, the report stays `investigated`, the next report is still
assessed. A gone paper or report (`PaperGone`, `ReportGone`) is a skip.

## Embeddings

What impact searches and stores (`embedding.py`, `chunks.py`). Storage
Management keeps the vectors (STORAGE.md, "Chunks"; CONTRACTS.md,
`PUT /internal/chunks`).

**The embedder.** Gemini `gemini-embedding-001` (`EMBED_MODEL`), through the
existing `google-genai` client and `GEMINI_API_KEY`, at
`output_dimensionality=1536`. `embed(texts, task)` is
`GeminiEmbedder.embed` behind an `Embedder` protocol, like `Llm` is for the
model, so impact's context carries it and tests fake it. `task` is
`document` (`RETRIEVAL_DOCUMENT`, stored text) or `query`
(`RETRIEVAL_QUERY`). Vectors are L2-normalised before they are returned,
because Gemini normalises only at 3072 dimensions. Texts are sent 100 to a
request, Gemini's batch limit, and come back in order; no texts makes no
call. An answer with the wrong number of vectors, a vector that isn't 1536
numbers, or an all-zero vector raises `ValueError`, so nothing unnormalised
is ever stored. A local model was rejected: the server already idles at
about 3 GB of 4 GB (DECISIONS.md, 2026-11-11).

**The chunker.** `chunk_text(text, size=3200, overlap=400)` cuts at
character positions, not words or sentences; the 400-character overlap
keeps a sentence cut at one edge whole in the next chunk. Blank text gives
no chunks.

**When.** Right after impact stores a report's evaluation, inline in
`assess_report` with no background task (the next report waits for it), never
before it. The evaluation `PUT` makes the report `assessed`, so the
researcher's next refresh shows the notification; storing chunks never
delays or blocks it. A report that is never assessed (no model key, or
impact failed) gets no chunks. There is no backfill: rows stored before
V13 have none. The embedder rides in `ImpactContext.embedder`: at startup a
`GeminiEmbedder` on the Gemini models' client; `None` (no
`GEMINI_API_KEY`, and the existing tests) stores no chunks.

**What is stored.** Alerts, then the report, then documents, each source
embedded and stored on its own (`PUT /internal/chunks`), each source's text
built in a worker thread (`asyncio.to_thread`) so `pypdf` reading a long
PDF doesn't stall the event loop.

- **An alert:** one chunk, lines `alert:` (change type), `alert severity:`,
  `detected:` (date), `description:`, `recommendation:`.
- **The report:** one chunk, lines `change summary:`, `change severity:`,
  `impact level:`, `evaluation:`, `recommendation:`, each when present. A
  change severity `none` report gets one too. A placeholder evaluation
  gets none, but its alerts and documents still do.
- **A document, every kind:** each chunk is prefixed
  `<kind> <doi> (report of <report created_at date>)` and a blank line
  (`Report.created_at` is now read; `AlertInReport` gains `recommendation`).
  - With a stored PDF: text extracted with `pypdf` from the bytes
    `gather_inputs` already read (no PDF is downloaded twice), one chunk per
    page with that page number (from 1). A page over 8000 characters is cut
    with `chunk_text(size=8000)`, keeping the 400-character overlap, every
    piece with the same page. A page with no extractable text (a scan) gives
    no chunk. NUL characters are dropped from a page's text, because
    Postgres `text` rejects them and the whole document would otherwise get
    no chunks. A PDF that `pypdf` can't read is logged and that document
    gets no chunks.
  - No PDF but `text` (Europe PMC, e.g. notices): chunks of 3200 characters
    with 400 of overlap, no page. A PDF wins over `text` when a document
    has both; notices never have a PDF, so theirs come from `text`.
  - Neither: no chunks.
- Impact still attaches the current report's PDFs to step 1 as before.

**Failures.** An embedding or `PUT /internal/chunks` failure is logged
(the source and the cause class, with a traceback only for an unexpected
error). It never changes the report's status or the evaluation, leaves no
chunks for that source, other sources are still stored, and it is never
retried (ARCHITECTURE.md, "Background work never raises and is never
retried").

**The feedback chunk.** When a researcher saves or deletes a report's
feedback, Storage Management calls `POST /embed/feedback`
(`feedback.store_feedback_chunk`, `chunks.feedback_piece`). The report's
feedback is embedded together with its evaluation as **one** chunk
(`RETRIEVAL_DOCUMENT`) and stored with `source_type` `feedback`, `source_id`
the report id. Content, in this order (empty fields and the note line left
out):

```
feedback on report of <report date YYYY-MM-DD>: "<paper title or DOI>"
changes: <change types, comma-separated>
change summary: ...
change severity: ...
impact level: ...
evaluation: ...
recommendation: ...
researcher's verdict: <fair | overstated | understated>
researcher's note: <note>
```

- The report date is the report's `created_at` (`unknown` when absent); the
  title falls back to the DOI, then `unknown paper`. `changes` lists each
  change type once, in alert order. The alerts' descriptions and the
  documents are not included.
- At most 8000 characters (`PAGE_LIMIT`), within `gemini-embedding-001`'s
  2048-token input. When the limit bites, everything between the title line
  and the verdict (the `changes` line included) is cut from the end; the
  title, verdict and note are never cut. A title of about 7,450 characters
  or more would push the chunk past the limit, since the title is never
  cut; its embed then fails and is logged (left as is).
- The title and each evaluation field are written on one line (every line
  break `str.splitlines` knows becomes a space), so model-written text can't
  put a `researcher's note:` or `researcher's verdict:` line above the real
  verdict and change what impact reads back. The note keeps its own text.
- A report with no feedback stores an empty list, which deletes the chunk.
  Failures are logged by exception class only and never raised, like
  `store_report_chunks`.
- `store_feedback_chunk` runs one at a time per report (an `asyncio.Lock`
  per report id, FIFO) and reads the report once it holds the lock, so of
  two quick saves the later one's chunk is the one stored. This holds
  because Research Evaluation runs one uvicorn worker.
- `Chunk.source_date` is optional: a feedback chunk can outlive its deleted
  feedback (a failed delete nudge), and its `feedback_updated_at` is then
  null; one such chunk must not fail the whole search.
- A feedback chunk is not backfilled when embedding failed; saving again
  re-embeds.

**Not stored:** profile chunks (the profile is not embedded; the `profile`
source type was removed), snapshots, tracked papers' PDFs and drafts
(ROADMAP.md).

## Notification

Research Evaluation sends no message itself. It posts two events to User
Management (`POST /internal/users/{id}/notifications`, CONTRACTS.md, "Research
Evaluation ↔ User Management"), to the report's owner, and User Management
decides whether to tell the researcher by email or Telegram and sends it
(ARCHITECTURE.md, "Outside notifications"). The in-app toast is separate: the
frontend notices assessed reports on its refresh.

- **`alerts`**, when investigation opens a report (Investigation, "The alerts
  post").
- **`report_assessed`**, right after impact's `PUT
  /internal/reports/{reportId}/evaluation` succeeds. The body is built from the
  report Storage Management returns from that `PUT`, not from the model's
  draft, because the evaluation is posted as stored. Reading that stored answer
  (a missing field, a null `assessment`) is inside the same guard as the paper
  read: a failure is logged as `reading report <id> for its notification
  failed: <cause>` and posts nothing, because `assess_reports` never raises and
  a malformed answer must not skip the reports after it. An assessed report
  whose owner is unknown is logged at INFO (`report <id> has no owner: its
  evaluation is not posted`). An `impact_level` of `none` is sent as null: User
  Management accepts only `low`, `medium`, `high` or null for it and treats null
  the same.
- **A placeholder evaluation is not posted.** It has no real content to send. It
  is recognised by `assessment.placeholder` in the stored report, not by
  comparing text.
- **Failures.** A failed post (a non-2xx answer, a timeout, a transport error) is
  logged as `notifying user <id> about report <id> failed: <cause>`, with the
  status code or exception class only, never a response body. Nothing is
  retried, and a failed post never changes what investigation or impact did:
  `assess_report` still returns True and the report still ends `investigated`.
  The post waits up to 30 s, since User Management sends before it answers.

One nudge posts `alerts` first and `report_assessed` later, once impact has
assessed the report (without `GEMINI_API_KEY`, only `alerts`).

## Evals

`backend/evals/` measures how well impact (the three steps) answers,
so a prompt or model change can be compared against a score instead of a
guess. Production runs them on Sonnet when `ANTHROPIC_API_KEY` is set, with
Gemini as its fallback; `--provider claude-api --effort low` (below) sends
production's Sonnet request, plus prompt caching. It's a dev tool: no service imports it, it isn't in the Docker
images, and pytest doesn't run it. It calls the real `assess.assess` on
hand-written cases, with a fake Storage Management that only serves the
draft, and scores the saved answers afterwards, so re-scoring costs no
quota.

**Running it**, from `backend/`. `fpdf2` (turns case text into PDFs) and
`rich` (tables) aren't project dependencies: `--with` adds them for that
one command only, from uv's cache, and never touches `pyproject.toml`,
`uv.lock` or `.venv`.

```
cd backend
uv run --with fpdf2 --with rich python evals/all.py                  # asks, then run → judge → summary → score; 3 runs per case
uv run --with fpdf2 --with rich python evals/all.py 01_correction_effect_size --runs 1
uv run --with fpdf2 --with rich python evals/all.py --skip-run       # re-judge and re-score the newest results, no generation
uv run --with fpdf2 --with rich python evals/all.py --folder evals/results/<stamp> --skip-run
uv run --with fpdf2 --with rich python evals/all.py --provider claude-code                       # Claude Code on your subscription: Sonnet tested and judging
uv run --with fpdf2 --with rich python evals/all.py --provider claude-code --folder evals/results/<stamp> --resume   # continue a run stopped at the usage limit
```

To run them you need `uv` (SETUP.md; on Windows `py -m uv`), and for
`claude-code` Claude Code installed and logged in to your own Claude
account (`claude auth login`); for `claude-api` your own key. Each person
uses their own subscription or key. `.venv` isn't used directly.

Or one step at a time (each defaults to the newest results folder):

| Script | Does | Calls a model |
|---|---|---|
| `run.py [case ...] [--runs N] [--into <folder>] [--resume] [--provider P] [--model M] [--effort E]` | Each case through `assess`; saves `<case>__run<N>.json` (the `Evaluation` as stored) in `evals/results/<UTC stamp>/`. `--into` adds to an existing folder; `--resume` (needs `--into`) skips a run whose file exists and isn't a placeholder, so it re-runs placeholders and runs never made; `--dry` only builds the inputs and prints what each case attaches. Writes `meta.json` and `prompts.json` (below). Ends by listing each failed run with its cause and the `--into <folder> --resume` command that re-runs them | yes: `gemini` (default), `claude-code` or `claude-api` |
| `judge.py [<folder>] [--redo] [--label <label>] [--provider P]` | A second model grades the three free-text fields against the case's statements, then, in a second call, whether each claim's passages back it and which claims cover each statement of the three texts (`passages`, `statements` in the same file). The fact grading reads only the answer and the statements (the contract under "A case"), never the paper, notices or draft. A run graded before claims were judged gets only the claims grading when it runs again, without `--redo`. Writes `<run>.judge.json`, or `<run>.judge-<label>.json` with `--label`. Skips placeholders and runs already judged. `--redo` with `--label` regrades only that label's files. `--label default` is a usage error: `judge_check.py` reads `default` as `<run>.judge.json`, so a `judge-default` file would never be read | yes, the same providers |
| `score.py [<folder>]` | The grid (with a verdict per run), pass rates per case, category and overall, failure-type counts, the Metamorphic Groups table, a table per category with an OVERALL row, and the most missed tags and facts | no |
| `compare.py <baseline> <new>` | Regression check of two results folders (below) | no |
| `judge_check.py agree`, `sample` or `human` | Checks the judge (below) | no |

With `--provider gemini` (the default), `run.py` reads `GEMINI_API_KEY`
and `GEMINI_MODEL` from the environment or `backend/.env`, with the same
120 s timeout as the service. **No eval falls back to another model**, on
any provider: the Gemini run's `FallbackLlm` gets only `GEMINI_MODEL`
(`--model` overrides it), so `GEMINI_FALLBACK_MODELS` isn't used, and a
failed call makes the run a placeholder, listed at the end and re-run with
`--resume`. Production's fallback is unchanged. With Gemini, `judge.py`
reads `JUDGE_API_KEY` (falling back to `GEMINI_API_KEY`) and `JUDGE_MODEL`
(default `gemini-flash-lite-latest`) and grades at temperature 0. Use a
separate key, so the judge doesn't use up the tested model's free-tier
quota.

The judge model may be the tested model of the runs it grades. `judge.py`
checks every case's
`expected.facts` against the rubric contract (`judge.Rubric`) before its
first call and exits non-zero naming the case on a violation. A judge call
that fails (a schema mismatch, a placeholder-type failure) leaves that run
ungraded (`unjudged` in `score.py`) and moves on; at the end it lists those
runs with their causes, and running it again grades only them. It exits 0,
so `all.py` still writes its summary.

`all.py` passes `--provider`, `--model`, `--effort` and `--resume` on to
`run.py`, and `--provider` to `judge.py`; it writes `summary.txt` into the
folder. On a failed step it stops and prints the command to continue: for
`run.py`, `--folder <folder> --resume` (`--skip-run` would leave the
remaining runs unmade); for `judge.py`, `--folder <folder> --skip-run`.
With `claude-api` its "Do you want to execute" prompt says the run is
billed to the key. `evals/results/` is tracked in git.

**Claude providers** (`evals/claude_llm.py`, dev only; no service imports it):

- `--provider claude-code` (`ClaudeLlm`) runs each call through `claude -p`
  on the Claude Code login, a claude.ai subscription, and makes no API
  calls and never uses an API key: `ANTHROPIC_API_KEY` and
  `ANTHROPIC_AUTH_TOKEN` are removed from its environment, `backend/.env`
  isn't read, and an `init` event whose `apiKeySource` isn't `none` stops
  the run (`NotOnSubscription`). Before the first call `claude auth status`
  must report `"authMethod": "claude.ai"`, or it exits before any call; if
  `claude` isn't on `PATH` (`shutil.which`, so an npm `claude.cmd` works
  too) it exits and says to install Claude Code and log in. Defaults:
  `--model sonnet --effort medium` tested, `sonnet` at effort medium judging
  (`JUDGE_MODEL` overrides it). The command is
  `claude -p --verbose --input-format stream-json --output-format stream-json --model <m> --effort <e> --system-prompt <system> --tools "" --strict-mcp-config --disable-slash-commands --no-session-persistence --setting-sources= --json-schema <schema>`,
  run in a new empty temp folder that is removed afterwards. So the model
  has no tools (only `StructuredOutput`), no MCP servers, skills, user or
  project settings or hooks, and no files: it gets only what
  `change_prompt`, `impact_prompt` and `actions_prompt` built, never a case
  file (which holds its expected answer). The prompt goes on stdin as one
  user message: text parts as text blocks, each PDF as a base64
  `application/pdf` `document` block, in order. `--bare` isn't used: it
  never reads the OAuth login. The answer is the `result` event's
  `structured_output`, validated against the step's schema;
  `model_version` is the `init` event's model. After each call it prints
  the call's tokens and the 5-hour and 7-day utilization. The judge shares
  the subscription's limits with the tested model.
- **The usage limit** (a `rejected` `rate_limit_event`, or an error
  `result` mentioning a limit) raises `ClaudeLimitReached`: no file is saved
  for the run in progress, and `run.py` prints the reset time and the
  `--into <folder> --resume` command, then exits non-zero; `judge.py` keeps
  the runs already graded and grades the rest when run again. Any other
  failure (non-zero exit, no `result`, an error result, no
  `structured_output`, a schema mismatch, the 300 s timeout) makes the run a
  placeholder whose cause holds no response text; a limit missed and saved
  as a placeholder is recovered by `--resume` too.
- `--provider claude-api` (`ClaudeApiLlm`) calls the Claude API with the
  official SDK, billed to `ANTHROPIC_API_KEY` (environment, or
  `backend/.env` for this provider only). `anthropic` isn't a project
  dependency: add `--with anthropic` to the command; the other providers
  never import it. Defaults: `claude-sonnet-5-5` at effort `medium` tested
  (`--model`, `--effort`), `claude-sonnet-5-5` at effort `medium` judging
  (`JUDGE_MODEL`). Each call is `messages.parse` with the step's schema as
  `output_format`, `system`, `max_tokens` 16000, one user message (parts in
  order, PDFs as base64 `document` blocks after their label),
  `output_config={"effort": ...}` (always sent: Sonnet 5.5 defaults to
  `high`), top-level `cache_control` ephemeral (through `extra_body`, as
  `parse` has no such argument) so runs 2 and 3 of a case can read run 1's
  step 1 prefix, a 300 s timeout, and no `temperature`, `thinking` or
  `fallbacks` (both models think adaptively when `thinking` is omitted). A
  `RateLimitError` after the SDK's 2 retries is `ClaudeLimitReached` (no
  file, `--resume`); a 4xx (`AuthenticationError`, `PermissionDeniedError`,
  `NotFoundError`, `BadRequestError`, …) stops the run, since it would
  repeat; a 5xx, `APIConnectionError` (timeouts included), a `refusal` or
  `max_tokens` stop, no `parsed_output` or a schema mismatch makes a
  placeholder. After each call it prints `response.usage` (input, cache
  write, cache read, output) and, for a failed call, the request id. The
  Message Batches API isn't used: step 2 depends on step 1's answer.
- Claude has no temperature or seed setting here (the CLI has no flag for
  them); `meta.json` records them as null, and the Claude judge has no
  temperature 0, so check its agreement with itself (`judge_check.py
  agree`).
- **Compare like with like.** Production leads with Sonnet at effort `low`;
  the evals default to `medium`, so pass `--effort low` to measure
  production, and a Gemini run measures the fallback. `compare.py` across
  providers shows the model change; it isn't a regression check.

**A case** (`evals/cases/NN_name.json`, 24 of them, 13 marked
`"tier": "hard"`) is one report: `paper` (details) and `paper_text`,
`alerts`, a `notice` or a `documents` list (kind, DOI, text, optional
`pdf_text` for a stored PDF), an optional `draft_text`, and `expected`:

- `tags`: a path into the answer (`change.severity`, `alert.scope`,
  `impact.use.role`, `impact.impact_level`, …) and the values accepted;
  `"ordered": true` gives half marks for one level off.
- `hard_fail_if`: answers that are wrong whatever else is right (e.g.
  `change.severity == none` for a real correction).
- `facts`: `must_include` and `must_not_include` statements for the free
  text. The **rubric contract** (`judge.Rubric`):

  ```json
  {"must_include": ["<one fact, checkable from the answer alone>"],
   "must_not_include": ["<one fact, checkable from the answer alone>"]}
  ```

  Both keys are required and no other key is allowed; each item is a
  non-empty string stating one fact that can be checked from the answer
  alone (the judge never sees the case material); the two lists together
  hold at least one statement.
- `why`: the reasoning behind the expected answer.
- `variant_of` (optional): the stem of another case this one is a
  **metamorphic variant** of: the same facts in other words (or sections in
  another order), so the same `expected` answer applies. A variant is its
  original's file with only the texts changed (case 21 varies 01) and
  `variant_of` added; every fact and tag must stay true of the
  new texts. Variant file names contain no `__`, which splits case from run
  in result file names. A `variant_of` naming no case file is skipped.

The real cases are 28 (a retraction) and 29 (an expression of concern),
the two smallest real papers (`evals/real/SOURCES.md`). No case has a
`new_version` alert or compares two real PDFs; case 11 still has a
`new_version` document.

**The judge's fact-grading contract.** The fact grading's prompt is two
JSON documents and nothing else, on every provider. First the answer:

```json
{"change_summary": "...", "evaluation": "... or null", "recommendation": "... or null"}
```

then the rubric's two lists, `must_include` first, numbered from 1,
without saying which list each came from:

```json
{"statements": [{"n": 1, "text": "..."}]}
```

It returns `Verdicts`:

```json
{"items": [{"n": 1, "evidence": "<words of the answer, or empty>", "present": true}]}
```

The judge reports `present`; the code decides pass or fail. It judges
meaning, not wording: a statement is present when the answer gives its core
point in any words, even without an example or a detail in parentheses. Whether an
answer is true of the sources is checked by the statements, the tags and
the quote check, not by the judge. Text scores from before this contract
(when the judge also read the case material) aren't comparable with later
ones.

**The scores**, per run:

- **tag**: the mean of the tag scores (1, 0.5 or 0). A case may list no
  expected tags. Its tag score is then left out of the averages (per case
  in `all.py`, per category and overall in `score.py`), and shown as `-`
  when no tag score is left, including a category or the overall row with
  only such runs. A PLACEHOLDER run of a tagless case also has no tag
  score; one of a tagged case scores 0.
- **text**: the share of facts met. The judge only says whether each
  statement is present, and the code decides pass or fail, so a
  `must_not_include` passes when it's absent. A statement the judge
  skipped counts as failed.
- **hard**: which `hard_fail_if` rules hit.
- **quotes**: each step 1 evidence quote is checked against the text its
  `source` names, and each step 2 use quote against the draft. A source is
  matched leniently: a DOI anywhere in it (a notice's, or the paper's own
  for the stored paper), `stored paper`, `current copy`, `new version`, or
  `alert <id>` for that alert's line, which is rebuilt with the prompt's
  own `alerts_block`. Whitespace, case and curly quotes are normalised, and
  `...` / `…` in a quote skips words. Each quote comes out as one of:
  - **invented**: in none of the case's texts.
  - **misattributed**: real, but not in the text its source names (for a
    use, not in the draft).
  - **unknown source**: real, but its source names no text (e.g.
    `OpenAlex` for a retraction flag with no notice). Since `PROMPT_VERSION`
    5 the prompt has the label `alert <id>` for an alert's line.

  `check_quotes` also covers each step's raw `claims[].passages` in
  `assessment`, against the source the model named. `source_label` knows
  `draft`, and `quote_sources` includes each document's Crossref record and
  links, as the service does (`quotes.leaves`). It also includes a notice's
  `update_to_includes_paper` line, which the prompt shows; the service doesn't
  search that line, and step prompts tell the model not to quote it.

  A sentence that really is in two texts (a notice repeating the paper's
  title) passes under either label.
- **claims**: three counts per run, from `evaluation.claims` and the judge's
  grading of them (`check_passages(ev, grade=None)`):
  - **unlinked claim**: a claim with no passage. An `impact` claim on a run
    whose `assessment.draft` isn't `read` is exempt: a general judgment has
    nothing to quote. So is a claim the model marked `absence: true`
    (`PROMPT_VERSION` 12 on) or the judge lists in `absences`: it states
    only that something is missing from the material (a notice, its text, a
    banner), and nothing can be quoted for that. The model's flag is taken
    as given; the judge's list covers runs from before the flag. A derived
    claim (`from_claims` naming other claims of its step, `PROMPT_VERSION` 13
    on, or `from_change`/`from_impact` naming an earlier step's claims,
    `PROMPT_VERSION` 15 on) is exempt too: it is graded on whether it follows instead.
    `unlinked_claim` counts the evaluation's `claims`.
  - **unbacked passage**: one count each for a passage the judge says shows
    none of its claim, a quoted claim whose passages together don't show its
    substance (each number, name, date, place, direction, reason and scope
    word), and a derived claim a careful reader wouldn't accept from the
    claims it names plus the plain meaning of their words (a level follows
    when those claims give the facts it rests on). Rewording and words that
    sum up what the passages show ("headline", "key") are not gaps; the judge
    gives the benefit of the doubt and fails only a clear gap or a
    contradiction. A fact the source splits can be quoted in pieces: only the
    claim as a whole must be shown.
  - **uncovered statement**: the judge finds a factual statement in
    `change_summary`, `evaluation` or `recommendation` that no claim covers.
    Code can't prove coverage, so it is measured this way. A factual statement
    is one the sources could show true or false: the judge doesn't list advice,
    which no quote can prove, what the assistant itself does (keeps tracking,
    raises a new alert) or general knowledge. For a run with sentence lists (`PROMPT_VERSION`
    11 on), the judge sees each field as the sentences the stored text kept,
    each marked `[fact]` or `[advice]`, and skips the `[advice]` ones whole.
    With no draft read, the `evaluation`'s statements aren't graded: it is a
    general judgment, as for unlinked.

  The claims grading shows the judge each passage's source (its stored
  `target`), so it can tell a quote of the draft from a quote of the paper,
  and, for a draft or paper the case gives as `*_text` or `*_doc`, the
  heading of the section the quote sits in (`case_text.section_of`), so a
  claim can name its section without quoting a heading. It also shows the
  case's tracked paper (title, DOI, journal, year) as given facts, and says a
  fetched document's passage may come from its Crossref record.

  The judge reports, the code decides: a passage the judge skipped counts as
  unbacked, a `covered_by` number that isn't a claim covers nothing, and an
  `absences` number counts only for a claim that has no passage. Every
  passage is judged, including ones not found verbatim; an invented quote also
  counts as `invented_quote`. A run without `claims` (saved before
  `PROMPT_VERSION` 5) skips all three; a run with `claims` the judge hasn't
  graded for them yet scores `unlinked_claim` only. `supported` itself is only
  "found verbatim"; whether quotes back claims is measured here, offline.
  `evals/check_passage_scoring.py` (run by command, inline fixtures) checks
  this scoring.
- **PLACEHOLDER**: every model failed a step. Its tag score is 0
  (none for a case with no expected tags) and it isn't
  judged, but the quotes of the steps that finished before it are still
  checked.

**The verdict**, per run: `pass`, `fail`, `unjudged` (no `<run>.judge.json`)
or `error` (a PLACEHOLDER run, usually quota). A run passes when `tag` >=
0.8, `text` >= 0.8, and there are no hard fails, invented quotes or
misattributed quotes. These are the eval's acceptance criteria, fixed in
`evals/thresholds.json` before a run and changed only on purpose, never to
make a run pass:

```json
{"tag": 0.8, "text": 0.8, "max_hard_fails": 0, "max_invented": 0,
 "max_misattributed": 0, "max_unlinked": 0, "max_unbacked": 2,
 "max_uncovered": 1, "case_pass_rate": 0.67}
```

- A hard fail, invented or misattributed quote fails the run when there are
  more than the matching `max_*`, so 0 allows none.
- Unknown-source quotes never fail a run.
- Unlinked, unbacked and uncovered counts fail the run when above
  `max_unlinked`, `max_unbacked` and `max_uncovered`; a run without `claims`
  skips them.
- A tagless case skips the tag threshold; a judged run whose case lists no
  facts skips the text threshold.
- A failed run lists its failure types: `hard_fail`, `wrong_tag` (tag below
  threshold), `missed_fact` (a `must_include` failed), `wrong_fact` (a
  `must_not_include` failed), `invented_quote`, `misattributed_quote`, `unlinked_claim`,
  `unbacked_passage`, `uncovered_statement`. An
  unjudged run still shows the structured ones found so far; an `error`
  run none.
- `missed_fact` and `wrong_fact` appear only when the text score is below
  the `text` threshold: they say which kinds of statement failed. In a case
  with five or more statements, one failed statement leaves text at 0.8 or
  above and the run can still pass.

**Pass rates.** `score.py` reports `k/N` per case, per category and
overall, over counted runs only (`pass` and `fail`); `unjudged` and `error`
runs are shown next to it and left out of the rate. A rate with no counted
run shows `-`. A case meets its target when at least `case_pass_rate`
(0.67) of its counted runs pass (2 of 3); the rate is rounded to two places
before the comparison, so 2 of 3 meets it. There is no overall target; the
overall rate is only reported. `score.py` prints a "Pass Rates by Case"
table (Runs, Pass, Target `meets`/`misses`/`-`, Unjudged, Error), Pass,
Unjudged and Error columns on the category table and OVERALL row, an
overall pass rate in the diagnostics panel and a one-line count of failure
types; the grid's `verdict` column lists failure types after `fail`.
`all.py`'s summary has Pass, Target, Unj and Err columns per case, a pass
rate per category, an "Overall pass rate:" line and the failure-type
counts. Failure-type counts are over failed runs only, so they explain the
pass rate. A few runs per case illustrate reliability but don't estimate
it: raise `--runs` for a final comparison.

**Metamorphic Groups.** `score.py` prints this table only when at least two
members of a group (an original and its `variant_of` variants) have runs in
the folder. Per member it shows the pass rate and each tag's most common
value across its non-PLACEHOLDER runs (values tied for most common are all
shown, sorted, joined with ` | `). Each group ends with a `(group)` row:
pass rates `same` or `differ` (`-` when a member has no counted run), and
`inconsistent: <tags>` or `all tags consistent`. A member with only
PLACEHOLDER runs shows `-` for its values, which makes every tag
inconsistent.

**`meta.json`.** `run.py` records what produced each run in the folder's
`meta.json`:

```json
{"runs": {"<case>__run<N>": {"started_at": "<ISO UTC>",
          "prompt_version": 5, "prompt_sha256": "<hex>",
          "provider": "gemini" | "claude-code" | "claude-api",
          "models": ["gemini-flash-latest"],
          "generation": {"temperature": null, "seed": null},
          "case_sha256": "<hex of the case file bytes>"}}}
```

- `--into` adds entries; an entry for a re-run run is replaced. An entry is
  written right after the run's file, one run at a time, so a run stopped
  part-way (quota) still has entries for the runs it saved. `started_at` is
  taken before the run starts.
- `models` is the model the run was given (one: no eval falls back), not
  the version that answered; that one is in the run's own
  `assessment.model_version`.
- `provider` is `run.py`'s `--provider`.
- `prompt_sha256` keys the run's prompt texts in the folder's
  `prompts.json` (below).
- `generation` is recorded, not set: temperature and seed are the model's
  defaults, in production and in the evals (`impact/llm.py` sets neither,
  and the Claude CLI has no flag for them).
- `case_sha256` hashes the case file's bytes as read from disk, so a
  reformat with no content change also counts as a changed case.
- The git commit of a run is not recorded.

**`prompts.json`.** `run.py` also records the prompt templates the folder's
runs used, for every provider:

```json
{"<sha256>": {"prompt_version": 5, "system": "...", "change_task": "...",
              "impact_task": "...", "actions_task": "...",
              "judge_system": "...", "judge_passage_system": "..."}}
```

- The texts are `prompts.py`'s `SYSTEM`, `CHANGE_TASK`, `IMPACT_TASK` and
  `ACTIONS_TASK` and `judge.py`'s `SYSTEM` and `PASSAGE_SYSTEM`, read when
  the run starts. The key, `prompt_sha256`, is the sha256 of those six
  texts as canonical JSON (sorted keys, no spaces), without
  `prompt_version`, so a wording change without a `PROMPT_VERSION` bump
  shows up.
- A new hash adds an entry and existing ones are kept, so a folder filled
  with `--into` after a prompt change holds both.
- Rendered prompts aren't logged: they hold case text and PDFs, and the
  run's case file (`case_sha256`) and these templates rebuild them.

`score.py`, `all.py` and `judge.py` take the same runs: `*__run*.json` in
the folder (any run number). Every name containing `.judge`
(`<run>.judge.json`, `<run>.judge-<label>.json`) is a grade, not a run, and
`meta.json` and `prompts.json` are not runs; `score.run_paths` lists the runs. `score.py`
reads only `<run>.judge.json`. `judge.py` repeats the glob rather than import
`score.py`, so keep the two in step.

With several runs, `all.py` also prints each run's overall tag score and
the spread between them. Read that spread as the noise floor: a prompt
change that moves the score by less than it hasn't shown anything.

**Regression workflow.** Record a baseline folder for each `PROMPT_VERSION`,
re-run after every prompt, model or input change, compare, and keep a change
only when it beats the noise floor. `compare.py <baseline> <new>` (no LLM
calls):

- For the cases in both folders it reports the change in overall pass rate,
  mean tag and mean text (`score.py`'s overall means, PLACEHOLDER runs and
  missing scores left out), and each case's pass rate before and after
  (better, worse, same). The pass-rate change prints as `±k/N` when both
  sides count the same number of runs, as a percentage otherwise. A case
  with no counted run on one side is "not comparable".
- Each of the three changes is "within noise" when its size is at most the
  larger of the two folders' noise floors, "beyond noise" otherwise. A
  folder's floor is the spread of per-run overall tag score over all its
  runs, as in `all.py`; when only one folder has one, it is used; when
  neither does (one run per case) the label is "no noise floor (1 run)".
- A case whose `case_sha256` differs between the folders is "case changed"
  and, like cases in only one folder ("only in baseline" / "only in new"),
  is left out of the totals. A case with no `meta.json` entry on one side
  is not flagged (no hash to compare is not a change). A folder without
  `meta.json` (made before it) is compared without the hash check, and the
  output says so.
- A different `prompt_version`, model list, `prompt_sha256` or `provider` is
  a header line, not an error. A folder made before `prompt_sha256` and
  `provider` were recorded is compared without them. Across providers the
  comparison shows the model change, not a regression.
- Limit: a case that regresses in only some of its runs widens the new
  folder's spread too, so a regression in a single case can come out
  "within noise". Read the per-case lines as well as the totals.

**Checking the judge.** A judge that disagrees with itself or with a person
makes the text score itself flaky, so check it before trusting text scores.
`judge_check.py`:

- `agree <folder> <labelA> <labelB>`: grade the folder twice
  (`judge.py --label b`), then compare. Agreement is per statement (the
  judge's `present`) over statements graded in both; it prints `k/N (%)` and
  lists the differing statements. The label `default` means
  `<run>.judge.json`. Statements are paired by their text, not position; a
  statement in only one grading, and runs graded under only one label, are
  left out and counted on separate lines.
- `sample <folder> [--n 20] [--seed N] [--out <csv>]`: writes a random
  sample of graded statements to `<folder>/judge_sample.csv` (or `--out`)
  with columns `run, n, fact, kind, answer_excerpt, judge_present,
  human_present`. It samples the default grading (what the text score
  uses), leaves out statements the judge skipped, and sorts rows by run and
  statement number. Without `--seed` the sample differs each time.
  `answer_excerpt` is the run's three free-text fields as the judge saw
  them, not the judge's evidence quote, so the person marks without seeing
  its reasoning. Fill `human_present` with `true` or `false`.
- `human <folder> <csv>`: reports how often the person agrees with the
  judge. It compares each marked row with the `present` in the folder's
  current `<run>.judge.json`, falling back to the CSV's `judge_present` when
  that grade is gone, skipped the statement, or its statement `n` is now a
  different fact. `judge_present` and `human_present` are trimmed and
  lowercased before comparing (a spreadsheet's `TRUE`/`FALSE` works); the
  CSV is read as `utf-8-sig` (a "CSV UTF-8" export works). A `human_present`
  that is not `true`/`false` is listed as unmarked with its value;
  disagreements are listed too.

To add a case, copy one close to it, change the texts and `expected`, and
run `run.py --dry <case>` to check that it builds.

### Before/after smoke tests

`backend/tests/research_evaluation/impact/test_rag_effect_live.py` holds four
`live` pytest tests (so `uv run pytest` never runs them) that run `assess`
with a real model, with and without added context, on the IJAA retraction
(so steps 2 and 3 run). They are one-run smoke checks, not scored evals: no
pass rates or rubrics, one run each, no retries, and a Sonnet judge. They do
not test retrieval (the search tests do; the context is filled into
`ReportInputs` directly) and do not show the output is better, only that the
context changes it.

- `test_profile_changes_tone`: with a profile asking for casual, plain
  writing, the judge says the text is noticeably more casual and plain.
- `test_profile_keeps_levels`: change severity and impact level are each the
  baseline's or one step from it (the profile never changes a level).
- `test_feedback_affects_output`: one `overstated` feedback item from another
  of the researcher's papers; the judge says the text differs from the
  baseline's (says something new or leaves something out).
- `test_past_timeline_affects_output`: one past alert and one past report on
  an earlier expression of concern about the same paper's data; same
  judge question.

The judge reads only the two runs' text bodies, labelled "without context"
and "with context" (every sentence of the summary, explanation and
recommendation, and every action, whether or not a claim backs it), never
the added context, and answers `{"passed": bool}`. Claims are left to the
other tests. Each test prints both texts and the verdict (`-s`). A run that
comes back as the placeholder fails its test, since a comparison against it
says nothing. A baseline (no profile, feedback or past) and each variant run
once per session, 4 `assess` runs and 3 judge calls.

```
cd backend
uv run pytest -m live -s tests/research_evaluation/impact/test_rag_effect_live.py
```

The default tested model and judge are Sonnet at effort medium through
Claude Code (`claude auth login`). Overrides: `RAG_EFFECT_PROVIDER`
(`claude-code`, `gemini`, `claude-api`), `RAG_EFFECT_MODEL` (default `sonnet`
on Claude Code, `claude-sonnet-5-5` on the API, `GEMINI_MODEL` on Gemini),
`RAG_EFFECT_JUDGE_PROVIDER` and `RAG_EFFECT_JUDGE_MODEL` (same defaults). If
the chosen provider is unavailable (no key, `claude` not installed or not
logged in) the tests skip; a Claude usage limit skips the rest.

## Where the code lives

`backend/src/research_evaluation/`:

| What | Where |
|---|---|
| App, endpoints, lifespan, background task | `main.py` (`evaluate_changes`, `evaluate_reports`, `embed_feedback`, `investigate_then_assess`, `create_app`) |
| Settings | `config.py` (`ResearchEvaluationSettings`) |
| Service-token check on its endpoints | `auth.py` (`require_service_token`) |
| Storage Management client | `storage.py`: snapshots, change keys, alerts, reports, documents, PDFs, evaluation, chunks (`store_chunks`, `search_chunks`, `Chunk`); `PaperGone`, `ReportGone` |
| Embeddings | `embedding.py` (`Embedder`, `GeminiEmbedder`, `chunk_text`), `chunks.py` (the chunk text for each source, PDF text with `pypdf`, `feedback_piece`), `feedback.py` (`store_feedback_chunk`, behind `POST /embed/feedback`) |
| User Management client | `users.py`: `ResearcherProfile`, `researcher_profile(http, user_id)`, `notify(http, user_id, body)` (the events above, 30 s timeout set on the post) |
| Detection, rules, per-paper evaluation | `changes.py`, `rules.py`, `evaluate.py` |
| Investigation | `investigation/`: `http.py` (one GET, `FetchStatus`), `crossref.py`, `europepmc.py`, `investigate.py` (plan, fetch), `run.py` |
| Impact | `impact/`: `llm.py` (`ClaudeLlm`, `GeminiLlm`, `FallbackLlm`, the `Llm` protocol), `prompts.py` (the prompt texts, `PROMPT_VERSION`), `schemas.py`, `inputs.py`, `history.py` (the query, the three searches), `assess.py`, `claims.py` (`build_claims`), `quotes.py` (`norm`, `quoted_in`, `join_hyphens`, `pdf_pages`, `PdfUnreadable`, `leaves`, `DOI`, `ALERT`, `ELLIPSIS`: shared with the evals; `pypdf` is a project dependency), `run.py` (`ImpactContext`) |
| Shared with Updating | `backend/src/common/`: `doi.py` (`normalize_doi`), `service_token.py` (`ServiceTokenAuth`), `config.py` |
| Dev stub of Storage Management | `backend/dev/stub_storage.py` (every internal endpoint in memory, plus `/dev/*` helpers) |
| Tests | `backend/tests/research_evaluation/` (with `investigation/` and `impact/`); fixtures in `backend/tests/fixtures/` |
| Evals of impact (dev only) | `backend/evals/`: `cases/`, `thresholds.json`, `run.py`, `claude_llm.py` (`ClaudeLlm` through Claude Code, `ClaudeApiLlm` through the Claude API, `preflight`, `ClaudeLimitReached`, `NotOnSubscription`), `judge.py` (`Rubric`), `judge_check.py`, `check_passage_scoring.py`, `real/extract.py` (imports `research_evaluation.impact.quotes` from `src/`; run as `uv run python evals/real/extract.py`, no `--with pypdf`), `score.py` (also the shared helpers `verdict`, `pass_rate`, `group_by`, `noise_floor`, `overall_mean`, `failure_line`), `compare.py`, `all.py` |

The lifespan makes Storage Management's HTTP client (base URL, service
token, 10 s), User Management's (`UM_BASE_URL`, service token, same
timeout), one for Crossref and Europe PMC (no base URL and no auth), and
impact's Anthropic and Gemini clients, each only with its key. User
Management's is always opened, even without a model key, but only `ImpactContext` and `InvestigationContext` use it (`ImpactContext.um`
and `InvestigationContext.um`, both optional: `None` sends nothing, as in the evals).

## Gotchas

- **The service token must never leave for an outside host.** Outside
  calls (Crossref, Europe PMC) use their own clients, never the
  Storage Management one.
- **Never log quotes or PDF text** (claims verification logs neither).
- **Never log bodies, prompts, the draft or model text.** Every `_cause`
  helper logs status codes and exception classes. (Known gaps: the
  default branch of `_cause` logs `str(exc)`; investigation's `_cause` has
  no `ValidationError` case and can quote part of a body.)
- **Background tasks must never raise.** `investigate_paper`
  and `assess_report` catch everything; a report a crash
  leaves `investigating` or `investigated` stays that way.
- **`No paper <id>` is the only "gone" `404`.** For the paper PDF and the
  draft, any other `404` means "no file" / "no draft" (most projects have
  no draft). `document_pdf` treats every `404` as "no PDF".
- **The draft is read only after the gate**, and only in step 2;
  `gather_inputs` never reads it (a test counts the requests).
- **The profile is read only after the gate**, next to the draft, so a
  change at severity `none` never touches User Management. A failed read
  (caught with `except Exception`) never fails a report: it continues
  without the profile and records `failed`. Profile values are never logged
  or stored in `assessment`: the log has the report id and the exception
  class only, with no `exc_info`, since a traceback or a `ValidationError`
  message can carry the response body.
- **Fetched text is data.** `prompts._defuse` escapes `<document` tags in
  any case or spacing inside document text; PDFs can't be defused, so the
  system instruction says never to follow instructions in them.
- **A placeholder locks the report:** an evaluation is written once, so
  `POST /evaluate/reports` skips it. Only `assessment.placeholder` tells it
  from a real `low`.
- **Slow paths add up:** reports are assessed one after another, each up
  to (models in the chain) × 3 × `IMPACT_LLM_TIMEOUT_SECONDS` (18 minutes
  with Sonnet and both Gemini models).
- **Sonnet is paid per token** (`ANTHROPIC_API_KEY`, console.anthropic.com),
  with no free tier: a report is three calls, each sending its PDFs.
- **Free-tier Gemini:** about 20 requests per model per day (`429`), and
  `gemini-flash-latest` (currently `gemini-3.8-flash`) is often `503`
  ("high demand"). `gemini-2.5-flash` isn't available to new keys. A
  meaningful report takes three calls. Drafts sent on the free tier may be
  used by Google (accepted for this project).
- **`POST /evaluate/reports` replies before assessing.** A `202` means
  accepted; read the report to see whether it became `assessed`.
- **The stub differs from the real service:** `422` where Storage
  Management answers `400`, timestamps ending `+00:00`, no locking between
  concurrent report opens, one draft per paper (the real service keys
  drafts by owner and project). Seed it with `/dev/papers`, `/dev/pdfs`,
  `/dev/papers/{id}/pdf` and `/dev/papers/{id}/draft`. For the feedback path
  it also serves `PUT /internal/chunks`, a report `feedback` field, and the
  stub-only `PUT /dev/reports/{id}/feedback` and `GET /dev/chunks/{type}/{id}`.
- **Tests:** ASGITransport finishes background tasks before the nudge's
  response returns, so a test can check reports right after `nudge()`.
  `conftest.ExternalApis` fakes Crossref and Europe PMC; impact uses a
  `FakeLlm`. Only `-m live` tests reach real APIs (Crossref, Europe PMC,
  Gemini).
- **`storage/src/main/resources/dev/fake-report/report.json` is a saved
  impact output** (dev mode's fake report, STORAGE.md). A schema change in
  `research_evaluation/impact/schemas.py` can make
  `tests/research_evaluation/test_fake_report_example.py` fail; the file is
  then regenerated.
- **Evals spend the free-tier quota:** a full run is 24 cases × 3 runs ×
  up to 3 calls, far over about 20 requests per model per day. Run one or
  a few cases (`--runs 1`) while iterating, and give the judge its own key.
  A `PLACEHOLDER` row usually means the quota ran out, not a bad prompt;
  `--resume` re-runs it.
- **Evals on Claude Code spend the subscription's limits:** up to 216
  generation and 144 judge calls for 24 cases × 3 runs, judge included.
  Try one case first and read the printed utilization; at the limit the
  run stops and `--resume` continues it in the next window.
