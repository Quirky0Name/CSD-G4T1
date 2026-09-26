import time

import jwt
import pytest
from fastapi.testclient import TestClient
from research_evaluation_support import nudge, updating_token, user_token
from support import TEST_JWT_KEY, TEST_JWT_SECRET

from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.main import create_app


def signed(claims: dict, key: bytes = TEST_JWT_KEY) -> str:
    return jwt.encode(claims, key, algorithm="HS256")


def service_claims(**overrides) -> dict:
    now = int(time.time())
    return {"sub": "svc:updating", "role": "service", "iat": now, "exp": now + 300, **overrides}


async def test_updatings_service_token_is_accepted(client):
    assert (await nudge(client, [], token=updating_token())).status_code == 202


async def test_a_user_token_is_forbidden(client):
    response = await nudge(client, [], token=user_token())

    assert response.status_code == 403


@pytest.mark.parametrize(
    "token",
    [
        signed(service_claims(), key=b"another-secret-of-at-least-32-bytes!"),
        signed(service_claims(exp=int(time.time()) - 10)),
        signed({k: v for k, v in service_claims().items() if k != "exp"}),
        signed({k: v for k, v in service_claims().items() if k != "sub"}),
        signed({"sub": "not-a-uuid", "exp": int(time.time()) + 300}),  # neither a service nor a user token
        "not-a-jwt",
    ],
    ids=["wrong key", "expired", "no exp", "no sub", "not a user either", "garbage"],
)
async def test_a_bad_token_is_unauthorized(client, token):
    assert (await nudge(client, [], token=token)).status_code == 401


async def test_a_missing_or_non_bearer_header_is_unauthorized(client):
    no_header = await client.post("/evaluate/changes", json={"paper_ids": []})
    basic = await client.post("/evaluate/changes", json={"paper_ids": []}, headers={"Authorization": "Basic abc"})

    assert (no_header.status_code, basic.status_code) == (401, 401)


async def test_the_token_is_checked_before_the_body(client):
    response = await client.post("/evaluate/changes", json={"paper_ids": "nope"})

    assert response.status_code == 401


@pytest.mark.parametrize(
    "body",
    [{"paper_ids": ["not-a-uuid"]}, {"paper_ids": "nope"}, {}, {"ids": []}],
    ids=["not a uuid", "not a list", "empty", "wrong field"],
)
async def test_a_bad_body_is_unprocessable(client, body):
    response = await client.post(
        "/evaluate/changes", json=body, headers={"Authorization": f"Bearer {updating_token()}"}
    )

    assert response.status_code == 422


def test_startup_wires_the_key_and_a_storage_management_client_that_can_fail():
    # the real lifespan: nothing listens on port 9, so the evaluation fails and answers 503
    settings = ResearchEvaluationSettings(jwt_secret=TEST_JWT_SECRET, sm_base_url="http://127.0.0.1:9")
    with TestClient(create_app(settings)) as http:
        rejected = http.post("/evaluate/changes", json={"paper_ids": []})
        accepted = http.post("/evaluate/changes", json={"paper_ids": []},
                             headers={"Authorization": f"Bearer {updating_token()}"})
        failed = http.post("/evaluate/changes", json={"paper_ids": ["00000000-0000-0000-0000-000000000001"]},
                           headers={"Authorization": f"Bearer {updating_token()}"})

    assert rejected.status_code == 401
    assert accepted.status_code == 202
    assert failed.status_code == 503
    assert failed.json()["failed_paper_ids"] == ["00000000-0000-0000-0000-000000000001"]
