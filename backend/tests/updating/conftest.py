import asyncio
from datetime import UTC, datetime, timedelta
from urllib.parse import quote
from uuid import UUID, uuid4

import httpx
import pytest
import respx
from pydantic import SecretStr
from support import TEST_JWT_KEY, TEST_JWT_SECRET, load_fixture
from updating_support import record_run

from dev.scenarios import DOIS, Scenario
from dev.stub_research_evaluation import create_app as create_stub_re_app
from dev.stub_storage import create_app as create_stub_app
from updating.config import UpdatingSettings
from updating.db import init_db, make_engine, make_sessions
from updating.main import create_app
from updating.models import PollTrigger
from updating.poll import PollDeps
from updating.snapshot import Snapshot, author_ids
from updating.sources import OPENALEX_AUTHORS_URL, OpenAlexWork
from updating.storage import ServiceTokenAuth, SnapshotId, post_snapshot


class FakeSources:
    """Crossref, OpenAlex and OpenAlex's author batch, answering from the recorded fixtures."""

    def __init__(self, router: respx.MockRouter) -> None:
        self._router = router
        self.routes: dict[tuple[str, str], respx.Route] = {}

    def serve(self, name: str, **behaviour: int | Exception | dict) -> None:
        """Serve a fixture DOI. Per source (`crossref`, `openalex`, `openalex_authors`): omit
        for its fixture (404 if it has none), or pass a status code or an exception to inject,
        or a dict to answer 200 with that body instead of the fixture."""
        doi = quote(DOIS[name], safe="/")
        work = OpenAlexWork.model_validate(load_fixture("openalex", name))
        author_filter = "openalex:" + "|".join(author_ids(work))
        matchers = {
            "crossref": (f"https://api.crossref.org/works/{doi}", {}),
            "openalex": (f"https://api.openalex.org/works/doi:{doi}", {}),
            # a batch for any other ids matches no route, and respx fails the test
            "openalex_authors": (OPENALEX_AUTHORS_URL, {"params__contains": {"filter": author_filter}}),
        }
        for source, (url, match) in matchers.items():
            route = self.routes.get((name, source)) or self._router.get(url, **match)
            self.routes[(name, source)] = route
            self.set(name, source, behaviour.get(source))

    def set(self, name: str, source: str, behaviour: int | Exception | dict | None) -> None:
        route = self.routes[(name, source)]
        route.side_effect = None
        if isinstance(behaviour, Exception):
            route.side_effect = behaviour
        elif isinstance(behaviour, dict):
            route.return_value = httpx.Response(200, json=behaviour)
        elif behaviour is not None:
            route.return_value = httpx.Response(behaviour)
        elif (name, source) == ("arxiv", "crossref"):
            route.return_value = httpx.Response(404, text="Resource not found.")
        else:
            route.return_value = httpx.Response(200, json=load_fixture(source, name))


class FaultInjectingTransport(httpx.AsyncBaseTransport):
    """Makes Storage Management fail for the requests whose path ends with a given suffix."""

    def __init__(self, inner: httpx.AsyncBaseTransport) -> None:
        self._inner = inner
        self.faults: dict[str, int | Exception] = {}

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        for suffix, fault in self.faults.items():
            if request.url.path.endswith(suffix):
                if isinstance(fault, Exception):
                    raise fault
                return httpx.Response(fault, request=request)
        return await self._inner.handle_async_request(request)


class StubSm:
    """The stub Storage Management app plus the ways a test pokes at it."""

    def __init__(self) -> None:
        self.transport = FaultInjectingTransport(httpx.ASGITransport(app=create_stub_app(TEST_JWT_KEY)))
        self.client = httpx.AsyncClient(
            transport=self.transport, base_url="http://stub", auth=ServiceTokenAuth(TEST_JWT_KEY)
        )

    async def add_paper(self, doi: str | None, paper_id: UUID | None = None) -> UUID:
        body = {"id": str(paper_id or uuid4()), "doi": doi}
        response = await self.client.post("/dev/papers", json=body)
        return UUID(response.json()["id"])

    async def seed_scenario(self, scenario: Scenario) -> UUID:
        """A paper with the scenario's earlier snapshot, via the stub's `POST /dev/seed`."""
        response = await self.client.post("/dev/seed", params={"scenario": scenario})
        assert response.status_code == 201
        return UUID(response.json()["id"])

    async def reset(self) -> None:
        await self.client.post("/dev/reset")

    async def seed(self, paper_id: UUID, snapshot: Snapshot) -> SnapshotId:
        """Store a snapshot the way a previous poll (or a demo seed) would have."""
        return await post_snapshot(self.client, paper_id, snapshot)

    async def history(self, paper_id: UUID) -> list[dict]:
        response = await self.client.get(f"/internal/papers/{paper_id}/background-info/history")
        return response.json()["snapshots"]


class StubRe:
    """The stub Research Evaluation app plus the ways a test pokes at it."""

    def __init__(self) -> None:
        self.transport = FaultInjectingTransport(httpx.ASGITransport(app=create_stub_re_app()))
        self.client = httpx.AsyncClient(transport=self.transport, base_url="http://stub-re")

    async def received(self) -> list[list[UUID]]:
        """The paper ids of each nudge that arrived, in order."""
        response = await self.client.get("/dev/received")
        return [[UUID(i) for i in body["paper_ids"]] for body in response.json()]

    async def fail(self, on: bool = True) -> None:
        await self.client.post("/dev/fail", params={"on": on})


@pytest.fixture
def sources():
    with respx.mock(assert_all_called=False) as router:
        yield FakeSources(router)


@pytest.fixture
async def sm():
    stub = StubSm()
    yield stub
    await stub.client.aclose()


@pytest.fixture
async def re():
    stub = StubRe()
    yield stub
    await stub.client.aclose()


@pytest.fixture
async def deps(sm, re, sources, tmp_path):
    engine = make_engine(f"sqlite+aiosqlite:///{tmp_path / 'updating.db'}")
    await init_db(engine)
    async with httpx.AsyncClient() as source_client:
        yield PollDeps(
            sm=sm.client,
            sources=source_client,
            re=re.client,
            sessions=make_sessions(engine),
            crossref_mailto="",
            openalex_api_key=SecretStr(""),
            lock=asyncio.Lock(),
        )
    await engine.dispose()


@pytest.fixture
async def updating_app(sm, re, tmp_path, clean_settings_env):
    """The real Updating app on a fresh SQLite database, wired to the in-process stubs.

    ASGITransport doesn't run the lifespan, so it is entered here. A scheduled poll is put on
    record first, so the scheduler's next tick is a full interval away instead of running at
    startup and storing baselines before the test's trigger."""
    url = f"sqlite+aiosqlite:///{tmp_path / 'app.db'}"
    await record_run(url, PollTrigger.SCHEDULED, datetime.now(UTC))
    settings = UpdatingSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET, database_url=url)
    app = create_app(settings, sm_transport=sm.transport, re_transport=re.transport)
    async with app.router.lifespan_context(app):
        assert app.state.scheduler.get_job("poll").next_run_time > datetime.now(UTC) + timedelta(hours=1)
        yield app


@pytest.fixture
async def updating_client(updating_app):
    transport = httpx.ASGITransport(app=updating_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://updating") as client:
        yield client
