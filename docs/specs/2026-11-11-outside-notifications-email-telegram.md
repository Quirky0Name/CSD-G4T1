# Outside notifications by email and Telegram

**Status:** approved
**Base commit:** 0758864586f2834e6849ff98fd13735ba2bdc05b
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

**Commands:**
- User Management (from `users/`):
  - Full test suite: `./mvnw test` (`.\mvnw.cmd test` on Windows)
  - Single test file: `./mvnw test -Dtest=<ClassName>`
  - Typecheck: none (compiled by `./mvnw test`)
  - Lint: none
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
| S3 | none (tests against the Interface below with a fake User Management) |

Run order: S1, S2, S3. S3 runs last so no commit has Research Evaluation
calling an endpoint that doesn't exist yet.

External blockers:
- Human checks need a real Telegram bot token (BotFather) and SMTP
  credentials. The automated tests need neither, so this blocks only the
  Human checks below, not the run (user, 2026-11-11: "proceed with it all").

Git state: on `feat/external-notif` (not `main`), working tree clean.

## Branch plan

Starting branch: `feat/external-notif`

| Order | Branch | Based on | Subtasks |
|---|---|---|---|
| 1 | `feat/external-notif` | none | S1 |
| 2 | `feat/external-notif-send` | `feat/external-notif` | S2 |
| 3 | `feat/external-notif-research-evaluation` | `feat/external-notif-send` | S3 |

One subtask per branch (user, 2026-11-11: "split into a chain of three
branches"), so each stays under the 500-line PR limit and passes the full
suite at its tip: S1 alone is a working preferences API, S2 adds sending on
top of it, and S3 only calls the endpoint S2 makes.

## Out of scope

- The frontend: no settings page or test button (user; `transient`).
- The in-app report-ready toast stays exactly as it is (S2 doc delta, in
  the DECISIONS.md entry).
- Linking Telegram through the bot (deep link, `getUpdates`, webhook): the
  user types their chat id (user; S1 doc delta).
- Verifying the account email, or a second email address: email goes to
  the account email (user; S2 doc delta).
- Notification history, read state, digests, batching across reports, and
  retrying a failed send (S2 doc delta, `docs/ROADMAP.md`).
- The GitHub workflows' Telegram PR messages and their secrets: untouched
  (`transient`).
- Notifications for anything but alerts and assessed reports (`transient`).

## Interfaces

### `POST /internal/users/{id}/notifications` (S2 makes it, S3 calls it)

Owned by User Management. Service token only (`/internal/**` already
takes only `ROLE_SERVICE`). Doc delta on S2 (`docs/CONTRACTS.md`,
"Research Evaluation ↔ User Management").

Request, one of:

```json
{
  "event": "alerts",
  "paper": {"id": "uuid", "title": "string or null", "doi": "string or null"},
  "report_id": 3,
  "alerts": [
    {"id": 7, "change_type": "retraction", "alert_severity": "high", "description": "..."}
  ]
}
```

```json
{
  "event": "report_assessed",
  "paper": {"id": "uuid", "title": "string or null", "doi": "string or null"},
  "report_id": 3,
  "change_severity": "high",
  "change_summary": "...",
  "impact_level": "medium",
  "evaluation": "string or null",
  "recommendation": "string or null"
}
```

- `event` is `alerts` or `report_assessed`; anything else is `400`.
- `alerts`: at least one; each `alert_severity` is `low`, `medium` or
  `high`.
- `report_assessed`: the fields as stored by `PUT
  /internal/reports/{reportId}/evaluation`; `impact_level`, `evaluation`
  and `recommendation` are null when `change_severity` is `none`.

Response `200`:

```json
{"channels": {"email": "sent", "telegram": "no_match"}}
```

Each channel's outcome is one of `sent`, `failed`, `disabled` (the user
turned it off), `no_match` (the event is below its thresholds),
`not_configured` (the server has no SMTP host or bot token).

Errors: `400` a body that breaks the rules above; `401`/`403` as for every
`/internal` call; `404` `No user <id>` (no account, or no preferences
row: the dev mode admin).

