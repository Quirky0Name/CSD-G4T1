from urllib.parse import quote
from uuid import UUID, uuid4

import httpx
import pytest
import respx
from pydantic import SecretStr
from support import TEST_JWT_KEY, load_fixture
from updating_support import DOIS

from dev.stub_storage import create_app as create_stub_app
from updating.db import init_db, make_engine, make_sessions
from updating.poll import PollDeps
from updating.storage import ServiceTokenAuth


class FakeSources:
    """Crossref and OpenAlex, answering from the recorded fixtures."""

    def __init__(self, router: respx.MockRouter) -> None:
        self._router = router
        self.routes: dict[tuple[str, str], respx.Route] = {}

    def serve(self, name: str, **behaviour: int | Exception) -> None:
        """Serve a fixture DOI. Per source: omit for its fixture (404 if it has none), or pass
        a status code or an exception to inject."""
        doi = quote(DOIS[name], safe="/")
        urls = {
            "crossref": f"https://api.crossref.org/works/{doi}",
            "openalex": f"https://api.openalex.org/works/doi:{doi}",
        }
        for source, url in urls.items():
            route = self.routes.get((name, source)) or self._router.get(url)
            self.routes[(name, source)] = route
            self.set(name, source, behaviour.get(source))

    def set(self, name: str, source: str, behaviour: int | Exception | None) -> None:
        route = self.routes[(name, source)]
        route.side_effect = None
        if isinstance(behaviour, Exception):
            route.side_effect = behaviour
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

    async def reset(self) -> None:
        await self.client.post("/dev/reset")

    async def history(self, paper_id: UUID) -> list[dict]:
        response = await self.client.get(f"/internal/papers/{paper_id}/background-info/history")
        return response.json()["snapshots"]


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
async def deps(sm, sources, tmp_path):
    engine = make_engine(f"sqlite+aiosqlite:///{tmp_path / 'updating.db'}")
    await init_db(engine)
    async with httpx.AsyncClient() as source_client:
        yield PollDeps(
            sm=sm.client,
            sources=source_client,
            sessions=make_sessions(engine),
            crossref_mailto="",
            openalex_api_key=SecretStr(""),
        )
    await engine.dispose()
