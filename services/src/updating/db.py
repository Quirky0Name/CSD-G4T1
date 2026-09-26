"""Engine and schema setup. Postgres (the real run) is migrated with Alembic; SQLite
(tests and local runs without Postgres) has no schemas, so it maps `updating` to none
and creates the tables directly."""

from alembic import command
from alembic.config import Config
from sqlalchemy import Connection
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine

from updating.models import SCHEMA, Base


def make_engine(url: str) -> AsyncEngine:
    if url.startswith("sqlite"):
        return create_async_engine(url, execution_options={"schema_translate_map": {SCHEMA: None}})
    return create_async_engine(url)


def make_sessions(engine: AsyncEngine) -> async_sessionmaker:
    return async_sessionmaker(engine, expire_on_commit=False)


def run_migrations(connection: Connection) -> None:
    config = Config()
    config.set_main_option("script_location", "updating:migrations")
    config.attributes["connection"] = connection
    command.upgrade(config, "head")


async def init_db(engine: AsyncEngine) -> None:
    if engine.dialect.name == "sqlite":
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        return
    # one connection, one commit at the end: the whole migration is atomic
    async with engine.connect() as conn:
        await conn.run_sync(run_migrations)
        await conn.commit()
