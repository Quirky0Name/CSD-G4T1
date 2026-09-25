"""Test data and DB readers specific to the Updating poll tests."""

from uuid import UUID

from sqlalchemy import select

from updating.models import PollRun, TrackedPaper
from updating.poll import PollDeps

DOIS = {
    "lancet": "10.1016/s0140-6736(20)31180-6",
    "ijaa": "10.1016/j.ijantimicag.2020.105949",
    "jbc": "10.1016/s0021-9258(19)52451-6",
    "arxiv": "10.48550/arxiv.1201.0490",  # a DataCite DOI: Crossref has never heard of it
}


async def tracked_rows(deps: PollDeps) -> dict[UUID, TrackedPaper]:
    async with deps.sessions() as session:
        return {row.paper_id: row for row in await session.scalars(select(TrackedPaper))}


async def poll_runs(deps: PollDeps) -> list[PollRun]:
    async with deps.sessions() as session:
        return list(await session.scalars(select(PollRun).order_by(PollRun.id)))
