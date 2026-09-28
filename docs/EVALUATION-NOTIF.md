# Telling the researcher an evaluation is done (plan)

**Status: done (S1–S2), 2026-09-28, each subtask verified.** Work
happened on `feat/eval-notif`; see "Codebase context" for what was built
and where.

For the demo, the researcher should hear about it when Research Evaluation
has finished judging a change, without having to open the platform: a
message on their phone with what changed, how it affects their draft and
what to do. It follows on from
[EVALUATION-IMPACT.md](EVALUATION-IMPACT.md): the moment impact stores a
report's evaluation (the report becomes `assessed`) is "evaluation done".

## The goal

- When impact stores a report's evaluation, send **one Telegram message**
  to **one hard-coded chat** (`NOTIFY_TELEGRAM_CHAT_ID`), whoever owns the
  paper. There's no User Management yet, so there's no user to look up;
  the chat is the demo's researcher.
- The message is plain text: the report id, the paper's title and DOI, the
  change's severity and summary, the impact level on the draft, the
  evaluation and the recommendation. A report rated `none` (not
  meaningful) gets a short message: the summary and "Nothing to do."
  The placeholder evaluation stored when every Gemini model failed
  ([EVAL-GEM-FAILSAFE.md](EVAL-GEM-FAILSAFE.md)) is sent like any other:
  `low` change and impact, and its fixed text as summary, evaluation and
  recommendation.
- A notification never changes what impact does. It's sent only after the
  evaluation is stored, and a failure to send is logged and nothing more:
  the report stays `assessed`.
- Without `TELEGRAM_BOT_TOKEN` and `NOTIFY_TELEGRAM_CHAT_ID` set,
  notifications are off and nothing else changes.

```
... impact ─▶ PUT the evaluation (SM) ─▶ assessed ─▶ Telegram sendMessage ─▶ the researcher's phone
                                                    (failure: one log line, nothing else)
```

## Why Telegram

The demo needs a message that reaches a phone, not a production channel.
A Telegram bot is one HTTPS call (`POST /bot<token>/sendMessage`) with no
account beyond the bot, no SMTP server or app password, and no spam
folder; the message pops up on the phone during the demo. Email would need
SMTP credentials and could land in spam. See DECISIONS.md, 2026-09-28.

## Subtasks

| | Goal | State |
|---|---|---|
| S1 | `TelegramNotifier`: builds the message for an assessed report and sends it to the configured chat, never raising and never logging the token or the text; the two settings and `telegram_configured` | done |
| S2 | `assess_report` notifies after the evaluation is stored (never when a report is skipped or fails); `create_app` builds the notifier with its own client when both variables are set | done |

## Setting it up

1. In Telegram, message **@BotFather**, send `/newbot`, pick a name and a
   username. It answers with the bot's token (`123456:ABC...`): that's
   `TELEGRAM_BOT_TOKEN`.
2. Open a chat with your new bot and send it any message (a bot can only
   write to someone who wrote to it first).
3. Open `https://api.telegram.org/bot<token>/getUpdates` in a browser.
   The chat id is `result[0].message.chat.id` (a number):
   `NOTIFY_TELEGRAM_CHAT_ID`. For a group, add the bot to the group, send
   a message there, and use the group's id (negative).
4. Put both in `backend/.env` (see [SETUP.md](SETUP.md)). To check them
   without running a pipeline, from `backend/`:
   `py -m uv run pytest -m live tests/research_evaluation/test_notify.py`
   sends one sample message (skipped when either variable is missing).

## Codebase context

| What | Where |
|---|---|
| The notifier | `backend/src/research_evaluation/notify.py`: `TelegramNotifier(http, token, chat_id).report_assessed(report, paper, evaluation)` returns `True` once Telegram accepted the message, `False` (logged) otherwise, and never raises |
| The message | `notify.message(report, paper, evaluation)`, a pure function; `Report` and `PaperDetails` are from `research_evaluation/storage.py`, `Evaluation` (the body impact `PUT`s) from `impact/schemas.py` |
| "Is it configured?" | `notify.telegram_configured(settings)`: both variables set and not blank (whitespace only counts as unset) |
| Settings | `ResearchEvaluationSettings.telegram_bot_token` (`SecretStr`, optional) and `notify_telegram_chat_id` (default empty) in `research_evaluation/config.py` |
| Where it's called | `impact/run.py` `assess_report`: after `store_evaluation` succeeds, `context.notifier.report_assessed(report, inputs.paper, evaluation)`, outside the `try`, so a skipped or failed report returns before it and the return value is `True` whatever Telegram does |
| Where it's built | `ImpactContext.notifier` (optional, default `None`) in `impact/run.py`; `main.create_app`'s lifespan makes it with its own `httpx.AsyncClient` (the `telegram` client) when `telegram_configured`, stripping both values. It hangs off the impact context, so without `GEMINI_API_KEY` there's no impact and nothing to notify |
| Tests | `backend/tests/research_evaluation/test_notify.py` (message and sending, Telegram faked with `httpx.MockTransport`; startup wiring; one `live` test), settings in `test_config.py`, end to end in `impact/test_run.py` (nudge → assessed → one message; `none`; failing, unstored and skipped reports send nothing; Telegram failing leaves the report assessed) and `impact/test_trigger.py` (`POST /evaluate/reports` notifies). The fake is `FakeTelegram` in `tests/research_evaluation_support.py`, the `telegram` fixture in `tests/research_evaluation/conftest.py` |

### Gotchas

- **The bot token is in the URL.** Nothing of ours logs the URL, and a
  failure logs only the status code or the exception class (an
  `InvalidURL`'s message, or a traceback, could carry the URL). But
  **httpx's own INFO log line prints every request's URL**: the app keeps
  the `httpx` logger at WARNING (`main.configure_logging`), and that must
  stay so, or the token lands in the logs.
- **The message text isn't logged either.** It's the model's judgment of
  the researcher's draft, which EVALUATION-IMPACT.md keeps out of logs.
- **Plain text, no `parse_mode`.** The summary and evaluation are the
  model's words, which quote notices written by anyone; as Markdown or
  HTML a stray `<` or `_` would make Telegram refuse the message, or
  format it in ways nobody meant.
- **4096 is Telegram's limit, in UTF-16 code units** (an emoji counts
  2). Longer messages are cut to 4095 units plus `…`, never through the
  middle of an emoji.
- **The client must be its own**, with no base URL and no auth, like
  investigation's: the Storage Management client carries the service
  token, which must never reach Telegram.
- **One message per report, in the same loop.** The notification is
  awaited before the next report is assessed, so a slow Telegram adds up
  to 10 s (`notify.HTTP_TIMEOUT_SECONDS`) per report. Fine for the demo;
  move it off the loop if reports are ever assessed in bulk.
- A bot can't start a conversation: if the chat never wrote to the bot,
  Telegram answers `403` and the notification is logged as failed.
