# Evals: realistic case PDFs and cases from real published papers

**Status:** approved
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

Source of the task: the eval cases' inputs are a few hundred characters
each, built as plain single-column PDFs, and every case is made up, so the
evals don't test finding a fact in a realistic paper or draft, or a real
published PDF's layout and length, or a real notice's wording. The
real-world patterns and real examples are in `docs/RE-changes-explained.md`
(§8 for the real papers). Made-up cases stay: they give controlled traps,
and the model may remember famous real papers.

**Commands** (from `backend/`):
- Full test suite: `uv run pytest` (the evals have none: they are scored by
  command, not by pytest, DECISIONS.md 2026-10-27)
- Single test file: none for the evals
- Run the scorer: `uv run --with rich --with fpdf2 python evals/score.py <results folder>`
- Build cases without Gemini: `uv run --with rich --with fpdf2 python evals/run.py --dry [case ...]`
- Read a built PDF back (throwaway checks only): add `--with pypdf`
- Extract a real PDF's text: `uv run --with pypdf python evals/real/extract.py`
- Typecheck: none
- Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | S1 |
| S3 | S1 |
| S4 | S3 |

Run order: S1, S2, S3, S4.

S3 is blocked by S1 because both change `case_text.py`, `run.py` and
`score.py`; S4's drafts use S1's `draft_doc` format.

External blockers:
- `docs/specs/2026-11-05-eval-pass-rates-and-regression.md` is done on
  `feat/ai-eval-output`, whose PR to `main` is open. This branch is
  stacked on it, so its own PR merges after that one. Variant
  `22_retraction_key_evidence_reordered.json` from that spec exists and
  S2 converts it (user, 2026-11-05).
- S4 downloads PDFs and notice texts from the publishers and Europe PMC,
  so it needs network access. A file that can't be fetched (bot check,
  `403`) is a Human check, not a failure.

Git state: branch `feat/eval-realistic-pdfs`, at `907c2e0` on top of
`feat/ai-eval-output` (user, 2026-11-05). No uncommitted changes besides
this spec.

## Suggested splits

No split: S2 and S4 write cases in the formats S1 and S3 add, and S3
changes the same files as S1.

## Out of scope

- Pass/fail thresholds, pass rates, run records, regression compare, judge
  checks and metamorphic variants: done in
  `docs/specs/2026-11-05-eval-pass-rates-and-regression.md`. `transient`.
- Cases for investigation (user, 2026-11-05). ROADMAP.md already lists
  them (`transient`).
- Real papers that aren't CC-BY (Elsevier, the Lancet fixtures): never
  committed. Doc delta on S4.
- Real drafts: every draft is written for the eval, citing the paper.
  Doc delta on S4.
- Rewriting the made-up cases not named in S2. The plain `*_text` fields
  stay valid. Doc delta on S1.

## Interfaces

- **Rich document in a case** (S1, written by S2 and S4): wherever a case
  has `paper_text`, `draft_text` or a document's `pdf_text`, it may
  instead have `paper_doc`, `draft_doc` or `pdf_doc`, of this shape:
  ```json
  {"layout": "one_column" | "two_column",
   "running_head": "J. Fake Psychology 2020; 12: 40-52 · doi:10.9999/...",
   "banner": "This article has been retracted. See ...",
   "watermark": "RETRACTED",
   "title": "...", "authors": "...", "abstract": "...",
   "sections": [{"heading": "...", "text": "...",
                 "table": {"caption": "...", "rows": [["...", "..."]]},
                 "figure": {"caption": "..."}}],
   "references": ["..."]}
  ```
  Only `title` and `sections` are required. Doc delta on S1
  (EVALUATION.md).
- **Real PDF in a case** (S3, written by S4): wherever a case has
  `paper_text` or a document's `pdf_text` (or their `_doc` forms), it may
  instead have `paper_file` or `pdf_file`: a file name in `evals/real/`,
  e.g. `"pone.0258361.pdf"`. `run.py` attaches the file's bytes as they
  are; `case_text` reads its text from the committed `<same stem>.txt`
  next to it (made once by `evals/real/extract.py`), so `score.py` and
  `judge.py` need no PDF library. Doc delta on S3 (EVALUATION.md).