Research Evaluation side (S3), in `backend/src/research_evaluation/users.py`:

```python
async def notify(http: httpx.AsyncClient, user_id: UUID, body: dict[str, Any]) -> None:
    """Posts one event; raises on any non-2xx answer or transport error."""
```

## Subtasks

### S1: Notification preferences in User Management

- **Goal:** User Management stores each researcher's notification
  preferences: per channel (email, Telegram) whether it's on, Telegram's
  chat id, and that channel's thresholds. They're served at `GET
  /auth/me/notifications`, `PUT /auth/me/notifications/email` and `PUT
  /auth/me/notifications/telegram`. Every account has a row from
  registration on; deleting the account deletes it.
- **Blocked by:** none
- **Branch:** `feat/external-notif`
- **Order on branch:** 1
- **Files:**
  `users/src/main/resources/db/migration/V6__create_notification_preferences.sql`,
  `users/src/main/java/com/g4t1/users/notification/` (new package: the
  `NotificationPreferences` entity, repository, service,
  `NotificationController`, request and response records),
  `users/src/main/java/com/g4t1/users/auth/AuthService.java` (register
  writes the row),
  `users/src/test/java/com/g4t1/users/notification/NotificationPreferencesTest.java`,
  `users/src/test/java/com/g4t1/users/notification/NotificationPreferencesMigrationTest.java`
  (V6 backfill: its own H2 database migrated to V5, then V6),
  `docs/CONTRACTS.md`, `docs/ARCHITECTURE.md`, `GLOSSARY.md`.
- **Seams:**
  - `GET /auth/me/notifications` and `PUT /auth/me/notifications/{email,telegram}`
    over HTTP: catches defaults, replacement, validation, auth; misses
    sending.
  - `POST /auth/register` then `GET /auth/me/notifications`: catches that
    a new account gets the defaults.
  - `DELETE /auth/me`: catches the cascade.
- **Decisions:**
  - "notif preference in UM" (user): the preferences live in User
    Management, as account data deleted with the account (docs:
    `docs/ARCHITECTURE.md`). Doc delta.
  - "they get to customize everything" (user): every channel has its own
    on/off and its own three thresholds. Doc delta.
  - "they can decide the thresehold for both change severity and impact
    level. send if either exceeds" (user): each channel has
    `min_change_severity` and `min_impact_level`; a report sends when its
    change severity or its impact level is at or above that channel's
    threshold; "exceeds" means at or above, so `high` sends `high`
    (user: ok). Alerts use `min_alert_severity` the same way (user: ok).
    Each threshold is `low`, `medium`, `high` or `null`
    (never). Doc delta.
  - Defaults (user: ok): email off, Telegram off with no chat id; both
    channels `min_alert_severity` `high`, `min_change_severity` `medium`,
    `min_impact_level` `medium`. Doc delta.
  - "keep it simple and store default upon creation" (user): one row per
    account, written with the defaults in register's
    transaction (code: `AuthService.register` saves the profile there
    too). V6 also inserts a default row for every existing account
    (`insert ... select id from users`, valid on Postgres and H2). Every
    column is `not null` except `telegram_chat_id` and the six
    thresholds. The dev mode admin's
    row from that insert is deleted with the admin account if dev mode
    goes off (cascade); one created later gets none. No code path
    handles a missing row except the internal endpoint's `404`. Doc
    delta (ARCHITECTURE.md data model).
  - Table `notification_preferences`: `user_id` uuid primary key, FK to
    `users` with `on delete cascade`; `email_enabled` boolean;
    `email_min_alert_severity`, `email_min_change_severity`,
    `email_min_impact_level` text null; `telegram_enabled` boolean;
    `telegram_chat_id` bigint null; `telegram_min_alert_severity`,
    `telegram_min_change_severity`, `telegram_min_impact_level` text null.
    A threshold column holds `low`, `medium`, `high` or null.
  - `me` follows the existing convention (code: `/auth/me`,
    `/auth/me/profile`): the account is the token's `sub`, never an id
    in the path. `/auth/me/**` already requires `ROLE_USER` (code:
    `SecurityConfig`), so admin and service tokens get `403`. Each
    handler answers `401` `Your account no longer exists` for a deleted
    account (code: `ProfileController.requireAccount`).
  - `GET /auth/me/notifications` → `200`:

    ```json
    {
      "email": {"enabled": false, "address": "a@b.c",
                "min_alert_severity": "high", "min_change_severity": "medium", "min_impact_level": "medium"},
      "telegram": {"enabled": false, "chat_id": null,
                   "min_alert_severity": "high", "min_change_severity": "medium", "min_impact_level": "medium"}
    }
    ```

    `address` is the account email, read-only (user: email goes to the
    account email).
  - `PUT /auth/me/notifications/email` replaces that channel: body
    `{"enabled", "min_alert_severity", "min_change_severity", "min_impact_level"}`.
    `PUT /auth/me/notifications/telegram`: the same plus `chat_id`. Both
    answer the `GET` body. A PUT stores the whole channel; there is no
    partial update. A left-out threshold or `chat_id` is `null`; a
    left-out `enabled` is `400`.
  - Validation, `400` with `detail` starting with the field name: a
    threshold is exactly `low`, `medium`, `high` or `null`. `chat_id` is a
    JSON integer, not `0`, or `null`. `enabled` true with `chat_id` null is
    `400` `chat_id is required to enable Telegram`.
  - "type chat id" (user): the user enters the chat id themselves (from a
    bot such as `@userinfobot`), and must press Start on the project's
    bot first, since a bot can't message a chat that hasn't. Saving
    doesn't contact Telegram (user: ok); the test endpoint (S2) checks it.
    Doc delta (SETUP.md and CONTRACTS.md).
  - The chat id is never logged.
