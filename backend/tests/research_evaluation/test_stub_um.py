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
    "body",
    [
        alerts(event="digest"),
        alerts(alerts=[]),
        alerts(alerts=[{**ALERT, "alert_severity": "none"}]),
        alerts(alerts=[{**ALERT, "alert_severity": "critical"}]),
        assessed(impact_level="none"),
        assessed(impact_level="severe"),
        assessed(change_severity="severe"),
        assessed(change_severity=None),
    ],
    ids=[
        "unknown event",
        "no alerts",
        "alert severity none",
        "alert severity unknown",
        "impact level none",
        "impact level unknown",
        "change severity unknown",
        "change severity missing",
    ],
)
async def test_a_body_breaking_the_rules_is_answered_400_and_not_recorded(um, body):
    assert (await post(um, body)).status_code == 400
    assert um.notifications == []
