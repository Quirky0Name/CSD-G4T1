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

1. **Storage Management:** show the baseline snapshot seeded for the tracked
   paper (retraction status, Crossref updates, DOAJ/journal, authors). Don't
   poll it yet: a poll before step 3 would take the nudge that step 3 shows.
2. **Research Evaluation:** call `/evaluate/stance` for tracked vs.
   contradicts (expect `contradicts`) and tracked vs. supports (expect
   `supports`).
3. **Updating, Swagger UI:** `POST /run-poll?paper_id=<tracked>` with
   `Authorization: Bearer <token>` from the user who owns the tracked paper
   (a `401` without it, a `404` with another user's) —
   show the new snapshot (now `is_retracted=true` with a `retraction`
   entry in `crossref_updates`) and that the poll summary lists the paper
   as nudged, then show that Research Evaluation read the two snapshots
   from Storage Management, worked out the differences and evaluated them
   (alert severity, impact text and recommendation). The nudge's reply says how
   many alerts it created, and the alert is stored in Storage Management:
   `GET /papers/{id}/alerts` with the researcher's token (or, against the
   stub, `GET /dev/papers/{id}/alerts`).
   Once impact has assessed the report (in the background, up to a minute
   with three Gemini calls), **show the in-app "Report ready" toast** in the
   frontend (up to about 30 s after the report is assessed, on the
   frontend's refresh); its View button opens the paper's alerts and
   reports. It needs `GEMINI_API_KEY` in `backend/.env`. If Gemini's free tier is out of requests the report
   stays `investigated` and no toast comes; assess it again later with
   `POST /evaluate/reports`.
4. **Frontend:** show the changes panel on the paper detail page, then
   acknowledge one change. Until the panel exists, show the same thing on
   Storage Management's API: `GET /papers/{id}/alerts` (newest first, with
   alert severity, description, recommendation and detection time), then
   `PATCH /alerts/{id}` with `{"status": "acknowledged"}`.
5. Run the poll again live to show it stores another snapshot but doesn't
   nudge again.

## Triggering the change on demand (dev mode)

With `DEV_MODE=true` (SETUP.md, "Dev mode: triggering a change"), the real
retraction needn't be waiting: 1. poll the paper; 2. as the admin,
`POST /api/admin/papers/<id>/snapshots` with `{"is_retracted": false,
"removed_crossref_update_types": ["retraction"]}`; 3. poll again, and the
flagged change raises its alert. To repeat it, `DELETE
/api/admin/papers/<id>/history`, then poll (a baseline, which never
alerts), change the flag and poll again. Use an actually retracted paper:
the poll fetches the real value, so the edit can only restore a real
change. `POST /api/admin/papers/<id>/fake-report` puts a finished report
on a paper without a poll.

## Showing claim highlighting (dev mode)

Use two windows that don't share a session (the session is in
`localStorage`): a normal and a private window, or `localhost:8088` and
`127.0.0.1:8088`.

1. Researcher window: register. No paper or draft is needed.
2. Admin window: sign in as `admin` / `12345679` (opens `/dev`), pick the
   researcher, click **Add fake paper**, then **Add fake report** at
   **Assessed**.
3. Researcher window: within 30 s the "Report ready" toast appears. In
   **Alerts**, the report has **Open report**.
4. Pick a claim: its sentence in the example draft and its passage in the
   cited source are highlighted side by side. `?claim=<id>` keeps the
   choice across a reload.

## Rehearsing the Updating half

The seeded-scenario harness in [SETUP.md](SETUP.md) ("Mock harness") runs steps 3
and 5 without the real papers or a real change: seed a scenario, trigger the poll
from Swagger and check the nudge arrives. Nothing may poll the tracked paper
between seeding and step 3, including Updating's startup poll and a short
`POLL_INTERVAL_HOURS`.

## Fallback if a live call fails

- LLM calls: pre-warmed cache serves the result even if DeepSeek is slow
  or down at demo time.
- External API calls (Crossref/OpenAlex/Semantic Scholar): have a
  terminal ready with the fixture JSON from `backend/tests/fixtures/` to
  show as a backup, and narrate what the live call would have returned.
