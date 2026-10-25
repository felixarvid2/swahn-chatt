"""Request/response models (see docs/API.md)."""

import base64
import binascii
import re
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, BeforeValidator, ConfigDict, Field, StringConstraints, model_validator

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
USERNAME_RE = re.compile(r"^[a-z0-9_.-]{3,32}$")

MAX_ENVELOPE_BYTES = 64 * 1024
MAX_KEY_BYTES = 4096


def _uuid(v: str) -> str:
    if not UUID_RE.match(v):
        raise ValueError("must be a lowercase UUID")
    return v


def _b64(v: str) -> str:
    try:
        raw = base64.b64decode(v, validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("must be standard base64 with padding") from None
    if not raw:
        raise ValueError("must not be empty")
    return v


def _b64_key(v: str) -> str:
    _b64(v)
    if len(base64.b64decode(v)) > MAX_KEY_BYTES:
        raise ValueError("key too large")
    return v


def _b64_envelope(v: str) -> str:
    _b64(v)
    if len(base64.b64decode(v)) > MAX_ENVELOPE_BYTES:
        raise ValueError("envelope larger than 64 KiB")
    return v


def _norm_username(v):
    return v.strip().lower() if isinstance(v, str) else v


Username = Annotated[str, BeforeValidator(_norm_username), StringConstraints(pattern=USERNAME_RE.pattern)]
UUIDStr = Annotated[str, AfterValidator(_uuid)]
B64Key = Annotated[str, StringConstraints(max_length=8192), AfterValidator(_b64_key)]
B64Envelope = Annotated[str, StringConstraints(max_length=100_000), AfterValidator(_b64_envelope)]
DisplayName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)]
DeviceName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)]
Password = Annotated[str, StringConstraints(min_length=10, max_length=256)]


def _no_nul(value: Any) -> Any:
    """PostgreSQL cannot store NUL characters in text columns; reject them everywhere instead of failing later."""
    if isinstance(value, str):
        if "\x00" in value:
            raise ValueError("must not contain NUL characters")
    elif isinstance(value, dict):
        for v in value.values():
            _no_nul(v)
    elif isinstance(value, list):
        for v in value:
            _no_nul(v)
    return value


class Req(BaseModel):
    model_config = ConfigDict(extra="ignore")

    @model_validator(mode="before")
    @classmethod
    def _reject_nul(cls, data: Any) -> Any:
        return _no_nul(data)


DISAPPEARING_MIN_SECONDS = 300
DISAPPEARING_MAX_SECONDS = 365 * 24 * 3600  # one year


def _disappearing(v: int | None) -> int | None:
    if v is not None and not DISAPPEARING_MIN_SECONDS <= v <= DISAPPEARING_MAX_SECONDS:
        raise ValueError(f"must be null or between {DISAPPEARING_MIN_SECONDS} and {DISAPPEARING_MAX_SECONDS}")
    return v


# ---------------------------------------------------------------- responses
class ErrorOut(BaseModel):
    """Body of every non-2xx response. Some errors add fields (documented per endpoint)."""

    model_config = ConfigDict(extra="allow")
    code: str
    message: str
    request_id: str | None = None  # also in the X-Request-ID response header


class HealthOut(BaseModel):
    status: str


class UnavailableOut(BaseModel):
    model_config = ConfigDict(extra="allow")
    status: str
    code: str


class ReadyOut(BaseModel):
    status: str
    migration: str | None = None


class UserOut(BaseModel):
    id: str
    username: str
    display_name: str
    avatar_attachment_id: str | None
    is_admin: bool
    created_at: int


class UserListItemOut(UserOut):
    """GET /users: `online` and `last_seen_at` are set only for users who opted in to sharing presence."""

    online: bool | None = None
    last_seen_at: int | None = None


class UserSettingsOut(BaseModel):
    share_presence: bool


class DeviceOut(BaseModel):
    id: str
    user_id: str
    number: int
    name: str
    created_at: int
    last_seen_at: int
    identity_key: str | None


class OwnDeviceOut(DeviceOut):
    """The calling device as `GET /devices/me` shows it: DeviceOut plus its push health."""

    last_push_at: int | None = None  # when the server last tried to push to this device
    last_push_status: int | None = None  # HTTP status of that attempt; 0 = no answer (network error, timeout)


class DeviceRename(Req):
    name: DeviceName


class PushTestQueued(BaseModel):
    status: Literal["queued"]


class AuthResponse(BaseModel):
    access_token: str
    access_expires_at: int
    refresh_token: str
    refresh_expires_at: int
    user: UserOut
    device: DeviceOut


