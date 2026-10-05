"""Impact after a nudge, end to end: Research Evaluation against the stub Storage Management,
with Crossref and Europe PMC faked (conftest.ExternalApis) and a fake model in Gemini's place."""

import json
import logging
from uuid import UUID

import httpx
import pytest
from google.genai import errors as genai_errors
from pydantic import ValidationError
from research_evaluation_support import notice, nudge

from research_evaluation.impact.llm import AllModelsFailed, Answer, ModelFailure
from research_evaluation.impact.run import ImpactContext, assess_reports
from research_evaluation.impact.schemas import (
    PLACEHOLDER_TEXT,
    ChangeAssessment,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.notify import TelegramNotifier

PAPER_DOI = "10.1016/j.ijantimicag.2020.105949"  # every test snapshot's DOI
NOTICE = "10.1016/j.ijantimicag.2024.107416"
DRAFT = b"%PDF-1.7 the researcher's draft"


def change(severity: str) -> ChangeAssessment:
    return ChangeAssessment.model_validate(
        {
            "alerts": [],
            "relations": None,
            "severity": severity,
            "summary": f"A {severity} change.",
        }
    )


IMPACT = ImpactAssessment.model_validate(
    {
        "cited": True,
        "reference_entry": "[1] Gautret P, et al.",
        "uses": [],
        "impact_level": "medium",
        "explanation": "Your Discussion relies on it.",
    }
)
ACTIONS = RecommendedActions.model_validate(
    {"actions": [{"action": "Replace the citation.", "where": None}], "recommendation": "Replace it."}
)


def schema_error() -> ValidationError:
    try:
        ChangeAssessment.model_validate({})
    except ValidationError as error:
        return error
    raise AssertionError("unreachable")


class FakeLlm:
    """Answers by schema; `fail_on` makes the call with that number (1-based, across the whole
    run) raise `failure` instead."""

    model = "fake-gemini"

    def __init__(self, severity: str = "high", fail_on: int | None = None, failure=None):
        self.answers = {
            ChangeAssessment: change(severity),
            ImpactAssessment: IMPACT,
            RecommendedActions: ACTIONS,
        }
        self.fail_on = fail_on
        self.failure = failure
        self.calls: list[type] = []

    async def generate(self, *, system, parts, schema):
        self.calls.append(schema)
        if len(self.calls) == self.fail_on:
            raise self.failure
        return Answer(self.answers[schema], "fake-gemini-001")


async def retracted_paper(sm, draft: bool = True) -> UUID:
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(
        paper, 2, is_retracted=True, crossref_updates=[notice(NOTICE, "retraction")]
    )
    await sm.client.post(f"/dev/papers/{paper}/pdf", content=b"%PDF-1.7 stored paper")
    if draft:
        await sm.client.post(f"/dev/papers/{paper}/research-paper", content=DRAFT)
    return paper


async def the_report(sm, paper: UUID) -> dict:
    [report_id] = {alert["report_id"] for alert in await sm.alerts(paper)}
    response = await sm.client.get(f"/internal/reports/{report_id}")
    response.raise_for_status()
    return response.json()


@pytest.fixture(autouse=True)
def crossref_knows_the_notice(external):
    external.crossref[NOTICE] = "ijaa_notice"


async def test_a_new_retraction_ends_assessed_with_the_models_answers(client, sm, impact):
    llm = FakeLlm("high")
    impact.context = ImpactContext(llm)
    paper = await retracted_paper(sm)

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json()["alerts_created"] == 1
    report = await the_report(sm, paper)
    assert report["status"] == "assessed"
    assert report["change_summary"] == "A high change."
    assert report["change_severity"] == "high"
    assert report["impact_level"] == "medium"
    assert report["evaluation"] == "Your Discussion relies on it."
    assert report["recommendation"] == "Replace it."
    assert report["assessment"]["draft"] == "read"
    assert report["assessment"]["actions"] == ACTIONS.model_dump(mode="json")
    assert llm.calls == [ChangeAssessment, ImpactAssessment, RecommendedActions]
    assert sm.calls("GET", "/research-paper") == 1


async def test_a_change_rated_none_stores_the_summary_and_severity_only(client, sm, impact):
    llm = FakeLlm("none")
    impact.context = ImpactContext(llm)
    paper = await retracted_paper(sm)

    await nudge(client, [paper])

    report = await the_report(sm, paper)
    assert report["status"] == "assessed"
    assert (report["change_summary"], report["change_severity"]) == ("A none change.", "none")
    assert report["impact_level"] is None
    assert report["evaluation"] is None and report["recommendation"] is None
    assert llm.calls == [ChangeAssessment]
    assert sm.calls("GET", "/research-paper") == 0


async def test_without_a_gemini_key_reports_stay_investigated(client, sm, impact):
    assert impact.context is None
    paper = await retracted_paper(sm)

    response = await nudge(client, [paper])

    assert response.status_code == 202
    report = await the_report(sm, paper)
    assert report["status"] == "investigated"
    assert report["change_summary"] is None
    assert sm.calls("GET", "/research-paper") == 0
    assert sm.calls("PUT", "/evaluation") == 0


@pytest.mark.parametrize("step", [1, 2, 3])
@pytest.mark.parametrize(
    "failure",
    [
        ValueError("the model returned no text"),
        schema_error(),
        genai_errors.ServerError(503, {"error": {"code": 503, "status": "UNAVAILABLE"}}),
        httpx.ReadTimeout("timed out"),
    ],
    ids=["no text", "off schema", "gemini 503", "timeout"],
)
async def test_a_failing_report_stores_nothing_and_the_next_is_still_assessed(
    client, sm, impact, step, failure
):
    impact.context = ImpactContext(FakeLlm("high", fail_on=step, failure=failure))
    first = await retracted_paper(sm)
    second = await retracted_paper(sm)

    response = await nudge(client, [first, second])

    assert response.status_code == 202
    assert response.json()["alerts_created"] == 2
    failed = await the_report(sm, first)
    assert failed["status"] == "investigated"
    assert failed["change_summary"] is None and failed["assessment"] is None
    assert (await the_report(sm, second))["status"] == "assessed"


async def test_storage_management_failing_on_the_put_is_logged_not_raised(
    client, sm, impact, caplog
):
    impact.context = ImpactContext(FakeLlm("high"))
    sm.transport.faults["/evaluation"] = 500
    paper = await retracted_paper(sm)

    with caplog.at_level(logging.WARNING, logger="research_evaluation.impact.run"):
        response = await nudge(client, [paper])

    assert response.status_code == 202
    report = await the_report(sm, paper)
    assert report["status"] == "investigated"
    [record] = [r for r in caplog.records if r.name == "research_evaluation.impact.run"]
    assert f"assessing report {report['id']} failed" in record.getMessage()
    assert "Storage Management answered 500" in record.getMessage()
    assert "injected" not in record.getMessage()  # no response body


async def test_a_conflict_on_the_put_is_logged_not_raised(client, sm, impact, caplog):
    impact.context = ImpactContext(FakeLlm("high"))
    sm.transport.faults["/evaluation"] = httpx.Response(
        409, json={"detail": "Report 1 is already assessed"}
    )
    paper = await retracted_paper(sm)

    with caplog.at_level(logging.WARNING, logger="research_evaluation.impact.run"):
        response = await nudge(client, [paper])

    assert response.status_code == 202
    assert any("answered 409" in r.getMessage() for r in caplog.records)


async def test_a_gemini_error_is_logged_with_its_code_only(client, sm, impact, caplog):
    failure = genai_errors.ServerError(
        503, {"error": {"code": 503, "status": "UNAVAILABLE", "message": "secret prompt text"}}
    )
    impact.context = ImpactContext(FakeLlm("high", fail_on=1, failure=failure))
    paper = await retracted_paper(sm)

    with caplog.at_level(logging.WARNING, logger="research_evaluation.impact.run"):
        await nudge(client, [paper])

    [record] = [r for r in caplog.records if r.name == "research_evaluation.impact.run"]
    assert "Gemini answered 503 (UNAVAILABLE)" in record.getMessage()
    assert "secret prompt text" not in record.getMessage()


async def test_the_nudge_reply_is_the_same_whatever_impact_does(client, sm, impact):
    impact.context = ImpactContext(FakeLlm("high", fail_on=1, failure=ValueError("boom")))
    paper = await retracted_paper(sm)
    # this paper's evaluation fails, so the reply is a 503; the other paper is evaluated
    sm.transport.faults[f"/{paper}/alerts/change-keys"] = 500
    other = await retracted_paper(sm)

    response = await nudge(client, [paper, other])

    assert response.status_code == 503
    assert response.json()["failed_paper_ids"] == [str(paper)]
    assert response.json()["alerts_created"] == 1
    # the fake's first call fails the other paper's report: impact failing changes no reply
    assert (await the_report(sm, other))["status"] == "investigated"


async def test_only_investigated_reports_are_assessed(sm):
    llm = FakeLlm("high")
    context = ImpactContext(llm)
    paper = await sm.add_paper()
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "retraction",
            "change_key": "retraction",
            "severity": "high",
            "description": "d",
            "recommendation": "r",
            "notice_doi": None,
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 2,
            "previous_snapshot_id": 1,
        },
    )
    investigating = (await sm.client.post(f"/internal/papers/{paper}/reports")).json()["id"]

    assert await assess_reports(sm.client, context, [investigating, 999]) == []
    assert llm.calls == []
    # read, then skipped: no PDF fetched for either
    assert sm.calls("GET", "/pdf") == 0

    await sm.client.patch(
        f"/internal/papers/{paper}/reports/{investigating}", json={"status": "investigated"}
    )
    assert await assess_reports(sm.client, context, [investigating]) == [investigating]
    calls = len(llm.calls)
    # already assessed: skipped after the read, the model never called again
    assert await assess_reports(sm.client, context, [investigating]) == []
    assert len(llm.calls) == calls


