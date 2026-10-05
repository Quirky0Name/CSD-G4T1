# Decisions log

Dated log of decisions and why they were made, so settled questions stay
settled and anyone (including the TA) can see the reasoning. Newest first.
How things work now is in ARCHITECTURE.md and the service docs; this is
only the why. "The story owner's call" marks a decision the person who
owned that story made.

---

## 2026-10-05 — Week-7 scope is what's built; the docs are compressed

- **Week-7 scope is redefined as what exists**: tracking, snapshots,
  detection and rules, investigation, Gemini impact, the Telegram
  notification, the frontend, the demo tools. Stance and claims checks
  (DeepSeek), paper notes, GROBID COI text, citation metrics, User
  Management and deployment move to ROADMAP.md. The 2026-09-18 choice of
  DeepSeek is no longer in force: the only LLM in use is Gemini, and the
  provider gets picked again when stance and claims are started.
- **Docs by theme, not by story.** The per-story plans (alerts,
  investigation, impact, Gemini failsafe, notification, the three storage
  docs, the local Storage Management doc) are folded into one doc per
  service (STORAGE, EVALUATION, UPDATING), CONTRACTS, DECISIONS, SETUP and
  ROADMAP. Subtask logs and prompt texts are dropped (the prompts are in
  `impact/prompts.py`). RE-changes-explained.md stays as the research
  reference. Six 2026-09-25/26 decisions that never reached `main` (lost
  in a merge of `feat/eval-reviewing-changes`) are restored below.

---

## 2026-09-28 — Impact falls back through a list of Gemini models

