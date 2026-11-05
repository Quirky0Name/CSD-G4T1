# Replace the Telegram notification with an in-app one

**Status:** draft
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none

**Commands**
- Full backend test suite: `cd backend && uv run pytest`
- Single backend test file: `cd backend && uv run pytest <path>`
- Backend lint: `cd backend && uv run ruff check`
- Backend typecheck: none
- Frontend typecheck + build: `cd frontend/csd-frontend-vite && npm run build`
- Frontend lint: `cd frontend/csd-frontend-vite && npm run lint`
- Frontend tests: none (OPEN Q2)

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 Remove Telegram from Research Evaluation | none |
| S2 Toast when a report is assessed | none |

Run order: S1, S2.

External blockers: OPEN Q1, OPEN Q2, OPEN Q3.

Uncommitted changes that must stay out of the commits:
`frontend/csd-frontend-vite/package-lock.json`.

Current branch is `fix/frontend-tracking-session` (unrelated work).
Proposed branch: `feat/in-app-report-notification`, from `main`.

## Open questions

1. **OPEN Q1** — Do the GitHub PR workflows
   `.github/workflows/telegram-pr-created.yml` and
   `telegram-pr-approved.yml` also go, or only Research Evaluation's
   report notification? Affects S1.
2. **OPEN Q2** — The frontend has no test runner. Add Vitest (+ Testing
   Library) in S2 so the toast logic has automated tests, or rely on
   `npm run build && npm run lint` plus a human check? Affects S2.
3. **OPEN Q3** — Reports assessed while the researcher is signed out (or
   before the first load of a session) never toast: the first load only
   seeds the "seen" set, like alerts do today. Accept that, or also show
   them (e.g. a persistent "N new reports" badge)? Affects S2.
4. **ASSUMED** — The toast text is `Report ready on "<paper title>"`, tone
   `info`, with a `View` action that opens `/alerts?paper=<paper_id>`
   (where reports are listed today). Affects S2.
5. **ASSUMED** — "Newly assessed" means a report the frontend has seen
   before with status `investigating`/`investigated` and now sees as
   `assessed`, or a report first seen already `assessed` after the
   seeding load. Affects S2.

## Suggested splits

S1 and S2 share no files and neither blocks the other. They could be two
specs on two branches (S1: backend + docs; S2: frontend). Suggested: one
spec, because removing Telegram without the in-app notice leaves the
researcher with no notification at all between the two merges.

## Out of scope

- Email, push or any notification outside the app (user). Recorded by
  S1's doc delta to DECISIONS.md.
- Any new endpoint or call between services: no service sends or pings
  anything when a report is assessed (user). Recorded by S1's doc delta to
  ARCHITECTURE.md.
- Adding `change_summary`/`change_severity`/`impact_level` to
  `GET /papers/{id}/reports` (stays on ROADMAP.md). `transient`.

## Subtasks

### S1 — Remove Telegram from Research Evaluation

- **Goal:** Research Evaluation sends nothing to anyone when a report is
  assessed. No Telegram code, config, env var, test or doc remains in
  Research Evaluation.
- **Blocked by:** none
- **Files:**
  - delete `backend/src/research_evaluation/notify.py`,
    `backend/tests/research_evaluation/test_notify.py`
  - `backend/src/research_evaluation/impact/run.py` (drop `notifier` from
    `ImpactContext` and the call after the store)
  - `backend/src/research_evaluation/main.py` (drop the Telegram client and
    notifier wiring)
  - `backend/src/research_evaluation/config.py` (drop
    `telegram_bot_token`, `notify_telegram_chat_id`)
  - `backend/tests/conftest.py`, `backend/tests/research_evaluation/conftest.py`,
    `backend/tests/research_evaluation_support.py` (`FakeTelegram`),
    `backend/tests/research_evaluation/impact/test_run.py`,
    `test_trigger.py`, `test_config.py`
  - `backend/pyproject.toml` (if it names Telegram, e.g. the `live` marker
    text)
  - `.env.example`, `backend/.env.example`
  - docs: README.md, ARCHITECTURE.md, CONTRACTS.md, DECISIONS.md,
    DEMO.md, DEPLOY.md, EVALUATION.md, ROADMAP.md, SETUP.md,
    `docs/diagrams/microservices.html`
  - OPEN Q1: `.github/workflows/telegram-pr-*.yml`
- **Seams:** `POST /evaluate/changes`, `POST /evaluate/reports`,
  `impact.run.assess_report`, `ResearchEvaluationSettings`.
