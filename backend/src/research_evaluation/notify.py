"""Tells the researcher on Telegram that a report's evaluation is done (docs/EVALUATION-NOTIF.md).

For the demo the recipient is hard-coded: every assessed report goes to one chat,
NOTIFY_TELEGRAM_CHAT_ID, through the bot TELEGRAM_BOT_TOKEN. There's no user lookup yet.

Sending never raises: a notification that fails is logged and that's it, the evaluation is
already stored. The bot token is part of the request's URL, so neither the URL nor the message
(the model's text about the researcher's draft) is ever logged."""

import logging

import httpx

from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.impact.schemas import Evaluation
from research_evaluation.storage import PaperDetails, Report

TELEGRAM_API = "https://api.telegram.org"
# Telegram refuses longer messages; it counts UTF-16 code units
MAX_MESSAGE_LENGTH = 4096
HTTP_TIMEOUT_SECONDS = 10

log = logging.getLogger(__name__)


def telegram_configured(settings: ResearchEvaluationSettings) -> bool:
    return (
        settings.telegram_bot_token is not None
        and bool(settings.telegram_bot_token.get_secret_value())
        and bool(settings.notify_telegram_chat_id)
    )


def message(report: Report, paper: PaperDetails, evaluation: Evaluation) -> str:
    """The notification for an assessed report, as plain text."""
    lines = [f"Evaluation done: report {report.id}", f"Paper: {paper.title or '(no title)'}"]
    if paper.doi:
        lines.append(f"DOI: {paper.doi}")
    if evaluation.change_severity == "none":
        lines += [
            "",
            "Change: none (not meaningful)",
            evaluation.change_summary,
            "",
            "Nothing to do.",
        ]
        return _truncate("\n".join(lines))
    lines += ["", f"Change: {evaluation.change_severity}", evaluation.change_summary]
    if evaluation.impact_level is not None:
        lines += ["", f"Impact on your draft: {evaluation.impact_level}"]
    if evaluation.evaluation:
        lines.append(evaluation.evaluation)
    if evaluation.recommendation:
        lines += ["", "What to do:", evaluation.recommendation]
    return _truncate("\n".join(lines))


def _truncate(text: str) -> str:
    """Cuts the text to Telegram's limit, ending with '…' when cut."""
    units = text.encode("utf-16-le")
    if len(units) <= MAX_MESSAGE_LENGTH * 2:
        return text
    # an odd cut would split a surrogate pair: errors="ignore" drops the half
    kept = units[: (MAX_MESSAGE_LENGTH - 1) * 2].decode("utf-16-le", errors="ignore")
    return kept + "…"


class TelegramNotifier:
    """Sends notifications to the one configured chat. `http` must be its own client: no base
    URL and no auth, so the service token can never reach Telegram."""

    def __init__(self, http: httpx.AsyncClient, token: str, chat_id: str) -> None:
        self._http = http
        self._token = token
        self._chat_id = chat_id

    async def report_assessed(
        self, report: Report, paper: PaperDetails, evaluation: Evaluation
    ) -> bool:
        """True once Telegram accepted the message; False, logged, when it didn't. Never raises."""
        try:
            response = await self._http.post(
                f"{TELEGRAM_API}/bot{self._token}/sendMessage",
                # plain text: no parse_mode, so nothing the model or a notice wrote is markup
                json={
                    "chat_id": self._chat_id,
                    "text": message(report, paper, evaluation),
                    "disable_web_page_preview": True,
                },
                timeout=HTTP_TIMEOUT_SECONDS,
            )
            if response.is_success and _ok(response):
                return True
            cause = f"Telegram answered {response.status_code}"
        # a notification must never fail impact; only the class is logged, as a message (an
        # InvalidURL, say) or a traceback could carry the URL, and so the token
        except Exception as exc:  # noqa: BLE001
            cause = f"the request failed ({type(exc).__name__})"
        log.warning("notifying report %s failed: %s", report.id, cause)
        return False


def _ok(response: httpx.Response) -> bool:
    try:
        body = response.json()
    except ValueError:
        return False
    return isinstance(body, dict) and body.get("ok") is True