- **A failed Gemini call is tried on the next model** (`GEMINI_MODEL`,
  then `GEMINI_FALLBACK_MODELS`; the story owner's call). On the free
  tier `gemini-flash-latest` is often `503` and each model has its own
  daily quota, so one model failing says little about the next; before,
  one `503` failed the whole report. Any Gemini-side failure moves on
  (API error of any code, transport error or timeout, no text, a schema
  mismatch); anything else is a bug and raises. Each step starts again
  from `GEMINI_MODEL`.
- **Default fallback: `gemini-flash-lite-latest` only.** `gemini-3.8-flash`
  is what `gemini-flash-latest` resolves to (the same overloaded model) and
  `gemini-2.5-flash` isn't available to new keys.
- **When every model fails a step, a placeholder evaluation is stored**
  (the story owner's call, with its wording): both levels `low`, the fixed
  text in every text field, `assessment.placeholder` `true` with the
  failed step and each model's cause. Accepted consequences: the report
  is `assessed` for good (an evaluation is written once), it's notified,
  and the frontend shows it.

## 2026-09-28 — The researcher hears on Telegram when a report is assessed

- **"Evaluation done" is impact storing a report's evaluation**, from the
  nudge or `POST /evaluate/reports`: that's when there's something useful
  to say (what changed, the impact on the draft, what to do).
- **Telegram, one hard-coded chat, for the demo** (the story owner's
  call). A bot is one HTTPS call and pops up on a phone; there's no User
  Management, so no user or contact to look up.
- **Never fails impact**: sent after the store, a failure is one log line.
  Plain text (model words aren't markup), nothing of it logged (the token
  is in the URL, the text is about the draft), its own HTTP client so the
  service token can't reach Telegram. Off unless both variables are set.
- Rejected: **email** (SMTP credentials, spam folder); **looking up the
  paper's owner** (no users yet); **notifying when alerts are stored**
  (rule text is generic; impact's judgment is the useful message).

## 2026-09-28 — Updating signs every request to Research Evaluation

- **Updating's Research Evaluation client carries the service token**,
  like its Storage Management client. CONTRACTS already required it on
  the nudge, but the client had no auth, so every real nudge got `401`
  and no alert was ever created. Signing the client covers any call added
  later.
- **The Research Evaluation stub checks the token too**: the stub
  accepting anything is how the gap went unnoticed.

## 2026-09-28 — Reports can be assessed by id on request

- **`POST /evaluate/reports {"report_ids": [...]}`** (the story owner's
  call), to re-run a report whose impact failed or assess the demo's
  reports ahead of time. The one way in besides the nudge; service token
  only.
- **`202` and assess in the background**, only `investigated` reports
  (others cost one read). `503` when Gemini isn't configured rather than
  accepting ids it can't assess. Ids must be JSON integers, so a typo
  isn't read as another report.

## 2026-09-28 — Impact runs right after investigation, on the reports it finished

- **The nudge's background task is investigation, then impact** on the ids
  investigation returns, one report at a time. Nothing in it changes the
  reply.
- **Without `GEMINI_API_KEY` impact doesn't run**; the service still
  starts. A failed assessment isn't retried and stores nothing (no half
  evaluations); retrying is for later.
- **Each Gemini call has its own timeout** (`IMPACT_LLM_TIMEOUT_SECONDS`),
  and the SDK makes one attempt (no hidden retries). Logs carry the report
  id and a cause, never bodies, prompts or the draft.

## 2026-09-28 — Impact asks Gemini three questions, reading the PDFs itself

- **Impact's LLM is Gemini** (the story owner's call). It reads PDFs
  natively (several per request, 50 MB or 1,000 pages each), so the stored
  paper, new versions, the current copy and the draft go in as files and
  no PDF text extraction is needed (none exists). Structured output against
  pydantic schemas, no tools.
- **Three calls, one gate** (the story owner's call): what changed and how
  severe (`none` stops there), how it affects the researcher's draft, what
  to do. Each call has one job and is tested alone with a fake model; the
  gate saves two calls for the common "not meaningful" cases.
- **The draft is read only after the gate, and only in the second call**,
  never alongside third-party notice text.
- **The impact level follows a fixed table** (change severity × how the
  draft relies on the paper; RE-changes-explained.md §6), given in the
  prompt.
- **Drafts go to whichever key is configured, free tier included** (the
  story owner's call: acceptable for this project).
- **Fetched text and PDFs are data**: tagged `<document>` blocks with tags
  inside them defused, labelled PDFs, a system instruction never to
  follow them; `PROMPT_VERSION` and the model version are stored.
- Rejected: **one call with everything** (no gate, draft next to notice
  text, untestable by step); **extracting PDF text first** (not built, and
  Gemini reads layout and figures itself).

## 2026-09-28 — A report's impact evaluation is stored once, through report-id endpoints

- **Impact reads and writes a report by id alone**
  (`GET /internal/reports/{id}`, `PUT …/evaluation`), keeping the bare
  report-id handoff from investigation.
- **Written once, only on an `investigated` report** (the story owner's
  call): `409` otherwise; one conditional update, so two writes can't both
  land. Re-assessing is for later.
- **"Meaningful" is the change severity** (`none` = not meaningful), not a
  separate boolean that could disagree. At `none` only the summary and
  severity are stored; the report is `assessed` either way.
- **Two level columns and three texts, the rest as JSON** (`assessment`),
  so the detail's shape can change without a migration.
- **Impact never changes alerts**: their rule-based severity stays, so a
  retraction never depends on an LLM.
- Rejected: overwriting an assessed evaluation (races, no need yet);
  reading reports by paper and report id (changes the agreed handoff).

## 2026-09-28 — The frontend reads a paper's reports

- **One endpoint per paper, `GET /papers/{id}/reports`, with alerts and
  documents inside** (the story owner's call), built before impact so the
  shape doesn't change when it lands.
- **Alerts in the alert API's shape and order**, so the frontend reuses
  its type and endpoints; `change_key` stays hidden.
- **Every report and every alert it grouped, dismissed ones included**: a
  report is what impact judges together, and the researcher's status
  never decides reports. A report whose alerts moved on is still listed.
- **Documents without `file_key`, `sha256`, `report_id`**; text in full.
- Rejected: a text-less list plus a per-report endpoint (two calls for a
  few hundred KB); hiding dismissed alerts; a user endpoint for a
  document's PDF for now (`pdf_source_url` links the source).

## 2026-09-27 — A late retraction notice replaces a notice-less retraction alert

- **When a retraction notice arrives after a `retraction` alert stored
  without one** (OpenAlex's flag came first), Storage Management replaces
  the row and makes it new again (`status` `new`, out of its report, `201`;
  the story owner's call), so the notice gets investigated.
- **Only retractions**, because their key is always `retraction` and the
  key check would skip the notice forever; other types are keyed by notice
  DOI. **Research Evaluation sends it anyway, Storage Management decides**
  (only it knows whether the stored alert has a notice). Within one window
  the notice wins over the flag.
- Rejected: an alert in two reports (link table, more rules for one
  case); replacing a wrong notice too (Storage Management can't tell a
  wrong notice from a right one).

## 2026-09-27 — Investigation runs after the nudge's reply

- **Reply once the alerts are stored, investigate in the background**
  (the story owner's call). A PDF download can take over 30 s per link and
  Updating's nudge timeout is 20 s; waiting would turn slow publishers into
  failed nudges.
- **No resuming, no retries** of a report left `investigating` or a failed
  fetch (the story owner's call), for now.
- **The handoff to impact is a report id** (the story owner's call):
  impact reads everything from Storage Management, so neither package
  imports the other and each can be re-run alone.

## 2026-09-27 — Investigation fetches notices itself, deterministically

- **Fixed code decides and fetches, before any LLM**, instead of an LLM
  calling fetch tools: cost, time and tests are predictable, and nothing
  an LLM reads can steer what gets fetched.
- **Its own Crossref fetcher**, not Updating's: separate services, and the
  fields differ (a notice's title, date, `update-to`, `relation` vs a
  paper's `updated-by`). Sharing via `common/` is a later refactor.
- **Europe PMC is the only text source**: Crossref has no notice text, and
  publisher pages and PDFs are bot-blocked. Paywalled notices get none.
- **Fetched text is data for impact, never instructions.**

## 2026-09-27 — Storage Management downloads a document's PDF when it's stored

- **`POST /internal/documents` downloads the PDF** with the existing
  `OpenAccessPdfClient` (the story owner's call): the download code stays
  in Java and PDFs stay where every other PDF is.
- **Only when it creates the row** (the story owner's call); an existing
  row is never downloaded again. `GET …/pdf` only reads.
- **No "is it new" verdict**: hashes can't tell a new version (different
  sources, download stamps, repository copies never corrected), so
  `sha256` and the source link are facts and the judgment is later.
- Rejected: downloading in Python and uploading the bytes (copies the link
  lookup and checks); downloading on first read (reads with side effects,
  a second call for investigation).

## 2026-09-27 — Reports group a nudge's new alerts and hold what investigation fetched

- **One report per paper per nudge that stored new alerts** (the story
  owner's call), grouping every alert not in a report yet, done by Storage
  Management in one transaction (only it knows what's ungrouped).
- **The report is where evaluation happens**, not the alert: real cases
  only make sense together (a correction, then an EoC, then a retraction;
  a wrong notice next to the real one), and a conclusion about how alerts
  relate needs a home.
- **Documents belong to the report, one per DOI**; the current copy once
  per report. **No link table to alerts** (the story owner's call): they
  match by DOI. **A stored document is never overwritten** (the story
  owner's call). Researcher status stays on alerts. Owner and project come
  from the paper.
- Rejected: one report per paper rewritten each time (loses history,
  races); documents per alert (the same copy downloaded per alert);
  documents shared by DOI across reports and users (the current copy is
  the paper at a given time).

## 2026-09-27 — Alert notes migration renumbered to V5

- **`V4__create_alert_notes.sql` became `V5__…`**, unchanged, because
  `background_metadata` and alert notes were each written as V4 on their
  own branches and Flyway refused to start on `main`. No new migration can
  fix two files with one version, so this one breaks the "never edit a
  migration on `main`" rule. Alert notes moved because
  `background_metadata` was merged first. Consequence: a database that ran
  alert notes as V4 needs a reset, and new migrations check the numbers on
  other open branches.

## 2026-09-27 — No separate LLM step for `other` changes

- **The `llm.py` placeholder (`investigate()`, for `other` changes only,
  doing nothing) was removed.** The LLM evaluation is one layer over every
  change, `other` included, not an extra layer that first works out what
  an `other` change is. Stored alerts don't change. That layer became
  impact, on reports.

## 2026-09-27 — Research Evaluation reads tracked papers' PDFs

- **`GET /internal/papers/{id}/pdf` is built**, with three distinct `404`
  details, since Research Evaluation skips a paper only on `No paper <id>`.
- **No paper-details endpoint for Research Evaluation**: the snapshots
  carry the same identity fields, fresher (`papers.title` goes stale), and
  it needs nothing else from `papers`. Revisit if a stage needs the owner,
  folder or ingest metadata.

## 2026-09-27 — The researcher's own paper, one per project

- **Storage Management keeps the researcher's draft per project**
  (`POST /research-paper`), so a change can be judged against what they're
  writing. Stored like a tracked paper's PDF.
- **A folder is a project; `folder_id` null is the "no folder" project**,
  everywhere, never a sentinel; missing or `""` means no folder on input.
- **A project is keyed by owner and `folder_id`**: folders aren't checked
  (no User Management), so the owner in the key keeps users apart.
- **One draft per project, the newest upload replaces it** (same id, old
  file deleted): a change is judged against the draft as it is now.
- **Read by tracked paper id** (`GET /internal/papers/{id}/research-paper`):
  Research Evaluation only has paper ids, and a tracked paper is in exactly
  one project.
- **Its own table**, no GROBID or Crossref on upload.
- Rejected: a flag on `papers` (Updating would poll it); a sentinel folder
  id; keeping every draft version; reading by owner and folder, or by DOI
  across users (hands over other users' drafts); a papers ↔ folders join
  table (revisit with User Management).

## 2026-09-26 — Researchers can keep a log of notes on an alert

- **Append-only notes per alert** (`POST`/`GET /alerts/{id}/notes`), e.g.
  "Removed the citation from my draft": the status says *that* they dealt
  with it, not *what* they did. Separate from the status, own endpoints
  (the alert shape stays), newest first, no author column (only the owner
  can write), 1–2000 UTF-16 units.
- Rejected: one overwritten note field; a history of status changes (the
  story owner's call: not needed).

## 2026-09-26 — Storage stores snapshots and open-access PDFs; migrations tidied

- **Storage Management keeps Updating's snapshots** (CG-68), insert-only;
  the JSON fields (`crossref_updates`, `authors`, `source_status`) stored
  as sent so Updating can add fields freely; authors stay inside the
  snapshot (no `authors_background` table until something queries them).
- **Migrations V1, V3, V4**: the deleted V2 isn't restored (V1 already
  creates `file_key`); local databases that ran it are reset instead.
  From here on a migration on `main` is never edited or deleted.
- **A DOI-only paper needs an open-access PDF**: every OpenAlex `pdf_url`,
  then Semantic Scholar's; the first real PDF under 25 MB. None: `422`,
  not tracked, so every tracked paper has a PDF to evaluate. The message
  doesn't suggest uploading (usually paywalled).
- Rejected: restoring V2 plus an undo migration; typed columns for the
  JSON fields; tracking without a file and asking for an upload later;
  `best_oa_location` only (misses PLOS copies); Europe PMC, HAL and
  publisher pages (all blocked). Consequence: the ScienceDirect demo
  papers can't be tracked by DOI, only uploaded.

## 2026-09-26 — Alerts from one poll are listed most severe first

- **The alert list breaks ties on `detected_at` by severity, then newer
  `id`.** Alerts from one pair of snapshots share a detection time and
  were ordered by `id`, which put the retraction (stored first) below an
  erratum from the same poll. Storage Management sorts in memory; the
  store order doesn't matter.

## 2026-09-26 — Research Evaluation compares only the newest N snapshots

- **Each nudge reads the newest N snapshots** (`EVALUATION_SNAPSHOT_WINDOW`,
  default 5, via `last=N`), not the ever-growing history. Not only the last
  pair: after a failed nudge, Updating re-sends on the next poll, by which
  time the change is in an older pair.
- **The limit:** a change survives N − 2 failed nudges in a row (about 3
  days at N = 5 and daily polls), then is lost silently. Raise N before
  deployment; a bigger N only costs a larger read.

## 2026-09-26 — Only changes not stored yet are evaluated

- **Detection runs on the window, then the paper's stored change keys are
  read and only new changes are assessed and stored.** Cheap now, but
  needed once evaluation costs LLM calls. The lookup is skipped when
  nothing was detected. A key in two pairs is evaluated once (the earlier;
  changed 2026-09-27 for retractions with a notice).
- Known limit: two nudges at the same moment can both evaluate a new
  change (only one alert is stored).

## 2026-09-26 — Research Evaluation is called only by Updating's nudge

- **Only Updating calls Research Evaluation, only to nudge**: Updating
  notices, Research Evaluation decides what to evaluate. **Storage
  Management never calls it.** (`POST /evaluate/reports` was added
  2026-09-28 as the one manual way in.)
- **`/evaluate/background-info`, `/citation-neighbourhood` and `/stance`
  are under review**: their callers came from the 2026-09-18 design, which
  the 2026-09-24 split made obsolete. Likely internal steps, not endpoints.
  `RE_BASE_URL` is Updating's only.

## 2026-09-26 — One alert per Crossref notice, for now

- **A notice Crossref lists under several types gives one alert, of the
  most severe type** (`retraction`, `expression_of_concern`, `correction`,
  unclassified, `erratum`). IJAA's retraction notice is also a publisher
  `erratum`, and a Lancet notice is both `correction` and `erratum`: one
  alert per type showed two alerts for one document, the second milder.
- **A known notice under a new type gives nothing new**, unless it's now a
  `retraction`. Notices without a DOI count per type.
- **Temporary**: the intended design is an LLM judging all flagged changes
  together (ROADMAP.md).

## 2026-09-25 — Alerts: stored in Storage Management, evaluated in stages

- **Storage Management stores alerts**, written by Research Evaluation
  (`POST /internal/papers/{id}/alerts`): it's the persistence service the
  frontend already talks to, it knows each paper's owner (no copied
  `owner_id`), and Research Evaluation stays off the frontend's path with
  no database of its own.
- **Idempotent on (`paper_id`, `change_key`)**, a repeat returns the stored
  alert unchanged (`200`), so a re-sent nudge never resets a researcher's
  status. A race is settled by the unique constraint and a re-read in a
  fresh transaction. Alerts are deleted with their paper.
- **Detection time is the first snapshot's `fetched_at`**, not when the
  alert was stored, so retries and catch-ups don't change recency.
- **Another user's paper or alert gets the same `404` as a missing one**
  (a `403` would confirm a guessed id exists).
- **Status:** dismissing hides an alert by default, acknowledging keeps it
  (the researcher's call); either can replace the other; repeating changes
  nothing; never back to `new`.
- **Deterministic detection, then a rule-based assessment, then (later)
  LLM stages**: a retraction never depends on an LLM, and every alert is
  complete. Severities: retraction `high` (findings withdrawn); EoC and
  correction `medium` (in question; content changed); erratum and DOAJ
  delisting `low` (publisher error; about the journal); `other` `medium`
  (looked at rather than ignored). One retraction alert per paper; an
  unclassified Crossref type becomes `other`, never dropped.
- **`POST /evaluate/changes` takes service tokens only**, so a user can't
  trigger evaluations of any paper id.
- **Evaluate before replying `202`, keep no state**: Updating clears its
  flag on a `202`, so a change accepted and then lost would be gone for
  good; replying after the evaluation (`503` on any failure) makes
  Updating's flag the retry. Any failure is `503` for now; one failing
  paper doesn't stop the others.
- **Paper-not-found is recognised by `detail` exactly `No paper <id>`**:
  Spring answers a missing route with problem details too.
- Rejected: Research Evaluation owning alerts in its own schema (puts it on
  the frontend's path with user JWTs, CORS and a copied owner).

## 2026-09-25 — Storage keeps every tracked paper's PDF

- **Storage Management keeps the PDF of every tracked paper**: a snapshot
  only says *that* a paper changed; judging what it means needs the paper.
  This reversed a commit that discarded uploads after GROBID read them.
- **Local disk for now**, Postgres holds only the key; deployed, a
  persistent volume on the VM.
- **Research Evaluation reads PDFs only through
  `GET /internal/papers/{id}/pdf`**, never from the folder, so the services
  can move to different hosts or the files to S3.
- Rejected: discarding the PDF after GROBID; keeping only extracted text
  (fixes the extraction at ingest); S3 now (an account and credentials for
  everyone, for a one-VM stack); Research Evaluation reading the folder.

## 2026-09-25 — Updating stores no snapshot when Crossref or OpenAlex errors

- **On `error` from either source, no snapshot for that paper this poll**
  (`source_errors`, retried next poll). Only `error` gates: `not_found` is
  stable and stored; a failed author batch is stored (authors aren't
  compared).
- **Why:** fields whose source wasn't `ok` are never compared, so a change
  published during an outage would be lost (Mon ok; Tue down, correction
  published; Wed ok: Wed vs Tue is skipped, Thu matches Wed). With the
  gate, every stored snapshot is comparable with the one before it.
- Rejected: per-source "last ok" baselines (extra state, and Research
  Evaluation would have to copy the logic).
- Also: `JWT_SECRET` must be base64 of 32+ bytes even in sprint 1 (Storage
  Management's library rejects shorter keys), and the first scheduled poll
  counts from the last scheduled poll, so restarts can't postpone it.

## 2026-09-24 — Updating owns fetching and snapshots; Research Evaluation evaluates

- **Ownership split:** Updating fetches Crossref/OpenAlex data and stores
  snapshots, recording no changes; Research Evaluation works out the
  differences, classifies and evaluates them, and owns alerts. Replaces the
  2026-09-18 design where Research Evaluation built snapshots and Updating
  diffed them.
- **The handoff is a nudge with paper ids, not a payload**; the two share
  data only through Storage Management. Updating does a plain comparison
  to decide whether to nudge and keeps `nudge_pending` per paper (after a
  failed nudge the next snapshot would look unchanged), so the nudge must
  tolerate repeated ids. Rejected: pushing each change with its data
  (couples shapes); a bare nudge with no ids (Research Evaluation would
  scan and keep a watermark); change ids read back from Updating (two
  services to call, change rows to keep).
- **Snapshots per paper, insert-only, full history, stored even when
  nothing changed**: each user gets their own baseline, differences can be
  worked out and replayed after the fact. Keying by DOI was rejected
  (fan-out logic for a few rows saved).
- **Citation counts stored, not alerted on.**
- Facts from live responses that shape the rules: `updated-by` lists one
  notice per source, sometimes under different types (identify entries by
  `notice_doi`, `type`); DOAJ status belongs to the journal, and repository
  locations are always false (so a delisting needs the same journal
  source); OpenAlex and Crossref citation counts disagree (OpenAlex only);
  the batched `/authors` answer is unordered; Crossref uses more types
  than we alert on; `papers.title` goes stale; DataCite DOIs aren't in
  Crossref (`not_found`).

## 2026-09-18 — Research Evaluation + Updating scope and design

- **Updating is Python**, sharing one project and image with Research
  Evaluation (two FastAPI apps), to avoid duplicating DTOs, JWT validation
  and HTTP code across Python and Java.
- **Updating's own data lives in its own schema**, so its writes don't
  depend on Storage Management's uptime.
- **New-related-paper detection is out of week-7 scope**, moved to week 13
  with topic discovery (similar OpenAlex queries).
- **Crossref's retraction signal is `updated-by`, not `relation`**
  (checked live on two retracted papers).
- **No separate DOAJ client**: OpenAlex's `is_in_doaj` comes free. A
  subscription journal is never in DOAJ, so only a true → false flip is a
  change.
- **OpenAlex needs an API key** for its full free budget.
- **No `/health` endpoints.**
- *Superseded:* DeepSeek as the LLM provider, the stance endpoint taking
  DOIs, cached Semantic Scholar snippets and a capped local cache were
  all planned for stance and claims, which weren't built (2026-10-05).
