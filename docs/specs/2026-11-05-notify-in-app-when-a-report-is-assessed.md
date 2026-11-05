# Notify in the app when a report is assessed

**Status:** approved
**Repo:** `D:\SMU\CS203\project\notify-worktree`
**Context file to update:** none named

**Commands:**
- Frontend (from `frontend/csd-frontend-vite/`):
  - Full test suite: none (the frontend has no test runner)
  - Single test file: none
  - Typecheck: `npm run build` (runs `tsc -b`)
  - Lint: `npm run lint`
- Backend (from `backend/`):
  - Full test suite: `uv run pytest`
  - Single test file: `uv run pytest <path>`
  - Typecheck: none
  - Lint: `uv run ruff check`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | S1 |
| S3 | none |

Run order: S1, S2, S3. S3 runs last so the branch never has a commit with
neither notification.

External blockers: none.

Git state: on `feat/notify` (not `main`), working tree clean. No
uncommitted changes to keep out. No new branch needed.

## Suggested splits

No split: all subtasks are connected. S2 is blocked by S1, and S3 changes
the same docs as S2 (`docs/ARCHITECTURE.md`, `docs/DECISIONS.md`,
`docs/EVALUATION.md`, `docs/DEMO.md`).

## Out of scope

- The GitHub workflows that message Telegram about PRs
  (`.github/workflows/telegram-pr-created.yml`,
  `telegram-pr-approved.yml`) and their secrets, and the line in
  `docs/ARCHITECTURE.md` that describes them. The user said not to touch
  the GitHub workflow (S3 doc delta).
- Any change to `.github/workflows/deploy.yml` (user; `transient`).
- A frontend test runner. S1 and S2 are proven by build, lint, grep and
  the Human checks (user; `transient`).
- A lean reports endpoint in Storage Management. The refresh reuses
  `GET /papers/{id}/reports` (user; S2 doc delta in `docs/CONTRACTS.md`).
- A notification list, history, badge, unread count or read state: the
  notification is a toast that auto-dismisses after 6 s like every other
  toast (S2 doc delta).
- Notifying about reports assessed while no tab was open (S2 doc delta).
- Push from the server (WebSocket, SSE, browser notifications): the
  frontend finds assessed reports on its refresh (S2 doc delta).

## Interfaces

`TrackingState` in `frontend/csd-frontend-vite/src/tracking.tsx` (S1 makes
it, S2 reads it; `transient`, internal to the frontend):

```ts
export type TrackingState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; papers: Paper[]; alerts: Alert[]; reports: UserReport[] }
```

`reports` holds every report of every paper in `papers`, newest first by
`created_at`.

## Subtasks

### S1: The shared refresh loads reports too, every 30 s

- **Goal:** The tracking refresh runs every 30 s instead of 60 s. Each
  refresh (first load, the 30 s interval, a manual `refresh()`, a route
  change) fetches the reports of every tracked paper along with papers and
  alerts and puts them in `TrackingState`.
  `ReportList` reads reports from `useTracking()` (filtered by the
  `paper` query parameter) and no longer fetches them itself. What the
  Alerts page shows does not change.
- **Blocked by:** none
- **Files:** `frontend/csd-frontend-vite/src/tracking.tsx`,
  `frontend/csd-frontend-vite/src/components/ReportList.tsx`
- **Seams:** `useTracking()` and its `TrackingState`; the `ReportList`
  component.
- **Decisions:**
  - "change to 30s" (user): the refresh interval is 30 s. The constant
    `ALERT_REFRESH_MS = 60_000` becomes `TRACKING_REFRESH_MS = 30_000`,
    since it now times papers, alerts and reports (`transient`; the doc
    delta below records the interval). The comment in `ReportList.tsx`
    that says "every minute" goes with S1's removal of its fetch.
  - The refresh reads reports with `listReports(paperId)`
    (`GET /papers/{id}/reports`), one call per paper, with no backend
    change (user). Doc delta on S2 (`docs/CONTRACTS.md`).
  - Reports are fetched in the same all-or-nothing run as papers and
    alerts: if any call fails, the tracking state is `error` (as today for
    alerts) (user; `transient`).
  - `TrackingState.ready.reports` is sorted newest first by `created_at`,
    as `ReportList` sorts them today (code: `ReportList.tsx`; `transient`).
