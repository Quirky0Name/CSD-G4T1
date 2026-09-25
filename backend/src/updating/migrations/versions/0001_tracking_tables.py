"""tracked_papers and poll_runs

Revision ID: 0001
"""

import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None

SCHEMA = "updating"


def upgrade() -> None:
    op.create_table(
        "tracked_papers",
        sa.Column("paper_id", sa.Uuid(), primary_key=True),
        sa.Column("doi", sa.Text(), nullable=False),
        sa.Column("last_snapshot_id", sa.BigInteger()),
        sa.Column("nudge_pending", sa.Boolean(), nullable=False, server_default=sa.false()),
        schema=SCHEMA,
    )
    op.create_table(
        "poll_runs",
        sa.Column("id", sa.BigInteger().with_variant(sa.Integer(), "sqlite"), primary_key=True),
        sa.Column("trigger", sa.String(16), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True)),
        sa.Column("summary", sa.JSON()),
        sa.Column("error", sa.Text()),
        schema=SCHEMA,
    )


def downgrade() -> None:
    op.drop_table("poll_runs", schema=SCHEMA)
    op.drop_table("tracked_papers", schema=SCHEMA)
