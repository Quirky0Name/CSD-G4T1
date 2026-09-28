"""The Telegram notifier: the message for an assessed report and how it's sent, with Telegram
faked by a MockTransport, and how startup builds it. Impact calling it is in impact/test_run.py."""

import json
import logging
from uuid import UUID

import httpx
import pytest
from fastapi.testclient import TestClient
from support import TEST_JWT_SECRET

from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.impact.schemas import Evaluation
from research_evaluation.main import create_app
from research_evaluation.notify import (
    MAX_MESSAGE_LENGTH,
    TelegramNotifier,
    message,
    telegram_configured,
)
from research_evaluation.storage import PaperDetails, Report

TOKEN = "123456:secret-bot-token"
CHAT = "987654321"
REPORT = Report(
    id=12, paper_id=UUID("11111111-1111-1111-1111-111111111111"), status="assessed",
    alerts=[], documents=[],
)
PAPER = PaperDetails(
    doi="10.1016/j.ijantimicag.2020.105949",
    title="Hydroxychloroquine and azithromycin as a treatment of COVID-19",
)
MEANINGFUL = Evaluation(
    change_summary="The paper was retracted for methodological concerns.",
    change_severity="high",
    impact_level="medium",
    evaluation="Your Discussion relies on its viral-clearance result.",
    recommendation="Replace the citation in your Discussion.",
    assessment={},
)
NOT_MEANINGFUL = Evaluation(
    change_summary="An author's affiliation was corrected.",
    change_severity="none",
    assessment={},
)


def utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


# the message (Telegram itself is conftest's `telegram`, a FakeTelegram)


def test_a_meaningful_change_names_the_paper_and_every_part_of_the_evaluation():
    text = message(REPORT, PAPER, MEANINGFUL)

    assert text == (
        "Evaluation done: report 12\n"
        "Paper: Hydroxychloroquine and azithromycin as a treatment of COVID-19\n"
        "DOI: 10.1016/j.ijantimicag.2020.105949\n"
        "\n"
        "Change: high\n"
        "The paper was retracted for methodological concerns.\n"
        "\n"
        "Impact on your draft: medium\n"
        "Your Discussion relies on its viral-clearance result.\n"
        "\n"
        "What to do:\n"
        "Replace the citation in your Discussion."
    )


def test_a_change_rated_none_says_it_isnt_meaningful_and_nothing_else():
    text = message(REPORT, PAPER, NOT_MEANINGFUL)

    assert text == (
        "Evaluation done: report 12\n"
        "Paper: Hydroxychloroquine and azithromycin as a treatment of COVID-19\n"
        "DOI: 10.1016/j.ijantimicag.2020.105949\n"
        "\n"
        "Change: none (not meaningful)\n"
        "An author's affiliation was corrected.\n"
        "\n"
        "Nothing to do."
    )


def test_a_paper_without_title_or_doi_still_gets_a_message():
    text = message(REPORT, PaperDetails(), MEANINGFUL)

    assert text.startswith("Evaluation done: report 12\nPaper: (no title)\n\nChange: high\n")
    assert "DOI:" not in text


def test_a_message_over_telegrams_limit_is_cut_and_ends_with_an_ellipsis():
    long = MEANINGFUL.model_copy(update={"evaluation": "x" * 5000})

    text = message(REPORT, PAPER, long)

    assert utf16_length(text) == MAX_MESSAGE_LENGTH
    assert text.endswith("x…")
    assert text.startswith("Evaluation done: report 12\n")


def test_the_limit_counts_utf16_units_and_never_splits_an_emoji():
    # the text up to the evaluation, which starts with `pad`; emoji are 2 units each, so a pad
    # of the right parity puts the cut (after MAX - 1 units) in the middle of one
    before = message(REPORT, PAPER, MEANINGFUL.model_copy(update={"evaluation": "@"}))
    units_before = utf16_length(before[: before.index("\n@") + 1])
    pad = "a" if (MAX_MESSAGE_LENGTH - 1 - units_before) % 2 == 0 else ""
    long = MEANINGFUL.model_copy(update={"evaluation": pad + "😀" * 3000})

    text = message(REPORT, PAPER, long)

    assert (MAX_MESSAGE_LENGTH - 1 - units_before - len(pad)) % 2 == 1  # the cut splits one
    assert utf16_length(text) == MAX_MESSAGE_LENGTH - 1  # the half is dropped, then "…"
    assert text.endswith("😀…")
    text.encode("utf-8")  # no lone surrogate left behind


def test_a_message_at_the_limit_is_left_alone():
    base = message(REPORT, PAPER, MEANINGFUL.model_copy(update={"recommendation": ""}))
    fill = MAX_MESSAGE_LENGTH - utf16_length(base) - len("\n\nWhat to do:\n")
    exact = MEANINGFUL.model_copy(update={"recommendation": "y" * fill})

    text = message(REPORT, PAPER, exact)

    assert utf16_length(text) == MAX_MESSAGE_LENGTH
    assert text.endswith("y")