# notifying the researcher (docs/EVALUATION.md, "Notification"), with Telegram faked


TITLE = "Hydroxychloroquine and azithromycin as a treatment of COVID-19"  # the test snapshots'


def notifier(telegram) -> TelegramNotifier:
    return TelegramNotifier(telegram.client, "123456:test-token", "987654321")


async def investigated_report(sm) -> int:
    """A report of a new paper (with snapshots, as impact reads the paper's details from them),
    marked investigated without running investigation."""
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "retraction",
            "change_key": "retraction",
            "severity": "high",
            "description": "d",
            "recommendation": "r",
            "notice_doi": None,
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 2,
            "previous_snapshot_id": 1,
        },
    )
    report_id = (await sm.client.post(f"/internal/papers/{paper}/reports")).json()["id"]
    await sm.client.patch(
        f"/internal/papers/{paper}/reports/{report_id}", json={"status": "investigated"}
    )
    return report_id


async def test_an_assessed_report_is_notified_once_with_its_evaluation(
    client, sm, impact, telegram
):
    impact.context = ImpactContext(FakeLlm("high"), notifier(telegram))
    paper = await retracted_paper(sm)

    response = await nudge(client, [paper])

    assert response.status_code == 202
    report = await the_report(sm, paper)
    assert report["status"] == "assessed"
    [text] = telegram.texts()
    assert text == (
        f"Evaluation done: report {report['id']}\n"
        f"Paper: {TITLE}\n"
        f"DOI: {PAPER_DOI}\n"
        "\n"
        "Change: high\n"
        "A high change.\n"
        "\n"
        "Impact on your draft: medium\n"
        "Your Discussion relies on it.\n"
        "\n"
        "What to do:\n"
        "Replace it."
    )
    [request] = telegram.requests
    assert request.url.path == "/bot123456:test-token/sendMessage"
    assert json.loads(request.content)["chat_id"] == "987654321"


