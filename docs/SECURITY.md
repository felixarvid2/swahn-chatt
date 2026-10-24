# FamilyChat security design

## What is protected, from whom

| Asset | Protected against the server operator / a stolen Pi? | How |
|---|---|---|
| Message text, edits, attachment contents, file names, attachment keys | **Yes** | Signal protocol, encrypted on the phone |
| Who talks to whom, when, message sizes, group names/avatars, member lists, read/delivery receipts, user avatars, display names | No (metadata) | Only TLS in transit |
| Passwords | Yes (not recoverable) | Argon2id |
| Tokens | Hashes only on the server | SHA-256 of 256-bit random refresh tokens |
| Network traffic | Yes | TLS (HTTPS/WSS) via Tailscale or a reverse proxy. **TLS is not end-to-end encryption.** |

The threat model is a small family on a self-hosted server: a compromised or seized Raspberry Pi, a backup that
leaks, or a curious administrator must not reveal message contents. Metadata hiding (sealed sender, private
groups) is out of scope; see "Known limitations".

## Protocol choice

We do not design cryptography. Candidates evaluated:

| Option | Verdict |
|---|---|
| **libsignal** (Signal Messenger, Rust core, `org.signal:libsignal-android` / `libsignal-client` on Maven Central) | **Chosen.** X3DH/PQXDH key agreement with Kyber1024 + Double Ratchet: forward secrecy and post-compromise security; the most scrutinised messaging protocol. Ships Android native libraries and a JVM build with Linux natives, so the protocol is unit-tested on a normal JVM |
| vodozemac (Matrix Olm/Megolm, Apache-2.0) | Good alternative; Kotlin bindings exist only through the large matrix-rust-sdk. Megolm has weaker post-compromise security for groups |
| MLS (RFC 9420, OpenMLS) | Best group scaling, but no maintained Android binding; overkill for 5–15 people |
| libsodium / Tink primitives directly | Would mean inventing a ratchet/session protocol ourselves — rejected |

Risks of libsignal that we accept and mitigate:
- **License: AGPL-3.0.** Anyone who distributes the app or runs a modified version for others must provide
  the source. For a private family deployment this is fine; FamilyChat is therefore licensed AGPL-3.0 as a
  whole. Do not combine it with proprietary code.
- **No API stability promise.** Signal states libsignal is built for Signal's own apps; the Java API changes
  between releases. The version is pinned in `android/gradle/libs.versions.toml` and all use goes through one
  module (`:core:crypto`), so an upgrade touches one place. Check release notes before bumping.
- **Size.** The native library adds ~10 MB per ABI to the APK. We ship `arm64-v8a` (and `x86_64` for emulators).
- **Server side has no libsignal.** The Python server only stores and forwards public keys and opaque
  ciphertext; it never needs crypto beyond TLS, Argon2id, JWT and SHA-256.

## How messages are encrypted

- **Per-device identities.** Every app installation is a device with its own identity key pair
  (Curve25519), registration id, signed prekey, Kyber last-resort prekey and batches of one-time EC and Kyber
  prekeys. Private keys are generated on the phone and never leave it. The server stores public keys only
  (`device_keys`, `one_time_prekeys`, `one_time_pq_prekeys` tables).
- **Sessions.** To message a device the sender fetches its prekey bundle (`GET /keys/{user}`), verifies the
  signatures and runs PQXDH (libsignal `SessionBuilder`), then encrypts with the Double Ratchet
  (`SessionCipher`). The first message is a `PreKeySignalMessage` (`prekey`), later ones `SignalMessage`
  (`whisper`).
- **Fan-out to devices (1:1 and groups).** A message is encrypted separately for every active device of every
  member, including the sender's own other devices. The server rejects a send whose device set does not match
  current membership (`409 mismatched_devices`). This is how membership changes stay secure: a removed member's
  devices simply stop getting envelopes, and a newly added member cannot read anything sent before they joined.
  Pairwise fan-out costs O(devices) per message, which is trivial at family size. Signal-style Sender Keys or
  MLS can replace it later without changing the server model.