- **Acceptance criteria:**
  - [x] `npm run build` passes.
  - [x] `npm run lint` passes.
  - [x] `grep -n "listReports" frontend/csd-frontend-vite/src/components/ReportList.tsx`
        returns nothing.
  - [x] `grep -n "listReports" frontend/csd-frontend-vite/src/tracking.tsx`
        finds the call.
  - [x] `grep -n "30_000" frontend/csd-frontend-vite/src/tracking.tsx`
        finds the interval, and `grep -n "60_000" frontend/csd-frontend-vite/src/tracking.tsx`
        returns nothing.
- **Doc deltas:**
  - `docs/ARCHITECTURE.md` (data flow, step 5): "The shared refresh of
    papers, alerts and reports runs every 30 s only while a user is signed
    in, and starts fresh for each user."
  - `tracking.tsx` header comment: the loop covers reports too.
  - Decisions made during implementation:
    - Each run fetches the alert lists and the report lists in parallel
      (one `Promise.all` over both), so adding reports doesn't make a run
      take twice as long (`transient`: the code shows it; no doc needed).
    - `ReportList` filters `state.reports` by `paper_id === paper` query
      parameter, so `/alerts?paper=<id>` for an id not in `GET /papers`
      shows no reports instead of calling `GET /papers/{id}/reports` for
      it (`transient`: Storage Management answers `404` for such an id
      anyway; no doc needed).
    - `ReportList` shows loading and error from the tracking state alone;
      its own `ReportsState` is gone (`transient`: follows from reading
      reports from `useTracking()`; no doc needed).

### S2: Toast when a report becomes assessed

- **Goal:** While signed in, when a refresh finds a report of one of the
  user's papers with status `assessed` that was not assessed at the
  provider's first load and has not been notified yet, the app shows one
  toast: `Report ready on "<paper title>"` with a `View` button that opens
  `/alerts?paper=<paper id>`.
- **Blocked by:** S1
- **Files:** `frontend/csd-frontend-vite/src/tracking.tsx`
- **Seams:** `useTracking()` / `TrackingProvider`.
- **Decisions:**
  - Notify in the app, not through Telegram (user).
  - "make it do upon the 60s refresh from the front end" (user), then
    "change to 30s" (user): the check runs in the tracking refresh (every
    30 s after S1), after each successful run.
  - "The notification names the paper ("Report ready on "<paper title>"")
    and has a View button that opens that paper's alerts and reports."
    (user). View navigates to `/alerts?paper=<paper_id>`, the page that
    shows that paper's `AlertList` and `ReportList` (code: `App.tsx`,
    `ReportList.tsx`).
  - A paper with no title gives `Report ready on "a tracked paper"`, the
    same fallback the new-alert toast uses (user; `transient`).
  - The toast uses tone `success` (green check icon) (user; `transient`).
  - "Each assessed report produces exactly one notification. It doesn't
    reappear on later refreshes or when I switch pages." (user) and
    "Reports that were already assessed when I sign in don't notify me."
    (user). The notified-report tracker:
    - `assessed` is the report's processing status (impact stored its
      evaluation), not a read state. A report never leaves `assessed`
      (docs: `docs/CONTRACTS.md`, `docs/EVALUATION.md`). Nothing records
      whether the user read a report.
    - `TrackingProvider` holds a ref `seenAssessedReportIds: Set<number> |
      null`, starting at `null`, like `seenAlertIds` (code:
      `tracking.tsx`).
    - First successful run: the set is created holding the id of every
      report that is `assessed` now. No toast.
    - Every later successful run: for each report with status `assessed`
      whose id is not in the set, add the id and show one toast.
    - A failed run changes nothing in the set and shows no toast (user).
    - The set lives only in memory. `TrackingProvider` does not remount
      on route changes, so it survives page switches. A reload, a new tab
      or another user signing in counts as signing in: it builds a new
      set, seeded again with no toast (user). A report assessed while no
      tab was open never notifies. Each open tab notifies once per report.
    - Example, report 7:

      | Run | Report 7 status | Id in set before the run? | Toast? |
      |---|---|---|---|
      | 1 (sign-in, seeds) | `investigated` | no; only `assessed` ids are seeded | no |
      | 2 | `assessed` | no; added now | yes |
      | 3, 4, … | `assessed` | yes | no |
      | 1 after a reload | `assessed` | no; seeded | no |
  - "without seeing other users' reports" (user): only reports from
    `GET /papers/{id}/reports` for papers in `GET /papers` are considered;
    Storage Management returns only the caller's papers and answers `404`
    for another user's paper (docs: `docs/CONTRACTS.md`).
- **Acceptance criteria:**
  - [x] `npm run build` passes.
  - [x] `npm run lint` passes.
  - [x] `grep -n "Report ready on" frontend/csd-frontend-vite/src/tracking.tsx`
        finds the message.
  - [x] `grep -n "seenAssessedReportIds" frontend/csd-frontend-vite/src/tracking.tsx`
        finds the ref.