async def test_a_report_rated_none_gets_the_short_notification(client, sm, impact, telegram):
    impact.context = ImpactContext(FakeLlm("none"), notifier(telegram))
    paper = await retracted_paper(sm)

    await nudge(client, [paper])

    report = await the_report(sm, paper)
    [text] = telegram.texts()
    assert text.startswith(f"Evaluation done: report {report['id']}\n")
    assert text.endswith("Change: none (not meaningful)\nA none change.\n\nNothing to do.")


async def test_a_failing_report_is_not_notified_and_the_next_one_is(client, sm, impact, telegram):
    # the first report's first model call fails; the second report is assessed
    impact.context = ImpactContext(
        FakeLlm("high", fail_on=1, failure=ValueError("boom")), notifier(telegram)
    )
    first = await retracted_paper(sm)
    second = await retracted_paper(sm)

    await nudge(client, [first, second])

    assert (await the_report(sm, first))["status"] == "investigated"
    assessed = await the_report(sm, second)
    assert assessed["status"] == "assessed"
    [text] = telegram.texts()
    assert text.startswith(f"Evaluation done: report {assessed['id']}\n")


async def test_a_report_whose_evaluation_isnt_stored_is_not_notified(client, sm, impact, telegram):
    impact.context = ImpactContext(FakeLlm("high"), notifier(telegram))
    sm.transport.faults["/evaluation"] = 500
    paper = await retracted_paper(sm)

    await nudge(client, [paper])

    assert (await the_report(sm, paper))["status"] == "investigated"
    assert telegram.requests == []