- A case has one form per field (`_text`, `_doc` or `_file`), never two.

## Subtasks

### S1: Cases can describe realistic PDFs, and every built PDF takes Unicode

- **Goal:** A case can give its paper, draft or a document's PDF as a rich
  document (two columns, running head, banner, a watermark on every page,
  tables, figure placeholders, references), `run.py` builds it, and the
  quote check and the judge read the same text the PDF shows. Every built
  PDF, plain or rich, renders non-Latin-1 characters.
- **Blocked by:** none
- **Files:** `backend/evals/case_text.py` (new), `backend/evals/run.py`,
  `backend/evals/score.py` (`quote_sources`), `backend/evals/judge.py`
  (`prompt`), `backend/evals/fonts/` (new: DejaVu Sans regular and bold,
  and its licence file), `docs/EVALUATION.md`
- **Seams:** `run.py --dry`; `run.build_inputs`; `case_text.doc_text`; the
  scorer command.
- **Decisions:**
  - The rich document shape as in Interfaces; the plain `_text` fields stay
    valid and build as they do now, apart from the font (user, 2026-11-05).
  - The watermark is drawn on every page, light grey and diagonal, behind
    the text (RE-changes-explained.md §1, R1). The banner is drawn on
    page 1 and the running head on every page.
  - DejaVu Sans (regular and bold) under `evals/fonts/`, used for every
    built PDF (user, 2026-11-05).
  - `check_quotes` itself is unchanged; `quote_sources` gives it, for a
    rich document, `case_text`'s full text, so a quote of the running head,
    banner, watermark or a table cell counts as real, and the watermark and
    banner belong to the document they're drawn on (e.g. `current copy`).
  - The judge's CASE MATERIAL uses the same full texts.
  - `case_text.py` uses only the standard library, so `judge.py` still
    needs neither `rich` nor `research_evaluation` (code: `judge.py`'s
    comment on `score.run_paths`).
  - `case_text.doc_text` returns every word the built PDF shows, in reading
    order: running head, banner, watermark, title, authors, abstract, each
    section's heading, text, table caption and cells, figure caption, then
    references. `paper_text(case)`, `draft_text(case)` and
    `pdf_text(document)` return the `_text` field or `doc_text` of the
    `_doc` field. `transient` (code shows it).
