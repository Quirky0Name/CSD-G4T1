"""Test data and DB readers specific to the Updating poll tests."""

from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import select
from support import load_fixture

from updating.models import PollRun, TrackedPaper
from updating.poll import PollDeps
from updating.snapshot import Snapshot, build_snapshot
from updating.sources import CrossrefWork, OpenAlexAuthor, OpenAlexWork, SourceStatus

DOIS = {
    "lancet": "10.1016/s0140-6736(20)31180-6",
    "ijaa": "10.1016/j.ijantimicag.2020.105949",
    "jbc": "10.1016/s0021-9258(19)52451-6",
    "arxiv": "10.48550/arxiv.1201.0490",  # a DataCite DOI: Crossref has never heard of it
}


def author_batch(name: str) -> list[OpenAlexAuthor]:
    """A recorded `/authors` batch, in the order OpenAlex returned it."""
    rows = load_fixture("openalex_authors", name)["results"]
    return [OpenAlexAuthor.model_validate(row) for row in rows]


def fixture_snapshot(name: str, **changes) -> Snapshot:
    """What a poll builds from the recorded fixtures, with `changes` applied on top, for
    seeding a "before" state in Storage Management."""
    crossref = (
        SourceStatus.NOT_FOUND  # a DataCite DOI: Crossref has never heard of it
        if name == "arxiv"
        else CrossrefWork.model_validate(load_fixture("crossref", name)["message"])
    )
    snapshot = build_snapshot(
        DOIS[name],
        datetime.now(UTC).replace(microsecond=0),
        crossref,
        OpenAlexWork.model_validate(load_fixture("openalex", name)),
        author_batch(name),
    )
    return snapshot.model_copy(update=changes)


async def tracked_rows(deps: PollDeps) -> dict[UUID, TrackedPaper]:
    async with deps.sessions() as session:
        return {row.paper_id: row for row in await session.scalars(select(TrackedPaper))}


async def poll_runs(deps: PollDeps) -> list[PollRun]:
    async with deps.sessions() as session:
        return list(await session.scalars(select(PollRun).order_by(PollRun.id)))
