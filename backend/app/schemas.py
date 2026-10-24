"""Request/response models (see docs/API.md)."""

import base64
import binascii
import re
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, BeforeValidator, ConfigDict, Field, StringConstraints

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


class Req(BaseModel):
    model_config = ConfigDict(extra="ignore")


# ---------------------------------------------------------------- responses
class UserOut(BaseModel):
    id: str
    username: str
    display_name: str
    avatar_attachment_id: str | None
    is_admin: bool
    created_at: int


class DeviceOut(BaseModel):
    id: str
    user_id: str
    number: int
    name: str
    created_at: int
    last_seen_at: int
    identity_key: str | None


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
    kind: Literal["message", "edit", "delete"]
    target_message_id: str | None
    client_ts: int
    server_ts: int
    seq: int
    attachment_ids: list[str]
    deleted: bool
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


class InvitationOut(BaseModel):
    id: str
    note: str | None
    is_admin: bool
    expires_at: int
    created_at: int
    used_at: int | None
    used_by: str | None
    revoked_at: int | None


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


class AddMembers(Req):
    user_ids: list[UUIDStr] = Field(min_length=1, max_length=200)


class RoleChange(Req):
    role: Literal["admin", "member"]


class ReadRequest(Req):
    up_to_seq: int = Field(ge=0, le=2**62)


class EnvelopeIn(Req):
    device_id: UUIDStr
    type: Literal["prekey", "whisper"]
    body: B64Envelope


class SendMessage(Req):
    id: UUIDStr
    conversation_id: UUIDStr
    kind: Literal["message", "edit", "delete"] = "message"
    target_message_id: UUIDStr | None = None
    client_ts: int = Field(ge=0, le=2**62)
    attachment_ids: list[UUIDStr] = Field(default_factory=list, max_length=10)
    envelopes: list[EnvelopeIn] = Field(default_factory=list, max_length=1000)


class AckRequest(Req):
    cursor: int = Field(ge=0, le=2**62)