- **Doc deltas:**
  - `docs/CONTRACTS.md` ("Frontend ↔ Storage Management" feature table),
    new row: "Report-ready notification | no push: each refresh re-fetches
    `GET /papers/{id}/reports` for every paper and notifies `assessed`
    ids it hasn't seen; the first load after sign-in (or a reload) only
    records them".
  - `docs/ARCHITECTURE.md` (week-7 scope list): replace the Telegram
    bullet with "An in-app notification, on the frontend's 30 s refresh,
    when one of the signed-in researcher's reports is assessed." Data
    flow: step 4 ends at impact; step 5 says the frontend notifies when a
    report becomes `assessed`. Record that the notification is a toast
    with no history or read state, that the ids already notified are kept
    in memory per tab, and that reports assessed while no tab was open
    don't notify.
  - `docs/DECISIONS.md`: new entry `2026-11-05 — The researcher is
    notified in the app when a report is assessed`: in-app toast on the
    frontend's refresh, shortened from 60 s to 30 s so it shows sooner;
    only the signed-in user's reports; one per assessed report; reports
    already assessed at sign-in or reload don't notify, so nothing needs
    to persist; reuses `GET /papers/{id}/reports` rather than a new
    endpoint; replaces the Telegram notification of 2026-09-28. Rejected:
    server push (the refresh already exists); a stored read or notified
    state (not needed for the rule above).
  - `docs/ROADMAP.md`: the "Notifications to the paper's owner instead of
    one hard-coded Telegram chat" item is done (in-app); add open items
    for a notification history and for reports assessed while signed out.
  - `docs/DEMO.md` step 3: show the in-app "Report ready" toast instead of
    the Telegram message (up to about 30 s after the report is
    assessed).
  - Decisions made during implementation:
    - In a run that finds both, the new-alert toasts are shown before the
      report-ready toasts (`noticeNewAlerts` runs first) (`transient`:
      order inside one run; no doc needed).
    - A run that finds several newly assessed reports shows one toast per
      report, in the newest-first order of `reports` (`transient`:
      follows from "one toast per assessed report"; no doc needed).

### S3: Remove the Telegram notification from Research Evaluation

- **Goal:** Research Evaluation sends no notification. `notify.py`,
  `TelegramNotifier`, `telegram_configured`, the `TELEGRAM_BOT_TOKEN` and
  `NOTIFY_TELEGRAM_CHAT_ID` settings, the Telegram HTTP client in the
  lifespan, and `ImpactContext.notifier` are gone. Impact still stores
  evaluations exactly as before. The GitHub workflows are untouched.
- **Blocked by:** none (runs after S2)
- **Files:** `backend/src/research_evaluation/notify.py` (deleted),
  `backend/src/research_evaluation/config.py`,
  `backend/src/research_evaluation/main.py`,
  `backend/src/research_evaluation/impact/run.py`,
  `backend/tests/research_evaluation/test_notify.py` (deleted),
  `backend/tests/research_evaluation_support.py` (`FakeTelegram`),
  `backend/tests/research_evaluation/conftest.py` (`telegram` fixture),
  `backend/tests/conftest.py` (cleared env vars),
  `backend/tests/research_evaluation/impact/test_run.py`,
  `backend/tests/research_evaluation/impact/test_trigger.py`,
  `backend/tests/research_evaluation/test_config.py`,
  `backend/pyproject.toml` (the `live` marker text),
  `backend/.env.example`, `.env.example`, and the docs under Doc deltas.
- **Seams:** `assess_reports` / `assess_report` and `ImpactContext`;
  `POST /evaluate/reports` and `POST /evaluate/changes` through the test
  client; `ResearchEvaluationSettings`.
- **Decisions:**
  - "remove the telegram notification but don't touch the github
    workflow" (user). No file under `.github/` changes.
  - Settings use `extra="ignore"` (code: `config.py`), so an old `.env`
    that still sets the two variables still starts the service
    (`transient`).
  - A test whose only subject is the notification is deleted. A test that
    also asserts assessment keeps those assertions, without the notifier
    (`transient`).
  - `configure_logging` is unchanged: the `httpx` logger stays at WARNING.
    Only the gotcha that gives the bot token as the reason is removed
    (user; `transient`).
