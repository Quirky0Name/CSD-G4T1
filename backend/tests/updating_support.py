"""Test data and DB readers specific to the Updating poll tests."""

from datetime import datetime
from uuid import UUID

import httpx
from sqlalchemy import select

from updating.db import init_db, make_engine, make_sessions
from updating.models import PollRun, PollTrigger, RunStatus, TrackedPaper
from updating.poll import PollDeps


async def tracked_rows(deps: PollDeps) -> dict[UUID, TrackedPaper]:
    async with deps.sessions() as session:
        return {row.paper_id: row for row in await session.scalars(select(TrackedPaper))}


async def poll_runs(deps: PollDeps) -> list[PollRun]:
    async with deps.sessions() as session:
        return list(await session.scalars(select(PollRun).order_by(PollRun.id)))


RUN_POLL = "/run-poll"


async def record_run(database_url: str, trigger: PollTrigger, started_at: datetime) -> None:
    """A finished poll already on record, e.g. to keep the scheduler from polling at startup."""
    engine = make_engine(database_url)
    await init_db(engine)
    async with make_sessions(engine)() as session:
        session.add(PollRun(trigger=trigger, status=RunStatus.SUCCEEDED, started_at=started_at))
        await session.commit()
    await engine.dispose()


async def trigger_poll(client: httpx.AsyncClient, paper_id: UUID | None = None) -> dict:
    """Call `POST /run-poll`, expecting a run summary back."""
    params = {} if paper_id is None else {"paper_id": str(paper_id)}
    response = await client.post(RUN_POLL, params=params)
    assert response.status_code == 200, response.text
    return response.json()