# sending


async def test_it_posts_the_message_as_plain_text_to_the_configured_chat(telegram):
    notifier = TelegramNotifier(telegram.client, TOKEN, CHAT)

    assert await notifier.report_assessed(REPORT, PAPER, MEANINGFUL) is True

    [request] = telegram.requests
    assert request.method == "POST"
    assert str(request.url) == f"https://api.telegram.org/bot{TOKEN}/sendMessage"
    body = json.loads(request.content)
    assert body["chat_id"] == CHAT
    assert body["text"] == message(REPORT, PAPER, MEANINGFUL)
    assert "parse_mode" not in body
    assert "authorization" not in request.headers


@pytest.mark.parametrize(
    "answer",
    [
        httpx.Response(500, text="oops"),
        httpx.Response(403, json={"ok": False, "description": "bot was blocked by the user"}),
        httpx.Response(200, json={"ok": False}),
        httpx.Response(200, text="not json"),
        httpx.Response(200, json=["ok"]),
        httpx.ReadTimeout("timed out"),
        httpx.ConnectError("connection refused"),
    ],
    ids=["500", "403", "ok false", "not json", "not an object", "timeout", "connect error"],
)
async def test_a_failure_returns_false_and_logs_neither_the_token_nor_the_text(
    telegram, caplog, answer
):
    telegram.response = answer
    notifier = TelegramNotifier(telegram.client, TOKEN, CHAT)

    # our loggers only: httpx's own INFO line has the URL, which is why the app keeps httpx at
    # WARNING (main.configure_logging)
    with caplog.at_level(logging.DEBUG, logger="research_evaluation"):
        assert await notifier.report_assessed(REPORT, PAPER, MEANINGFUL) is False

    ours = [r for r in caplog.records if r.name.startswith("research_evaluation")]
    [record] = ours
    assert record.name == "research_evaluation.notify"
    assert record.levelno == logging.WARNING
    assert "notifying report 12 failed" in record.getMessage()
    assert record.exc_info is None
    assert TOKEN not in record.getMessage()
    assert "retracted for methodological concerns" not in record.getMessage()


# startup


@pytest.fixture
def app_env(tmp_path, monkeypatch, clean_settings_env):
    """No .env and none of the developer's variables; a JWT secret and a Gemini key, so impact
    runs and the notifier depends only on the Telegram variables."""
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    return monkeypatch


def test_startup_gives_impact_a_notifier_with_its_own_client_without_auth(app_env):
    app_env.setenv("TELEGRAM_BOT_TOKEN", f" {TOKEN} ")
    app_env.setenv("NOTIFY_TELEGRAM_CHAT_ID", f"{CHAT}\n")
    app = create_app()

    with TestClient(app):
        notifier = app.state.impact.notifier
        assert isinstance(notifier, TelegramNotifier)
        # Telegram only: never the service token, never Storage Management's URL, and not
        # investigation's client either
        http = notifier._http
        assert http is not app.state.sm
        assert http is not app.state.investigation.external
        assert http.auth is None
        assert str(http.base_url) == ""
        assert (notifier._token, notifier._chat_id) == (TOKEN, CHAT)


@pytest.mark.parametrize(
    "variables",
    [
        {},
        {"TELEGRAM_BOT_TOKEN": TOKEN},
        {"NOTIFY_TELEGRAM_CHAT_ID": CHAT},
        {"TELEGRAM_BOT_TOKEN": "  ", "NOTIFY_TELEGRAM_CHAT_ID": CHAT},
        {"TELEGRAM_BOT_TOKEN": TOKEN, "NOTIFY_TELEGRAM_CHAT_ID": " "},
    ],
    ids=["neither", "token only", "chat only", "blank token", "blank chat"],
)
def test_startup_leaves_notifications_off_unless_both_variables_are_set(app_env, variables):
    for name, value in variables.items():
        app_env.setenv(name, value)
    app = create_app()

    with TestClient(app):
        assert app.state.impact is not None
        assert app.state.impact.notifier is None


def test_without_a_gemini_key_there_is_nothing_to_notify(app_env):
    app_env.delenv("GEMINI_API_KEY")
    app_env.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    app_env.setenv("NOTIFY_TELEGRAM_CHAT_ID", CHAT)
    app = create_app()

    with TestClient(app):
        assert app.state.impact is None


@pytest.mark.live
async def test_live_telegram_delivers_a_notification():
    settings = ResearchEvaluationSettings(jwt_secret=TEST_JWT_SECRET)
    if not telegram_configured(settings):
        pytest.skip("TELEGRAM_BOT_TOKEN or NOTIFY_TELEGRAM_CHAT_ID is not set")
    async with httpx.AsyncClient() as http:
        notifier = TelegramNotifier(
            http, settings.telegram_bot_token.get_secret_value(), settings.notify_telegram_chat_id
        )

        assert await notifier.report_assessed(REPORT, PAPER, MEANINGFUL) is True
