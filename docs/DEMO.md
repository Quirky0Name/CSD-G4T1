# Week 7 demo runbook

**Status: stub.** Fill this in properly once the LLM step (claims +
stance) exists and has been rehearsed — see the plan's Build order,
step 6-7. What's below is the planned shape.

## Demo papers

| Role | Paper | DOI |
|---|---|---|
| Tracked | Gautret et al. 2020, HCQ + azithromycin (IJAA) — retracted in both OpenAlex and Crossref | `10.1016/j.ijantimicag.2020.105949` |
| Contradicts | Molina et al. 2020, "No evidence of rapid antiviral clearance…" | `10.1016/j.medmal.2020.03.006` |
| Supports | Gautret et al. 2020, 80-patient follow-up (TMAID) | `10.1016/j.tmaid.2020.101663` |

All three PDFs are on ScienceDirect, which usually blocks bots, so
download them by hand and upload them through Storage Management ahead
of time. Storage Management keeps them, and Research Evaluation reads
them from there (`GET /internal/papers/{id}/pdf`), so nothing depends on
the publisher during the demo.

## Before the slot

1. `scripts/seed_demo.py` — uploads the three demo PDFs to Storage
   Management (`POST /papers`), so each paper has a stored PDF, and
   inserts a pre-retraction baseline snapshot for the
   tracked paper (`is_retracted=false`, no Crossref updates,
   `fetched_at` set to a date before the actual retraction).
2. `scripts/prewarm_llm.py` — runs claims + stance for the demo papers
   ahead of time so live calls hit the cache instead of DeepSeek.

## Live sequence

1. **Updating, Swagger UI:** run the poll once for the tracked paper and
   show the snapshot it stores (retraction status, Crossref updates,
   DOAJ/journal, authors).
2. **Research Evaluation:** call `/evaluate/stance` for tracked vs.
   contradicts (expect `contradicts`) and tracked vs. supports (expect
   `supports`).
3. **Updating, Swagger UI:** `POST /admin/run-poll?paper_id=<tracked>` —
   show the new snapshot (now `is_retracted=true` with a `retraction`
   entry in `crossref_updates`) and that the run summary lists the paper
   as nudged, then show that Research Evaluation read the two snapshots
   from Storage Management, worked out the differences and evaluated them
   (severity, impact text and recommendation). The nudge's reply says how
   many alerts it created, and the alert is stored in Storage Management:
   `GET /papers/{id}/alerts` with the researcher's token (or, against the
   stub, `GET /dev/papers/{id}/alerts`).
4. **Frontend:** show the changes panel on the paper detail page, then
   acknowledge one change. Until the panel exists, show the same thing on
   Storage Management's API: `GET /papers/{id}/alerts` (newest first, with
   severity, description, recommendation and detection time), then
   `PATCH /alerts/{id}` with `{"status": "acknowledged"}`. Then record what
   the researcher did, `POST /alerts/{id}/notes` with
   `{"text": "Removed the citation from my draft."}`, and read the log back
   with `GET /alerts/{id}/notes`.
5. Run the poll again live to show it stores another snapshot but doesn't
   nudge again.

## Fallback if a live call fails

- LLM calls: pre-warmed cache serves the result even if DeepSeek is slow
  or down at demo time.
- External API calls (Crossref/OpenAlex/Semantic Scholar): have a
  terminal ready with the fixture JSON from `backend/tests/fixtures/` to
  show as a backup, and narrate what the live call would have returned.