- **Decisions:**
  - Notification is through the app itself, not Telegram (user: "notifying
    thru the app itself"). Doc delta.
  - "delete telegram" (user). Doc delta.
  - The frontend finds out by reading Storage Management with the user's
    JWT; Storage Management only returns the caller's own papers, so no
    service routes a notification to a user (code:
    `frontend/csd-frontend-vite/src/tracking.tsx`; docs: ARCHITECTURE.md,
    "Storage Management … stores what it's sent"). Doc delta.
- **Acceptance criteria:**
  - [ ] `uv run pytest` passes with `test_notify.py` deleted.
  - [ ] A test in `tests/research_evaluation/impact/test_run.py` assesses a
        report through `POST /evaluate/reports` and asserts the evaluation
        is stored and no request goes to any host other than Storage
        Management and the fake LLM.
  - [ ] `ResearchEvaluationSettings` has no `telegram_bot_token` or
        `notify_telegram_chat_id` (test in `test_config.py`).
  - [ ] `grep -rniI telegram backend/src backend/tests backend/pyproject.toml .env.example backend/.env.example docs README.md`
        returns nothing except DECISIONS.md history entries.
  - [ ] `uv run ruff check` passes.
- **Doc deltas:**
  - DECISIONS.md: new entry (2026-11-04) "The researcher hears in the app
    when a report is assessed": Telegram removed; the frontend toasts a
    newly assessed report from its existing refresh; rejected: email,
    Research Evaluation or Storage Management pinging User Management
    (Storage Management fetches nothing on its own; User Management already
    calls Storage Management). Mark the 2026-09-28 Telegram entry
    superseded by it.
  - ARCHITECTURE.md: drop Telegram from Week-7 scope, data-flow diagram,
    step 4 ("then notify sends the Telegram message"), CI line (per OPEN
    Q1). State: no service sends a notification; the frontend shows one
    when its refresh sees a report turn `assessed`.
  - EVALUATION.md: remove step 5 and the "Notification" section, the
    Telegram HTTP client, the Telegram gotchas and fakes; lifespan makes
    three HTTP clients.
  - CONTRACTS.md: background work ends at impact's `PUT
    /internal/reports/{reportId}/evaluation`.
  - SETUP.md, DEPLOY.md, DEMO.md, README.md: remove the Telegram bot
    section, env vars, the live test, the demo's phone step (DEMO.md shows
    the in-app toast instead).
  - ROADMAP.md: remove "Notifications to the paper's owner instead of one
    hard-coded Telegram chat"; reword the `DEV_TOOLS` item without
    Telegram.
  - `docs/diagrams/microservices.html`: drop Telegram.

### S2 — Toast when a report is assessed

- **Goal:** While a researcher is signed in, within one refresh (60 s) of
  a report on one of their papers becoming `assessed`, the app shows a
  toast for it, once.
- **Blocked by:** none
- **Files:** `frontend/csd-frontend-vite/src/tracking.tsx`; per OPEN Q2,
  `package.json`, a Vitest config and a test file.
- **Seams:** `TrackingProvider` (its refresh loop and `useToast`),
  `listReports(paperId)` in `api.ts`.
- **Decisions:**
  - In-app only (user). Doc delta (S1's DECISIONS.md entry).
  - Reports are read in the same shared refresh loop as papers and alerts
    (`tracking.tsx`, `ALERT_REFRESH_MS`), one `listReports` per paper, not
    a second interval (code: `tracking.tsx` header comment). Doc delta:
    ARCHITECTURE.md, data-flow step 5.
  - Same seeding rule as alerts: the first successful load seeds the seen
    set and never toasts (code: `tracking.tsx`; ASSUMED pending OPEN Q3).
  - Toast text and action: ASSUMED 4.
- **Acceptance criteria:**
  - [ ] `npm run build` and `npm run lint` pass.
  - [ ] (if OPEN Q2 = Vitest) a test renders `TrackingProvider` with a
        mocked `api`: first load with an `investigated` report shows no
        toast; next refresh with it `assessed` shows exactly one toast
        `Report ready on "<title>"`; a third refresh shows no new toast.
  - [ ] (if OPEN Q2 = Vitest) a test: a report first seen as `assessed`
        on the seeding load never toasts.
- **Doc deltas:**
  - ARCHITECTURE.md, data-flow step 5: the shared refresh also reads each
    paper's reports and toasts each report it sees turn `assessed`.

## Human checks

- [ ] S2: Sign in, track a paper, replay a retraction with the demo tools
      (DEMO.md) with `GEMINI_API_KEY` set. Expected: within about 60 s of
      the report becoming `assessed`, one toast `Report ready on "<title>"`
      appears; `View` opens the paper's alerts and reports; it does not
      appear again on later refreshes.
- [ ] S2: Sign in as a second user in another browser. Expected: that user
      sees no toast for the first user's report.
