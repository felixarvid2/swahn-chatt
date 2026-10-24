"""suspension, presence opt-in, multi-use invitations, persistent login failures, resumable uploads

Revision ID: 0006
Revises: 0005
Create Date: 2026-10-24 23:00:00.000000
"""
from alembic import op
import sqlalchemy as sa


revision = '0006'
down_revision = '0005'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Plain ALTER TABLE, no batch (table copy): `users` is referenced by foreign keys, and SQLite would refuse to
    # drop and recreate it (or cascade into the child tables) on a database that holds data.
    op.add_column('users', sa.Column('suspended_at', sa.BigInteger(), nullable=True))
    op.add_column('users', sa.Column('share_presence', sa.Boolean(), server_default=sa.false(), nullable=False))
    op.add_column('users', sa.Column('presence_last_seen_at', sa.BigInteger(), nullable=True))
    op.add_column('invitations', sa.Column('max_uses', sa.Integer(), server_default='1', nullable=False))
    op.add_column('invitations', sa.Column('uses', sa.Integer(), server_default='0', nullable=False))
    # Redeemable now means uses < max_uses: invitations that were already redeemed must count as used.
    op.execute("UPDATE invitations SET uses = 1 WHERE used_at IS NOT NULL")

    op.create_table('login_failures',
    sa.Column('id', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), autoincrement=True, nullable=False),
    sa.Column('key_hash', sa.String(length=64), nullable=False),
    sa.Column('at', sa.BigInteger(), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('login_failures', schema=None) as batch_op:
        batch_op.create_index('ix_login_failures_key_at', ['key_hash', 'at'], unique=False)

    op.create_table('upload_sessions',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('user_id', sa.String(length=36), nullable=False),
    sa.Column('purpose', sa.String(length=10), nullable=False),
    sa.Column('size', sa.BigInteger(), nullable=False),
    sa.Column('sha256', sa.String(length=64), nullable=True),
    sa.Column('offset', sa.BigInteger(), nullable=False),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.Column('expires_at', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('upload_sessions', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_upload_sessions_expires_at'), ['expires_at'], unique=False)
        batch_op.create_index(batch_op.f('ix_upload_sessions_user_id'), ['user_id'], unique=False)


def downgrade() -> None:
    with op.batch_alter_table('upload_sessions', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_upload_sessions_user_id'))
        batch_op.drop_index(batch_op.f('ix_upload_sessions_expires_at'))
    op.drop_table('upload_sessions')

    with op.batch_alter_table('login_failures', schema=None) as batch_op:
        batch_op.drop_index('ix_login_failures_key_at')
    op.drop_table('login_failures')

    op.drop_column('invitations', 'uses')
    op.drop_column('invitations', 'max_uses')
    op.drop_column('users', 'presence_last_seen_at')
    op.drop_column('users', 'share_presence')
    op.drop_column('users', 'suspended_at')
