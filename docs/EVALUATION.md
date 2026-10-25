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
          3. investigation  investigation/   open a report, fetch its documents → report ids
          4. impact         impact/          Gemini, three steps → PUT evaluation → assessed
          5. notify         notify.py        Telegram message for each assessed report
POST /evaluate/reports (report ids, by hand) ─▶ 4 and 5 only
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
- The change key is set here: `retraction`, `<type>:<notice_doi>`,
  `other:<type>:<notice_doi>`, `doaj_delisting:<snapshot_id>`.
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
correction, `other` `medium`; erratum, DOAJ delisting `low`) and template
text. Every alert is complete before any LLM runs.

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
`POST /evaluate/reports`. Without `GEMINI_API_KEY` it doesn't run (one log
line) and reports stay `investigated`.

Per report:

1. `GET /internal/reports/{id}`; skip unless `investigated` (before any PDF
   or Gemini call, so a wrong or repeated id costs one read).
2. `inputs.gather_inputs`: the paper's details (newest snapshot), the
   paper's stored PDF, each document whose `pdf_status` is `ok`. Anything
   missing is recorded, not a failure.
3. `assess.assess`, three Gemini calls in a row (`prompts.py`, structured
   output against the schemas in `schemas.py`, no tools):

   | Step | Question | Reads | Answer |
   |---|---|---|---|
   | 1. Change | What changed, is it about this paper, how severe? | paper details, alerts, documents (`<document>` blocks, each with a `links:` line, JSON, or `links: none`), PDFs: stored paper, new versions, related documents (labelled `RELATED, <doi>.`), current copy | `ChangeAssessment`: summary, change severity `none`/`low`/`medium`/`high` |
   | gate | change severity `none` = not meaningful: stop, store summary and change severity only | | |
   | 2. Impact | How does the draft use the paper; is each use affected; how much? | step 1's answer; PDF: the **draft**, read only now (`storage.draft_pdf`) | `ImpactAssessment`: uses, impact level, explanation |
   | 3. Actions | What should the researcher do? | steps 1 and 2 as JSON, no PDFs | `RecommendedActions` |

   No draft: step 2 judges for a typical researcher citing the paper as key
   evidence, and step 3 adds "upload your draft". The impact level follows
   a fixed table in the prompt (change severity × how the draft relies on
   the paper; RE-changes-explained.md §6). Step 1's PDFs are attached in
   that order up to `MAX_INLINE_PDF_BYTES` (40 MB); one that doesn't fit is
   left out and recorded. Step 1's task says what a RELATED document is
   (reached by following a notice's or new version's links; no alert of its
   own). `PROMPT_VERSION` is 3 since that wording changed.
4. `PUT /internal/reports/{id}/evaluation`, once; the report becomes
   `assessed`. Nothing is written between steps, so an `assessed` report is
   always complete.

**Three levels mean three things:** the alert's `alert_severity` is the rule's,
by change type, and never changes; the report's `change_severity` is how
serious the change really is for anyone relying on the paper; its
`impact_level` is how much this researcher's draft is affected. A
retract-and-replace can be a `high` alert, a `medium` change and a `low`
impact.

**What's stored:** `change_summary`, `change_severity`, `impact_level`,
`evaluation` (step 2's explanation), `recommendation` (step 3's), and
`assessment`, JSON with everything else: `prompt_version`, `model`,
`model_version`, `answered_by` per step, `draft` (`read` / `none` /
`not_needed`), `pdfs` attached and left out, each step's full answer,
`placeholder`.

**Model fallback** (`llm.FallbackLlm`): each call tries `GEMINI_MODEL`,
then `GEMINI_FALLBACK_MODELS` in order (duplicates removed), starting from
`GEMINI_MODEL` again for every step. Any Gemini-side failure moves on: an
API error of any code, a transport error or timeout
(`IMPACT_LLM_TIMEOUT_SECONDS`), no text, an answer that doesn't fit the
schema. Anything else raises. The SDK makes one attempt per call, so every
retry is a fallback.

**When every model fails a step**, `assess` returns a placeholder
evaluation: both levels `low`, all three texts `PLACEHOLDER_TEXT`
(`schemas.py`), `assessment.placeholder` `true` with `failed_step` and each
model's `failures`. It's stored and notified like any evaluation.

**Other failures** (Storage Management unreachable, a `409`, a blank answer
that fails the `PUT`): logged with the report id and cause, nothing
stored, the report stays `investigated`, the next report is still
assessed. A gone paper or report (`PaperGone`, `ReportGone`) is a skip.

## Notification

