# Evals: cases from real published papers

**Status:** draft
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

Source of the task: every eval case is made up, so the evals never test a
real published PDF's layout and length or a real notice's wording. The real
examples are in `docs/RE-changes-explained.md` §8. Made-up cases stay: they
give controlled traps, and the model may remember famous real papers.

**Commands** (from `backend/`):
- Full test suite: `uv run pytest` (the evals have none: they are scored by
  command, not by pytest, DECISIONS.md 2026-10-27)
- Single test file: none for the evals
- Run the scorer: `uv run --with rich --with fpdf2 python evals/score.py <results folder>`
- Build cases without Gemini: `uv run --with rich --with fpdf2 python evals/run.py --dry [case ...]`
- Extract a real PDF's text: `uv run --with pypdf python evals/real/extract.py`
- Typecheck: none
- Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | S1 |

Run order: S1, S2.

External blockers:
- The open questions below.
- `docs/specs/2026-11-05-eval-realistic-pdfs.md` must be merged to `main`
  first: S2's drafts use its `draft_doc` format and `case_text.py`, and
  both specs change `run.py`, `score.py` and `cases/`.
- S2 downloads PDFs and notice texts from the publishers and Europe PMC,
  so it needs network access. A file that can't be fetched (bot check,
  `403`) is a Human check, not a failure.

Git state: proposed branch `feat/eval-real-papers`, from `main` after
`feat/eval-realistic-pdfs` has merged. Uncommitted changes that must stay
out of every commit:
- `backend/evals/cases/01_correction_effect_size.json` (editor reformat only,
  no content change). No subtask edits this file.
- `frontend/csd-frontend-vite/package-lock.json`
- `docs/specs/2026-11-04-in-app-report-notification.md` (another task's spec)

## Open questions

1. **Committed or downloaded** (S1, S2). ASSUMED: the real PDFs are
   committed under `backend/evals/real/`, each with its licence and source
   in `evals/real/SOURCES.md`, as CC-BY allows redistribution with
   attribution. The alternative is a fetch script with sha256 checksums
   into a gitignored folder, which keeps the repo small but breaks when a
   publisher's link dies or starts a bot check.
2. **Which real papers** (S2). ASSUMED six, from RE-changes-explained.md
   §8:
   - R1 retraction: PLOS ONE `10.1371/journal.pone.0258361`, notice
     `10.1371/journal.pone.0322065`
   - E1 expression of concern: PLOS ONE `10.1371/journal.pone.0035065`,
     notice `10.1371/journal.pone.0298436`
   - C1 admin correction: Sci Rep `10.1038/s41598-024-79772-7`, notice
     `10.1038/s41598-025-93099-x`
   - C2 figures switched: PLOS ONE `10.1371/journal.pone.0309025`, notice
     `10.1371/journal.pone.0320535`
   - E4 concern lifted: RSC Advances `10.1039/c3ra45263h`, notice
     `10.1039/d6ra90114j`
   - C6 new version: F1000Research `10.12688/f1000research.187739.1` and
     `.2`

   One whose licence isn't CC-BY is dropped, not replaced.
3. **Who downloads** (S2). ASSUMED: whoever runs S2 downloads them (curl),
   and any file it can't get is listed for the user to download by hand.

## Suggested splits

No split: S2 writes cases in the format S1 adds.

## Out of scope

- Real papers that aren't CC-BY (Elsevier, the Lancet fixtures): never
  committed. Doc delta on S2.
- Real drafts: every draft is written for the eval, citing the real paper.
  Doc delta on S2.
- Cases for investigation (user, 2026-11-05). ROADMAP.md already lists
  them (`transient`).
- Changing or removing the made-up cases. `transient`.

## Interfaces

- **Real PDF in a case** (S1, written by S2): wherever a case has
  `paper_text` or a document's `pdf_text` (or their `_doc` forms), it may
  instead have `paper_file` or `pdf_file`: a file name in `evals/real/`,
  e.g. `"pone.0258361.pdf"`. `run.py` attaches the file's bytes as they
  are. `case_text` reads its text from the committed `<same stem>.txt`
  next to it (made once by `evals/real/extract.py`), so `score.py` and
  `judge.py` need no PDF library. A case has one form per field. Doc delta
  on S1 (EVALUATION.md).

## Subtasks

### S1: A case can attach a real PDF, read through its extracted text

- **Goal:** A case can point its stored paper or a document at a real PDF
  in `evals/real/`; `run.py` attaches the exact bytes, and the quote check
  and the judge read the PDF's extracted text.
- **Blocked by:** none
- **Files:** `backend/evals/real/extract.py` (new),
  `backend/evals/case_text.py`, `backend/evals/run.py` (`build_inputs`),
  `backend/evals/score.py` (`norm`), `docs/EVALUATION.md`
- **Seams:** `run.py --dry`; `run.build_inputs`; `case_text`; the scorer
  command; `extract.py`.
- **Decisions:**
  - `paper_file` / `pdf_file` as in Interfaces.
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

### S2: Six cases use real published papers and their real notices

- **Goal:** Six cases attach a real open-access paper's PDF and its real
  notice text, with a draft written for the eval that cites the paper, so
  the evals also test real layout, length and notice wording.
- **Blocked by:** S1
- **Files:** `backend/evals/real/` (the PDFs, their `.txt` texts,
  `SOURCES.md`), six new case files `28_real_…` to `33_real_…`,
  `docs/EVALUATION.md`, `docs/DECISIONS.md`, `docs/ROADMAP.md`
- **Seams:** `run.py --dry`; `case_text`; the scorer command.
- **Decisions:**
  - Committed under `evals/real/` with `SOURCES.md` (DOI, title, URL
    fetched from, date, licence, sha256 per file). ASSUMED (Q1).
  - The six papers in Q2. Before a paper is used, its licence is checked
    in Crossref's `license` field (or the PDF's own licence line); a paper
    that isn't CC-BY (any version) is dropped, and `SOURCES.md` says why.
    ASSUMED (Q2).
  - Downloads by whoever runs S2; a file that can't be fetched is left out
    and listed in the run's final report. ASSUMED (Q3).
  - The case files are written by hand (by whoever runs S2), as JSON. No
    script or LLM call generates their text.
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

- [ ] (S2) Download by hand any real PDF the run listed as not fetched,
      put it in `evals/real/`, add it to `SOURCES.md` and run `extract.py`.
      Expected: every real case builds.
- [ ] (S2) Read each real case against its real paper and notice.
      Expected: `expected` and `why` match what the notice says, and the
      draft doesn't misstate the paper.
- [ ] (S2) Open two of the `.txt` files beside their PDFs. Expected: the
      text is in reading order (columns not interleaved); if not, quotes
      from that paper will be marked invented and the `.txt` needs fixing.
- [ ] (S2) Run one real case against Gemini: from `backend/`,
      `uv run --with fpdf2 --with rich python evals/all.py 28_real_<name> --runs 1`.
      Expected: no PLACEHOLDER from the per-minute token limit.
