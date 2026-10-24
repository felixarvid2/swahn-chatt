"""Database schema (see docs/ARCHITECTURE.md). Times are epoch milliseconds."""

from sqlalchemy import JSON as SAJSON
from sqlalchemy import (
    BigInteger,
    Boolean,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    Text,
    UniqueConstraint,
    false,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

BigIntPK = BigInteger().with_variant(Integer, "sqlite")  # SQLite autoincrement needs INTEGER PRIMARY KEY


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    username: Mapped[str] = mapped_column(String(32), unique=True)
    display_name: Mapped[str] = mapped_column(String(64))
    password_hash: Mapped[str] = mapped_column(String(255))
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    avatar_attachment_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger)
    disabled_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # Admin suspension: the account keeps its data, devices and memberships but cannot sign in or use the API.
    suspended_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # Presence is opt-in: nothing about when this user is online leaves the server unless this is true.
    share_presence: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    presence_last_seen_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)  # only kept while sharing


class Invitation(Base):
    __tablename__ = "invitations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    note: Mapped[str | None] = mapped_column(String(200), nullable=True)
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    created_by: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)  # NULL = CLI
    created_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger)
    used_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    used_by: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    revoked_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # Multi-use invitations: `uses` counts redemptions; used_at/used_by mean "exhausted" / the last redeemer.
    max_uses: Mapped[int] = mapped_column(Integer, default=1, server_default="1")
    uses: Mapped[int] = mapped_column(Integer, default=0, server_default="0")


class PasswordReset(Base):
    """Single-use code an administrator hands to a user who lost their password (stored hashed, like invitations)."""

    __tablename__ = "password_resets"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    created_by: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)  # NULL = CLI
    created_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger)
    used_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    revoked_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (UniqueConstraint("user_id", "number", name="uq_devices_user_number"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    number: Mapped[int] = mapped_column(Integer)
    name: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[int] = mapped_column(BigInteger)
    last_seen_at: Mapped[int] = mapped_column(BigInteger)
    revoked_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    push_kind: Mapped[str | None] = mapped_column(String(20), nullable=True)
    push_endpoint: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    acked_cursor: Mapped[int] = mapped_column(BigInteger, default=0)
    # Push health (written by the push worker): time and outcome of the last attempt (HTTP status; 0 = no response)
    # and how many pushes in a row failed for good (retries exhausted or refused); reset by a success.
    last_push_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    last_push_status: Mapped[int | None] = mapped_column(Integer, nullable=True)
    push_failures: Mapped[int] = mapped_column(Integer, default=0, server_default="0")


class Session(Base):
    __tablename__ = "sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    refresh_token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    created_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger)
    revoked_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


class SessionToken(Base):
    """Every refresh token hash a session has ever issued (current one included), for reuse detection."""

    __tablename__ = "session_tokens"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(ForeignKey("sessions.id", ondelete="CASCADE"), index=True)
    issued_at: Mapped[int] = mapped_column(BigInteger)


class DeviceKey(Base):
    __tablename__ = "device_keys"
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), primary_key=True)
    registration_id: Mapped[int] = mapped_column(BigInteger)
    identity_key: Mapped[str] = mapped_column(Text)
    signed_prekey_id: Mapped[int] = mapped_column(BigInteger)
    signed_prekey_public: Mapped[str] = mapped_column(Text)
    signed_prekey_signature: Mapped[str] = mapped_column(Text)
    pq_last_resort_id: Mapped[int] = mapped_column(BigInteger)
    pq_last_resort_public: Mapped[str] = mapped_column(Text)
    pq_last_resort_signature: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[int] = mapped_column(BigInteger)


class OneTimePrekey(Base):
    __tablename__ = "one_time_prekeys"
    __table_args__ = (UniqueConstraint("device_id", "key_id", name="uq_otpk_device_key"),)
    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    key_id: Mapped[int] = mapped_column(BigInteger)
    public_key: Mapped[str] = mapped_column(Text)


class OneTimePqPrekey(Base):
    __tablename__ = "one_time_pq_prekeys"
    __table_args__ = (UniqueConstraint("device_id", "key_id", name="uq_otpqk_device_key"),)
    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    key_id: Mapped[int] = mapped_column(BigInteger)
    public_key: Mapped[str] = mapped_column(Text)
    signature: Mapped[str] = mapped_column(Text)


class Conversation(Base):
    __tablename__ = "conversations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    type: Mapped[str] = mapped_column(String(10))
    name: Mapped[str | None] = mapped_column(String(64), nullable=True)
    avatar_attachment_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    direct_key: Mapped[str | None] = mapped_column(String(73), unique=True, nullable=True)
    created_by: Mapped[str] = mapped_column(String(36))
    created_at: Mapped[int] = mapped_column(BigInteger)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    last_message_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    last_message_seq: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # Server-side pruning timer only; clients do not trust it (see docs/SECURITY.md). NULL = keep forever.
    disappearing_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)