- **Acceptance criteria:**
  - [x] `uv run pytest` passes (from `backend/`).
  - [x] `uv run ruff check` passes (from `backend/`).
  - [x] `git grep -n -i telegram -- backend .env.example` returns nothing.
  - [ ] `git grep -n -i telegram -- docs README.md` returns only the
        line in `docs/ARCHITECTURE.md` about the PR workflows, entries in
        `docs/DECISIONS.md` dated before 2026-11-05, the 2026-11-05
        entry's lines about replacing Telegram, and the ROADMAP item
        marked done.
  - [x] `git diff --stat <base> -- .github` is empty.
  - [x] A test shows a report is stored as `assessed` with
        `ImpactContext(llm)` built with only the model (existing
        `test_run.py` assessment tests, adjusted).
- **Doc deltas:**
  - `docs/EVALUATION.md`: drop step 5 from the flow diagram (and "4 and 5
    only" becomes "4 only"); replace the "Notification" section with one
    sentence: Research Evaluation sends no notification; the frontend
    notices assessed reports on its refresh (ARCHITECTURE.md); drop the
    `notify.py` row in code layout, the Telegram client from the lifespan
    clients paragraph, the bot-token and Telegram gotchas, "each
    notification is awaited", and `FakeTelegram` / Telegram from the tests
    gotcha.
  - `docs/SETUP.md`: drop the Telegram row from the keys table, the env
    var row, the "Telegram bot" section, and Telegram from the `-m live`
    comment.
  - `docs/ARCHITECTURE.md`: drop Telegram from the service diagram line.
    Keep the line about the PR workflows' Telegram messages.
  - `docs/CONTRACTS.md`: "then the Telegram notification goes out" is
    removed from `POST /evaluate/changes`.
  - `docs/DEPLOY.md`: drop `TELEGRAM_BOT_TOKEN`, `NOTIFY_TELEGRAM_CHAT_ID`
    from the list of app settings.
  - `docs/DEMO.md`: drop `TELEGRAM_BOT_TOKEN`, `NOTIFY_TELEGRAM_CHAT_ID`
    and the bot check from step 3.
  - `README.md`: drop "the Telegram notification" from the EVALUATION.md
    row and "Telegram bot" from the SETUP.md row.
  - `docs/diagrams/microservices.html`: drop Telegram from the
    description and the external-APIs label.
  - `docs/DECISIONS.md`: the 2026-11-05 entry (S2) also records that the
    Telegram notification and its two env vars were removed from Research
    Evaluation, and that the PR workflows' Telegram messages stay.
  - Decisions made during implementation:
    - Deleted `test_run.py` tests whose only subject was the notification
      (short notification, Telegram failing) and those whose assessment
      assertions another test already makes: the notified-once test, the
      failing-report test, the not-stored test and the skipped-reports
      test. The kept tests that make those assertions are, in order,
      `test_a_new_retraction_ends_assessed_with_the_models_answers`,
      `test_a_failing_report_stores_nothing_and_the_next_is_still_assessed`,
      `test_storage_management_failing_on_the_put_is_logged_not_raised` and
      `test_only_investigated_reports_are_assessed`.
      `test_each_report_it_assesses_is_notified` in `test_trigger.py` is
      deleted too: the test before it already asserts both reports end
      `assessed` (`transient`: test layout; no doc needed).
    - Kept, without the notifier: the placeholder test (renamed
      `test_every_model_failing_a_step_stores_the_placeholder`), the
      placeholder-put failure test, and the no-notifier test, renamed
      `test_an_investigated_report_is_stored_assessed_with_only_the_model`,
      which now reads the report back as `assessed` (`transient`; no doc
      needed).
    - Added `test_a_dotenv_with_settings_the_service_no_longer_reads_still_loads`
      in `test_config.py`, so an old `.env` that still sets a removed
      variable is proved to load (`extra="ignore"`) (`transient`; no doc
      needed).

## Human checks

- [ ] **S1** — Open `/alerts`, then `/alerts?paper=<id>`: the reports list
  shows the same reports as before the change (all papers, then only that
  paper), newest first.
- [ ] **S2** — Sign in as a user with a tracked paper whose report is
  `investigated` (stub or real stack; assess it with
  `POST /evaluate/reports`). Within about 30 s of it becoming `assessed`,
  a toast `Report ready on "<title>"` appears. Click View: the Alerts page
  opens filtered to that paper, and the report shows as assessed.
- [ ] **S2** — After that toast, wait two more refreshes and move between
  `/`, `/project` and `/alerts`: no second toast for that report.
- [ ] **S2** — Sign out and back in, and reload the page: no toast for
  reports that were already `assessed`.
- [ ] **S2** — Sign in as a second user while the first user's report gets
  assessed: the second user sees no toast.
