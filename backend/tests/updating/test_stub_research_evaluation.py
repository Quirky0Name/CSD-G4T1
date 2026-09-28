import time
from uuid import uuid4

import httpx
import jwt
import pytest
from support import TEST_JWT_KEY

from common.service_token import mint_service_token
from dev.stub_research_evaluation import create_app

WRONG_KEY = b"a-different-key-of-32-bytes-long!"


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def user_token() -> str:
    now = int(time.time())
    return jwt.encode({"sub": str(uuid4()), "iat": now, "exp": now + 300}, TEST_JWT_KEY, algorithm="HS256")


SERVICE = bearer(mint_service_token(TEST_JWT_KEY, "svc:updating"))


@pytest.fixture
async def client():
    app = create_app(TEST_JWT_KEY)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://stub") as client:
        yield client


async def test_a_nudge_is_accepted_with_202_and_recorded(client):
    first, second = str(uuid4()), str(uuid4())

    response = await client.post("/evaluate/changes", json={"paper_ids": [first, second]}, headers=SERVICE)

    assert response.status_code == 202
    assert (await client.get("/dev/received")).json() == [{"paper_ids": [first, second]}]


async def test_while_failing_nothing_is_recorded_and_the_answer_is_503(client):
    assert (await client.post("/dev/fail")).status_code == 204

    response = await client.post("/evaluate/changes", json={"paper_ids": [str(uuid4())]}, headers=SERVICE)

    assert response.status_code == 503
    assert (await client.get("/dev/received")).json() == []


async def test_failing_can_be_turned_off_again(client):
    await client.post("/dev/fail")
    await client.post("/dev/fail", params={"on": False})

    response = await client.post("/evaluate/changes", json={"paper_ids": [str(uuid4())]}, headers=SERVICE)

    assert response.status_code == 202


async def test_a_malformed_body_is_422(client):
    response = await client.post("/evaluate/changes", json={"paper_ids": ["not-a-uuid"]}, headers=SERVICE)

    assert response.status_code == 422


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": "Bearer not-a-jwt"},
        {"Authorization": mint_service_token(TEST_JWT_KEY, "svc:updating")},  # no "Bearer "
        bearer(mint_service_token(WRONG_KEY, "svc:updating")),
        bearer(jwt.encode({"sub": "svc:updating", "role": "service"}, TEST_JWT_KEY, algorithm="HS256")),  # no exp
    ],
    ids=["none", "not a jwt", "no bearer prefix", "wrong key", "no exp"],
)
async def test_a_missing_or_bad_token_is_401_and_nothing_is_recorded(client, headers):
    response = await client.post("/evaluate/changes", json={"paper_ids": [str(uuid4())]}, headers=headers)

    assert response.status_code == 401
    assert (await client.get("/dev/received")).json() == []


async def test_a_user_token_is_403(client):
    response = await client.post(
        "/evaluate/changes", json={"paper_ids": [str(uuid4())]}, headers=bearer(user_token())
    )

    assert response.status_code == 403
    assert (await client.get("/dev/received")).json() == []


async def test_any_service_token_is_accepted(client):
    headers = bearer(mint_service_token(TEST_JWT_KEY, "svc:research-evaluation"))

    response = await client.post("/evaluate/changes", json={"paper_ids": [str(uuid4())]}, headers=headers)

    assert response.status_code == 202


async def test_the_dev_helpers_need_no_token(client):
    assert (await client.get("/dev/received")).status_code == 200
    assert (await client.post("/dev/fail", params={"on": False})).status_code == 204


def test_without_a_key_it_reads_jwt_secret_from_the_environment(monkeypatch):
    monkeypatch.delenv("JWT_SECRET", raising=False)
    with pytest.raises(KeyError):
        create_app()
