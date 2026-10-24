"""poll_runs -> polls (GLOSSARY.md: a poll, never a "poll run")

Revision ID: 0002
"""

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

SCHEMA = "updating"


def upgrade() -> None:
    op.rename_table("poll_runs", "polls", schema=SCHEMA)


def downgrade() -> None:
    op.rename_table("polls", "poll_runs", schema=SCHEMA)
