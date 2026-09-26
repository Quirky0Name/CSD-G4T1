import asyncio
from datetime import UTC, datetime, timedelta

import pytest
from apscheduler.triggers.interval import IntervalTrigger
from fastapi.testclient import TestClient
from support import TEST_JWT_SECRET

from updating.config import UpdatingSettings
from updating.db import init_db, make_engine, make_sessions
from updating.main import create_app
from updating.models import PollRun, PollTrigger, RunStatus
from updating.scheduler import build_scheduler, first_run_time, last_scheduled_start

NOW = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)


def test_first_run_is_now_when_there_has_never_been_a_run():
    assert first_run_time(None, 24, NOW) == NOW


def test_first_run_counts_from_the_last_scheduled_poll_not_from_startup():
    assert first_run_time(NOW - timedelta(hours=6), 24, NOW) == NOW + timedelta(hours=18)


def test_a_poll_that_is_already_due_runs_immediately():
    assert first_run_time(NOW - timedelta(hours=30), 24, NOW) == NOW


def test_job_is_registered_at_the_configured_interval():
    # registered here, never run, so the job's dependencies can be a placeholder
    scheduler = build_scheduler(object(), 6, NOW)

    [job] = scheduler.get_jobs()
    assert job.id == "poll"
    assert isinstance(job.trigger, IntervalTrigger)
    assert job.trigger.interval == timedelta(hours=6)
    assert job.max_instances == 1
    assert job.coalesce is True
    assert job.next_run_time == NOW
    assert job.args[1] is PollTrigger.SCHEDULED


def seed_run(database_url: str, trigger: PollTrigger, started_at: datetime) -> None:
    async def seed() -> None:
        engine = make_engine(database_url)
        await init_db(engine)
        async with make_sessions(engine)() as session:
            session.add(PollRun(trigger=trigger, status=RunStatus.SUCCEEDED, started_at=started_at))
            await session.commit()
        await engine.dispose()

    asyncio.run(seed())


@pytest.mark.usefixtures("clean_settings_env")
def test_app_starts_the_scheduler_after_the_last_scheduled_run_and_stops_it(tmp_path):
    url = f"sqlite+aiosqlite:///{tmp_path / 'updating.db'}"
    last = datetime.now(UTC).replace(microsecond=0) - timedelta(hours=1)
    seed_run(url, PollTrigger.SCHEDULED, last)
    settings = UpdatingSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET, database_url=url)

    app = create_app(settings)
    with TestClient(app):
        scheduler = app.state.scheduler
        assert scheduler.running
        assert scheduler.get_job("poll").next_run_time == last + timedelta(hours=24)

    assert not scheduler.running


def test_last_scheduled_start_ignores_other_triggers(tmp_path):
    url = f"sqlite+aiosqlite:///{tmp_path / 'updating.db'}"
    started = datetime(2026, 9, 24, 8, 0, tzinfo=UTC)
    seed_run(url, PollTrigger.SCHEDULED, started)

    async def read():
        engine = make_engine(url)
        try:
            return await last_scheduled_start(make_sessions(engine))
        finally:
            await engine.dispose()

    assert asyncio.run(read()) == started
