from uuid import UUID

import httpx
import pytest
from research_evaluation_support import snapshot_dict
from support import TEST_JWT_KEY, TEST_JWT_SECRET

from common.service_token import ServiceTokenAuth
from dev.stub_storage import create_app as create_stub_app
from research_evaluation.auth import jwt_key
from research_evaluation.config import DEFAULT_SNAPSHOT_WINDOW, ResearchEvaluationSettings
from research_evaluation.main import create_app, snapshot_window
from research_evaluation.storage import SERVICE_SUBJECT, sm_client


class FaultInjectingTransport(httpx.AsyncBaseTransport):
    """Makes Storage Management's `/internal/**` endpoints fail for requests whose path ends
    with a given suffix, and records every `/internal/**` request. The stub-only `/dev/**`
    helpers the tests read with never fail and aren't recorded."""

    def __init__(self, inner: httpx.AsyncBaseTransport) -> None:
        self._inner = inner
        self.faults: dict[str, int | httpx.Response | Exception] = {}
        self.requests: list[tuple[str, str, str]] = []  # (method, path, query string)

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/internal/"):
            self.requests.append((request.method, request.url.path, str(request.url.params)))
        for suffix, fault in self.faults.items():
            if request.url.path.startswith("/internal/") and request.url.path.endswith(suffix):
                if isinstance(fault, Exception):
                    raise fault
                if isinstance(fault, httpx.Response):
                    return fault
                return httpx.Response(fault, json={"detail": "injected"}, request=request)
        return await self._inner.handle_async_request(request)


class StubSm:
    """The stub Storage Management, reached the way Research Evaluation reaches it."""

    def __init__(self) -> None:
        self.transport = FaultInjectingTransport(httpx.ASGITransport(app=create_stub_app(TEST_JWT_KEY)))
        self.client = httpx.AsyncClient(
            transport=self.transport, base_url="http://stub", auth=ServiceTokenAuth(TEST_JWT_KEY, SERVICE_SUBJECT)
        )

    async def add_paper(self) -> UUID:
        response = await self.client.post("/dev/papers", json={"doi": "10.1016/j.ijantimicag.2020.105949"})
        return UUID(response.json()["id"])

    async def add_snapshot(self, paper_id: UUID, day: int, **fields) -> dict:
        """Stores a snapshot fetched on `day` (see snapshot_dict); the stub assigns its id."""
        body = snapshot_dict(day, **fields)
        del body["snapshot_id"], body["paper_id"]
        response = await self.client.post(f"/internal/papers/{paper_id}/background-info", json=body)
        response.raise_for_status()
        return response.json()

    async def alerts(self, paper_id: UUID) -> list[dict]:
        return (await self.client.get(f"/dev/papers/{paper_id}/alerts")).json()

    def calls(self, method: str, suffix: str) -> int:
        """How many `/internal/**` requests with this method and path ending were made."""
        return sum(1 for m, path, _ in self.transport.requests if m == method and path.endswith(suffix))

    def queries(self, method: str, suffix: str) -> list[str]:
        """The query strings of the `/internal/**` requests with this method and path ending."""
        return [query for m, path, query in self.transport.requests if m == method and path.endswith(suffix)]


@pytest.fixture
async def sm():
    stub = StubSm()
    yield stub
    await stub.client.aclose()


@pytest.fixture
def settings(request) -> ResearchEvaluationSettings:
    """The default window, or another one via `@pytest.mark.parametrize("settings", [3], indirect=True)`.
    Every value is passed explicitly, so a developer's env or .env can't leak in."""
    return ResearchEvaluationSettings(
        jwt_secret=TEST_JWT_SECRET,
        sm_base_url="http://stub",
        evaluation_snapshot_window=getattr(request, "param", DEFAULT_SNAPSHOT_WINDOW),
    )


@pytest.fixture
async def client(sm, settings):
    """Research Evaluation, talking to the stub. The lifespan doesn't run under
    ASGITransport, so the key, the Storage Management client and the snapshot window come in
    as overrides."""
    app = create_app(settings)
    app.dependency_overrides[jwt_key] = lambda: TEST_JWT_KEY
    app.dependency_overrides[sm_client] = lambda: sm.client
    app.dependency_overrides[snapshot_window] = lambda: settings.evaluation_snapshot_window
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://re") as http:
        yield http