- **Acceptance criteria:**
  - [x] `NotificationPreferencesTest`: a newly registered account's `GET`
        returns the defaults with `address` its email
        (`./mvnw test -Dtest=NotificationPreferencesTest`).
  - [x] `NotificationPreferencesTest`: `PUT .../email` and `PUT
        .../telegram` round-trip, each leaving the other channel
        unchanged; a `null` threshold round-trips as `null`.
  - [x] `NotificationPreferencesTest`: `400` for an unknown level (`High`,
        `none`), a missing `enabled`, `chat_id` `0` or a string, and
        Telegram enabled with no chat id; nothing changes on a `400`.
  - [x] `NotificationPreferencesTest`: admin and service tokens get `403`;
        a bad token `401`; a deleted account's token `401`.
  - [x] `NotificationPreferencesTest`: after `DELETE /auth/me` the row is
        gone (read through the repository, as `AdminAccountTest` reads the
        database).
  - [x] A test shows V6 gives an account created before it the default
        row (Flyway migrate to V5, insert a user, migrate to V6), or, if
        the test setup can't stop at V5, `grep -n "insert into
        notification_preferences" users/src/main/resources/db/migration/V6__create_notification_preferences.sql`
        finds the backfill.
  - [x] `./mvnw test` passes.
- **Doc deltas:**
  - `docs/CONTRACTS.md`, errors: a malformed body (not JSON) is Spring's
    generic `400`; field errors say `<field> is required` (`enabled`) or
    `<field> must be low, medium, high or null` /
    `chat_id must be a non-zero integer or null`. Why: the spec fixed only
    that detail starts with the field name.
  - `docs/CONTRACTS.md`: `chat_id` may be negative (group and channel ids
    are) and is stored as a bigint; anything but a JSON integer (a string,
    a fraction) is `400`. Why: the spec said "JSON integer, not 0".
  - `docs/CONTRACTS.md`: a PUT's `enabled` may be the strings Jackson
    coerces to a boolean ("true"); only a missing or null one is `400`. Why:
    Jackson's default; not tightened.
  - `docs/ARCHITECTURE.md`: register saves the default row in the same
    transaction as the account (and profile); the row is in the new
    `notification` package. Why: matches the spec's decision, names the package.
  - `docs/CONTRACTS.md` ("Frontend ↔ User Management"): the three
    endpoints, shapes, defaults, validation, errors, and that the user
    types the chat id after pressing Start on the bot.
  - `docs/ARCHITECTURE.md`: UM's "Owns" cell adds
    `notification_preferences`; data model paragraph for V6 (one row per
    account, written at registration and backfilled by V6, cascade with
    the account).
  - `GLOSSARY.md`: **Outside notification** (a message sent to a
    researcher by email or Telegram when an alert or assessed report
    passes that channel's thresholds; _Avoid_: external notif, push
    notification), **Notification channel** (email or Telegram),
    **Notification preferences** (a researcher's per-channel on/off,
    Telegram chat id and thresholds; _Avoid_: preferences on its own, which is
    easily taken for the profile), **Notification threshold** (the lowest
    alert severity, change severity or impact level that sends on one
    channel; a report sends when either of its levels reaches its
    threshold; _Avoid_: rule, filter).

