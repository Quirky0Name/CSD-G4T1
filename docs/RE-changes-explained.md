# Research Evaluation: what each detected change means

An analysis of `backend/src/research_evaluation/` (`changes.py`, `rules.py`,
`evaluate.py`, `llm.py`, `storage.py`) against the docs, written 26 Sep 2026.
No code was changed. The real-world examples come from our test fixtures (the
IJAA and Lancet hydroxychloroquine papers) and from live Crossref, OpenAlex,
PubMed, Europe PMC and Wayback Machine lookups made on 26 Sep 2026, plus the
Retraction Watch dataset and DOAJ's withdrawn-journals log downloaded that
day. Section 8 links every scenario to a real paper and its notice.

## Contents

- [1–4. The changes the code detects](#14-the-changes-the-code-detects)
  - [How a change shows up in real life](#how-a-change-shows-up-in-real-life)
  - [Retraction](#retraction--high--key-retraction)
  - [Expression of concern](#expression-of-concern--medium--key-expression_of_concerndoi)
  - [Correction](#correction--medium--key-correctiondoi)
  - [Erratum](#erratum--low--key-erratumdoi)
  - [DOAJ delisting](#doaj-delisting--low--key-doaj_delistingsnapshot_id)
  - [Other](#other--medium--key-othertypedoi)
  - [What is deliberately not a change](#what-is-deliberately-not-a-change)
- [5. Evaluating the impact on the user](#5-evaluating-the-impact-on-the-user)
- [6. Deciding what to do next](#6-deciding-what-to-do-next)
- [7. Things this turned up](#7-things-this-turned-up)
- [8. Real examples for every scenario](#8-real-examples-for-every-scenario)

---

## 1–4. The changes the code detects

All detection is in
[find_changes](../backend/src/research_evaluation/changes.py#L84-L106).
It reads three signals from each snapshot: Crossref's `updated-by` list
(`crossref_updates`), OpenAlex's `is_retracted`, and OpenAlex's `in_doaj` for
the paper's primary location. A field is compared only when it is non-null in
both snapshots and its source was `ok` in both.

| Change | Code trigger | Severity | Change key |
|---|---|---|---|
| Retraction | `is_retracted` False → True, or a new Crossref entry of type `retraction` | high | `retraction` |
| Expression of concern | a new Crossref entry of type `expression_of_concern` | medium | `expression_of_concern:<notice_doi>` |
| Correction | a new Crossref entry of type `correction` | medium | `correction:<notice_doi>` |
| Erratum | a new Crossref entry of type `erratum` | low | `erratum:<notice_doi>` |
| DOAJ delisting | `in_doaj` True → False, same journal, source type `journal` | low | `doaj_delisting:<snapshot_id>` |
| Other | a new Crossref entry of any other type | medium | `other:<type>:<notice_doi>` |

### How a change shows up in real life

Every change except a DOAJ delisting follows the same pattern, set by COPE's
guidelines and Crossref's Crossmark service. This is what a researcher sees
without any API.

1. **The journal publishes a separate notice.** It is a short item of its
   own, often in a later issue, with **its own DOI**, its own web page and
   its own PDF. The title names the kind of notice and, usually, the
   article. Titles from our fixtures, checked live:
   - "Retraction notice to 'Hydroxychloroquine and azithromycin as a
     treatment of COVID-19…' [International Journal of Antimicrobial
     Agents …]" (Elsevier style);
   - "Retraction—Hydroxychloroquine or chloroquine with or without a
     macrolide…" and "Expression of concern: Hydroxychloroquine or
     chloroquine…" (The Lancet);
   - "Department of Error" (The Lancet's title for every correction);
   - "Retraction and republication: cardiac toxicity of hydroxychloroquine
     in COVID-19" (The Lancet).

   Other publishers use "Retraction Note", "Author Correction" and
   "Publisher Correction" (Springer Nature), "Corrigendum to…" and "Erratum
   to…" (Elsevier), or "Correction: …" (PLOS). The body is a paragraph or
   two: what is wrong, who asked for the notice (the authors, the editor,
   the publisher or an institution), and sometimes what readers should do.
2. **The original article stays online and is marked.** COPE says a
   retracted article shouldn't be deleted. It gets a banner linking to the
   notice. A retraction also puts "RETRACTED:" before the title (live: the
   Lancet and IJAA titles in Crossref now start with it) and "RETRACTED"
   watermarked across every PDF page.
3. **The notice and the article link to each other.** On the web these are
   ordinary links. In machine-readable form, the publisher registers the
   notice with `update-to: <article DOI>`, and Crossref shows the reverse
   link on the article as `updated-by`. That is what Updating stores as
   `crossref_updates`. Since 2023, Crossref has also added Retraction
   Watch's records (`source: retraction-watch`), which cover notices the
   publisher never registered or labelled wrongly.
4. **Other places a person might see it:**
   - the Crossmark "Check for updates" button on the article page and in
     many PDFs, which reads the same Crossref data live;
   - PubMed: "Retraction in:", "Erratum in:" and "Expression of concern
     in:" links on the article, and publication types such as "Retracted
     Publication" (live on the Lancet paper, PMID 32450107);
   - the Retraction Watch database;
   - reference managers (Zotero warns about retracted items in a library).
5. **The copy the researcher already has never changes.** A PDF downloaded
   before the notice stays as it was. Unless they click Crossmark or go back
   to the article page, they won't find out. That is the gap this system
   fills.

**Which DOI is involved depends on what happened:**

| What happened | DOI |
|---|---|
| A notice was published | a **new DOI**: the notice's own |
| The article was corrected in place, or watermarked | the **same DOI**, with new content; no version number |
| A corrected replacement was published (retract and republish) | a **third DOI**, linked from the notice |
| A new version appeared on a versioned platform (preprint servers, eLife, F1000Research, Zenodo) | a version DOI, or the same DOI with a version suffix (`v2`) |

Each section below gives:
- the code trigger;
- what it means in plain terms and in real life;
- every way it shows up, and whether our pipeline sees it;
- when it might not matter, and how to check.

---

### Retraction · high · key `retraction`

**Code trigger.** There are two paths, merged into one change.

- (a) [_retraction_flag](../backend/src/research_evaluation/changes.py#L114-L119):
  OpenAlex `is_retracted` is exactly `False` in the previous snapshot and
  `True` in the current one.
- (b) [_new_notices](../backend/src/research_evaluation/changes.py#L128-L158)
  returns a Crossref entry of type `retraction` whose (notice DOI, type) pair
  is new. That includes a notice DOI already seen under another type and now
  relabelled `retraction`
  ([line 152](../backend/src/research_evaluation/changes.py#L152)).
  When one notice is listed under several types, `retraction` wins
  ([precedence](../backend/src/research_evaluation/changes.py#L33)).
- In `find_changes`, both paths produce one change with key `retraction`. If
  both appear in the same pair, the notice version wins because it has the
  details ([line 96](../backend/src/research_evaluation/changes.py#L96)).
  Only the first retraction notice is kept. If they arrive on different
  polls, the first one stored wins, so the alert may have no notice DOI.

**In plain terms.** A database now says the journal has officially withdrawn
the paper.

**What it means.** The journal no longer stands behind the findings. Reasons
include:
- misconduct: fabricated or falsified data, manipulated images, paper mills,
  fake peer review;
- an honest error big enough to sink the conclusions;
- data nobody can verify (the Lancet paper: the Surgisphere database behind
  it couldn't be audited);
- ethics failures (no approval, no consent);
- plagiarism, duplicate publication, authorship or copyright disputes.

**How it shows up, and whether we see it**

| # | How it shows up | Example | Seen by our pipeline? |
|---|---|---|---|
| R1 | The standard pattern: a separate notice with its own DOI. The article stays up, with "RETRACTED:" before its title, a banner linking to the notice, and "RETRACTED" across every PDF page. | IJAA: notice `10.1016/j.ijantimicag.2024.107416` ("Retraction notice to '…'", Dec 2024 / Jan 2025 issue). Lancet: notice `10.1016/s0140-6736(20)31324-6` ("Retraction—…", 5 June 2020). | **Yes**: a Crossref entry, and OpenAlex's flag |
| R2 | The notice exists, but only Retraction Watch labels it as a retraction. | In both fixtures, the real retraction notice is typed `retraction` only by the Retraction Watch entry. Elsevier's own entry for the same notice says `erratum`. | **Yes**: the Retraction Watch entry, and precedence makes it a retraction |
| R3 | The retracted article is registered as its own "retraction". | IJAA: an Elsevier entry of type `retraction` whose notice DOI is the paper's own DOI, dated 2020-07-01, 4½ years before the real retraction. | **Yes, but misleading**: following "the notice" leads back to the paper, and the date isn't the retraction date |
| R4 | Retract and republish (or "retract and replace"): the article is retracted and a corrected version is published, either under a new DOI or, at JAMA, **at the same DOI**. | New DOI: Lancet `10.1016/s0140-6736(20)31528-2`, "Retraction and republication: cardiac toxicity of hydroxychloroquine in COVID-19", for the Lancet commentary `…(20)31174-0`. Same DOI: JAMA's "Notice of Retraction and Replacement" for Dyrbye et al. (`10.1001/jama.2018.12615`). | **Only the retraction**, and it's misleading. The replacement's DOI appears only in the notice. For Dyrbye, only Retraction Watch registered it, as a `retraction`, so we'd raise a high "stop citing it" alert on a DOI whose current version is the corrected, citable one. Another JAMA case (Vitamin K2, `10.1001/jamainternmed.2024.5726`) has no Crossref entry at all, so we see nothing |
| R5 | A notice about a *different* article is linked onto this one. | Elsevier's entries on the Lancet paper list `…31174-0` as a `retraction`. That is a different article: a commentary that relied on the same data, itself retracted (live title: "RETRACTED: Chloroquine or hydroxychloroquine for COVID-19: why might they be hazardous?"). They also list `…31528-2`, that commentary's retract-and-republish notice, as an `erratum`. PubMed also lists `…31528-2` as "Retraction in" on the Lancet paper. | **Yes, wrongly**: see "might not matter" below |
| R6 | The retraction has no notice DOI (older papers, small journals). Retraction Watch lists 1,304 retractions whose notice has no DOI. | Fujii et al. 1999, Anaesthesia and Intensive Care (`10.1177/0310057X9902700109`), retracted in 2013 in the Fujii fabrication case: no Crossref `updated-by` at all, but OpenAlex says `is_retracted: true`. | **Only** through OpenAlex's flag. The alert then has no notice DOI |
| R7 | The paper was already retracted when the researcher started tracking it. | Anyone who starts tracking the IJAA paper today | **No**: the first snapshot is only a baseline |
| R8 | A retraction is reversed (reinstatement; Retraction Watch has 161). Crossref has no `reinstatement` type. | Bioengineered (`10.1080/21655979.2021.1996016`) was "retracted in error" on 29 Jan 2024 and reinstated on 1 Mar 2024. Crossref lists the reinstatement as an **`addendum`**, and OpenAlex still says `is_retracted: true`. | **No**. `True → False` is ignored, and in this case OpenAlex never flipped back. The reinstatement notice arrives as a medium `other:addendum` alert, if it's seen at all, while the high retraction alert stays |

OpenAlex's flag isn't precise. Live, OpenAlex marks the Lancet *expression
of concern notice* (`…31290-3`) as `is_retracted: true`. The flag alone is
weak evidence.

**When it might not matter, and how to check**

- **The metadata is wrong (R3, R5).**
  - If `notice_doi` equals the paper's DOI, "the notice" is the paper itself.
  - Fetch `api.crossref.org/works/{notice_doi}`. Its title says what it is:
    "Retraction notice to…" or "Retraction—…" is a real notice, while
    "RETRACTED: <some other title>" is another retracted article. Its
    `update-to` should list the tracked paper's DOI as a `retraction`.
  - Corroborate across sources: OpenAlex's flag, the Crossref notice, the
    Retraction Watch record (`record_id`), and PubMed's "Retraction in".
  - A free extra signal: the paper's own title now starting with
    "RETRACTED:". The snapshot has `title`, but nothing compares it.
- **The reason doesn't touch the findings.** Duplicate publication (the
  findings stand in the original copy, so cite that instead), authorship or
  copyright disputes, or a publisher error. The researcher still shouldn't
  cite the retracted copy. Check the Retraction Watch "Reason" field: the
  snapshot keeps `record_id`, and Crossref's public Retraction Watch dataset
  joins on it.
- **Retract and republish (R4).** A corrected version exists. The action is
  to cite the replacement.
- **The researcher cites it because it's controversial** (for example,
  writing about COVID-19 misinformation). Then the retraction is expected.

---

### Expression of concern · medium · key `expression_of_concern:<doi>`

**Code trigger.** A new Crossref entry of type `expression_of_concern`
([changes.py:101](../backend/src/research_evaluation/changes.py#L101)).
It ranks second in precedence, so a notice listed as both an EoC and a
retraction becomes the retraction.

**In plain terms.** The editors published a public warning: they doubt the
paper and are looking into it.

**What it means.** An investigation is ongoing or inconclusive. For example,
an institution is investigating, the authors can't produce the raw data, or
image problems were raised on PubPeer. It is a leading indicator. It usually
ends in a retraction, a correction, or the concern being lifted.

**How it shows up, and whether we see it**

| # | How it shows up | Example | Seen by our pipeline? |
|---|---|---|---|
| E1 | A separate notice with its own DOI, and a banner on the article linking to it. The article's PDF is usually not watermarked. | Lancet `10.1016/s0140-6736(20)31290-3`, "Expression of concern: Hydroxychloroquine or chloroquine with or without a macrolide…" (3 June 2020). PubMed marks it "Expression of Concern", and the article's PubMed record links to it as "Expression of concern in:". | **Yes** |
| E2 | An editor's note on the article page with no DOI. Springer Nature's usual wording: "Editor's Note: Readers are alerted that…". A "Matters Arising" (a published critique) is linked the same way. | Scientific Reports' "Sodom" paper (`10.1038/s41598-021-97778-3`): an Editor's Note appeared on 15 Feb 2023 ("Readers are alerted that concerns raised about the data presented and the conclusions of this article are being considered by the Editors"). It's visible in the Wayback snapshot of 2 Mar 2023 and absent from 7 Feb 2023, and it's in no Crossref record. | **No**: there's no Crossref entry |
| E3 | Concern raised outside the journal: open letters, statements by the society that owns the journal, PubPeer threads, news. | Lancet: an open letter from over 100 researchers (28 May 2020, on Zenodo as `10.5281/zenodo.3871094`) came before the EoC. IJAA: the journal's society (ISAC) said in April 2020 that the paper didn't meet its expected standard, but no EoC was ever registered (the fixture has none) before the retraction in Dec 2024. | **No** |
| E4 | The EoC is updated or lifted by a follow-up notice ("Update to expression of concern", "Correction and removal of expression of concern"). | RSC Advances' "Correction and removal of expression of concern" (`10.1039/d6ra90114j`) is registered as a **`correction`**; its text says "This notice supersedes the information provided in the expression of concern". PNAS's "Update—Editorial Expression of Concern" (`10.1073/pnas.2420879121`) is registered as an **`expression_of_concern`**. | **Yes, wrongly**. Each follow-up raises a *new* medium alert, as a correction or as another EoC, and nothing tells the researcher the concern was resolved |
| E5 | The EoC is followed by a retraction or a correction. Both notices stay linked. | Lancet: EoC 3 June 2020, retraction 5 June 2020 | **Yes**, as two alerts |

**Your question: does the EoC have its own DOI, and can an API read its
text?** It usually does (E1; not E2). The DOI resolves to a web page, and
every API gives you the *metadata*: title, date, and a link back to the
paper. The *text* depends on the journal:
- **Open-access journals that deposit in PubMed Central** (PLOS, Scientific
  Reports, RSC Advances, PNAS): Europe PMC's full-text API returns it. The
  PLOS ONE EoC `10.1371/journal.pone.0298436` names Fig. 4 and Fig. 7 and
  quotes the exact result sentence it no longer supports.
- **Paywalled journals** (the six Elsevier fixture notices): none of the
  free APIs returned the text. A person can still read all of them for free
  in a browser.

See [5.3](#53-can-you-get-the-notice-and-the-updated-paper-live-check).

**When it might not matter, and how to check**

- The concern is about something the researcher doesn't use: ethics approval
  paperwork, a missing conflict-of-interest disclosure, or one figure.
- It's a publisher-wide sweep about the review process (for example, a
  special issue with compromised peer review) rather than this paper's data.
- It has already been resolved. Look for a later notice on the same paper
  (E4).
- **Check** by reading the notice for *what* is in question and what the
  editors advise, and by looking for Retraction Watch or PubPeer coverage.

---

### Correction · medium · key `correction:<doi>`

**Code trigger.** A new Crossref entry of type `correction`. If the same
notice is also listed as an `erratum` (as Elsevier usually does), it merges
into this correction by
[precedence](../backend/src/research_evaluation/changes.py#L33).

**In plain terms.** The paper had a mistake, and an official fix was
published.

**What it means.**
- Usually the authors' error, and usually minor: author names, affiliations,
  funding or conflict-of-interest statements, references, typos.
- Sometimes substantive: changed numbers in a table, a replaced figure, a
  re-analysis, a result that is no longer significant.
- The Lancet paper's correction (30 May 2020) fixed data attributed to
  Australian hospitals. The paper was retracted six days later anyway.

**How it shows up, and whether we see it**

| # | How it shows up | Example | Seen by our pipeline? |
|---|---|---|---|
| C1 | A notice, and the article is corrected in place: same DOI, new content, often with a note on the page ("This article was corrected on…", Springer Nature's "Change history", "The original Article has been corrected"). | Lancet `10.1016/s0140-6736(20)31249-6`. The Wayback snapshot of 29 May 2020 says "7555 (7·9%) from Asia … 609 (0·6%) from Australia"; on 31 May it says "8101 (8·4%) from Asia … 63 (0·1%) from Australia". Same DOI, same "Published: May 22, 2020"; the only visible sign is a new "Department of Error" link. | **The notice, yes. The changed content, no**: nothing in the snapshot fingerprints content. The pre-correction version usually disappears from the publisher's site, so the PDF Storage Management kept may be the only copy |
| C2 | A notice only, which carries the fix itself (the corrected figures or values). | PLOS ONE `10.1371/journal.pone.0320535`: "The images for Figs 1, 3–8 are incorrectly switched…", with the correct figures reprinted in the notice. | **Yes**. The "corrected paper" is the old paper plus the notice |
| C3 | A notice with a generic title. | The Lancet titles every correction "Department of Error". Live: `…31249-6`'s title is exactly that. | **Yes**. You need `update-to` to know which article, and the body to know what changed |
| C4 | Labels that depend on who made the error, or one notice covering several articles. | Springer Nature "Author Correction" (`10.1038/s41598-025-93099-x`, a wrong affiliation). Springer Nature "Publisher Correction: npj Nanophotonics, volume 1, missing Data Availability statements" (`10.1038/s44310-025-00058-5`), one notice for several articles. Elsevier "Corrigendum" in *Ophthalmology* (`10.1016/j.ophtha.2026.06.027`), an author correction registered as an **`erratum`**. | **Yes**, but the Crossref type depends on who registered it. Elsevier's corrigenda come in as low-severity errata |
| C5 | A silent ("stealth") correction: the content changes under the same DOI with no notice. | Aquarius et al. 2025 (*Learned Publishing*, `10.1002/leap.1660`) found 131 such articles across 10 publishers, 92 with changed figures, data or text. | **No** |
| C6 | A new version on a versioned platform. | F1000Research `10.12688/f1000research.187739.1` → `.2`; Cochrane review `10.1002/14651858.cd000525.pub3` → `.pub4`. Both versions stay online. | **Only** if Crossref records a `new_version` (which becomes `other`). arXiv and Zenodo DOIs are DataCite DOIs, so Updating records them as `not_found` |
| C7 | Retract and republish, the heaviest kind of correction | see R4 | see R4 |

**When it might not matter, and how to check.** Most corrections don't
matter. **Check** by reading the notice. It usually says exactly what
changed ("In table 2, the value for X should read Y"). Sort it into
administrative (names, affiliations, funding, COI, typos, references) or
substantive (data, results, conclusions). Then check whether the corrected
part is anything the researcher cites.

---

### Erratum · low · key `erratum:<doi>`

**Code trigger.** A new Crossref entry of type `erratum`. It ranks last in
precedence, so any other type listed for the same notice wins.

**In plain terms.** A fix for a mistake, traditionally one the publisher
made during production.

**What it means.** Typesetting errors, a wrongly rendered table or figure,
rows dropped during production. It shows up in all the same ways as a
correction (C1–C6). Only the label differs: Elsevier's "Erratum to…",
Springer Nature's "Publisher Correction".

**The label is unreliable.** Live, Elsevier's own entries type all of these
as `erratum`:

| Notice | What it actually is |
|---|---|
| `10.1016/j.ijantimicag.2024.107416` | the IJAA retraction notice |
| `10.1016/s0140-6736(20)31324-6` | the Lancet retraction notice |
| `10.1016/s0140-6736(20)31249-6` | the Lancet correction ("Department of Error") |
| `10.1016/s0140-6736(20)31528-2` | a retract-and-republish notice for a *different* article |

Precedence rescues the first three, because Retraction Watch types them
correctly. The fourth has only Elsevier's entry, so **our code raises a
low-severity erratum alert for it on the Lancet paper**, with the text "An
erratum for this paper was published, fixing an error in the published
version". The fixture test
[test_changes.py:313](../backend/tests/research_evaluation/test_changes.py#L313)
expects exactly that. If Retraction Watch hasn't indexed a notice, a serious
notice can arrive as a low-severity erratum.

**When it might not matter, and how to check.** Often it doesn't matter, but
check what the notice really is before trusting the label.
- **The notice's own title** from Crossref is cheap and deterministic. Every
  live title above reveals the real kind of notice.
- **PubMed** is an independent second opinion for biomedical papers. Its
  `CommentsCorrections` entries are typed `ErratumIn`, `RetractionIn` or
  `ExpressionOfConcernIn`. It isn't perfect either: it lists `…31528-2` as
  `RetractionIn` on the Lancet paper.

---

### DOAJ delisting · low · key `doaj_delisting:<snapshot_id>`

**Code trigger.** [_doaj_delisting](../backend/src/research_evaluation/changes.py#L161-L180)
needs all of these:
- OpenAlex was `ok` in both snapshots;
- `in_doaj` goes from `True` to `False`;
- the same non-null `journal_source_id` in both;
- the current `journal_source_type` is `journal`.

The key includes the snapshot id, so a journal that is delisted, relisted
and delisted again gives a new alert each time.

**In plain terms.** The journal was dropped from the Directory of Open Access
Journals, the main list of vetted open-access journals.

**What it means.** This is about the *journal now*, not about this paper or
the journal when the paper was published. DOAJ removes journals for not
following best practice (for example, questionable peer review or predatory
behaviour). It also removes them for administrative reasons.

**How it shows up, and whether we see it**

| # | How it shows up | Seen by our pipeline? |
|---|---|---|
| D1 | Removed for not following best practice. The journal disappears from doaj.org, and DOAJ's public list of removed journals gives the date and reason. Nothing changes on the article page. The journal's website may keep showing the DOAJ logo. | **Yes**, with OpenAlex's lag. Tecnura was removed on 25 Sep 2026, and the DOAJ API no longer lists it, but OpenAlex still said `is_in_doaj: true` on 26 Sep |
| D2 | Removed for administrative reasons: the journal ceased publishing, stopped being fully open access, its website died, the publisher asked, or it didn't keep its record up to date. It looks the same as D1, but the reason is different. | **Yes**, but it says nothing about quality |
| D3 | The journal changed its name, ISSN or publisher. The old DOAJ record closes and a new one opens. | **Sometimes**: yes if OpenAlex keeps the same source id; no if it creates a new one (the same-journal guard blocks it). Interpersona and the Journal of Business Models both changed publisher in 2026, kept their OpenAlex source ids and flipped to `false`, so both would raise a delisting alert |
| D4 | OpenAlex refreshes its own DOAJ matching. There's no DOAJ event at all. | **Yes, wrongly**: a false alert. Repeated delistings of the same journal suggest this |
| D5 | The paper's primary location moves to a repository (PubMed), where `in_doaj` is always false. | Correctly **ignored** by the guard |

DOAJ's withdrawn-journals log has about 2,250 withdrawals since Feb 2024.
About 1,240 (55%) are for not adhering to best practice. The rest are
administrative: ceased publishing (440), website no longer works (431), no
longer open access (37), inactive (37), changed or transferred publisher
(29).

Related signals we don't track: Scopus's list of discontinued titles, and
Web of Science delistings (in 2023 it dropped dozens of journals, including
MDPI's IJERPH and many Hindawi titles).

**When it might not matter, and how to check**

- Most of D2–D4.
- A paper published years before the journal's problems.
- **Check** the DOAJ API by ISSN (`doaj.org/api/search/journals/issn:<issn>`)
  to see whether the journal is really gone.
- Check DOAJ's list of removed journals for the reason and date, and compare
  that date with the paper's `publication_year`.
- The snapshot has `issn_l`, but RE's `Snapshot` model drops it.

---

### Other · medium · key `other:<type>:<doi>`

**Code trigger.** A new Crossref entry of any type that isn't one of the
four classified types
([changes.py:92](../backend/src/research_evaluation/changes.py#L92),
[98-99](../backend/src/research_evaluation/changes.py#L98-L99)). It is
the only type sent to
[llm.investigate](../backend/src/research_evaluation/llm.py#L19-L20),
which is still a placeholder that returns the stage-2 text unchanged.

**How RE gets to see one.** Updating never nudges for these types
([compare.py:12](../backend/src/updating/compare.py#L12)). RE only sees
one when *some* nudge for that paper arrives while that pair of snapshots is
still in the window: in the same poll, or up to N−2 polls later. The docs
say "same poll", which slightly understates it.

**In plain terms.** Crossref recorded some other kind of official notice
that we have no rule for.

**What it means, and how it shows up, by Crossref type** (the list isn't
closed):

| Crossref type | What it means | How it shows up |
|---|---|---|
| `withdrawal` (3,396 in Crossref) | Elsevier: an article in press pulled before final publication, often a duplicate submission or an error. Close to a retraction. SAGE: "Administrative Duplicate Publication", meaning the paper also exists elsewhere. On preprint servers, the authors withdrew it. | Elsevier replaces the HTML and PDF with a statement ("This article has been withdrawn at the request of the author(s) and/or editor…"), so the content is gone. Its "WITHDRAWN:" notices usually point at the article itself (self-referencing). **Trap:** a *withdrawn correction notice* is registered as a `withdrawal` of the original article (`10.1016/j.bbi.2026.106787`), so the original gets an alert although only the correction was withdrawn. On arXiv, the withdrawal is a new version whose text is the withdrawal note, and the older versions stay available. |
| `removal` (704) | Taken down for legal reasons (defamation, a court order, privacy) or a serious health risk. **In practice, mostly Elsevier's "TEMPORARY REMOVAL"** of articles in press. | The title and authors stay; the text is replaced by a page saying the article was removed. Elsevier's notices are self-referencing ("TEMPORARY REMOVAL: <title>" at the article's own DOI). Your stored PDF may be the only copy. |
| `partial_retraction` (**2** in all of Crossref) | Part of the paper (a figure, an experiment) is retracted; the rest stands. | A notice "Partial retraction: <title>" naming the withdrawn parts. **Real partial retractions are registered as `retraction`**: the European Journal of Communication's "Partial retraction notice" (`10.1177/0267323116650776`) is a `retraction` from the publisher and a `correction` from Retraction Watch. Our precedence turns it into a **high, full retraction** alert, and OpenAlex also says `is_retracted: true`. |
| `corrigendum` (8,885) | In practice, a correction by the authors. Our code files it as `other`, not `correction`. | As a correction (C1–C4). Elsevier's corrigenda mostly come in as `erratum` instead (C4). |
| `addendum` (1,738) | Information added. Usually neutral, **but also used for reinstatements** (R8, Bioengineered) and, in SoftwareX, for software version updates. | A short notice with its own DOI. |
| `clarification` (507) | Wording clarified, **but RSC uses it for published Comments**: formal critiques of the paper. | "Comment on '<title>'" (`10.1039/d5sc05038c`) points out a "misinterpretation…, conceptual flaws" in the paper it updates. That's a published challenge, closer to an EoC than to a neutral note. |
| `new_version` (44,543), `new_edition` (10,887) | A newer version exists. | The notice DOI is typically the new version itself (F1000Research `.1` → `.2`, Cochrane `.pub3` → `.pub4`). Small publishers use `new_edition` self-referencing. |
| `reinstatement` (0) | A retraction reversed. | **Not a Crossref type.** It arrives as something else (R8: an `addendum`). |

The counts are Crossref's totals for each type on 26 Sep 2026.

**When it might not matter, and how to check.** Usually `addendum` and
`clarification`, but check the title first: an addendum can be a
reinstatement, and a clarification can be a published critique.
`new_version` depends on what changed. Most of the
interpretation is a lookup on the type name and needs no LLM. The LLM is
only needed to read the notice's *content*.

---

### What is deliberately not a change

- A null field, or a field whose source wasn't `ok` in both snapshots.
- A Crossref entry with no type.
- The same (notice DOI, type) pair from a second source (the publisher and
  Retraction Watch).
- A notice already seen under one type that is now listed under another
  type, unless the new type is `retraction`.
- An `in_doaj` flip where the journal changed or the location is a
  repository.
- `is_retracted` going `null → true` or `true → false`.
- A second retraction: there is one per paper.
- A change whose key is already stored in Storage Management.

---

## 5. Evaluating the impact on the user

The inputs are the old paper, the user's draft, and the **notice**. The
notice is the missing piece: it's the only reliable record of *what
changed*.

### 5.1 Method

1. **Find where the draft uses the tracked paper.** Run GROBID (already in
   the stack) on the draft and match the reference by DOI or title. Pull out
   every citation context: the sentence around each citation marker, plus
   its section. If the paper isn't cited at all, the impact is minimal.
2. **Rank how much the draft relies on it, deterministically first.**
   - Methods (their data, protocol, reagent or code) matters most.
   - Then results and discussion, where it's used as evidence.
   - Then introduction or background mentions.
   - A critical citation is a special case.
3. **Scope the change.** Take the notice text, plus the Retraction Watch
   reason for retractions, and map it onto the old paper: the whole paper,
   a specific table, figure or claim, admin details only, or the journal
   only.
4. **Have the LLM judge each citation context, in a fixed output shape.**
   For each one:
   - which claim from the tracked paper it relies on;
   - whether that claim falls in the affected scope (yes / no / unclear);
   - a quote from the notice and a quote from the old paper;
   - the action needed.

   Check the quotes verbatim against the sources. The stance contract
   already has `quote_verified`.
5. **Combine** the per-context results into one "impact on your draft"
   result, and keep a person validating it, as the docs already plan for
   stage 3.
6. **Also check transitively.** Other references in the draft may build on
   a retracted paper. ARCHITECTURE already lists the "retraction cascade"
   metric.

Sending an unpublished draft to DeepSeek is a privacy decision the team
should make explicitly.

### 5.2 What the system has today

| Input | Available? |
|---|---|
| The old paper | The PDF from ingest, through `GET /internal/papers/{id}/pdf`. It may be missing for DOI-only papers (pending decision). |
| The notice | Only its DOI, type, label and date. The alert stores the DOI. Nothing fetches the notice. |
| The user's draft | **No**: nothing stores or serves it. |
| The paper's DOI, title, ISSN, and the entry's source and Retraction Watch `record_id` | Storage Management returns them, but RE's [Snapshot and CrossrefUpdate](../backend/src/research_evaluation/changes.py#L41-L65) models drop them (`extra="ignore"`). `llm.Context` doesn't even have the paper's DOI. |

### 5.3 Can you get the notice and the updated paper? (live check)

I fetched the six fixture notices on 26 Sep 2026: `…31290-3`, `…31249-6`,
`…31324-6`, `…31174-0`, `…31528-2` and `10.1016/j.ijantimicag.2024.107416`.

| Route | What it gave |
|---|---|
| Crossref `api.crossref.org/works/{notice_doi}` | Title, date and journal for all six, plus `update-to` pointing back at the paper (from both the publisher and Retraction Watch). **No abstract for any of them.** The `link` URLs are Elsevier's text-mining API, which needs an API key and a text-mining licence. |
| OpenAlex `api.openalex.org/works/doi:{notice_doi}` | The work type (`retraction` for two; `article` for the EoC and the correction) and open-access status: **all free to read** (bronze or green), with a PDF URL. **No abstract.** It marks the EoC notice as `is_retracted: true`. |
| PubMed E-utilities (`efetch` on the paper's PMID) | `CommentsCorrections` typed `ErratumIn`, `ExpressionOfConcernIn` and `RetractionIn`, with the notices' PMIDs and DOIs, and the "Retracted Publication" type. **The notice's own record has no abstract.** |
| Europe PMC | The IJAA notice is indexed ("Retraction of Publication", "Retraction Notice") but isn't open access, so the full-text API errors. **For open-access notices it returns the full text**: tried on PLOS ONE (a retraction, an EoC, a correction), Scientific Reports (an Author Correction), RSC Advances and PNAS. |
| Wayback Machine (`web.archive.org/cdx/search/cdx?url=…`) | Dated snapshots of article pages. The Lancet paper has daily snapshots from 22 May to 12 June 2020, which show the in-place correction, the EoC link and the retraction banner appearing (C1, E5). Useful for old-vs-new when the publisher overwrote the page. |
| The publisher's PDF (thelancet.com) | `403` to a plain request. |
| The PMC PDF | A "Preparing to download…" bot-check page, not the PDF. |
| Crossref Labs API (Retraction Watch data) | Refuses requests without a `mailto`; Updating already sends one to Crossref. The whole Retraction Watch dataset is also a public CSV with "Reason", "Notes" and "URLS" columns. |

**Verdict.** A person can read every one of these notices for free in a
browser. A program gets the **metadata** for free. It gets the **text** for
free from Europe PMC when the notice is open access and in PubMed Central
(PLOS, Scientific Reports, RSC, PNAS). For paywalled journals such as
Elsevier's, the text is only available through:
- a publisher text-mining API with a key (Elsevier's, through the
  university);
- a headless browser, which publishers' terms and the docs' allowlist rules
  argue against;
- or the researcher pasting or uploading the notice. They can read it for
  free, so this is a realistic fallback.

Retraction *reasons* are machine-readable through the Retraction Watch data.

**Is there an "updated paper" to get?**

| Change | Is there a new version? | What you can realistically get |
|---|---|---|
| Retraction (R1–R3) | No. It's the same article, watermarked. | The notice and the Retraction Watch reason. |
| Retract and republish (R4) | Yes, under a new DOI, or (JAMA) at the same DOI. | The replacement's DOI from the notice, then fetch it like any paper. For JAMA, re-download the same DOI; the notice points to the versions to compare. |
| Expression of concern | No. | The notice only. |
| Correction or erratum, corrected in place (C1) | Yes, under the same DOI, with no version marker. | Re-download the paper's own DOI (OpenAlex `best_oa_location`, Unpaywall) and hash or diff it against the stored PDF. That comparison is the only way to know you have the new version. It's often bot-blocked. |
| Correction or erratum, notice only (C2) | No. | The old paper plus the notice. |
| Versioned platforms (C6), `new_version` | Yes. | The notice DOI or the `v2` URL. |
| Withdrawal or removal | The content is gone. | The notice. The stored PDF may be the only copy. |
| DOAJ delisting | Nothing about the paper changed. | DOAJ's record and the removal reason. |

**So build step 3 around the old paper, the notice and the draft.** Treat a
re-downloaded article as a bonus, and plan for the notice text to come from
a text-mining key or from the researcher.

---

## 6. Deciding what to do next

For each change, answer three questions:

1. **Is it real?** Run the checks from sections 1–4. If not, propose
   dismissing it and say why.
2. **What does it hit?** The whole paper, one part, the journal only, or
   admin details only.
3. **How does the draft rely on it?** Not cited, a background mention, key
   evidence, a methods or data dependency, or a critical citation.

Then pick the action:

| Situation | Action |
|---|---|
| The findings are invalid (a retraction for data or misconduct, or a partial retraction of the part used) **and** the draft uses them as evidence | Remove or replace the citation, and re-check the claim it supported |
| The researcher's own methods, data or code depend on it | Stop and escalate to co-authors or the supervisor. Editing the text won't fix it |
| A correction or erratum touches numbers or figures the draft quotes | Update the numbers and cite the correction |
| An EoC on something the draft relies on | Hedge the wording and don't build new work on it. Tracking continues, so a later retraction raises a new alert |
| The draft discusses the paper critically or historically, or the retraction was for duplication or authorship reasons | Keep the citation and cite the notice too (for duplication, cite the original copy) |
| An admin correction, an administrative DOAJ delisting, a change outside what the draft uses, or a paper the draft doesn't cite | No action, or dismiss |

**Store the impact as its own field.** Keep the LLM's "impact on your draft"
separate from the stage-2 severity instead of overwriting it. That keeps
"whether a paper was retracted never depends on an LLM" true, and it matches
the docs' plan for new columns holding the proposal and its validation. The
researcher still makes the final call, through acknowledge/dismiss and the
alert notes log.

**Build order for this**, if you're choosing what to do next:

1. Decide where the draft comes from. Step 3 is blocked without it.
2. Keep `doi`, `title`, `issn_l`, `source` and `record_id` in RE's models.
3. Build a notice-fetch tool: Crossref metadata, a check that `update-to`
   points at the paper, the notice's title, and the Retraction Watch reason.
   Store what it fetches. Add a way for the researcher to paste in the
   notice text.
4. Build the after-`202` path, including `PATCH /internal/alerts/{id}`.
5. Give step 3 its own stage name in the docs.

---

## 7. Things this turned up

1. **The Lancet fixture test expects a misleading alert.**
   `…31528-2` is "Retraction and republication: cardiac toxicity of
   hydroxychloroquine in COVID-19", about a different article. Our code
   stores it on the Lancet paper as a low "erratum", with a description
   that says the paper's error was fixed. See
   [test_changes.py:313](../backend/tests/research_evaluation/test_changes.py#L313).
2. **Elsevier's publisher-sourced types are unreliable, and some point at
   other articles** (R3, R5, the Erratum table). The notice's own Crossref
   title is a cheap, deterministic check.
3. **OpenAlex's `is_retracted` flags notices too.** Live, the Lancet EoC
   notice is marked retracted.
4. **RE drops the fields any checking needs:** `doi`, `title`, `issn_l`,
   `source` and `record_id`.
5. **The "RETRACTED:" title prefix is an unused signal.**
6. **A paper already retracted when tracking starts never alerts** (R7).
7. **Updating or lifting an EoC raises a new alert** (E4, confirmed): RSC's
   "Correction and removal of expression of concern" comes in as a
   `correction`, and PNAS's "Update—Editorial Expression of Concern" as an
   `expression_of_concern`.
8. **`other` changes are reachable up to N−2 polls later**, not just in the
   same poll as a known change. The docs understate this.
9. **`corrigendum` is classified as `other`**, not as a correction.
10. **Crossref Labs refuses requests without a `mailto`**, which matters if
    RE uses it for Retraction Watch data.
11. **A JAMA retract-and-replace looks like a plain retraction** (R4): a high
    "stop citing it" alert for a DOI whose current version is the corrected,
    citable one.
12. **A partial retraction becomes a high, full retraction**, because
    `partial_retraction` is almost never used and publishers register them
    as `retraction`.
13. **A reinstatement never clears the retraction** (R8). It arrives as an
    `addendum`, and OpenAlex may keep saying `is_retracted: true`.
14. **`clarification` can be a published critique** (RSC Comments), and
    **`withdrawal` can target the original article when only its correction
    was withdrawn** (Elsevier).
15. **Elsevier's corrigenda (author corrections) come in as low-severity
    errata** (C4).
16. **DOAJ delistings lag and are mostly not about quality**: OpenAlex was
    still behind a day after Tecnura's removal, and about 45% of DOAJ
    withdrawals are administrative.
17. **Open-access notices are machine-readable through Europe PMC**, which
    makes a notice-text fetch practical for a large share of journals
    without any key.

---

## 8. Real examples for every scenario

Every example below was checked on 26 Sep 2026 against Crossref, OpenAlex,
Europe PMC, PubMed, the Wayback Machine, the Retraction Watch dataset or
DOAJ's withdrawn-journals log. "Before" is the paper as first published;
"after" is the notice or the new version.

**How to look at them yourself:**
- `https://doi.org/<doi>` opens the publisher's page, which is free to read
  for every notice here.
- `https://api.crossref.org/works/<doi>` shows the record our pipeline reads:
  `updated-by` on a paper, `update-to` on a notice.
- `https://www.ebi.ac.uk/europepmc/webservices/rest/<PMCID>/fullTextXML`
  gives an open-access notice's full text.
- `https://web.archive.org/web/<timestamp>/<page URL>` opens a dated
  snapshot of the page, for pages the publisher overwrote.

### Retraction

| # | Before | After | What to compare | What our code does |
|---|---|---|---|---|
| R1 (open access) | PLOS ONE 2021, "Detecting phishing websites using machine learning technique": [10.1371/journal.pone.0258361](https://doi.org/10.1371/journal.pone.0258361), [PDF](https://journals.plos.org/plosone/article/file?id=10.1371/journal.pone.0258361&type=printable) | "Retraction: …" [10.1371/journal.pone.0322065](https://doi.org/10.1371/journal.pone.0322065) (23 Apr 2025); [notice text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12017829/fullTextXML) | The notice: "one of a series of submissions for which we have concerns about peer review integrity and potential manipulation of the publication process". The article page now carries a retraction banner. | High retraction, with the notice DOI |
| R1 (paywalled) | IJAA 2020 (fixture): [10.1016/j.ijantimicag.2020.105949](https://doi.org/10.1016/j.ijantimicag.2020.105949) | "Retraction notice to '…'" [10.1016/j.ijantimicag.2024.107416](https://doi.org/10.1016/j.ijantimicag.2024.107416), readable in [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC11718083/) | Title now "RETRACTED: …". Retraction Watch record 59596 gives the reasons: concerns about data, results and authorship; no or withdrawn patient consent; investigations by the publisher and a third party. | High retraction, notice `…107416` |
| R1 (Lancet) | Lancet 2020 (fixture): [10.1016/s0140-6736(20)31180-6](https://doi.org/10.1016/s0140-6736(20)31180-6) | "Retraction—…" [10.1016/s0140-6736(20)31324-6](https://doi.org/10.1016/s0140-6736(20)31324-6) (5 Jun 2020) | Wayback [29 May 2020](https://web.archive.org/web/20200529140835/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext) (clean) vs [6 Jun 2020](https://web.archive.org/web/20200606220137/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext) (title "RETRACTED: …", retraction linked). Retraction Watch reasons: investigation by a third party; unreliable data. | High retraction |
| R2 | Both fixtures | The same notice DOI typed twice in the paper's `updated-by` | IJAA: `…107416` is `retraction` from Retraction Watch and `erratum` from Elsevier. Lancet: `…31324-6` likewise. | Precedence makes it a retraction |
| R3 | IJAA | An Elsevier `retraction` entry whose notice DOI is [the paper itself](https://doi.org/10.1016/j.ijantimicag.2020.105949), dated 2020-07-01 | Retraction Watch's note on the record: "html page overwrite … separate retraction note published later". The article page was turned into the retraction before the separate notice existed. | On its own, a high retraction whose "notice" is the paper |
| R4 (new DOI) | Lancet commentary "Chloroquine or hydroxychloroquine for COVID-19: why might they be hazardous?": [10.1016/s0140-6736(20)31174-0](https://doi.org/10.1016/s0140-6736(20)31174-0) | "Retraction and republication: cardiac toxicity of hydroxychloroquine in COVID-19" [10.1016/s0140-6736(20)31528-2](https://doi.org/10.1016/s0140-6736(20)31528-2) (Jul 2020) | The retracted commentary vs the republished text | On the commentary: a retraction; the replacement isn't captured |
| R4 (same DOI) | JAMA 2018, Dyrbye et al., burnout and career choice regret: [10.1001/jama.2018.12615](https://doi.org/10.1001/jama.2018.12615) | "Notice of Retraction and Replacement" [10.1001/jama.2019.0167](https://doi.org/10.1001/jama.2019.0167) ([PubMed](https://pubmed.ncbi.nlm.nih.gov/30912842/)) | The DOI now serves the corrected replacement. JAMA's notices point to the original and corrected versions to compare. | Only Retraction Watch registered it, as `retraction`: a **high "stop citing it" alert** for a paper whose current version is fine |
| R4 (invisible) | JAMA Internal Medicine 2024, "Vitamin K2 in Managing Nocturnal Leg Cramps": [10.1001/jamainternmed.2024.5726](https://doi.org/10.1001/jamainternmed.2024.5726) | "Notice of Retraction and Replacement" [10.1001/jamainternmed.2025.2160](https://doi.org/10.1001/jamainternmed.2025.2160) | The paper's Crossref record has no `updated-by`; OpenAlex says `is_retracted: false`. | **Nothing** |
| R5 | Lancet (fixture) | Elsevier lists [`…31174-0`](https://doi.org/10.1016/s0140-6736(20)31174-0) as a `retraction` and [`…31528-2`](https://doi.org/10.1016/s0140-6736(20)31528-2) as an `erratum` on it | Both notices are about the commentary above, not this paper | A low erratum alert for `…31528-2` (the fixture test expects it) |
| R6 | Fujii et al. 1999, "Protection from Diaphragmatic Fatigue by Nitric Oxide Synthase Inhibitor in Dogs": [10.1177/0310057X9902700109](https://doi.org/10.1177/0310057X9902700109), [PDF](https://journals.sagepub.com/doi/pdf/10.1177/0310057X9902700109) | No notice DOI. Retraction Watch record 189: retracted 2013 for fabrication ([the Fujii case](https://retractionwatch.com/?s=Yoshitaka+Fujii)) | No Crossref `updated-by`; OpenAlex `is_retracted: true` | A retraction through OpenAlex's flag only, with no notice DOI |
| R7 | Any of the above, tracked from today | — | — | Nothing: the first snapshot is the baseline |
| R8 | Bioengineered 2021, "MicroRNA-4521 targets hepatoma up-regulated protein (HURP)…": [10.1080/21655979.2021.1996016](https://doi.org/10.1080/21655979.2021.1996016) | Retraction [10.1080/21655979.2024.2302649](https://doi.org/10.1080/21655979.2024.2302649) (29 Jan 2024), then reinstatement [10.1080/21655979.2024.2326368](https://doi.org/10.1080/21655979.2024.2326368) (1 Mar 2024) | Retraction Watch: "Retracted in error and reinstated." Crossref types the reinstatement as `addendum`; OpenAlex still says `is_retracted: true`. | The high retraction stays; the reinstatement is at most a medium `other:addendum` |
| Partial | European Journal of Communication 2015, "Legacy of Elisabeth Noelle-Neumann…": [10.1177/0267323115589265](https://doi.org/10.1177/0267323115589265), [PDF](https://journals.sagepub.com/doi/pdf/10.1177/0267323115589265) | EoC [10.1177/0267323115614741](https://doi.org/10.1177/0267323115614741) (Oct 2015), then "Partial retraction notice" [10.1177/0267323116650776](https://doi.org/10.1177/0267323116650776) (Jun 2016), [PDF](https://journals.sagepub.com/doi/pdf/10.1177/0267323116650776) | The notice says which part is retracted; the paper's title now starts "Partial retraction:". Typed `retraction` by the publisher and `correction` by Retraction Watch. | A medium EoC, then a **high full retraction** |

### Expression of concern

| # | Before | After | What to compare | What our code does |
|---|---|---|---|---|
| E1 (open access) | PLOS ONE 2012, "Over-Expression of LSD1 Promotes Proliferation, Migration and Invasion in Non-Small Cell Lung Cancer": [10.1371/journal.pone.0035065](https://doi.org/10.1371/journal.pone.0035065) | "Expression of Concern: …" [10.1371/journal.pone.0298436](https://doi.org/10.1371/journal.pone.0298436) (Feb 2024); [notice text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC10836678/fullTextXML) | The notice names Fig. 4 (a discontinuity in the LSD1 panel) and Fig. 7, and quotes the result sentence it no longer supports. That is exactly the scope the impact step needs. | Medium EoC |
| E1 (Lancet) | Lancet (fixture) | "Expression of concern: …" [10.1016/s0140-6736(20)31290-3](https://doi.org/10.1016/s0140-6736(20)31290-3) (3 Jun 2020) | Wayback [4 Jun 2020](https://web.archive.org/web/20200604194739/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext): the EoC is linked, with a retraction banner being added | Medium EoC |
| E2 | Scientific Reports 2021, "A Tunguska sized airburst destroyed Tall el-Hammam…": [10.1038/s41598-021-97778-3](https://doi.org/10.1038/s41598-021-97778-3), [PDF](https://www.nature.com/articles/s41598-021-97778-3.pdf) | An Editor's Note on the page (15 Feb 2023); no DOI | Wayback [7 Feb 2023](https://web.archive.org/web/20230207091952/https://www.nature.com/articles/s41598-021-97778-3) (no note) vs [2 Mar 2023](https://web.archive.org/web/20230302033943/https://www.nature.com/articles/s41598-021-97778-3) ("Readers are alerted that concerns raised about the data … are being considered by the Editors"). The page also links a "Matters Arising" critique (25 Mar 2022), another page-only item. | Nothing for the note. It does see the Author Corrections [10.1038/s41598-022-06266-9](https://doi.org/10.1038/s41598-022-06266-9) (Feb 2022) and [10.1038/s41598-023-35266-6](https://doi.org/10.1038/s41598-023-35266-6) (May 2023), and the retraction [10.1038/s41598-025-99265-5](https://doi.org/10.1038/s41598-025-99265-5) (Apr 2025) |
| E3 | Lancet (fixture) | "An open letter to Mehra et al and The Lancet", [10.5281/zenodo.3871094](https://doi.org/10.5281/zenodo.3871094) (28 May 2020) | A published concern, six days before the EoC | Nothing |
| E3 | IJAA (fixture) | ISAC's statement ([Retraction Watch, 6 Apr 2020](https://retractionwatch.com/2020/04/06/hydroxychlorine-covid-19-study-did-not-meet-publishing-societys-expected-standard/)) | Concern in April 2020; the retraction came in Dec 2024 | Nothing until the retraction |
| E4 (lifted) | RSC Advances 2013, "Microgel-stabilised non-aqueous emulsions": [10.1039/c3ra45263h](https://doi.org/10.1039/c3ra45263h) | "Correction and removal of expression of concern" [10.1039/d6ra90114j](https://doi.org/10.1039/d6ra90114j) (2026); [notice text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC13543934/fullTextXML) | "Fig. 1 was re-used from ref. 1 without being correctly attributed… This notice supersedes the information provided in the expression of concern." | A new medium **correction** alert, with nothing saying the concern is over |
| E4 (updated) | PNAS 2015, "NK cells require IL-28R for optimal in vivo activity": [10.1073/pnas.1424241112](https://doi.org/10.1073/pnas.1424241112) | "Update—Editorial Expression of Concern" [10.1073/pnas.2420879121](https://doi.org/10.1073/pnas.2420879121) (Nov 2024); [notice text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC11573608/fullTextXML) | It names Figs. 1A, 4D and 5 | A new medium EoC alert |
| E5 | Lancet (fixture) | Correction (30 May) → EoC (3 Jun) → retraction (5 Jun 2020) | Wayback snapshots [29 May](https://web.archive.org/web/20200529140835/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext), [31 May](https://web.archive.org/web/20200531235300/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext), [4 Jun](https://web.archive.org/web/20200604194739/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext), [6 Jun](https://web.archive.org/web/20200606220137/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext) | Three alerts over a week |
| E5 | Evolution 2022: [10.1111/evo.14496](https://doi.org/10.1111/evo.14496) | EoC [10.1093/evolut/qpad094](https://doi.org/10.1093/evolut/qpad094) (2023), then retract and replace [10.1093/evolut/qpae066](https://doi.org/10.1093/evolut/qpae066) (2024), per Retraction Watch | An EoC resolved by retract-and-replace | An EoC, then a retraction |

### Correction and erratum

| # | Before | After | What to compare | What our code does |
|---|---|---|---|---|
| C1 (in place) | Lancet (fixture) as of [29 May 2020](https://web.archive.org/web/20200529140835/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext) | "Department of Error" [10.1016/s0140-6736(20)31249-6](https://doi.org/10.1016/s0140-6736(20)31249-6); page as of [31 May 2020](https://web.archive.org/web/20200531235300/https://www.thelancet.com/journals/lancet/article/PIIS0140-6736(20)31180-6/fulltext) | "7555 (7·9%) from Asia … 609 (0·6%) from Australia" became "8101 (8·4%) from Asia … 63 (0·1%) from Australia", under the same DOI and publication date | A medium correction alert; the content change itself is invisible |
| C1 (in place, admin) | Scientific Reports 2024, "Fault correcting adder design for low power applications": [10.1038/s41598-024-79772-7](https://doi.org/10.1038/s41598-024-79772-7) | "Author Correction: …" [10.1038/s41598-025-93099-x](https://doi.org/10.1038/s41598-025-93099-x) (Mar 2025); [notice text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC11906582/fullTextXML) | One co-author's affiliation was wrong. "The original Article has been corrected." | A medium correction alert for something that doesn't matter |
| C2 | PLOS ONE 2024, "LightGBM hybrid model based DEM correction for forested areas": [10.1371/journal.pone.0309025](https://doi.org/10.1371/journal.pone.0309025) | "Correction: …" [10.1371/journal.pone.0320535](https://doi.org/10.1371/journal.pone.0320535) (Mar 2025); [notice text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC11902294/fullTextXML) | "The images for Figs 1, 3–8 are incorrectly switched", with the right figures reprinted in the notice | Medium correction |
| C3 | Lancet (fixture) | "Department of Error" `…31249-6` | A title that names neither the paper nor the error | Medium correction |
| C4 (batch) | Several npj Nanophotonics articles, e.g. [10.1038/s44310-024-00011-y](https://doi.org/10.1038/s44310-024-00011-y) | "Publisher Correction: npj Nanophotonics, volume 1, missing Data Availability statements" [10.1038/s44310-025-00058-5](https://doi.org/10.1038/s44310-025-00058-5) | One notice, several articles, an administrative fix | A medium correction alert on each article |
| C4 (Elsevier) | *Ophthalmology* 2026: [10.1016/j.ophtha.2026.01.009](https://doi.org/10.1016/j.ophtha.2026.01.009) | "Corrigendum" [10.1016/j.ophtha.2026.06.027](https://doi.org/10.1016/j.ophtha.2026.06.027) | An author correction, registered as `erratum` | **Low** erratum |
| C5 | — | Aquarius et al. 2025, "The Existence of Stealth Corrections in Scientific Literature": [10.1002/leap.1660](https://doi.org/10.1002/leap.1660), [preprint](https://arxiv.org/abs/2409.06852) | 131 silently changed articles across 10 publishers (92 with changed figures, data or text); the study's data lists them | Nothing |
| C6 | F1000Research, "Reassessing the Education–FDI–Trade Nexus…": [version 1](https://doi.org/10.12688/f1000research.187739.1) | [Version 2](https://doi.org/10.12688/f1000research.187739.2) (25 Sep 2026) | Both versions online with free PDFs: a clean old-vs-new pair | `other:new_version`, if seen |
| C6 | Cochrane review "Zuclopenthixol acetate for acute schizophrenia…": [.pub3](https://doi.org/10.1002/14651858.cd000525.pub3) | [.pub4](https://doi.org/10.1002/14651858.cd000525.pub4) (24 Sep 2026) | An updated systematic review under a new DOI | `other:new_version`, if seen |
| C7 | See R4 | | | |

### DOAJ delisting

All from [DOAJ's withdrawn-journals log](https://docs.google.com/spreadsheets/d/1Kv3MbgFSgtSDnEGkA2JacrSjunRu0umHeZCtcMeqO5E/edit)
(the "Withdrawn" sheet). Check a journal's status with
`https://doaj.org/api/search/journals/issn:<issn>` and OpenAlex's view with
`https://api.openalex.org/sources/issn:<issn>`.

| # | Journal | Removed | Reason in the log | OpenAlex on 26 Sep 2026 | What our code does |
|---|---|---|---|---|---|
| D1 | Revista Técnica de la Facultad de Ingeniería (ISSN 2477-9377); sample paper [10.22209/rt.v49a06](https://doi.org/10.22209/rt.v49a06) | 29 Apr 2026 | Journal not adhering to best practice | `is_in_doaj: false` (caught up) | A low delisting alert, for a quality reason |
| D1 + lag | Tecnura (ISSN 2248-7638); sample paper [10.14483/22487638.24200](https://doi.org/10.14483/22487638.24200) | 25 Sep 2026 | Journal not adhering to best practice | Still `true`; the DOAJ API no longer lists it | An alert only once OpenAlex catches up |
| D2 | Acta Futura (ISSN 2309-1940) | 8 Sep 2026 | Ceased publishing | Still `true` | Eventually a delisting alert that says nothing about quality |
| D2 / D3 | Journal of Business Models (ISSN 2246-2465); new papers are published by Emerald, e.g. [10.1108/jobm-07-2026-0019](https://doi.org/10.1108/jobm-07-2026-0019) | 20 Aug 2026 | Journal is no longer open access | `false`, same source id | A delisting alert for a business-model change |
| D3 | Interpersona (ISSN 1981-6472); sample paper [10.54899/iij.v20i1.1490](https://doi.org/10.54899/iij.v20i1.1490) | 15 Jul 2026 | Transferred to a new publisher | `false`, same source id | A delisting alert for an administrative reason |
| D4 | — | — | No live example found. The disagreements seen (Tecnura, Acta Futura) are OpenAlex lagging behind a real removal, not flipping on its own. | — | — |
| D5 | LIPIcs 2023, [10.4230/lipics.itp.2023.19](https://doi.org/10.4230/lipics.itp.2023.19) | — | — | Primary location is the DROPS repository (`is_in_doaj: false`), while its LIPIcs journal location is in DOAJ | Ignored by the guard if OpenAlex switches between them |

### Other Crossref types

| Type | Notice | Updates | What it really is | What our code does |
|---|---|---|---|---|
| `withdrawal` (self-referencing) | "WITHDRAWN: Unveiling negative memorable experiences of hotel guests…" [10.1016/j.ijhm.2026.104753](https://doi.org/10.1016/j.ijhm.2026.104753) | Itself | An Elsevier article in press, withdrawn | Medium `other:withdrawal` |
| `withdrawal` (duplicate) | "WITHDRAWAL—Administrative Duplicate Publication: Navigating AI in Digital Leisure…" [10.1177/00219096261481690](https://doi.org/10.1177/00219096261481690) | [10.1177/19367244261463488](https://doi.org/10.1177/19367244261463488) | The paper was published twice; the other copy stands | Medium `other:withdrawal` |
| `withdrawal` (trap) | "WITHDRAWN: Corrigendum to 'Complement activation sustains neuroinflammation…'" [10.1016/j.bbi.2026.106787](https://doi.org/10.1016/j.bbi.2026.106787) | The *original article* [10.1016/j.bbi.2019.08.004](https://doi.org/10.1016/j.bbi.2019.08.004) | Only the corrigendum was withdrawn | A withdrawal alert on the original article, which wasn't withdrawn |
| `removal` | "TEMPORARY REMOVAL: Establishing barriers and enablers to nurse-enabled…" [10.1016/j.clml.2026.09.007](https://doi.org/10.1016/j.clml.2026.09.007); "REMOVED: AQP4-IgG positivity in probable neurosarcoidosis…" [10.1016/j.jneuroim.2026.578975](https://doi.org/10.1016/j.jneuroim.2026.578975) | Themselves | Elsevier articles in press taken down, temporarily or for good | Medium `other:removal` |
| `partial_retraction` | See "Partial" under Retraction | | Registered as `retraction` | High retraction |
| `corrigendum` | "Corrección a: Factores asociados a mortalidad materna en Ica, Perú…" [10.5867/medwave.2026.08.8709](https://doi.org/10.5867/medwave.2026.08.8709) | [10.5867/medwave.2024.11.2961](https://doi.org/10.5867/medwave.2024.11.2961) | An author correction | Medium `other:corrigendum` |
| `addendum` (reinstatement) | See R8 | | A reversed retraction | Medium `other:addendum` |
| `addendum` (software) | "Version 2.0.1 - SpinGlassPEPS.jl…" [10.1016/j.softx.2026.103027](https://doi.org/10.1016/j.softx.2026.103027) | [10.1016/j.softx.2025.102257](https://doi.org/10.1016/j.softx.2025.102257) | A new software release | Medium `other:addendum` |
| `addendum` (classic) | "Addendum to 'A case of impacted third molar from the prehistoric Hypogeum of Calaforno…'" [10.1016/j.archoralbio.2026.106701](https://doi.org/10.1016/j.archoralbio.2026.106701) | [10.1016/j.archoralbio.2025.106371](https://doi.org/10.1016/j.archoralbio.2025.106371) | Information added | Medium `other:addendum` |
| `clarification` (critique) | "Comment on 'Mapping photoisomerization dynamics…'" [10.1039/d5sc05038c](https://doi.org/10.1039/d5sc05038c); [text](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12715712/fullTextXML) | [10.1039/d4sc07540d](https://doi.org/10.1039/d4sc07540d) | A published critique: "a misinterpretation…, conceptual flaws" | Medium `other:clarification`, with generic text |
| `new_version` | See C6 | | A newer version | Medium `other:new_version` |
| `new_edition` | [10.61927/igmin362](https://doi.org/10.61927/igmin362) | Itself | A small publisher re-issuing an article | Medium `other:new_edition` |
| `reinstatement` | None: 0 in Crossref | | Arrives as something else (R8) | — |