- **Content format** (inside the ciphertext): versioned JSON, padded to a multiple of 160 bytes:
  `{"v":2, "type":"text|edit|delete", "conversation_id", "message_id", "target_message_id" (edit/delete),
  "text":..., "attachments":[{"id","key","sha256","size","mime","name","kind":"image|video|voice|file",
  "width","height","duration_ms","thumbnail"}], "reply_to":...}`.
- **Content is bound to its context.** Everything the server says about a message (`sender_user_id`,
  `sender_device_id`, `conversation_id`, message id, `target_message_id`) is an untrusted claim. After decrypting,
  the client requires the conversation, message and target ids inside the content to equal the outer fields, the
  sender device to be listed under the claimed sender, and the sender to be a member of the conversation.
  Otherwise the message is dropped. So the server cannot move a private message into a group, re-aim an edit, or
  relabel who sent something.
- **Edits and deletes are authenticated.** Both are sent as encrypted content to every device. A client applies
  them only after decrypting content from the original author that names the target. Envelope-less edit or delete
  events (which only the server could produce) are ignored, so the server cannot wipe or rewrite messages on
  the phones. It can still withhold or drop ciphertext.
- **Attachments** are encrypted on the phone with Google Tink streaming AEAD (`AES256_GCM_HKDF_4KB`) under a
  fresh random 32-byte key per file, then uploaded. The key, the ciphertext SHA-256, the real MIME type and the
  file name travel only inside the Signal-encrypted message. The server therefore cannot validate MIME types of
  message attachments; the client validates type and size after decryption and never trusts the server.
- **Corrupted or forged ciphertext** fails libsignal's MAC check or Tink's GCM tag check; the client shows
  "could not decrypt" for that message instead of crashing, and the error does not advance the ratchet.

## Identity verification

- **Trust is per device UUID.** Sessions and identity pins are addressed by the device's immutable id
  (`<userId>/<deviceUuid>`), never by the server-assigned device number, which is display-only. A device is pinned
  (owner + identity key) the first time it appears in a device list. A later list, key bundle or prekey message
  showing another key or another owner for that id is an identity change: sending and receiving with that device
  stop, the conversation shows a warning, and the user must accept explicitly. An accept is refused unless the key
  matches the server's current device list. libsignal's own trust-on-first-use is disabled: an unpinned device
  is never trusted.
- **Every new device is announced.** One code path compares device lists. After the first fetch of a user (the
  baseline), every device not seen before produces "Anna added a new device" in every conversation with that user.
  This also covers devices learned through a `409 mismatched_devices` or as the sender of a message, and devices
  that reappear after the list was emptied. A new device on **your own account** shows up in all conversations.
- **New devices are held back where it matters.** If you have marked any of a contact's devices as verified,
  a new device of that contact gets nothing (no sends, its messages are held unread) until you approve it on the
  safety-number screen. The same applies to every new device on your own account. When you log in on a second
  phone, approve it on the old one (Inställningar → Säkerhetsnummer för mina enheter). Without this, a server could
  silently add a listening device.
- **Group membership changes are announced** ("X was added", "X left") in the conversation, so a server that adds
  an account to a group cannot do it unnoticed. Membership itself is still server-controlled metadata.
- **Safety numbers:** the contact screen shows a 60-digit libsignal fingerprint per contact device (computed over
  device UUIDs). Compare it in person or on a call and mark it verified.

## Keys on the phone

- The local Room database (messages, contacts and the libsignal session/identity/prekey stores) is encrypted
  with SQLCipher. Its 256-bit passphrase is generated once and stored wrapped by an AES-GCM key in the Android
  Keystore (hardware-backed where available, non-exportable).
- Access and refresh tokens are stored in the same encrypted database.
- The database is wiped only when the Keystore key is conclusively lost (missing alias, permanently invalidated
  key, failed GCM tag). A temporary Keystore error shows a retry screen instead.
- Attachment file names come from other people. They are sanitised (no path separators, leading dots, control or
  bidi characters) before a file is written for sharing.
- Backups by Android are disabled for app data (`android:allowBackup="false"`, data-extraction rules exclude
  everything), so keys never land in Google Drive.