async def test_skipped_reports_are_not_notified(sm, telegram):
    context = ImpactContext(FakeLlm("high"), notifier(telegram))
    ready = await investigated_report(sm)
    assert await assess_reports(sm.client, context, [ready]) == [ready]
    telegram.requests.clear()
    paper = await sm.add_paper()
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "retraction",
            "change_key": "retraction",
            "severity": "high",
            "description": "d",
            "recommendation": "r",
            "notice_doi": None,
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 2,
            "previous_snapshot_id": 1,
        },
    )
    investigating = (await sm.client.post(f"/internal/papers/{paper}/reports")).json()["id"]

    # already assessed, still investigating, and no such report
    assert await assess_reports(sm.client, context, [ready, investigating, 999]) == []
    assert telegram.requests == []


@pytest.mark.parametrize(
    "answer",
    [
        httpx.Response(500, text="oops"),
        httpx.Response(403, json={"ok": False, "description": "Forbidden"}),
        httpx.ConnectError("connection refused"),
        httpx.ReadTimeout("timed out"),
    ],
    ids=["500", "403", "connect error", "timeout"],
)
async def test_telegram_failing_leaves_the_report_assessed_and_counted(
    sm, telegram, caplog, answer
):
    telegram.response = answer
    context = ImpactContext(FakeLlm("high"), notifier(telegram))
    report_id = await investigated_report(sm)

    with caplog.at_level(logging.WARNING, logger="research_evaluation"):
        assert await assess_reports(sm.client, context, [report_id]) == [report_id]

    response = await sm.client.get(f"/internal/reports/{report_id}")
    assert response.json()["status"] == "assessed"
    assert len(telegram.requests) == 1
    [record] = [r for r in caplog.records if r.name.startswith("research_evaluation")]
    assert record.name == "research_evaluation.notify"
    assert f"notifying report {report_id} failed" in record.getMessage()
    assert "test-token" not in record.getMessage()


# every model failing a step: the placeholder (docs/EVALUATION.md, "Impact")


def every_model_failed() -> AllModelsFailed:
    return AllModelsFailed(
        [
            ModelFailure("gemini-flash-latest", "Gemini answered 503 (UNAVAILABLE)"),
            ModelFailure("gemini-flash-lite-latest", "Gemini answered 429 (RESOURCE_EXHAUSTED)"),
        ]
    )


@pytest.mark.parametrize(("step", "failed_step"), [(1, "change"), (2, "impact"), (3, "actions")])
async def test_every_model_failing_a_step_stores_the_placeholder_and_notifies_it(
    client, sm, impact, telegram, step, failed_step
):
    impact.context = ImpactContext(
        FakeLlm("high", fail_on=step, failure=every_model_failed()), notifier(telegram)
    )
    paper = await retracted_paper(sm)

    response = await nudge(client, [paper])

    assert response.status_code == 202
    report = await the_report(sm, paper)
    assert report["status"] == "assessed"
    assert report["change_summary"] == PLACEHOLDER_TEXT
    assert report["change_severity"] == "low"
    assert report["impact_level"] == "low"
    assert report["evaluation"] == PLACEHOLDER_TEXT
    assert report["recommendation"] == PLACEHOLDER_TEXT
    assert report["assessment"]["placeholder"] is True
    assert report["assessment"]["failed_step"] == failed_step
    assert [f["model"] for f in report["assessment"]["failures"]] == [
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
    ]
    assert sm.calls("GET", "/research-paper") == (0 if step == 1 else 1)
    [text] = telegram.texts()
    assert text.startswith(f"Evaluation done: report {report['id']}\n")
    assert f"Change: low\n{PLACEHOLDER_TEXT}" in text
    assert f"What to do:\n{PLACEHOLDER_TEXT}" in text


async def test_a_placeholder_report_isnt_assessed_again(sm):
    context = ImpactContext(FakeLlm("high", fail_on=1, failure=every_model_failed()))
    report_id = await investigated_report(sm)

    assert await assess_reports(sm.client, context, [report_id]) == [report_id]
    assert await assess_reports(sm.client, context, [report_id]) == []
    assert len(context.llm.calls) == 1


async def test_storage_management_failing_on_the_placeholders_put_still_fails_the_report(
    client, sm, impact, telegram
):
    impact.context = ImpactContext(
        FakeLlm("high", fail_on=1, failure=every_model_failed()), notifier(telegram)
    )
    sm.transport.faults["/evaluation"] = 500
    paper = await retracted_paper(sm)

    await nudge(client, [paper])

    report = await the_report(sm, paper)
    assert report["status"] == "investigated"
    assert report["change_summary"] is None
    assert telegram.requests == []


async def test_without_a_notifier_nothing_is_sent_and_the_report_is_assessed(sm, telegram):
    context = ImpactContext(FakeLlm("high"))
    report_id = await investigated_report(sm)

    assert context.notifier is None
    assert await assess_reports(sm.client, context, [report_id]) == [report_id]
    assert telegram.requests == []
