"""POST /evaluate/reports: assessing reports by id on request, against the stub with a fake
model. ASGITransport finishes the background task before the response comes back."""

from uuid import UUID

import pytest
from research_evaluation_support import updating_token, user_token

from research_evaluation.impact.llm import Answer
from research_evaluation.impact.run import ImpactContext
from research_evaluation.impact.schemas import (
    ChangeAssessment,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.notify import TelegramNotifier

ANSWERS = {
    ChangeAssessment: ChangeAssessment.model_validate(
        {"alerts": [], "relations": None, "severity": "high", "summary": "Retracted."}
    ),
    ImpactAssessment: ImpactAssessment.model_validate(
        {
            "cited": False,
            "reference_entry": None,
            "uses": [],
            "impact_level": "none",
            "explanation": "Your draft doesn't cite it.",
        }
    ),
    RecommendedActions: RecommendedActions.model_validate(
        {"actions": [], "recommendation": "No action needed."}
    ),
}


class FakeLlm:
    model = "fake-gemini"

    def __init__(self) -> None:
        self.calls: list[type] = []

    async def generate(self, *, system, parts, schema):
        self.calls.append(schema)
        return Answer(ANSWERS[schema], "fake-gemini-001")


@pytest.fixture
def llm(impact) -> FakeLlm:
    fake = FakeLlm()
    impact.context = ImpactContext(fake)
    return fake


async def report(sm, investigated: bool = True) -> int:
    """A report of a new paper with one alert, investigated unless asked otherwise."""
    paper: UUID = await sm.add_paper()
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
    if investigated:
        await sm.client.patch(
            f"/internal/papers/{paper}/reports/{report_id}", json={"status": "investigated"}
        )
    return report_id


async def status(sm, report_id: int) -> str:
    return (await sm.client.get(f"/internal/reports/{report_id}")).json()["status"]


async def trigger(client, body, token: str | None = None):
    headers = {"Authorization": f"Bearer {token}"} if token is not None else {}
    return await client.post("/evaluate/reports", json=body, headers=headers)


async def test_investigated_reports_are_assessed(client, sm, llm):
    first, second = await report(sm), await report(sm)

    response = await trigger(client, {"report_ids": [first, second]}, updating_token())

    assert response.status_code == 202
    assert response.json() == {"report_ids": [first, second]}
    assert await status(sm, first) == "assessed"
    assert await status(sm, second) == "assessed"
    assert len(llm.calls) == 6


async def test_each_report_it_assesses_is_notified(client, sm, impact, telegram):
    impact.context = ImpactContext(
        FakeLlm(), TelegramNotifier(telegram.client, "123456:test-token", "987654321")
    )
    first, second = await report(sm), await report(sm)
    skipped = await report(sm, investigated=False)

    response = await trigger(client, {"report_ids": [first, skipped, second]}, updating_token())

    assert response.status_code == 202
    texts = telegram.texts()
    assert [text.splitlines()[0] for text in texts] == [
        f"Evaluation done: report {first}",
        f"Evaluation done: report {second}",
    ]
    assert all("Change: high\nRetracted." in text for text in texts)


async def test_a_repeated_id_is_assessed_once_and_other_ids_are_skipped(client, sm, llm):
    ready = await report(sm)
    investigating = await report(sm, investigated=False)
    assessed = await report(sm)
    await trigger(client, {"report_ids": [assessed]}, updating_token())
    llm.calls.clear()

    response = await trigger(
        client, {"report_ids": [ready, investigating, ready, assessed, 999]}, updating_token()
    )

    assert response.status_code == 202
    assert response.json() == {"report_ids": [ready, investigating, assessed, 999]}
    assert await status(sm, ready) == "assessed"
    assert await status(sm, investigating) == "investigating"
    assert llm.calls == [ChangeAssessment, ImpactAssessment, RecommendedActions]


async def test_without_a_gemini_key_it_is_503_and_nothing_runs(client, sm, impact):
    assert impact.context is None
    report_id = await report(sm)

    response = await trigger(client, {"report_ids": [report_id]}, updating_token())

    assert response.status_code == 503
    assert response.json()["detail"] == "Gemini isn't configured"
    assert await status(sm, report_id) == "investigated"
    assert sm.calls("GET", f"/reports/{report_id}") == 1  # only this test's own read


async def test_it_needs_a_service_token(client, sm, llm):
    report_id = await report(sm)

    assert (await trigger(client, {"report_ids": [report_id]})).status_code == 401
    assert (await trigger(client, {"report_ids": [report_id]}, "nope")).status_code == 401
    assert (await trigger(client, {"report_ids": [report_id]}, user_token())).status_code == 403
    assert llm.calls == []
    assert await status(sm, report_id) == "investigated"


@pytest.mark.parametrize(
    "body",
    [
        {"report_ids": []},
        {"report_ids": ["abc"]},
        {"report_ids": ["5"]},
        {"report_ids": [1.5]},
        {"report_ids": [True]},
        {"report_ids": 5},
        {},
    ],
)
async def test_a_bad_body_is_422(client, llm, body):
    response = await trigger(client, body, updating_token())

    assert response.status_code == 422
    assert llm.calls == []