class MemberOut(BaseModel):
    user_id: str
    role: Literal["admin", "member"]
    joined_at: int


class ConversationOut(BaseModel):
    id: str
    type: Literal["direct", "group"]
    name: str | None
    avatar_attachment_id: str | None
    created_by: str
    created_at: int
    updated_at: int
    last_message_at: int | None
    last_message_seq: int | None
    disappearing_seconds: int | None = None
    my_read_seq: int
    unread_count: int
    members: list[MemberOut]


class EnvelopeOut(BaseModel):
    type: Literal["prekey", "whisper"]
    body: str


class MessageOut(BaseModel):
    id: str
    conversation_id: str
    sender_user_id: str
    sender_device_id: str
    kind: Literal["message", "edit", "delete", "reaction", "call"]
    target_message_id: str | None
    client_ts: int
    server_ts: int
    seq: int
    attachment_ids: list[str]
    deleted: bool
    expires_at: int | None = None
    envelope: EnvelopeOut | None


class SendResult(BaseModel):
    message: MessageOut
    duplicate: bool


class EventOut(BaseModel):
    seq: int
    type: str
    created_at: int
    conversation_id: str | None
    data: dict[str, Any]


class SyncResponse(BaseModel):
    events: list[EventOut]
    next_cursor: int
    has_more: bool


class AttachmentOut(BaseModel):
    id: str
    purpose: str
    content_type: str
    size: int
    sha256: str
    created_at: int


class InvitationCreated(BaseModel):
    id: str
    code: str
    note: str | None
    is_admin: bool
    expires_at: int
    created_at: int
    max_uses: int = 1


class InvitationOut(BaseModel):
    id: str
    note: str | None
    is_admin: bool
    expires_at: int
    created_at: int
    used_at: int | None
    used_by: str | None
    revoked_at: int | None
    max_uses: int = 1
    uses: int = 0
    remaining: int = 1  # 0 once used up, revoked or expired


class AdminDeviceOut(BaseModel):
    id: str
    number: int
    name: str
    last_seen_at: int
    push_registered: bool
    last_push_at: int | None
    last_push_status: int | None  # HTTP status of the last push attempt; 0 = no answer
    push_failures: int  # pushes in a row that failed for good (0 after a success)


class AdminUserOut(BaseModel):
    id: str
    username: str
    display_name: str
    is_admin: bool
    created_at: int
    disabled_at: int | None
    suspended_at: int | None = None
    last_seen_at: int | None  # newest last_seen_at over all of the user's devices
    active_devices: int
    attachment_bytes: int
    devices: list[AdminDeviceOut] = []  # active devices with their push health


class MigrationInfo(BaseModel):
    head: str  # newest revision this code ships
    current: str | None  # revision the database is at


class DbMaintenanceInfo(BaseModel):
    at: int  # when the last run finished (epoch ms)
    dialect: str
    ok: bool
    steps: list[str]  # what ran, e.g. ["optimize", "analyze", "wal_checkpoint", "vacuum"]
    vacuum: str  # "done", or why it was skipped ("not_needed", "disk_space", "busy", "n/a")
    duration_ms: int
    freelist_ratio: float | None = None
    error: str | None = None


class AdminStats(BaseModel):
    users: int
    active_devices: int
    conversations: int
    messages: int
    undelivered_envelopes: int
    attachments: int
    db_bytes: int | None  # None unless the database is a SQLite file
    attachments_bytes: int
    disk_free_bytes: int
    version: str
    migration: MigrationInfo
    uptime_seconds: int
    db_maintenance: DbMaintenanceInfo | None = None  # last weekly database maintenance run, null if none yet


class UploadSessionOut(BaseModel):
    upload_id: str
    offset: int
    size: int
    expires_at: int
    max_chunk_bytes: int


class UploadProgressOut(BaseModel):
    offset: int


class AuditEventOut(BaseModel):
    id: int
    at: int
    actor_user_id: str | None
    action: str
    target_user_id: str | None
    target_id: str | None
    ip: str | None
    details: dict


class PasswordResetCreated(BaseModel):
    code: str
    expires_at: int


class SignedKeyOut(BaseModel):
    key_id: int
    public_key: str
    signature: str


class OneTimeKeyOut(BaseModel):
    key_id: int
    public_key: str


class KeyBundleOut(BaseModel):
    device_id: str
    device_number: int
    registration_id: int
    identity_key: str
    signed_prekey: SignedKeyOut
    pq_prekey: SignedKeyOut
    one_time_prekey: OneTimeKeyOut | None