## Device replacement and loss

- **New phone / reinstall:** log in again. This creates a **new device** with new keys. Contacts see "added a
  new device". Earlier messages cannot be decrypted on the new device (the server only holds ciphertext for the
  old device, and Double Ratchet keys are single-use). This is the same trade-off Signal makes. A passphrase
  protected local history export/import is listed as future work in `HANDOVER.md`.
- **Lost or stolen phone:** from another device or by an administrator: revoke the device
  (`DELETE /devices/{id}`, or `remove-user` for the account) and change the password (revokes all other
  sessions). The revoked device's keys are deleted from the server and it stops receiving envelopes at once.
  Messages already on the lost phone are protected by the phone's lock screen, file-based encryption and the
  SQLCipher/Keystore layer.

## Server security

- Accounts only via single-use, expiring invitation codes (stored as SHA-256 hashes). No phone numbers.
- Passwords hashed with Argon2id (argon2-cffi defaults: t=3, m=64 MiB, p=4; tuned for a Pi 4). At most two
  hashes/verifications run at once (memory cap) and none ever runs inside a database transaction, so a flood of
  logins cannot hold the SQLite write lock or exhaust RAM; the account state is re-checked in the write
  transaction after the hash (invitation still unused, account still enabled, password unchanged).
- Short-lived JWT access tokens (15 min) + rotating refresh tokens with reuse detection. The server remembers
  the hash of **every** refresh token a session ever issued (`session_tokens`, dropped with the session and after
  one refresh lifetime): presenting any token other than the current one revokes the session and its device
  (`401 token_reused`), however many times a thief rotated in between. Every request checks that the session,
  device and user are still active (read-only, no write lock).
- In-process rate limiting on login, invitation redemption, refresh and key fetching. IPv6 clients are limited per
  /64 prefix (IPv4-mapped addresses count as IPv4); logs keep the exact address.
- **Password guessing per account:** besides the per-IP limits, failed logins are counted per username, whatever
  the source address: 10 failures within an hour (or 30 within a day) lock logins for that username with
  `429 rate_limited` until the oldest failures age out. Unknown usernames are counted and answered identically,
  so the lock cannot be used to enumerate accounts. Trade-off: someone who knows a username can keep new logins
  to that account blocked (for up to an hour per 10 attempts); devices that are already signed in keep working,
  because refreshing and API calls are not logins.
- **Prekey draining:** `GET /keys/{user}` is limited per caller *user* (120/min), and each caller user gets only
  10 bundles per target device and hour that include a one-time prekey. Beyond that the bundle still contains the
  signed prekey and the last-resort Kyber key but no one-time keys, and none are deleted, so a (logged-in) user
  cannot use up someone's one-time prekeys by logging in again to get fresh devices. Each account can have at
  most 10 active devices (`FAMILYCHAT_MAX_DEVICES_PER_USER`); logging in on another one fails with
  `409 device_limit` until a device is removed.
- Request bodies: 8 MiB for everything except `POST /api/v1/attachments`, which streams to disk with its own
  limit; the reverse proxy caps bodies too (Caddy: 9 MB, uploads `CADDY_MAX_UPLOAD`, default 110 MB), and
  WebSocket frames are limited to 64 KiB (`--ws-max-size`).
- **WebSocket:** at most 256 queued outgoing messages per connection (a client that stops reading is closed with
  1008; nothing is lost, events live in the database), inbound frames are rate limited per connection (flooding
  closes with 1008, extra typing frames and pings are dropped) and typing membership lookups are cached for 15 s.
  The session is re-checked right after the socket registers and then every `FAMILYCHAT_WS_REVALIDATE_SECONDS`
  (60 s) with a database lookup, so a device revoked by another process (the `cli` commands, which cannot kick
  sockets of the running server) loses its socket within that time; revocations through the API close it at once.
- Access tokens in the WebSocket query string (`?access_token=`, used by clients that cannot set headers) are
  redacted by a logging filter on uvicorn's loggers; the Authorization header is preferred. Request logs never
  contain query strings.
