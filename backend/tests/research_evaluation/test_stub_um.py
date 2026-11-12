"""The stub User Management holds Research Evaluation to the notification interface's rules
(docs/CONTRACTS.md, "Research Evaluation <-> User Management"), so a body that real User
Management would answer 400 can't pass a test."""

from uuid import uuid4

import pytest

ALERT = {"id": 7, "change_type": "retraction", "alert_severity": "high", "description": "d"}
PAPER = {"id": str(uuid4()), "title": None, "doi": None}


def alerts(**changes) -> dict:
    return {"event": "alerts", "paper": PAPER, "report_id": 3, "alerts": [ALERT], **changes}


def assessed(**changes) -> dict:
    return {
        "event": "report_assessed",
        "paper": PAPER,
        "report_id": 3,
        "change_severity": "high",
        "change_summary": "s",
        "impact_level": "medium",
        "evaluation": None,
        "recommendation": None,
        **changes,
    }


async def post(um, body: dict):
    return await um.client.post(f"/internal/users/{uuid4()}/notifications", json=body)


@pytest.mark.parametrize(
    "body",
    [
        alerts(),
        alerts(alerts=[ALERT, {**ALERT, "alert_severity": "low"}]),
        assessed(),
        assessed(impact_level=None),
        assessed(change_severity="none", impact_level=None),
    ],
    ids=["alerts", "two alerts", "assessed", "no impact level", "none change"],
)
async def test_a_body_within_the_rules_is_accepted_and_recorded(um, body):
    assert (await post(um, body)).status_code == 200
    assert [recorded for _, recorded in um.notifications] == [body]


@pytest.mark.parametrize(
    ("body", "detail"),
    [
        (alerts(event="digest"), "event must be alerts or report_assessed"),
        (alerts(paper={"title": None, "doi": None}), "paper is required"),
        (alerts(alerts=[]), "alerts must have at least one alert"),
        (alerts(alerts=[None]), "alerts must not contain null"),
        (alerts(alerts=[{**ALERT, "alert_severity": "none"}]), "alert_severity must be low, medium or high"),
        (alerts(alerts=[{**ALERT, "change_type": None}]), "change_type and description are required"),
        (alerts(alerts=[{**ALERT, "description": None}]), "change_type and description are required"),
        (assessed(change_severity="severe"), "change_severity must be none, low, medium or high"),
        (assessed(change_summary=None), "change_summary is required"),
        (assessed(impact_level="none"), "impact_level must be low, medium, high or null"),
        (
            assessed(change_severity="none", impact_level="low"),
            "impact_level must be null when change_severity is none",
        ),
        (
            assessed(change_severity="none", impact_level=None, evaluation="e"),
            "evaluation must be null when change_severity is none",
        ),
        (
            assessed(change_severity="none", impact_level=None, recommendation="r"),
            "recommendation must be null when change_severity is none",
        ),
    ],
    ids=[
        "unknown event",
        "paper with no id",
        "no alerts",
        "null alert",
        "alert severity none",
        "alert with no change type",
        "alert with no description",
        "change severity unknown",
        "no change summary",
        "impact level none",
        "impact on a none change",
        "evaluation on a none change",
        "recommendation on a none change",
    ],
)
async def test_a_body_breaking_the_rules_is_answered_400_with_the_detail_and_not_recorded(um, body, detail):
    response = await post(um, body)

    assert response.status_code == 400
    assert response.json() == {"detail": detail}
    assert um.notifications == []
