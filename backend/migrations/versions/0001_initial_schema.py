"""initial schema

Revision ID: 0001
Revises: 
Create Date: 2026-10-24 15:42:15.992497
"""
from alembic import op
import sqlalchemy as sa


revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('conversations',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('type', sa.String(length=10), nullable=False),
    sa.Column('name', sa.String(length=64), nullable=True),
    sa.Column('avatar_attachment_id', sa.String(length=36), nullable=True),
    sa.Column('direct_key', sa.String(length=73), nullable=True),
    sa.Column('created_by', sa.String(length=36), nullable=False),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.Column('updated_at', sa.BigInteger(), nullable=False),
    sa.Column('last_message_at', sa.BigInteger(), nullable=True),
    sa.Column('last_message_seq', sa.BigInteger(), nullable=True),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('direct_key')
    )
    op.create_table('counters',
    sa.Column('name', sa.String(length=40), nullable=False),
    sa.Column('value', sa.BigInteger(), nullable=False),
    sa.PrimaryKeyConstraint('name')
    )
    op.create_table('users',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('username', sa.String(length=32), nullable=False),
    sa.Column('display_name', sa.String(length=64), nullable=False),
    sa.Column('password_hash', sa.String(length=255), nullable=False),
    sa.Column('is_admin', sa.Boolean(), nullable=False),
    sa.Column('avatar_attachment_id', sa.String(length=36), nullable=True),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.Column('disabled_at', sa.BigInteger(), nullable=True),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('username')
    )
    op.create_table('attachments',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('uploader_id', sa.String(length=36), nullable=False),
    sa.Column('purpose', sa.String(length=10), nullable=False),
    sa.Column('content_type', sa.String(length=100), nullable=False),
    sa.Column('size', sa.BigInteger(), nullable=False),
    sa.Column('sha256', sa.String(length=64), nullable=False),
    sa.Column('storage_path', sa.String(length=255), nullable=False),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['uploader_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('attachments', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_attachments_uploader_id'), ['uploader_id'], unique=False)

    op.create_table('conversation_members',
    sa.Column('conversation_id', sa.String(length=36), nullable=False),
    sa.Column('user_id', sa.String(length=36), nullable=False),
    sa.Column('role', sa.String(length=10), nullable=False),
    sa.Column('joined_at', sa.BigInteger(), nullable=False),
    sa.Column('read_seq', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['conversation_id'], ['conversations.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('conversation_id', 'user_id')
    )
    with op.batch_alter_table('conversation_members', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_conversation_members_user_id'), ['user_id'], unique=False)

    op.create_table('devices',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('user_id', sa.String(length=36), nullable=False),
    sa.Column('number', sa.Integer(), nullable=False),
    sa.Column('name', sa.String(length=64), nullable=False),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.Column('last_seen_at', sa.BigInteger(), nullable=False),
    sa.Column('revoked_at', sa.BigInteger(), nullable=True),
    sa.Column('push_kind', sa.String(length=20), nullable=True),
    sa.Column('push_endpoint', sa.String(length=2048), nullable=True),
    sa.Column('acked_cursor', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('user_id', 'number', name='uq_devices_user_number')
    )
    with op.batch_alter_table('devices', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_devices_user_id'), ['user_id'], unique=False)

    op.create_table('invitations',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('code_hash', sa.String(length=64), nullable=False),
    sa.Column('note', sa.String(length=200), nullable=True),
    sa.Column('is_admin', sa.Boolean(), nullable=False),
    sa.Column('created_by', sa.String(length=36), nullable=True),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.Column('expires_at', sa.BigInteger(), nullable=False),
    sa.Column('used_at', sa.BigInteger(), nullable=True),
    sa.Column('used_by', sa.String(length=36), nullable=True),
    sa.Column('revoked_at', sa.BigInteger(), nullable=True),
    sa.ForeignKeyConstraint(['created_by'], ['users.id'], ),
    sa.ForeignKeyConstraint(['used_by'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('code_hash')
    )
    op.create_table('device_keys',
    sa.Column('device_id', sa.String(length=36), nullable=False),
    sa.Column('registration_id', sa.BigInteger(), nullable=False),
    sa.Column('identity_key', sa.Text(), nullable=False),
    sa.Column('signed_prekey_id', sa.BigInteger(), nullable=False),
    sa.Column('signed_prekey_public', sa.Text(), nullable=False),
    sa.Column('signed_prekey_signature', sa.Text(), nullable=False),
    sa.Column('pq_last_resort_id', sa.BigInteger(), nullable=False),
    sa.Column('pq_last_resort_public', sa.Text(), nullable=False),
    sa.Column('pq_last_resort_signature', sa.Text(), nullable=False),
    sa.Column('updated_at', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('device_id')
    )
    op.create_table('events',
    sa.Column('seq', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), autoincrement=True, nullable=False),
    sa.Column('device_id', sa.String(length=36), nullable=False),
    sa.Column('type', sa.String(length=30), nullable=False),
    sa.Column('conversation_id', sa.String(length=36), nullable=True),
    sa.Column('payload', sa.JSON(), nullable=False),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('seq'),
    sqlite_autoincrement=True
    )
    with op.batch_alter_table('events', schema=None) as batch_op:
        batch_op.create_index('ix_events_device_seq', ['device_id', 'seq'], unique=False)

    op.create_table('messages',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('seq', sa.BigInteger(), nullable=False),
    sa.Column('conversation_id', sa.String(length=36), nullable=False),
    sa.Column('sender_user_id', sa.String(length=36), nullable=False),
    sa.Column('sender_device_id', sa.String(length=36), nullable=False),
    sa.Column('kind', sa.String(length=10), nullable=False),
    sa.Column('target_message_id', sa.String(length=36), nullable=True),
    sa.Column('client_ts', sa.BigInteger(), nullable=False),
    sa.Column('server_ts', sa.BigInteger(), nullable=False),
    sa.Column('deleted_at', sa.BigInteger(), nullable=True),
    sa.Column('edited_at', sa.BigInteger(), nullable=True),
    sa.ForeignKeyConstraint(['conversation_id'], ['conversations.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['sender_device_id'], ['devices.id'], ),
    sa.ForeignKeyConstraint(['sender_user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('seq')
    )
    with op.batch_alter_table('messages', schema=None) as batch_op:
        batch_op.create_index('ix_messages_conversation_seq', ['conversation_id', 'seq'], unique=False)

    op.create_table('one_time_pq_prekeys',
    sa.Column('id', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), autoincrement=True, nullable=False),
    sa.Column('device_id', sa.String(length=36), nullable=False),
    sa.Column('key_id', sa.BigInteger(), nullable=False),
    sa.Column('public_key', sa.Text(), nullable=False),
    sa.Column('signature', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('device_id', 'key_id', name='uq_otpqk_device_key')
    )
    with op.batch_alter_table('one_time_pq_prekeys', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_one_time_pq_prekeys_device_id'), ['device_id'], unique=False)

    op.create_table('one_time_prekeys',
    sa.Column('id', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), autoincrement=True, nullable=False),
    sa.Column('device_id', sa.String(length=36), nullable=False),
    sa.Column('key_id', sa.BigInteger(), nullable=False),
    sa.Column('public_key', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('device_id', 'key_id', name='uq_otpk_device_key')
    )
    with op.batch_alter_table('one_time_prekeys', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_one_time_prekeys_device_id'), ['device_id'], unique=False)

    op.create_table('sessions',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('user_id', sa.String(length=36), nullable=False),
    sa.Column('device_id', sa.String(length=36), nullable=False),
    sa.Column('refresh_token_hash', sa.String(length=64), nullable=False),
    sa.Column('previous_token_hash', sa.String(length=64), nullable=True),
    sa.Column('created_at', sa.BigInteger(), nullable=False),
    sa.Column('expires_at', sa.BigInteger(), nullable=False),
    sa.Column('revoked_at', sa.BigInteger(), nullable=True),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('refresh_token_hash')
    )
    with op.batch_alter_table('sessions', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_sessions_device_id'), ['device_id'], unique=False)
        batch_op.create_index(batch_op.f('ix_sessions_previous_token_hash'), ['previous_token_hash'], unique=False)
        batch_op.create_index(batch_op.f('ix_sessions_user_id'), ['user_id'], unique=False)

    op.create_table('delivery_receipts',
    sa.Column('message_id', sa.String(length=36), nullable=False),
    sa.Column('user_id', sa.String(length=36), nullable=False),
    sa.Column('delivered_at', sa.BigInteger(), nullable=False),
    sa.ForeignKeyConstraint(['message_id'], ['messages.id'], ondelete='CASCADE'),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ),
    sa.PrimaryKeyConstraint('message_id', 'user_id')
    )
    op.create_table('message_attachments',
    sa.Column('message_id', sa.String(length=36), nullable=False),
    sa.Column('attachment_id', sa.String(length=36), nullable=False),
    sa.ForeignKeyConstraint(['attachment_id'], ['attachments.id'], ),
    sa.ForeignKeyConstraint(['message_id'], ['messages.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('message_id', 'attachment_id')
    )
    with op.batch_alter_table('message_attachments', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_message_attachments_attachment_id'), ['attachment_id'], unique=False)

    op.create_table('message_envelopes',
    sa.Column('message_id', sa.String(length=36), nullable=False),
    sa.Column('device_id', sa.String(length=36), nullable=False),
    sa.Column('type', sa.String(length=10), nullable=False),
    sa.Column('body', sa.LargeBinary(), nullable=False),
    sa.Column('delivered_at', sa.BigInteger(), nullable=True),
    sa.ForeignKeyConstraint(['device_id'], ['devices.id'], ),
    sa.ForeignKeyConstraint(['message_id'], ['messages.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('message_id', 'device_id')
    )
    with op.batch_alter_table('message_envelopes', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_message_envelopes_device_id'), ['device_id'], unique=False)



    # Monotonic message sequence (see app/services/messages.py).
    op.execute("INSERT INTO counters (name, value) VALUES ('message_seq', 0)")


def downgrade() -> None:
    with op.batch_alter_table('message_envelopes', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_message_envelopes_device_id'))

    op.drop_table('message_envelopes')
    with op.batch_alter_table('message_attachments', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_message_attachments_attachment_id'))

    op.drop_table('message_attachments')
    op.drop_table('delivery_receipts')
    with op.batch_alter_table('sessions', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_sessions_user_id'))
        batch_op.drop_index(batch_op.f('ix_sessions_previous_token_hash'))
        batch_op.drop_index(batch_op.f('ix_sessions_device_id'))

    op.drop_table('sessions')
    with op.batch_alter_table('one_time_prekeys', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_one_time_prekeys_device_id'))

    op.drop_table('one_time_prekeys')
    with op.batch_alter_table('one_time_pq_prekeys', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_one_time_pq_prekeys_device_id'))

    op.drop_table('one_time_pq_prekeys')
    with op.batch_alter_table('messages', schema=None) as batch_op:
        batch_op.drop_index('ix_messages_conversation_seq')

    op.drop_table('messages')
    with op.batch_alter_table('events', schema=None) as batch_op:
        batch_op.drop_index('ix_events_device_seq')

    op.drop_table('events')
    op.drop_table('device_keys')
    op.drop_table('invitations')
    with op.batch_alter_table('devices', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_devices_user_id'))

    op.drop_table('devices')
    with op.batch_alter_table('conversation_members', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_conversation_members_user_id'))

    op.drop_table('conversation_members')
    with op.batch_alter_table('attachments', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_attachments_uploader_id'))

    op.drop_table('attachments')
    op.drop_table('users')
    op.drop_table('counters')
    op.drop_table('conversations')