- Every conversation, message and attachment route checks membership; non-members get `404`.
- **Audit log:** the table `audit_events` records who did what to whom and from which address (`at`, actor,
  `action`, target user, target id, `ip`, small `details`). Actions: `invitation_created`, `invitation_revoked`,
  `invitation_redeemed`, `admin_granted` (an account created from an administrator invitation; there is no
  other way to change the role), `login_success`, `login_failure`, `password_changed`, `refresh_token_reuse`,
  `device_revoked` (`details.by`: `self`, `admin` or `logout`) and `user_removed` (API or CLI). It is written in the
  same transaction as the action; failed logins commit on their own. **Never stored:** passwords, invitation codes,
  tokens or token hashes; for a failed login on a username that does not exist the log says only `unknown user`
  (the name that was typed is not kept), for an existing account it records the account. Only administrators can
  read it (`GET /admin/audit`, or `python -m app.cli audit` on the server). Rows older than
  `FAMILYCHAT_AUDIT_RETENTION_DAYS` (default 180) are deleted by the hourly maintenance job. The log lives in the
  server's database: whoever controls the server can alter it, so it is a record for the family, not tamper-proof
  evidence.
- **Retention:** the hourly maintenance job deletes message envelopes (the ciphertext copies) that were delivered
  more than `FAMILYCHAT_ENVELOPE_RETENTION_DAYS` (default 30) days ago; once a device has fetched its copy the
  server has no use for it. Undelivered envelopes of active devices are kept for as long as the device is
  active, and revoking a device deletes its queued ones at once. Expired sessions and invitations are
  pruned as before.
- Attachments are stored under random UUID names outside any web root; client file names are never used on
  disk. Uploads are streamed with size limits and quotas; avatars are type-checked by magic bytes. The stored path
  of an attachment is validated (`xx/<uuid>` of that attachment, resolved inside the attachments directory) before
  every read, delete and on restore, so a tampered database cannot make the server read or delete other files.
- The JWT secret comes from the environment (`FAMILYCHAT_SECRET_KEY`, at least 32 bytes); the server refuses
  to start in production without it. Nothing secret is committed.
- Push notifications (UnifiedPush) carry no content at all — only "something new, please sync". The server
  only POSTs to https endpoints, and `FAMILYCHAT_PUSH_ALLOWED_HOSTS` limits them to your ntfy host so a
  logged-in user cannot make the server call arbitrary URLs (SSRF). The list is **required in production**: while
  it is empty, push registration is refused (`409 push_not_configured`), a warning is logged at startup and no
  push is sent. IP-literal hosts (incl. forms like `2130706433` or `0x7f.1`) are refused unless listed explicitly.
- The API listens on `127.0.0.1` in the Docker deployment; only Tailscale or the HTTPS reverse proxy reaches it.
  The containers run with `no-new-privileges`, all Linux capabilities dropped (Caddy keeps only
  `NET_BIND_SERVICE`) and, for the backend, a read-only root filesystem; the Caddy and ntfy images are pinned to
  versions.

## Backups and encryption

Server backups (SQLite online backup + attachments) contain accounts, password hashes, public keys, metadata
and **ciphertext only**. They cannot be used to read messages, and restoring a backup does not restore anyone's
ability to read old messages — that depends only on the phones. Keep backups private anyway: they contain
metadata and password hashes.

## Known limitations (be honest about these)

- Metadata (who, when, group names and members, receipts, sizes) is visible to the server.
- No sealed sender, no encrypted group metadata, no reproducible builds.
- Safety-number verification is manual (no QR scanning yet).
- The first device list fetched for a user, and the first view of a conversation's members, are accepted as the
  baseline without a notice. A server that is already malicious at that moment is not detected unless you
  compare safety numbers.
- A server can withhold, delay or drop messages, and can show a message as deleted before you received it (a
  tombstone without content). It cannot forge, edit, delete or re-route content you did receive.
- Display names, avatars and device names shown in notices are server data.
- No message history transfer between devices.
- Rate limiting (including the per-username login counters) is per-process memory and resets on restart.
- The security review in `HANDOVER.md` lists what was tested and what still needs an external review.