- **Acceptance criteria:**
  - [x] `run.py --dry` builds every case in `evals/cases/` (unchanged
        output apart from the font).
  - [x] Throwaway script, `--with pypdf`: a rich document with
        `two_column`, a watermark, a table and enough sections for 3 pages
        builds through `run.build_inputs`; the PDF has at least 3 pages,
        and every page's extracted text contains the watermark.
  - [x] Same script: plain `paper_text` and a rich document containing
        `7·9% – ≥ α "curly"` both build without an error.
  - [x] Same script: `score.check_quotes` on a fake run quoting one table
        cell of a rich stored paper as `stored paper`, and the watermark of
        a rich `current_version` document as `current copy`, finds neither
        invented nor misattributed.
  - [x] Same script: `judge.prompt` for a case with a rich draft contains
        a sentence from the draft's last section.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals", "A case": the `_doc` form of `paper_text`,
    `draft_text` and `pdf_text`, its fields, that a case uses one form per
    field, and that the plain form stays valid; that built PDFs use the
    DejaVu font in `evals/fonts/`; that quotes and the judge read the full
    text the PDF shows.
  - Decisions made in implementation (S1):
    - `case_text.doc_text` also includes the "Abstract" and "References"
      labels, because the rich PDF prints them and `doc_text` returns every
      word the PDF shows. EVALUATION.md "A case".
    - `case_text.form` raises `ValueError` when a field is given in two
      forms, so "one form per field" is enforced, not only documented.
      EVALUATION.md "A case".
    - The DejaVu files are matplotlib's bundled copies; `fonts/LICENSE` is
      their `LICENSE_DEJAVU`. No doc: the licence file is beside the fonts.
    - Rich layout: A4; in two columns the title block spans the page and the
      sections flow down the left column, then the right; the first table row
      is the header (bold); a figure is an empty grey box above its caption.
      EVALUATION.md "A case" (table's first row is the header).
    - The judge still shows a document's `text`, or its PDF text when it has
      none (as before, now including `pdf_doc`). No doc: code shows it.

### S2: Five cases become realistic, and four missing scenarios get cases

- **Goal:** Five existing cases (and the variant of one of them) use
  full-length rich PDFs with the tested facts deep inside, and four
  missing scenarios have cases, so the evals test finding facts in
  realistic documents.
- **Blocked by:** S1
- **Files:** `backend/evals/cases/02_retraction_key_evidence.json`,
  `11_concern_then_retraction_replacement.json`,
  `16_partial_retraction_two_uses.json`,
  `18_correction_use_unaffected.json`, `19_flag_with_watermark.json`,
  `22_retraction_key_evidence_reordered.json`, four new case files `24_…`
  to `27_…`, `docs/EVALUATION.md`
- **Seams:** `run.py --dry`; `case_text`; the scorer's metamorphic group
  table.
- **Decisions:**
  - The case files are written directly as JSON by subagents. No script or
    Gemini call generates their text. The PDFs are built from them by S1's
    code at run time and never committed.
  - The implementer doesn't write the long case files in its own context:
    it spawns one subagent per case file (user, 2026-11-06). Each subagent
    gets the case file path, its scenario and this subtask's Decisions,
    writes the file, runs `run.py --dry <case>`, and returns only the path
    and the result, never the case text. Variant 22 is written by the
    subagent that writes 02, so the pair differs only in wording and section
    order. `transient`.
  - Cases 02, 11, 16, 18, 19 move to the rich format (user, 2026-11-05).
    No other existing case is touched.
  - Variant 22 (of 02) moves to the rich format with 02, so the pair still
    differs only in wording and section order, and keeps 02's `expected`
    and `variant_of` (user, 2026-11-05: variants keep their original's
    format).
  - New cases, numbered 24 to 27, from RE-changes-explained.md (user,
    2026-11-05): R3 (the "notice" DOI is the paper's own DOI), R8 (a
    retraction reversed by a notice registered as `addendum`), C3 (a
    correction titled only "Department of Error") and a `withdrawal` whose
    content is gone (only the notice and the stored PDF remain). Each has
    `tier`, `category`, `expected` (tags, `hard_fail_if`, facts) and a
    `why` citing the RE-changes-explained.md section it comes from.
  - Lengths (user, 2026-11-05): a rich paper at least 5 pages, a rich
    draft at least 3 pages with at least 20 references, the tested facts
    outside page 1.
  - A rewritten case keeps its `expected` unless the new texts change what
    is true; any change to `expected` is listed in the commit body.
  - Filler text is about the case's own topic and states nothing that
    contradicts or adds to the facts the case tests. Fake DOIs stay under
    `10.9999/` and no real paper's text is quoted in made-up cases.
  - Case 19's watermark is the rich `watermark` field on its
    `current_version` document, with "RETRACTED:" before the title.
- **Acceptance criteria:**
  - [ ] `run.py --dry` builds every case in `evals/cases/`.
  - [ ] Throwaway script, `--with pypdf`: each rich paper has at least 5
        pages and each rich draft at least 3 pages and 20 references.
  - [ ] Same script: for each rewritten and new case, every value quoted in
        its `why` in double quotes appears in `case_text`'s text of the
        case, and the page-1 text of a rich draft doesn't contain the
        tracked paper's reference.
  - [ ] Same script: 22's `expected` equals 02's, and both use the rich
        format.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": the case count (the number of files in
    `evals/cases/`) and that some cases use full-length rich PDFs.

### S3: A case can attach a real PDF, read through its extracted text

- **Goal:** A case can point its stored paper or a document at a real PDF
  in `evals/real/`; `run.py` attaches the exact bytes, and the quote check
  and the judge read the PDF's extracted text.
- **Blocked by:** S1
- **Files:** `backend/evals/real/extract.py` (new),
  `backend/evals/case_text.py`, `backend/evals/run.py` (`build_inputs`),
  `backend/evals/score.py` (`norm`), `docs/EVALUATION.md`
