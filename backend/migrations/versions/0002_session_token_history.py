"""keep every refresh token hash a session issued (reuse detection across several rotations)

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-24 18:00:00.000000
"""
from alembic import op
import sqlalchemy as sa


revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # SQLite rebuilds `sessions` to drop a column, and with foreign keys on that cascades to child rows. So the
    # existing hashes are staged first and session_tokens is created and filled after the rebuild.
    op.execute("CREATE TABLE _token_stage AS SELECT id, refresh_token_hash, previous_token_hash, created_at FROM sessions")
    with op.batch_alter_table('sessions', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_sessions_previous_token_hash'))
        batch_op.drop_column('previous_token_hash')

    op.create_table('session_tokens',
    sa.Column('token_hash', sa.String(length=64), nullable=False),
    sa.Column('session_id', sa.String(length=36), nullable=False),
    sa.Column('issued_at', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['session_id'], ['sessions.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('token_hash')
    )
    with op.batch_alter_table('session_tokens', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_session_tokens_session_id'), ['session_id'], unique=False)

    # Sessions that exist today keep the protection they had: current and previous token.
    op.execute("INSERT INTO session_tokens (token_hash, session_id, issued_at) SELECT refresh_token_hash, id, created_at FROM _token_stage")
    op.execute(
        "INSERT INTO session_tokens (token_hash, session_id, issued_at) "
        "SELECT previous_token_hash, id, created_at FROM _token_stage "
        "WHERE previous_token_hash IS NOT NULL AND previous_token_hash <> refresh_token_hash"
    )
    op.execute("DROP TABLE _token_stage")


def downgrade() -> None:
    with op.batch_alter_table('sessions', schema=None) as batch_op:
        batch_op.add_column(sa.Column('previous_token_hash', sa.String(length=64), nullable=True))
        batch_op.create_index(batch_op.f('ix_sessions_previous_token_hash'), ['previous_token_hash'], unique=False)

    # Best effort: the most recently issued token that is not the current one becomes the "previous" one.
    op.execute(
        "UPDATE sessions SET previous_token_hash = (SELECT t.token_hash FROM session_tokens t "
        "WHERE t.session_id = sessions.id AND t.token_hash <> sessions.refresh_token_hash "
        "ORDER BY t.issued_at DESC LIMIT 1)"
    )
    with op.batch_alter_table('session_tokens', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_session_tokens_session_id'))

    op.drop_table('session_tokens')