class ConversationMember(Base):
    __tablename__ = "conversation_members"
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversations.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True, index=True)
    role: Mapped[str] = mapped_column(String(10), default="member")
    joined_at: Mapped[int] = mapped_column(BigInteger)
    read_seq: Mapped[int] = mapped_column(BigInteger, default=0)


class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (Index("ix_messages_conversation_seq", "conversation_id", "seq"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    seq: Mapped[int] = mapped_column(BigInteger, unique=True)
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversations.id", ondelete="CASCADE"))
    sender_user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    sender_device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"))
    kind: Mapped[str] = mapped_column(String(10))
    target_message_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    client_ts: Mapped[int] = mapped_column(BigInteger)
    server_ts: Mapped[int] = mapped_column(BigInteger)
    deleted_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    edited_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # server_ts + the conversation's disappearing_seconds at send time; the maintenance job deletes the row after it.
    expires_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True, index=True)


class MessageEnvelope(Base):
    """One ciphertext for one device; also the delivery state. `body` is opaque bytes."""

    __tablename__ = "message_envelopes"
    message_id: Mapped[str] = mapped_column(ForeignKey("messages.id", ondelete="CASCADE"), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), primary_key=True, index=True)
    type: Mapped[str] = mapped_column(String(10))
    body: Mapped[bytes] = mapped_column(LargeBinary)
    delivered_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


class MessageAttachment(Base):
    __tablename__ = "message_attachments"
    message_id: Mapped[str] = mapped_column(ForeignKey("messages.id", ondelete="CASCADE"), primary_key=True)
    attachment_id: Mapped[str] = mapped_column(ForeignKey("attachments.id"), primary_key=True, index=True)


class Attachment(Base):
    __tablename__ = "attachments"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    uploader_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(10))
    content_type: Mapped[str] = mapped_column(String(100))
    size: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str] = mapped_column(String(64))
    storage_path: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[int] = mapped_column(BigInteger)


class Event(Base):
    __tablename__ = "events"
    __table_args__ = (
        Index("ix_events_device_seq", "device_id", "seq"),
        {"sqlite_autoincrement": True},
    )
    seq: Mapped[int] = mapped_column(BigIntPK, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"))
    type: Mapped[str] = mapped_column(String(30))
    conversation_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    payload: Mapped[dict] = mapped_column(SAJSON)
    created_at: Mapped[int] = mapped_column(BigInteger)


class DeliveryReceipt(Base):
    __tablename__ = "delivery_receipts"
    message_id: Mapped[str] = mapped_column(ForeignKey("messages.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    delivered_at: Mapped[int] = mapped_column(BigInteger)


class Counter(Base):
    __tablename__ = "counters"
    name: Mapped[str] = mapped_column(String(40), primary_key=True)
    value: Mapped[int] = mapped_column(BigInteger, default=0)


class AuditEvent(Base):
    """Security-relevant actions (see docs/SECURITY.md). `details` never holds secrets, codes or tokens."""

    __tablename__ = "audit_events"
    __table_args__ = ({"sqlite_autoincrement": True},)  # ids are never reused: they are the paging cursor
    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True, autoincrement=True)
    at: Mapped[int] = mapped_column(BigInteger, index=True)
    actor_user_id: Mapped[str | None] = mapped_column(String(36), nullable=True)  # NULL = CLI or nobody (yet)
    action: Mapped[str] = mapped_column(String(40))
    target_user_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    target_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    details: Mapped[dict] = mapped_column(SAJSON, default=dict)


class LoginFailure(Base):
    """One failed password guess, so per-username lockouts survive a restart. `key_hash` is an HMAC of the username
    (never the name itself: unknown accounts must not be stored); rows older than a day are pruned."""

    __tablename__ = "login_failures"
    __table_args__ = (Index("ix_login_failures_key_at", "key_hash", "at"),)
    id: Mapped[int] = mapped_column(BigIntPK, primary_key=True, autoincrement=True)
    key_hash: Mapped[str] = mapped_column(String(64))
    at: Mapped[int] = mapped_column(BigInteger)


class UploadSession(Base):
    """A resumable attachment upload. `id` becomes the attachment id on completion. `size` is reserved quota until
    the session is finished or expires; the bytes so far live in `.tmp/uploads/<id>.part` under the attachments dir."""

    __tablename__ = "upload_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(10))
    size: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)
    offset: Mapped[int] = mapped_column(BigInteger, default=0)
    created_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger, index=True)


class ServerState(Base):
    """Small key/value store for the server's own bookkeeping (e.g. the last database maintenance run)."""

    __tablename__ = "server_state"
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[dict] = mapped_column(SAJSON)
    updated_at: Mapped[int] = mapped_column(BigInteger)