- **Seams:** `run.py --dry`; `run.build_inputs`; `case_text`; the scorer
  command; `extract.py`.
- **Decisions:**
  - `paper_file` / `pdf_file` as in Interfaces. `case_text`'s `paper_text`
    and `pdf_text` also return the `.txt` of a `_file` field. `transient`
    (code shows it).
  - `extract.py` (run with `--with pypdf`) writes `<stem>.txt` for each
    PDF in `evals/real/` that has none (or all with `--redo`), joining a
    word hyphenated across a line break. The `.txt` is committed and is
    what the quote check and the judge read.
  - `score.norm` also applies Unicode NFKC, so a ligature in a real PDF's
    text (`ﬁ`) matches a quote that spells it out (`fi`). No effect on
    ASCII cases.
- **Acceptance criteria:**
  - [ ] Throwaway script with a small PDF built by fpdf2 and saved into a
        temp `real/` folder: a case with `paper_file` builds through
        `run.build_inputs`, and the stored paper's bytes equal the file's.
  - [ ] Same script: `extract.py` on a PDF whose text has a word split
        `pro-` / `liferation` across lines writes `proliferation` in the
        `.txt`.
  - [ ] Same script: `score.check_quotes` on a fake run quoting a sentence
        of that `.txt` with `fi` where the text has `ﬁ`, as `stored paper`:
        not invented, not misattributed.
  - [ ] `run.py --dry` builds every case in `evals/cases/` as before.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals", "A case": `paper_file` / `pdf_file`,
    `evals/real/`, `extract.py`, and that the quote check and the judge
    read the committed `.txt`.

### S4: Four cases use real published papers and their real notices

- **Goal:** Four cases attach a real open-access paper's PDF and its real
  notice text, with a draft written for the eval that cites the paper, so
  the evals also test real layout, length and notice wording.
- **Blocked by:** S3
- **Files:** `backend/evals/real/` (the PDFs, their `.txt` texts,
  `SOURCES.md`), four new case files `28_real_…` to `31_real_…`,
  `docs/EVALUATION.md`, `docs/DECISIONS.md`, `docs/ROADMAP.md`
- **Seams:** `run.py --dry`; `case_text`; the scorer command.
- **Decisions:**
  - The real PDFs are committed under `evals/real/` with `SOURCES.md` (DOI,
    title, URL fetched from, date, licence, sha256 per file) (user,
    2026-11-06).
  - Four papers, from RE-changes-explained.md §8 (user, 2026-11-06). All
    are CC BY 4.0 for paper and notice in Crossref's `license` field
    (checked 2026-11-06):
    - R1 retraction: PLOS ONE `10.1371/journal.pone.0258361`, notice
      `10.1371/journal.pone.0322065`
    - E1 expression of concern: PLOS ONE `10.1371/journal.pone.0035065`,
      notice `10.1371/journal.pone.0298436`
    - C2 figures switched: PLOS ONE `10.1371/journal.pone.0309025`, notice
      `10.1371/journal.pone.0320535`
    - C6 new version: F1000Research `10.12688/f1000research.187739.1` and
      `.2`
  - Two papers were dropped and not replaced (user, 2026-11-06). `transient`.
    - C1 admin correction, Sci Rep `10.1038/s41598-024-79772-7`: paper and
      notice `10.1038/s41598-025-93099-x` are CC BY-NC-ND 4.0.
    - E4 concern lifted, RSC Advances `10.1039/c3ra45263h`: no licence in
      Crossref, no Europe PMC record, publisher page returns `403`.
  - Before a paper is used, the download subagent checks its and its
    notice's licences in Crossref's `license` field (or the PDF's own licence
    line); a paper with either not CC-BY (any version) is dropped, and
    `SOURCES.md` says why.
  - A subagent downloads the PDFs with curl, not the implementer (user,
    2026-11-06). It runs before the case file subagents, checks the licences,
    runs `extract.py`, writes `SOURCES.md`, and returns only which files were
    fetched and which failed, never the PDF or `.txt` text. A file that can't
    be fetched is left out and listed in the run's final report for the user
    to download by hand. `transient`.
  - The case files are written directly as JSON by subagents. No script or
    Gemini call generates their text.
  - The implementer doesn't write the case files or read the real papers in
    its own context: it spawns one subagent per case file (user, 2026-11-06).
    Each subagent gets the case file path, the paper's `.txt` path, the
    notice's source and this subtask's Decisions, reads them, writes the
    file, runs `run.py --dry <case>`, and returns only the path and the
    result, never the case or paper text. `transient`.
  - The notice is the real notice text from Europe PMC's `fullTextXML`
    (RE-changes-explained.md §8), as plain text in the case's `notice` or
    `documents` entry, as investigation stores it.
  - `paper` details (DOI, title, year, journal, authors) are the real
    ones; alerts are what the pipeline would raise for that notice
    (RE-changes-explained.md §8, "What our code does").
  - The draft is a rich `draft_doc` written for the case, citing the real
    paper, at least 3 pages and 20 references; it states nothing about the
    real paper that the paper doesn't say.
  - `expected` is written from reading the real paper and notice; `why`
    quotes the notice sentence each tag rests on.
  - Category names of these cases start with `real: `, so the category
    table shows real and made-up cases apart.
