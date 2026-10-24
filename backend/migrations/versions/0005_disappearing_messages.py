"""server side disappearing messages: conversation timer and per-message expiry

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-24 21:30:00.000000
"""
from alembic import op
import sqlalchemy as sa


revision = '0005'
down_revision = '0004'
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table('conversations', schema=None) as batch_op:
        batch_op.add_column(sa.Column('disappearing_seconds', sa.Integer(), nullable=True))

    with op.batch_alter_table('messages', schema=None) as batch_op:
        batch_op.add_column(sa.Column('expires_at', sa.BigInteger(), nullable=True))
        batch_op.create_index(batch_op.f('ix_messages_expires_at'), ['expires_at'], unique=False)


def downgrade() -> None:
    with op.batch_alter_table('messages', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_messages_expires_at'))
        batch_op.drop_column('expires_at')

    with op.batch_alter_table('conversations', schema=None) as batch_op:
        batch_op.drop_column('disappearing_seconds')
