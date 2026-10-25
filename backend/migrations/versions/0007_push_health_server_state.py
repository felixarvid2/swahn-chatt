"""push health per device, server_state key/value table

Revision ID: 0007
Revises: 0006
Create Date: 2026-10-25 09:00:00.000000
"""
from alembic import op
import sqlalchemy as sa


revision = '0007'
down_revision = '0006'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Plain ALTER TABLE, no batch (table copy): `devices` is referenced by foreign keys (see 0006).
    op.add_column('devices', sa.Column('last_push_at', sa.BigInteger(), nullable=True))
    op.add_column('devices', sa.Column('last_push_status', sa.Integer(), nullable=True))
    op.add_column('devices', sa.Column('push_failures', sa.Integer(), server_default='0', nullable=False))

    op.create_table('server_state',
    sa.Column('key', sa.String(length=64), nullable=False),
    sa.Column('value', sa.JSON(), nullable=False),
    sa.Column('updated_at', sa.BigInteger(), nullable=False),
    sa.PrimaryKeyConstraint('key')
    )


def downgrade() -> None:
    op.drop_table('server_state')
    op.drop_column('devices', 'push_failures')
    op.drop_column('devices', 'last_push_status')
    op.drop_column('devices', 'last_push_at')