- **Acceptance criteria:**
  - [ ] `run.py --dry` builds every case in `evals/cases/`, and each real
        case attaches its file's bytes unchanged (throwaway script compares
        sha256 with `SOURCES.md`).
  - [ ] Throwaway script, `--with pypdf`: each committed PDF opens, has
        more than one page, and its first page's text contains the paper's
        title from the case.
  - [ ] Same script: for each real case, every notice sentence quoted in
        its `why` appears in its notice text, and the stored paper's `.txt`
        contains the paper's title.
  - [ ] `SOURCES.md` lists a CC-BY licence for every committed file.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - EVALUATION.md "Evals": the real cases, `evals/real/SOURCES.md`, that
    drafts are always written for the eval, that real cases' categories
    start with `real: `, and the case count (the number of files in
    `evals/cases/`).
  - DECISIONS.md: a dated entry amending the 2026-10-27 evals entry
    ("nothing real is quoted"): a few real open-access (CC-BY) papers and
    notices are added beside the made-up cases, to test real layout and
    wording; made-up cases stay for controlled traps and because the model
    may remember famous real papers; non-CC-BY papers are never committed.
  - ROADMAP.md: reword "Add cases from real PDFs" to what remains (more
    real cases, investigation cases).

## Human checks

- [ ] (S1) Open one built rich PDF (e.g. from a throwaway `build_inputs`
      call written to disk). Expected: two columns, running head on every
      page, the watermark diagonal and readable behind the text, tables
      and figure boxes in place.
- [ ] (S2) Read the five rewritten cases, variant 22 and the four new
      cases. Expected: the filler is plausible and contradicts nothing;
      each new case matches its RE-changes-explained.md scenario; each
      `expected` still fits.
- [ ] (S2) Run one rich case against Gemini: from `backend/`,
      `uv run --with fpdf2 --with rich python evals/all.py 16_partial_retraction_two_uses --runs 1`.
      Expected: no PLACEHOLDER from the per-minute token limit; if there
      is one, the rich cases need running a few at a time.
- [ ] (S4) Download by hand any real PDF the run listed as not fetched,
      put it in `evals/real/`, add it to `SOURCES.md` and run `extract.py`.
      Expected: every real case builds.
- [ ] (S4) Read each real case against its real paper and notice.
      Expected: `expected` and `why` match what the notice says, and the
      draft doesn't misstate the paper.
- [ ] (S4) Open two of the `.txt` files beside their PDFs. Expected: the
      text is in reading order (columns not interleaved); if not, quotes
      from that paper will be marked invented and the `.txt` needs fixing.
- [ ] (S4) Run one real case against Gemini: from `backend/`,
      `uv run --with fpdf2 --with rich python evals/all.py 28_real_<name> --runs 1`.
      Expected: no PLACEHOLDER from the per-minute token limit.
- [ ] (S4) Record the `PROMPT_VERSION` 4 baseline (the pass-rates spec's
      human check) only after this spec has merged. Expected: the
      baseline's `meta.json` has every case's final hash, so later
      `compare.py` runs don't list them as "case changed".
