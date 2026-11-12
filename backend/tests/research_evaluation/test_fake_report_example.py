"""The saved output in storage's dev/fake-report/ (docs/STORAGE.md, "Dev mode"). It was generated once
through impact's three prompts, so a schema change can make it fail: the file is then regenerated."""

import json
from pathlib import Path
from typing import Any

import pytest

from research_evaluation.impact.schemas import (
    PLACEHOLDER_TEXT,
    ChangeAssessment,
    ImpactAssessment,
    RecommendedActions,
    prose,
)

REPO = Path(__file__).parents[3]
FOLDER = REPO / "storage/src/main/resources/dev/fake-report"
CASE = REPO / "backend/evals/cases/11_concern_then_retraction_replacement.json"


@pytest.fixture(scope="module")
def report() -> dict[str, Any]:
    return json.loads((FOLDER / "report.json").read_text(encoding="utf8"))


@pytest.fixture(scope="module")
def accepted() -> dict[str, list[str]]:
    tags = json.loads(CASE.read_text(encoding="utf8"))["expected"]["tags"]
    return {tag: tags[tag]["accept"] for tag in ("change.severity", "impact.impact_level")}


def _strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [text for item in value.values() for text in _strings(item)]
    if isinstance(value, list):
        return [text for item in value for text in _strings(item)]
    return []


def test_each_steps_answer_fits_its_schema(report: dict[str, Any]) -> None:
    assessment = report["evaluation"]["assessment"]

    ChangeAssessment.model_validate(assessment["change"])
    ImpactAssessment.model_validate(assessment["impact"])
    RecommendedActions.model_validate(assessment["actions"])


def test_it_says_it_is_fake_and_is_not_the_placeholder(report: dict[str, Any]) -> None:
    assessment = report["evaluation"]["assessment"]

    assert assessment["fake"] is True
    assert assessment["placeholder"] is False
    assert PLACEHOLDER_TEXT not in _strings(report)


def test_the_answer_is_what_case_11_accepts(report: dict[str, Any], accepted: dict[str, list[str]]) -> None:
    evaluation = report["evaluation"]

    assert evaluation["change_severity"] in accepted["change.severity"]
    assert evaluation["impact_level"] in accepted["impact.impact_level"]


def test_the_stored_fields_are_what_the_answers_say(report: dict[str, Any]) -> None:
    evaluation = report["evaluation"]
    assessment = evaluation["assessment"]

    change = ChangeAssessment.model_validate(assessment["change"])
    impact = ImpactAssessment.model_validate(assessment["impact"])
    actions = RecommendedActions.model_validate(assessment["actions"])

    # the free texts are stored as assess stores them: the kept sentences joined
    assert evaluation["change_summary"] == prose(change.summary, change.claims)
    assert evaluation["change_severity"] == change.severity
    assert evaluation["impact_level"] == impact.impact_level
    assert evaluation["evaluation"] == prose(impact.explanation, impact.claims)
    assert evaluation["recommendation"] == prose(actions.recommendation, actions.claims)


def test_it_has_two_alerts_and_four_documents(report: dict[str, Any]) -> None:
    assert len(report["alerts"]) == 2
    assert [document["kind"] for document in report["documents"]] == [
        "notice",
        "notice",
        "new_version",
        "current_version",
    ]


def test_each_pdf_is_there_and_is_a_pdf(report: dict[str, Any]) -> None:
    named = [document["pdf_file"] for document in report["documents"] if document["pdf_file"]]

    assert named == ["new-version.pdf", "current-version.pdf"]
    for name in [*named, "paper.pdf", "draft.pdf"]:
        assert (FOLDER / name).read_bytes().startswith(b"%PDF")
