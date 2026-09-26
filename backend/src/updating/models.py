"""Updating's own tables, in its `updating` schema (docs/ARCHITECTURE.md, Section 4).

Snapshots live in Storage Management; there are no change records here."""

import uuid
from datetime import datetime
from enum import StrEnum
from typing import Any

import sqlalchemy as sa
from sqlalchemy import BigInteger, DateTime, Integer, MetaData, Text, Uuid
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

SCHEMA = "updating"


class PollTrigger(StrEnum):
    SCHEDULED = "scheduled"
    MANUAL = "manual"  # POST /run-poll; never moves the schedule


class RunStatus(StrEnum):
    RUNNING = "running"  # a row that stays `running` was interrupted (the process stopped mid-run)
    SUCCEEDED = "succeeded"
    FAILED = "failed"


def _enum(enum_class: type[StrEnum]) -> sa.Enum:
    # stored as the string value, not the member name, and as plain text rather than a native enum
    return sa.Enum(
        enum_class,
        native_enum=False,
        length=16,
        values_callable=lambda members: [member.value for member in members],
    )


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA)


class TrackedPaper(Base):
    """A paper Storage Management tracks that has a DOI, and where its polling stands."""

    __tablename__ = "tracked_papers"

    paper_id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    doi: Mapped[str] = mapped_column(Text)
    last_snapshot_id: Mapped[int | None] = mapped_column(BigInteger)
    nudge_pending: Mapped[bool] = mapped_column(default=False, server_default=sa.false())


class PollRun(Base):
    __tablename__ = "poll_runs"

    # SQLite only autoincrements a plain INTEGER primary key
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True)
    trigger: Mapped[PollTrigger] = mapped_column(_enum(PollTrigger))
    status: Mapped[RunStatus] = mapped_column(_enum(RunStatus))
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    summary: Mapped[dict[str, Any] | None] = mapped_column(sa.JSON)
    error: Mapped[str | None] = mapped_column(Text)
