import pytest
import sqlalchemy as sa
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext

from updating.db import run_migrations
from updating.models import Base


@pytest.fixture
def connection():
    """SQLite with an attached `updating` database, standing in for the Postgres schema."""
    engine = sa.create_engine("sqlite://")
    with engine.connect() as conn:
        conn.execute(sa.text("ATTACH DATABASE ':memory:' AS updating"))
        yield conn


def test_migration_0001_creates_tables_that_match_the_models(connection):
    run_migrations(connection)

    version = connection.execute(sa.text("SELECT version_num FROM updating.alembic_version"))
    assert version.scalar_one() == "0001"

    context = MigrationContext.configure(
        connection,
        opts={"include_schemas": True, "version_table_schema": "updating", "compare_type": False},
    )
    diffs = compare_metadata(context, Base.metadata)
    assert diffs == []
