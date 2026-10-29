"""Test data and DB readers specific to the Updating poll tests."""

from datetime import datetime
from uuid import UUID

import httpx
from sqlalchemy import select
from support import user_token

from updating.db import init_db, make_engine, make_sessions
from updating.models import Poll, PollStatus, PollTrigger, TrackedPaper
from updating.poll import PollDeps


async def tracked_rows(deps: PollDeps) -> dict[UUID, TrackedPaper]:
    async with deps.sessions() as session:
        return {row.paper_id: row for row in await session.scalars(select(TrackedPaper))}


async def polls(deps: PollDeps) -> list[Poll]:
    async with deps.sessions() as session:
        return list(await session.scalars(select(Poll).order_by(Poll.id)))


RUN_POLL = "/run-poll"


async def record_run(database_url: str, trigger: PollTrigger, started_at: datetime) -> None:
    """A finished poll already on record, e.g. to keep the scheduler from polling at startup."""
    engine = make_engine(database_url)
    await init_db(engine)
    async with make_sessions(engine)() as session:
        session.add(Poll(trigger=trigger, status=PollStatus.SUCCEEDED, started_at=started_at))
        await session.commit()
    await engine.dispose()


async def trigger_poll(client: httpx.AsyncClient, paper_id: UUID | None = None, user: UUID | None = None) -> dict:
    """Call `POST /run-poll` as `user` (the tests' usual owner by default), expecting a poll summary back."""
    params = {} if paper_id is None else {"paper_id": str(paper_id)}
    headers = {"Authorization": f"Bearer {user_token(*([user] if user else []))}"}
    response = await client.post(RUN_POLL, params=params, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()
