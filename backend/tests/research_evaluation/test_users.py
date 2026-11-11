"""Research Evaluation's User Management client: reading a researcher's profile with its service
token (docs/CONTRACTS.md, "Research Evaluation ↔ User Management")."""

from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from support import TEST_JWT_SECRET

from common.service_token import ServiceTokenAuth
from research_evaluation.main import create_app
from research_evaluation.storage import Report
from research_evaluation.users import ResearcherProfile, researcher_profile

PROFILE = {
    "occupation": "PhD student",
    "occupation_other": None,
    "research_fields": ["Medicine", "Psychology"],
    "research_interests": ["vaccine safety"],
    "work_types": [],
    "response_preferences": "Keep it brief.",
}


async def test_a_saved_profile_is_read(um):
    user_id = uuid4()
    um.profiles[user_id] = PROFILE

    profile = await researcher_profile(um.client, user_id)

    assert profile == ResearcherProfile(
        occupation="PhD student",
        research_fields=["Medicine", "Psychology"],
        research_interests=["vaccine safety"],
        response_preferences="Keep it brief.",
    )
    [request] = um.requests
    assert request.url.path == f"/internal/users/{user_id}/profile"
    assert request.headers["Authorization"].startswith("Bearer ")


async def test_no_profile_is_none(um):
    assert await researcher_profile(um.client, uuid4()) is None


async def test_a_profile_with_every_answer_cleared_is_none(um):
    user_id = uuid4()
    um.profiles[user_id] = {
        "occupation": None,
        "occupation_other": None,
        "research_fields": [],
        "research_interests": [],
        "work_types": [],
        "response_preferences": None,
    }

    assert await researcher_profile(um.client, user_id) is None


async def test_unknown_fields_are_ignored(um):
    user_id = uuid4()
    um.profiles[user_id] = {**PROFILE, "something_new": True}

    profile = await researcher_profile(um.client, user_id)

    assert profile is not None and profile.occupation == "PhD student"


async def test_a_server_error_raises(um):
    um.failure = 500

    with pytest.raises(httpx.HTTPStatusError):
        await researcher_profile(um.client, uuid4())


async def test_a_404_with_another_detail_raises(um):
    um.failure = httpx.Response(404, json={"detail": "No static resource internal/users"})

    with pytest.raises(httpx.HTTPStatusError):
        await researcher_profile(um.client, uuid4())


@pytest.mark.parametrize(
    "body",
    [{**PROFILE, "research_fields": "Medicine"}, {**PROFILE, "occupation": 7}, ["not", "an", "object"]],
)
async def test_a_body_that_does_not_fit_raises(um, body):
    user_id = uuid4()
    um.failure = httpx.Response(200, json=body)

    with pytest.raises(ValidationError):
        await researcher_profile(um.client, user_id)


def test_startup_gives_impact_a_user_management_client_with_the_service_token(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    monkeypatch.setenv("UM_BASE_URL", "http://users.test:8080")
    app = create_app()

    with TestClient(app):
        um = app.state.impact.um
        assert str(um.base_url) == "http://users.test:8080"
        assert isinstance(um.auth, ServiceTokenAuth)


REPORT = {"id": 3, "paper_id": str(uuid4()), "status": "investigated", "alerts": [], "documents": []}


def test_a_report_carries_its_owner_id():
    owner = uuid4()

    assert Report.model_validate({**REPORT, "owner_id": str(owner)}).owner_id == owner


def test_a_report_without_an_owner_id_still_parses():
    assert Report.model_validate(REPORT).owner_id is None