`notify.py` `TelegramNotifier.report_assessed`, called by
`run.assess_report` right after the evaluation is stored (never for a
skipped or failed report). One plain-text message to one hard-coded chat
(`NOTIFY_TELEGRAM_CHAT_ID`) through the bot `TELEGRAM_BOT_TOKEN`, whoever
owns the paper: report id, paper title and DOI, change severity and
summary, impact level and evaluation, recommendation (a `none` change gets
"Nothing to do."). Off unless both variables are set (blank counts as
unset). It never raises: a failure is one log line and the report stays
`assessed`. Bot setup is in SETUP.md.

## Where the code lives

`backend/src/research_evaluation/`:

| What | Where |
|---|---|
| App, endpoints, lifespan, background task | `main.py` (`evaluate_changes`, `evaluate_reports`, `investigate_then_assess`, `create_app`) |
| Settings | `config.py` (`ResearchEvaluationSettings`) |
| Service-token check on its endpoints | `auth.py` (`require_service_token`) |
| Storage Management client | `storage.py`: snapshots, change keys, alerts, reports, documents, PDFs, evaluation; `PaperGone`, `ReportGone` |
| Detection, rules, per-paper evaluation | `changes.py`, `rules.py`, `evaluate.py` |
| Investigation | `investigation/`: `http.py` (one GET, `FetchStatus`), `crossref.py`, `europepmc.py`, `investigate.py` (plan, fetch), `run.py` |
| Impact | `impact/`: `llm.py` (Gemini client, `FallbackLlm`), `prompts.py` (the prompt texts, `PROMPT_VERSION`), `schemas.py`, `inputs.py`, `assess.py`, `run.py` (`ImpactContext`) |
| Notification | `notify.py` |
| Shared with Updating | `backend/src/common/`: `doi.py` (`normalize_doi`), `service_token.py` (`ServiceTokenAuth`), `config.py` |
| Dev stub of Storage Management | `backend/dev/stub_storage.py` (every internal endpoint in memory, plus `/dev/*` helpers) |
| Tests | `backend/tests/research_evaluation/` (with `investigation/` and `impact/`); fixtures in `backend/tests/fixtures/` |

The lifespan makes four HTTP clients: Storage Management's (base URL,
service token, 10 s), one for Crossref and Europe PMC, one for Telegram
(both with no base URL and no auth), and the Gemini client.

## Gotchas

- **The service token must never leave for an outside host.** Outside
  calls (Crossref, Europe PMC, Telegram) use their own clients, never the
  Storage Management one.
- **The bot token is in the Telegram URL**, and httpx's INFO log prints
  every URL: `main.configure_logging` keeps the `httpx` logger at WARNING;
  keep it so. A notification failure logs only a status code or exception
  class.
- **Never log bodies, prompts, the draft or model text.** Every `_cause`
  helper logs status codes and exception classes. (Known gaps: the
  default branch of `_cause` logs `str(exc)`; investigation's `_cause` has
  no `ValidationError` case and can quote part of a body.)
- **Background tasks must never raise.** `investigate_paper`,
  `assess_report` and the notifier catch everything; a report a crash
  leaves `investigating` or `investigated` stays that way.
- **`No paper <id>` is the only "gone" `404`.** For the paper PDF and the
  draft, any other `404` means "no file" / "no draft" (most projects have
  no draft). `document_pdf` treats every `404` as "no PDF".
- **The draft is read only after the gate**, and only in step 2;
  `gather_inputs` never reads it (a test counts the requests).
- **Fetched text is data.** `prompts._defuse` escapes `<document` tags in
  any case or spacing inside document text; PDFs can't be defused, so the
  system instruction says never to follow instructions in them.
- **Telegram:** plain text, no `parse_mode` (model text would break
  Markdown/HTML); limit 4096 UTF-16 units, cut at 4095 + `…`; a bot can't
  message a chat that never wrote to it (`403`).
- **A placeholder locks the report:** an evaluation is written once, so
  `POST /evaluate/reports` skips it. Only `assessment.placeholder` tells it
  from a real `low`.
- **Slow paths add up:** reports are assessed one after another, each up
  to (1 + fallbacks) × 3 × `IMPACT_LLM_TIMEOUT_SECONDS` (12 minutes with
  the defaults), and each notification is awaited (up to 10 s).
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
  `/dev/papers/{id}/pdf` and `/dev/papers/{id}/draft`.
- **Tests:** ASGITransport finishes background tasks before the nudge's
  response returns, so a test can check reports right after `nudge()`.
  `conftest.ExternalApis` fakes Crossref and Europe PMC; impact uses a
  `FakeLlm`, Telegram a `FakeTelegram` (`tests/research_evaluation_support.py`).
  Only `-m live` tests reach real APIs (Crossref, Europe PMC, Gemini,
  Telegram).