### S2: Send outside notifications from User Management

- **Goal:** `POST /internal/users/{id}/notifications` (Interface above)
  applies each channel's thresholds and sends to each enabled channel by email
  (SMTP) and Telegram (`sendMessage`). `POST /auth/me/notifications/test`
  sends a test message. The root stack passes the mail and Telegram
  settings to User Management.
- **Blocked by:** S1
- **Branch:** `feat/external-notif-send`
- **Order on branch:** 1
- **Files:** `users/pom.xml` (`spring-boot-starter-mail`; GreenMail as a
  test dependency), `users/src/main/java/com/g4t1/users/notification/`
  (`InternalNotificationController`, `NotificationSender`, `EmailChannel`,
  `TelegramChannel`, `MessageBuilder`, `EventValidator`, and the small
  `NotificationEvent`, `NotificationOutcomes`, `Message`, `Outcome` and
  `Levels` types; the test endpoint is in `NotificationController`),
  `users/src/main/resources/application.yml`,
  `users/src/test/java/com/g4t1/users/TelegramStub.java` (an `HttpServer`
  stub like `StorageStub`),
  `users/src/test/java/com/g4t1/users/notification/SendNotificationTest.java`,
  `users/src/test/java/com/g4t1/users/notification/SendNotificationNotConfiguredTest.java`
  (its own context with no `MAIL_HOST` and no bot token: one Spring context
  can't have both),
  `users/src/test/resources/application-test.yml`, `docker-compose.yml`,
  `.env.example`, `docs/CONTRACTS.md`, `docs/ARCHITECTURE.md`,
  `docs/SETUP.md`, `docs/DEPLOY.md`, `docs/DECISIONS.md`,
  `docs/ROADMAP.md`.
- **Seams:**
  - `POST /internal/users/{id}/notifications` over HTTP, with GreenMail as
    the SMTP server and `TelegramStub` as Telegram (`TELEGRAM_API_URL`):
    catches per-channel thresholds, outcomes, message content,
    validation, auth; misses real delivery and spam folders.
  - `POST /auth/me/notifications/test` over HTTP: catches that each
    enabled channel gets the test message, and the outcomes.
- **Decisions:**
  - "POST to UM then UM build and send" (user's option A): Research
    Evaluation posts an event, User Management applies the thresholds, builds
    the message and sends it. Rejected: Research Evaluation getting the
    channels and sending itself, which would take the bot token, SMTP
    password, email address and chat id out of User Management. Doc delta
    (DECISIONS.md).
  - Thresholds per channel (S1), ordered `low` < `medium` < `high`. An
    `alerts` event passes a channel when at least one alert's severity is
    at or above its `min_alert_severity`; the message lists only those
    alerts, most severe first. A `report_assessed` event passes when
    `change_severity` is at or above `min_change_severity` **or**
    `impact_level` is at or above `min_impact_level` (user: "send if
    either exceeds"). A `null` threshold never passes; `change_severity`
    `none` (and its null `impact_level`) never passes.
  - Email: to the account email (user: "yes"), plain text, subject
    `Research Assistant: New alerts on "<title>"` or `Research Assistant:
    Report ready on "<title>"`; a missing title is `a tracked paper`
    (code: the in-app toast's fallback). From `MAIL_FROM`; SMTP from
    `MAIL_HOST`, `MAIL_PORT` (default 587), `MAIL_USERNAME`,
    `MAIL_PASSWORD`, STARTTLS on.
  - Telegram: `sendMessage` to the chat id, plain text (no `parse_mode`),
    `disable_web_page_preview` true, cut to 4096 UTF-16 units ending `…`
    (code: the removed `backend/src/research_evaluation/notify.py`, in
    git history). Its own `RestClient` with base `TELEGRAM_API_URL`
    (default `https://api.telegram.org`). The bot token is in the URL, so
    neither the URL nor the text is logged.
  - Message body. `alerts`: title, DOI, then one line per passing alert
    `<ALERT SEVERITY> <change type>: <description>`. `report_assessed`:
    title, DOI, `Change: <change_severity>`, the change summary, `Impact
    on your draft: <impact_level>`, the evaluation, `What to do:` and the
    recommendation. Then the link `<APP_URL>/papers/<paper id>` when
    `APP_URL` is set, none when it isn't (user: ok).
  - Synchronous (user: ok): User Management sends during the request and
    answers `200` with each channel's outcome only after trying both;
    Research Evaluation calls it with a 30 s timeout (S3). Doc delta
    (CONTRACTS.md). Channels are tried one
    after another, each with a 10 s timeout; one failing doesn't stop the
    other. A failed channel logs the user id, channel and status code or
    exception class only; never the text, address or chat id.
  - Test endpoint (user: ok): `POST /auth/me/notifications/test`, no
    body, sends a fixed test message to every enabled channel ignoring the
    thresholds; answers `{"channels": {...}}` with `sent`, `failed`,
    `disabled` or `not_configured`.
  - A channel with no server config (no `MAIL_HOST`, or no
    `TELEGRAM_BOT_TOKEN`) can still be saved enabled; sending gives it
    `not_configured` (user: ok).
  - Config: `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
    `MAIL_FROM`, `TELEGRAM_BOT_TOKEN`, `APP_URL`, all optional, in
    `application.yml`; the root `docker-compose.yml` passes them to
    `users`; listed in `.env.example`.
  - The in-app toast is unchanged (user: frontend out of scope).
- **Acceptance criteria:**
  - [x] `SendNotificationTest`: email `min_alert_severity` `high`, Telegram
        `min_alert_severity` `low`, both on: an `alerts` event with one `high`
        and one `low` alert sends both; GreenMail received one mail to the
        account email with the `high` line and not the `low` line; the
        stub got one `sendMessage` to the chat id with both lines, `high`
        first (`./mvnw test -Dtest=SendNotificationTest`).
  - [x] `SendNotificationTest`: an event below both channels' thresholds,
        or with `null` thresholds, gives `no_match` for both and sends
        nothing.
  - [x] `SendNotificationTest`: with `min_change_severity` `high` and
        `min_impact_level` `high`: change `high` / impact `low` sends;
        change `medium` / impact `high` sends; change `medium` / impact
        `medium` doesn't; change `none` never sends.
  - [x] `SendNotificationTest`: the `report_assessed` mail contains the
        change summary, the evaluation and the recommendation.
  - [x] `SendNotificationTest`: the stub answering `403` gives
        `telegram: failed` and `email: sent`.
  - [x] `SendNotificationTest`: a channel turned off is `disabled`; no
        `MAIL_HOST` gives `email: not_configured`.
  - [x] `SendNotificationTest`: a Telegram message over 4096 units is cut
        and ends with `…`.
  - [x] `SendNotificationTest`: `400` for an unknown event or an empty
        `alerts`; `404` `No user <id>`; `403` for a user token.
  - [x] `SendNotificationTest`: the test endpoint sends to each enabled
        channel whatever its thresholds.
  - [x] `./mvnw test` passes.
  - [x] `docker compose config` succeeds with `.env.example` copied to
        `.env` and `JWT_SECRET`, `POSTGRES_PASSWORD` filled.
- **Doc deltas:**
  - `docs/CONTRACTS.md`: an event's `400` details start with the field:
    `event must be alerts or report_assessed`, `paper is required` (`paper`
    needs an `id`), `alerts must have at least one alert`, `alert_severity
    must be low, medium or high`, `change_type and description are
    required` (each alert), `change_severity must be none, low, medium or
    high`, `change_summary is required`, `impact_level must be low, medium,
    high or null`. Why: the spec fixed only the rules, not the wording. A
    body that isn't JSON, or whose `paper.id` isn't a uuid, is Spring's
    generic `400`. The body is checked before the user is looked up, so a
    bad body for an unknown user is `400`, not `404`.
  - `docs/CONTRACTS.md`: the test endpoint answers `401` `Your account no
    longer exists` for a deleted account (like the other `/auth/me`
    handlers) and `404` `No user <id>` for an account with no preferences
    row. Why: the spec only listed the outcomes.
  - `docs/CONTRACTS.md`: Telegram counts as `sent` on any `2xx` from the bot
    API; the `ok` field of its answer isn't read, unlike the removed
    `notify.py`. Any other status, a refused connection or a timeout is
    `failed`.
  - `docs/CONTRACTS.md` and `docs/SETUP.md`: `MAIL_FROM` has no default, so with
    a `MAIL_HOST` and no `MAIL_FROM` the mail has no From and the SMTP server
    or JavaMail refuses it (`failed`, logged as `MailSendException`). Why:
    the spec's "From `MAIL_FROM`"; falling back to `MAIL_USERNAME` would be
    a guess. `not_configured` still means only "no `MAIL_HOST`".
  - `docs/SETUP.md`: SMTP authentication happens whenever `MAIL_USERNAME` and
    `MAIL_PASSWORD` are both set (JavaMail's rule), so there's no separate
    auth setting; STARTTLS is used when the server offers it (`enable`, not
    `required`), and the connect, read and write timeouts are 10 s each.
    Why: the spec said "STARTTLS on" and "10 s timeout" per channel.
  - `docs/SETUP.md`: `TELEGRAM_API_URL` exists for the tests (default
    `https://api.telegram.org`) and isn't in `.env.example` or the compose
    file, as the spec's list of seven settings leaves it out. `MAIL_PORT` is
    `587` in `.env.example` and the compose file, so an empty value in `.env`
    never reaches Spring as an empty port.
  - `docs/CONTRACTS.md`: the paper link in a message is `<APP_URL>/papers/<paper
    id>` with trailing slashes of `APP_URL` dropped. A missing title is `a
    tracked paper` in the message's first line as well as the subject; the
    DOI line is left out when `doi` is null. The report message leaves out the
    `Impact on your draft`, evaluation and `What to do:` parts that are null,
    and an `alerts` message's alerts are sorted most severe first, equal
    severities keeping the order they were posted in. Change types print as
    posted (`citation_jump`, not "citation jump").
  - `docs/CONTRACTS.md`: the test message is a fixed text: subject `Research
    Assistant: Test notification`, body `This is a test message from
    Research Assistant. If you can read it, this channel works.`; the same
    body goes to Telegram. It has no link.
  - `docs/CONTRACTS.md`: Telegram's cut keeps whole characters: when the 4095th
    unit would be the first half of an emoji, the cut is one unit earlier, so
    a cut message can be 4095 units long. Email is never cut.
  - `docs/ARCHITECTURE.md`: sending holds no database transaction; the account
    and the preferences row are read first, then the channels are tried.
  - `docs/SETUP.md`: `MAIL_PASSWORD`, like every setting in `.env`, also reaches
    Research Evaluation and Updating, which load `.env` whole (`env_file`);
    only User Management reads it. Why: the compose file's existing
    pattern, left alone.
  - `docs/CONTRACTS.md`: the Interface (new section under "Research
    Evaluation ↔ User Management"), the test endpoint, `APP_URL` in "Env
    vars every service needs to agree on".
  - `docs/ARCHITECTURE.md`: scope list adds outside notifications; data
    flow diagram and rules: Research Evaluation posts events to User
    Management, which sends email and Telegram; User Management now
    calls Telegram and an SMTP server.
  - `docs/SETUP.md`: env var rows for the seven settings; an "Email
    (SMTP)" section; a "Telegram bot" section (create it with BotFather,
    set `TELEGRAM_BOT_TOKEN`; a user presses Start on the bot and types
    their chat id).
  - `docs/DEPLOY.md`: the new settings in the app settings list; on AWS
    use port 587, since EC2 blocks outbound port 25 by default.
  - `docs/DECISIONS.md`: new entry `2026-11-11 — Outside notifications by
    email and Telegram, sent by User Management`: the user's call,
    reversing the 2026-09-28 rejection of email and the 2026-11-05
    removal of Telegram; User Management owns the preferences and the
    sending (option A, option B rejected and why); thresholds per channel, a report sending when either level reaches its threshold; the
    user types the Telegram chat id; email to the unverified account
    email; opt-in defaults written at registration; no retries; the
    in-app toast stays.
  - `docs/ROADMAP.md`: done: notifying about reports while signed out (by
    email or Telegram); open: notification history, retries, email
    verification, linking Telegram without typing a chat id.

### S3: Research Evaluation posts the two events

- **Goal:** Research Evaluation posts an `alerts` event when
  investigation opens a report, and a `report_assessed` event when impact
  has stored a report's real (not placeholder) evaluation, to the
  report's owner. A failed post never changes investigation or impact.
- **Blocked by:** none (tests against the Interface with a fake User
  Management)
- **Branch:** `feat/external-notif-research-evaluation`
- **Order on branch:** 1
- **Files:** `backend/src/research_evaluation/users.py`,
  `backend/src/research_evaluation/investigation/run.py`,
  `backend/src/research_evaluation/impact/run.py`,
  `backend/src/research_evaluation/main.py` (the UM client reaches
  investigation; 30 s timeout on this call),
  `backend/tests/research_evaluation/` (the `um` fixture in
  `conftest.py`, new tests), `docs/EVALUATION.md`, `docs/CONTRACTS.md`,
  `docs/ARCHITECTURE.md`.
- **Seams:**
  - `investigate_paper(sm, context, paper)` with fake SM and UM: catches
    the `alerts` body and that a UM failure still finishes the report;
    misses UM's filtering.
  - `assess_report(sm, context, report_id)` with fake SM and UM: catches
    the `report_assessed` body, no post for a placeholder, no post when
    the store failed, and that a UM failure still returns True.
  - `POST /evaluate/changes` through the test client: catches that one
    nudge leads to both posts, `alerts` first.
- **Decisions:**
  - The `alerts` event is posted right after `open_report`, not as each
    alert is stored: alerts are stored before the nudge's reply, which
    Updating waits for, and at that point Research Evaluation doesn't know
    the paper's owner. The opened report groups the nudge's new alerts for
    the paper (one message, not one per alert), and reading it gives
    `owner_id` (code: `Report` in `storage.py`; docs: ARCHITECTURE.md
    data flow steps 3 and 4). It happens moments after the alerts are
    stored, before any document is fetched. A missing `owner_id` skips the
    post (logged). Doc delta.
  - The `report_assessed` event is posted right after `store_evaluation`
    succeeds, with the evaluation just stored. Doc delta.
  - A placeholder evaluation is not notified: when every Gemini model
    fails a step, impact stores a fixed placeholder so the report isn't
    left unassessed (levels `low`, every text `PLACEHOLDER_TEXT`,
    `assessment.placeholder` true; code: `impact/schemas.py`; docs:
    CONTRACTS.md `POST /evaluate/changes`). It has no real content to
    send. Doc delta.
  - The `alerts` event lists every alert of the opened report; User
    Management filters per channel. Paper title and DOI come from
    `paper_details` (code: `storage.py`).
  - Sent only when a UM client exists; `None` sends nothing, as in the
    evals (code: `ImpactContext.um`).
  - "Background work never raises and is never retried" (docs:
    ARCHITECTURE.md): a failed post logs `notifying user <id> about
    report <id> failed: <cause>`, with the status code or exception class
    only.
- **Acceptance criteria:**
  - [x] A test shows an opened report posts one `alerts` event to
        `/internal/users/<owner id>/notifications` with the report's
        alerts (`uv run pytest tests/research_evaluation/investigation`).
  - [x] A test shows UM answering `500` still ends the report
        `investigated`.
  - [x] A test shows an assessed report posts one `report_assessed` event
        carrying the stored `change_severity`, `change_summary`,
        `impact_level`, `evaluation` and `recommendation`; a placeholder
        evaluation posts none (`uv run pytest tests/research_evaluation/impact`).
  - [x] A test shows UM timing out still returns True from
        `assess_report`.
  - [x] `uv run pytest` passes.
  - [x] `uv run ruff check` passes.
- **Doc deltas:**
  - `docs/EVALUATION.md`: the flow gains the two posts and when each is
    sent; replace "Research Evaluation sends no notification" with what it
    posts, that a placeholder isn't posted, and that User Management
    decides and sends.
  - `docs/CONTRACTS.md` (`POST /evaluate/changes`, "After the reply"):
    the same replacement.
  - `docs/ARCHITECTURE.md` data flow step 4: the same replacement.
  - `docs/EVALUATION.md` (Investigation): the alerts post reads the report again (`GET /internal/reports/{id}`) for `owner_id` and the alerts' ids, types, severities and descriptions, because the opened report only carries change keys, and reads the newest snapshot (`paper_details`) for title and DOI. Why: the Interface needs both and the opened report has neither.
  - `docs/EVALUATION.md`: failing to read the report or paper for a post is logged (`reading report <id> for its notification failed: <cause>`) and also changes nothing, as a failed post doesn't. Why: the reads exist only for the post, and a Storage Management blip there must not leave a report `investigating`.
  - `docs/EVALUATION.md`: a report whose owner is unknown is logged (`report <id> has no owner: its alerts are not posted`) at INFO, not WARNING. Why: it is a skip the spec asks for, not a failure.
  - `docs/EVALUATION.md`: the `report_assessed` body is built from the report Storage Management returns from the evaluation PUT, not from the model's draft. Why: the spec says the evaluation as stored.
  - `docs/EVALUATION.md`: a placeholder is recognised by `assessment.placeholder` in the stored report. Why: the flag is the documented marker, so no text comparison.
  - `docs/EVALUATION.md`: an `impact_level` of `none` is sent as null. Why: User Management's interface accepts only `low`, `medium`, `high` or null for it and treats null the same.
  - `docs/CONTRACTS.md`: the 30 s timeout is set on the post itself (`users.notify`), so the shared 10 s User Management client in `main.py` keeps its timeout for profile reads; `main.py` only passes that client to investigation. Why: one client, two timeouts.
  - `docs/CONTRACTS.md`: the failure log gives `User Management answered <status>` or the exception class name, never a body. Why: spec says status code or exception class only.
  - Events go through the User Management client both stages already share (`InvestigationContext.um`, new, and `ImpactContext.um`); the `um` test client now records posted notifications and `client` gives investigation that client. Why: no second client to configure.

## Human checks

- [ ] **S2** — With a real bot token and SMTP settings in `.env`, run the
  root stack, sign in, press Start on the bot, get your chat id from
  `@userinfobot`, `PUT /auth/me/notifications/telegram` with it and
  `enabled` true, `PUT /auth/me/notifications/email` with `enabled` true,
  then `POST /auth/me/notifications/test`: the mail arrives (check spam),
  the Telegram message arrives, both outcomes are `sent`.
- [ ] **S3** — With both channels allowing `high`, raise a retraction on
  a tracked paper (dev mode, SETUP.md "Dev mode: triggering a change") and
  poll: a "New alerts" message arrives on both channels, then a "Report
  ready" message after impact; the link opens the paper's page.