class KeyBundlesOut(BaseModel):
    user_id: str
    devices: list[KeyBundleOut]


class KeyStatus(BaseModel):
    one_time_prekeys: int
    one_time_pq_prekeys: int
    signed_prekey_id: int | None
    pq_last_resort_prekey_id: int | None


# ---------------------------------------------------------------- requests
class RedeemRequest(Req):
    code: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    username: Username
    password: Password
    display_name: DisplayName
    device_name: DeviceName


class LoginRequest(Req):
    username: Annotated[str, BeforeValidator(_norm_username), StringConstraints(max_length=64)]
    password: Annotated[str, StringConstraints(max_length=256)]
    device_name: DeviceName


class RefreshRequest(Req):
    refresh_token: Annotated[str, StringConstraints(min_length=1, max_length=256)]


class InvitationCreate(Req):
    note: Annotated[str, StringConstraints(max_length=200)] | None = None
    expires_in_hours: int = Field(72, ge=1, le=720)
    is_admin: bool = False
    max_uses: int = Field(1, ge=1, le=20)


class AdminUserUpdate(Req):
    is_admin: bool


class SelfDelete(Req):
    password: Annotated[str, StringConstraints(max_length=256)]


class UserSettingsUpdate(Req):
    share_presence: bool | None = None


class UploadCreate(Req):
    size: int = Field(ge=1, le=2**62)
    purpose: Literal["message"]
    sha256: Annotated[str, StringConstraints(pattern=r"^[0-9a-fA-F]{64}$")] | None = None


class PasswordResetRequest(Req):
    code: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    new_password: Password
    device_name: DeviceName


class ProfileUpdate(Req):
    display_name: DisplayName | None = None
    avatar_attachment_id: UUIDStr | None = None


class PasswordChange(Req):
    current_password: Annotated[str, StringConstraints(max_length=256)]
    new_password: Password


class PushRegistration(Req):
    kind: Literal["unifiedpush"]
    endpoint: Annotated[str, StringConstraints(min_length=8, max_length=2048)]


class SignedKey(Req):
    key_id: int = Field(ge=0, le=2**31 - 1)
    public_key: B64Key
    signature: B64Key


class OneTimeKey(Req):
    key_id: int = Field(ge=0, le=2**31 - 1)
    public_key: B64Key


class OneTimePqKey(OneTimeKey):
    signature: B64Key


class KeyUpload(Req):
    registration_id: int | None = Field(None, ge=0, le=2**31 - 1)
    identity_key: B64Key | None = None
    signed_prekey: SignedKey | None = None
    pq_last_resort_prekey: SignedKey | None = None
    one_time_prekeys: list[OneTimeKey] = Field(default_factory=list, max_length=200)
    one_time_pq_prekeys: list[OneTimePqKey] = Field(default_factory=list, max_length=200)


class ConversationCreate(Req):
    type: Literal["direct", "group"]
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)] | None = None
    member_user_ids: list[UUIDStr] = Field(default_factory=list, max_length=200)
    avatar_attachment_id: UUIDStr | None = None


class ConversationUpdate(Req):
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)] | None = None
    avatar_attachment_id: UUIDStr | None = None
    disappearing_seconds: Annotated[int | None, AfterValidator(_disappearing)] = None


class AddMembers(Req):
    user_ids: list[UUIDStr] = Field(min_length=1, max_length=200)


class RoleChange(Req):
    role: Literal["admin", "member"]


class ReadRequest(Req):
    up_to_seq: int = Field(ge=0, le=2**62)
    # false: move the pointer without telling the other members (own devices still get the `read` receipt)
    notify: bool = True


class EnvelopeIn(Req):
    device_id: UUIDStr
    type: Literal["prekey", "whisper"]
    body: B64Envelope


class SendMessage(Req):
    id: UUIDStr
    conversation_id: UUIDStr
    kind: Literal["message", "edit", "delete", "reaction", "call"] = "message"
    target_message_id: UUIDStr | None = None
    client_ts: int = Field(ge=0, le=2**62)
    attachment_ids: list[UUIDStr] = Field(default_factory=list, max_length=10)
    envelopes: list[EnvelopeIn] = Field(default_factory=list, max_length=1000)


class TurnCredentials(BaseModel):
    urls: list[str]
    username: str | None
    credential: str | None
    ttl: int


class AckRequest(Req):
    cursor: int = Field(ge=0, le=2**62)
