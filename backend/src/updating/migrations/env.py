"""Alembic environment. Only the programmatic path exists: `init_db` hands in a
connection (see updating/db.py), so there's no alembic.ini, CLI or offline mode."""

from alembic import context
from sqlalchemy import text

from updating.models import SCHEMA

connection = context.config.attributes["connection"]

if connection.dialect.name == "postgresql":
    # not committed here: the caller commits once, so schema and tables land together
    connection.execute(text(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}"))

context.configure(connection=connection, version_table_schema=SCHEMA)

with context.begin_transaction():
    context.run_migrations()
