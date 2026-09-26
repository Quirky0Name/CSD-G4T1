"""The polling schedule. The job is `run_poll` itself: APScheduler logs a job's
exceptions and carries on, so no wrapper is needed. `max_instances=1` stops a slow
poll overlapping the next tick (one process only). `run_poll` also takes the lock it shares
with the manual trigger: a tick that finds it held waits for that run rather than being skipped,
which would push every other paper back a full interval."""

from datetime import UTC, datetime, timedelta

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from updating.models import PollRun, PollTrigger
from updating.poll import PollDeps, run_poll


async def last_scheduled_start(sessions: async_sessionmaker[AsyncSession]) -> datetime | None:
    async with sessions() as session:
        started = await session.scalar(
            select(func.max(PollRun.started_at)).where(PollRun.trigger == PollTrigger.SCHEDULED)
        )
    if started is not None and started.tzinfo is None:  # SQLite drops the timezone
        started = started.replace(tzinfo=UTC)
    return started


def first_run_time(last_started: datetime | None, hours: float, now: datetime) -> datetime:
    """When the first scheduled poll after a start should run.

    Counting from the last scheduled poll, not from startup, means restarts and
    redeploys can't keep pushing the poll back; a poll that's due runs immediately."""
    if last_started is None:
        return now
    return max(last_started + timedelta(hours=hours), now)


def build_scheduler(deps: PollDeps, hours: float, next_run_time: datetime) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler(timezone=UTC)
    scheduler.add_job(
        run_poll,
        IntervalTrigger(hours=hours, timezone=UTC),
        args=[deps, PollTrigger.SCHEDULED],
        id="poll",
        next_run_time=next_run_time,
        max_instances=1,
        coalesce=True,
        # a poll that starts late (slow startup) should still run, not be dropped as a misfire
        misfire_grace_time=None,
    )
    return scheduler
