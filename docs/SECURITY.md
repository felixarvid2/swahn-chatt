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
- **Reactions are bound to their target.** A reaction (`kind: "reaction"`) is sent like a message: encrypted for every
  device, the emoji inside the encrypted content together with the conversation id, its own id and the
  `target_message_id`. The server sees who reacted to which message and when (metadata, like any message) but never the
  emoji. A client applies it only if the decrypted target id equals the outer one and the target message exists in
  that conversation. Anyone in the conversation may react to any message; the server checks only membership, the
  target's conversation and that it is a live regular message.
- **Edits and deletes are authenticated.** Both are sent as encrypted content to every device. A client applies
  them only after decrypting content from the original author that names the target. Envelope-less edit or delete
  events (which only the server could produce) are ignored, so the server cannot wipe or rewrite messages on
  the phones. It can still withhold or drop ciphertext.
- **Disappearing messages: the server's timer is not trusted.** The conversation's `disappearing_seconds` is
  plain server state, so a malicious or hacked server (or any member's stolen token in a direct chat) could set it to
  five minutes and watch phones wipe their history if clients obeyed it. Clients therefore never delete local
  history because of `disappearing_seconds` or `expires_at`: the timer change travels as encrypted content from the
  member who made it (authenticated like an edit) and only that content sets the phones' own timers. The server
  value does one thing: the hourly maintenance job deletes expired **server-side** copies (messages, their
  ciphertext envelopes, attachment links; unreferenced attachments are collected afterwards), which limits how long
  undelivered ciphertext and metadata stay on the Pi. Worst case of a manipulated value is that undelivered
  messages are dropped earlier than intended (the server could withhold them anyway). Allowed values: none or 300 s to
  one year; `expires_at` is fixed when a message is stored.
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

## Links and drafts

- Links in messages are recognised only for `http`, `https`, `mailto` and `tel`. Nothing is fetched or
  previewed: link previews would make the phone contact a site before the user decided to.
- Tapping a link opens a confirmation dialog showing the full address and the host it leads to. It warns when the
  address hides something (user info before the host, non-ASCII or punycode host). Only after confirmation does
  the system open it; a phone number only opens the dialer.
- Unsent drafts are stored in the SQLCipher database. They are removed on send, when the conversation is
  removed, and on logout.

## Search, notifications and app lock

- **Search index:** message search uses an FTS4 index of the decrypted text inside the SQLCipher database. It is
  wiped on logout (`rebuild` and `VACUUM`), so no tokens remain after sign-out.
- **Notification content:** configurable as "Namn och meddelande", "Endast namn" or "Ingen information". The
  lock-screen (public) version of a notification never shows message text.
- **Inline reply:** sent through the normal outbox. Its broadcast receiver is not exported, and its PendingIntent
  is the only mutable one (explicit component). The reply action is hidden when notifications show
  "Ingen information" and while the app lock is on. A reply that arrives after either was switched on is dropped,
  and changing the settings reposts or removes notifications already shown.
- **App lock and screenshot blocking** (`FLAG_SECURE`) are UI gates only. The database key does not depend on the
  lock, so they protect against someone holding the unlocked phone, not against a forensic attacker.

## QR codes, local deletion and unknown content

- **QR codes are untrusted input.** An invitation QR (`familychat://invite?v=1&server=…&code=…`) is parsed
  strictly. It may only pre-fill the invitation form and shows the server host prominently, with a warning for
  punycode hosts. It never submits, never touches the login form or the saved server address, and accepts https
  only in release builds. No intent filter is registered for invitation links, so no other app or web page can open
  FamilyChat with an invitation. Safety-number QR codes carry the libsignal scannable fingerprint and are compared with
  `matchesScanned`.
- **"Radera för mig"** deletes the message, its receipts and its downloaded files on this phone only, and writes a
  local tombstone so a later sync, edit or receipt cannot bring it back. Nothing is sent. "Radera för alla" is the
  authenticated, encrypted delete described above.
- **Unknown content:** clients ignore unknown JSON fields. Well-formed, bound content of a type this version does
  not know is decrypted (so the ratchet stays in step), not shown, and leads to a single "Uppdatera appen" notice
  per conversation. Malformed or unbound content is rejected. Versions 0.1–0.3 still show such messages as "could
  not decrypt".
- **Reactions and disappearing-message timers** are encrypted, bound content like edits. A reaction is bound to
  its target and its author. A timer change applies only when it arrives as encrypted `timer` content from a
  member. In groups the receiving phone also requires that the sender is an admin in its local member list. The server's `disappearing_seconds` and `expires_at` only drive server-side pruning:
  a client never deletes local history because the server says so. Expiry starts when the message is first shown
  on the device and removes the text, files and search index rows. The search index is then merged and the
  database overwrites deleted pages (`secure_delete`), so purged words do not linger.
- **Share target:** FamilyChat accepts `ACTION_SEND`/`ACTION_SEND_MULTIPLE` from other apps. Shared items are
  untrusted: only `content://` URIs are read (never `file://` or the app's own provider, also with a `0@` user prefix), copies are size-limited
  and never read past the limit, names are sanitised, and the app lock must be unlocked before conversations are
  shown.
- **Forwarding** re-encrypts the content for the target conversation and re-uploads attachments under fresh keys.
- **History export/import** (moving to a new phone): one file, format `FCHX` version 1. The header holds the KDF
  id and parameters: Argon2id (BouncyCastle) with 64 MiB memory, t=3, p=1 and a 16-byte random salt. The payload is
  a ZIP encrypted with Tink streaming AEAD `AES256_GCM_HKDF_1MB`, with the whole header as associated data.
  Passphrases have at least 12 characters and are never stored. The file never contains keys, sessions, tokens or
  identity material (tested). Imports are verified completely before anything is merged. Imported messages are
  marked "Importerad", are never re-sent and only go into existing conversations of the same account. Expiring
  messages are not exported. The file is as strong as its passphrase, so use a long one.
- **Import trusts nothing in the file.** A message is taken only if its sender is a member of that conversation on
  this phone, and its `seq` is clamped to the conversation's last known one. Imported rows never count for unread
  counts, read marks, notifications or "latest message". "Importerad" is always shown. Only this account's own
  reactions are imported. Limits count decompressed bytes per entry and in total, with at most 200,000 message
  lines. The header may ask for at most 128 MiB and 6 passes, and running out of memory is a refusal, not a crash.
  Imported media is never removed by "clear downloaded media".

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

- Accounts only via expiring invitation codes (stored as SHA-256 hashes), single use unless an administrator
  sets `max_uses` (2..20; never for administrator invitations); the use counter is claimed with one atomic
  `UPDATE ... WHERE uses < max_uses`, so concurrent redemptions cannot exceed it. No phone numbers.
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
  because refreshing and API calls are not logins. The counters are in memory for speed, but every *failure* is
  also written to `login_failures` (an HMAC of the username keyed with the server secret, plus a timestamp: unknown
  names are not stored) and replayed at startup, so restarting the server does not hand a guesser a fresh
  budget. Nothing is written for successful logins or other requests; rows older than a day are pruned. (Without a
  fixed `FAMILYCHAT_SECRET_KEY`, i.e. development, the key changes per start and the replay finds nothing.)
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
- **Suspension, roles, self-deletion.** An administrator can suspend an account (`POST /admin/users/{id}/suspend`):
  the session check in front of every route answers `403 account_suspended`, login (after the password was
  verified, so usernames are not revealed), refresh and password reset codes are refused, open sockets are
  closed. Nothing is deleted: memberships, devices and sessions stay, so unsuspending restores the account and
  senders keep encrypting for its devices. Promoting and demoting (`PATCH /admin/users/{id}`) refuses to leave
  the server without an **active** administrator (not removed, not suspended), the caller included; the check
  runs inside the serialised write transaction, so two administrators cannot demote each other at once. Users
  can delete their own account (`DELETE /users/me`) after re-entering the password (rate limited, failures
  audited); a stolen unlocked phone with a valid session therefore cannot erase the account without the password.
  The effect is that of an administrator's removal, plus the avatar being unlinked and the account's
  unreferenced attachments being garbage-collected at the next hourly run.
- **Re-authentication and lockout:** the password prompts of `DELETE /users/me` and `PUT /users/me/password` count wrong passwords toward the same persistent per-username lockout as `/auth/login` (`429` while locked, surviving restarts); `PUT /users/me/password` audits `password_change_failed` (no passwords are stored).
- **Admin races:** every mutating `/admin` route re-reads the caller inside its write transaction (writers are serialised by the database) and refuses with `403`/`401` when the caller was suspended, removed or demoted since the token was checked; suspending or demoting the last active administrator is `409 last_admin`. Two administrators suspending each other can never both succeed.
- **Metrics:** `GET /metrics` exposes counts and histograms by route template and status, gauges for sockets,
  queues, database size and free disk, and nothing per user (no ids, names or addresses in labels). The `method` label is one of GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS or `OTHER`, and a request that matches no route has the route `unmatched`, so an unauthenticated client cannot create unbounded series. It answers
  only a request that comes from this machine without being forwarded by a proxy (so Caddy or `tailscale serve`,
  which forward remote clients from `127.0.0.1` with `X-Forwarded-For`, do not open it) or an administrator's
  bearer token; everybody else gets `404`, as if it did not exist.
- **Resumable uploads:** a session reserves quota for its full size when it starts, belongs to its creator
  (everyone else gets `404`), expires after 24 hours and is deleted with its temp file by the hourly job. Temp files
  are named by the server-generated session id only, live under `attachments/.tmp/uploads/`, are created `0600`
  and are excluded from backups. Chunks are limited to 8 MiB, offsets are checked, an interrupted chunk is cut off
  again, and the optional sha256 is verified over the whole file before it becomes an attachment.
- **Audit log:** the table `audit_events` records who did what to whom and from which address (`at`, actor,
  `action`, target user, target id, `ip`, small `details`). Actions: `invitation_created`, `invitation_revoked`,
  `invitation_redeemed` (once per use of a multi-use code, with the new user as target and `details.use`/`max_uses`),
  `admin_granted` (`via` = `invitation` or `admin`) and `admin_revoked` (role changes through `PATCH /admin/users/{id}`),
  `user_suspended`, `user_unsuspended`, `login_blocked` (right password, suspended account), `self_delete_failed`, `password_change_failed`,
  `presence_setting_changed` (`share_presence` true/false), `login_success`, `login_failure`, `password_changed`, `refresh_token_reuse`,
  `device_revoked` (`details.by`: `self`, `admin` or `logout`), `user_removed` (API or CLI; `details.via` = `self` for self-deletion), `password_reset_issued`
  (`details.via`: `admin` or `cli`) and `password_reset_used` (`details.revoked_devices`). It is written in the
  same transaction as the action; failed logins commit on their own. **Never stored:** passwords, invitation codes,
  tokens or token hashes; for a failed login on a username that does not exist the log says only `unknown user`
  (the name that was typed is not kept), for an existing account it records the account. Only administrators can
  read it (`GET /admin/audit`, or `python -m app.cli audit` on the server). Rows older than
  `FAMILYCHAT_AUDIT_RETENTION_DAYS` (default 180) are deleted by the hourly maintenance job. The log lives in the
  server's database: whoever controls the server can alter it, so it is a record for the family, not tamper-proof
  evidence.
- **Password reset by an administrator:** the administrator (API) or whoever has the server shell (CLI) gets a
  single-use code, valid 24 hours, stored as a SHA-256 hash (in its own hash domain, so an invitation code is
  not a reset code), which they pass to the user. Redeeming it sets the new password and **ends everything that was
  signed in**: all sessions and devices of the account are revoked, their keys and queued ciphertext deleted, their
  sockets closed, and a new device with new keys is created for the person resetting. This is deliberately as
  disruptive as a lost phone: whoever knows the code (including an administrator who intercepts it) gets an
  account that can log in but **cannot read any past message**, and every contact sees "new device" for this
  person and, where devices were verified, has to approve it. The code is rate limited like invitation redemption
  and never appears in the audit log or the logs; both events are audited. An administrator cannot read the user's
  old messages this way, but can impersonate the user from then on, which contacts can only notice through that
  new-device notice.
- **Request IDs:** every request gets an `X-Request-ID` (a client value is used only if it matches
  `^[A-Za-z0-9._-]{8,64}$`, so it cannot inject log lines or grow without bound). It appears in the response, in
  every JSON log line of that request and in error bodies, and carries no personal data.
- **Database maintenance:** once a week the maintenance job runs `PRAGMA optimize`, `ANALYZE`, a WAL checkpoint and, if
  more than 20 % of the SQLite file is free pages, `VACUUM` (PostgreSQL: `ANALYZE`). It runs on the maintenance thread,
  not in a request; VACUUM briefly blocks writers, so it waits for a quiet minute and for enough free disk. Backups
  are unaffected (they use the SQLite online backup API).
- **Retention:** the hourly maintenance job deletes message envelopes (the ciphertext copies) that were delivered
  more than `FAMILYCHAT_ENVELOPE_RETENTION_DAYS` (default 30) days ago; once a device has fetched its copy the
  server has no use for it. Undelivered envelopes of active devices are kept for as long as the device is
  active, and revoking a device deletes its queued ones at once. Expired sessions, invitations
  and password reset codes are pruned as before, expired disappearing messages are deleted (see above) and avatars
  that no user or conversation uses any more are removed after 24 hours.
- Attachments are stored under random UUID names outside any web root; client file names are never used on
  disk. Uploads are streamed with size limits and quotas; avatars are type-checked by magic bytes. The stored path
  of an attachment is validated (`xx/<uuid>` of that attachment, resolved inside the attachments directory) before
  every read, delete and on restore, so a tampered database cannot make the server read or delete other files.
- `python -m app.cli verify-backup <archive>` checks a backup completely without restoring it (entry names,
  manifest, checksums, SQLite integrity check on a temporary copy, attachment paths); `check-config` prints the
  effective non-secret settings and warns about risky ones (production without a push allow-list, an unset or short
  secret, public API docs, trusted proxies missing behind a proxy, low disk).
- The JWT secret comes from the environment (`FAMILYCHAT_SECRET_KEY`, at least 32 bytes); the server refuses
  to start in production without it. Nothing secret is committed.
- Push notifications (UnifiedPush) carry no content at all — only "something new, please sync". The server
  only POSTs to https endpoints, and `FAMILYCHAT_PUSH_ALLOWED_HOSTS` limits them to your ntfy host so a
  logged-in user cannot make the server call arbitrary URLs (SSRF). The list is **required in production**: while
  it is empty, push registration is refused (`409 push_not_configured`), a warning is logged at startup and no
  push is sent. IP-literal hosts (incl. forms like `2130706433` or `0x7f.1`) are refused unless listed explicitly.
- Response headers on every reply: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `X-Frame-Options: DENY`, a minimal `Permissions-Policy` (camera, microphone, geolocation, payment, usb off),
  `Cross-Origin-Resource-Policy: same-site`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` and
  `Cache-Control: no-store` (a route may set its own, e.g. attachment downloads). Only the Swagger UI page
  (`/api/v1/docs`, off in production unless `FAMILYCHAT_EXPOSE_DOCS=true`) gets a relaxed CSP: it allows scripts and
  styles from `cdn.jsdelivr.net`, the favicon from `fastapi.tiangolo.com` and inline script/style, which Swagger UI
  needs, so do not expose the docs on a server you do not control. `Strict-Transport-Security` (`max-age=31536000`, no
  `includeSubDomains`) is sent only with `FAMILYCHAT_HSTS=true`; turn it on only when every client reaches the server
  over HTTPS (Caddy or `tailscale serve`), because browsers then refuse plain http for that host for a year.
- Operator alerts (optional): `FAMILYCHAT_ADMIN_NTFY_URL` (the full topic URL, `https` only except in development) and
  `FAMILYCHAT_ADMIN_NTFY_TOKEN` make the server post a short fixed plain-text message to that ntfy topic when disk
  space is low (once per 6 h), the hourly or database maintenance fails (once per 6 h), a login lockout was triggered
  (it says only that, not for whom, once per 30 min), a refresh token was reused (once per 10 min) and a new
  administrator was granted (once per minute); at most 20 alerts per hour in all. Alerts contain no user names,
  addresses, message data or secrets and are sent from a background thread, so a dead ntfy server never delays a request
  (failures are logged without the URL). Failing backups are not seen by the server (they run outside it): use the
  systemd `OnFailure` unit described in the operations handbook. Use a topic that only you can read: anyone who knows
  the topic URL can read the alerts, and with an open ntfy server also post to it.
- Push health and test: the server stores when it last pushed to each device and the HTTP status it got, and
  administrators see this in `GET /admin/users` (never the endpoint URL). `POST /devices/me/push/test` lets a device
  send itself one content-free push (3 per 10 minutes).
- The API listens on `127.0.0.1` in the Docker deployment; only Tailscale or the HTTPS reverse proxy reaches it.
  The containers run with `no-new-privileges`, all Linux capabilities dropped (Caddy keeps only
  `NET_BIND_SERVICE`) and, for the backend, a read-only root filesystem; the Caddy and ntfy images are pinned to
  versions.

## Calls (server and operations)

Audio/video calls are WebRTC between the phones; media is DTLS-SRTP, end to end (the relay only forwards encrypted
packets). Signalling (offer, answer, ICE candidates, hangup, busy) is ordinary end-to-end encrypted content in
messages of kind `call`, so the server never sees SDP, which also means it sees no codecs, call type or the phones'
addresses in signalling. What the server does learn is metadata: who signalled whom and when (a `call` message), how
many, and that a push marked urgent was sent.

- `call` messages: direct conversations only, full device-set envelopes, no attachments or targets, no effect on
  `last_message_*`, unread counts or history, 60 per minute per sender, and **deleted with their envelopes 10
  minutes after sending** (`FAMILYCHAT_CALL_RETENTION_SECONDS`), delivered or not.
- The push for a `call` message is content-free like all pushes (`{"t":"sync"}`); it only adds `Priority: 5` and
  `Urgency: high` headers so the phone is woken at once. A `{"t":"call"}` body was rejected on purpose: the header
  already tells the push server (your own ntfy) "urgent", and nothing more should leak to a third-party distributor.
- TURN credentials (`GET /calls/turn`) follow the TURN REST API: username `<expiry>:<user_id>`, credential
  `base64(HMAC-SHA1(secret, username))`, valid one hour, handed only to authenticated users (30 requests per minute).
  HMAC-SHA1 is what coturn defines; it is a keyed MAC here, not a hash used for collision resistance. The shared
  secret (`FAMILYCHAT_TURN_SECRET`, at least 32 bytes) is never logged or printed by `check-config`; a leaked credential
  works for at most its lifetime. coturn limits allocations per user (10) and in total (40), restricts relays to UDP
  ports 49160-49200, and has no CLI or web admin.
- **TURN sees IP addresses**, and the peers see each other's unless the app forces relay-only candidates. In a direct
  connection the other party learns your current IP (a stalker-style risk when calling someone you do not trust); with
  relay-only the callee sees only the relay's address, at the price of the server's bandwidth and latency. The TURN
  server sees both phones' IPs and traffic volume and timing, never the content.
- The relay must not become a tunnel into the server's network: coturn runs with `denied-peer-ip` for loopback,
  link-local, RFC 1918, ULA and `0.0.0.0/8`, with multicast peers off, **and for the Tailscale range
  `100.64.0.0/10` (+ `fd7a:115c:a1e0::/48`) by default**. Anyone holding a credential could otherwise ask the relay to
  reach every other host on the tailnet that the Pi can reach. Allowing it (`TURN_ALLOW_TAILNET_PEERS=true`) is only
  needed when phones reach each other through the relay while on the tailnet; most of the time tailnet phones connect
  directly and do not need it.
- Opening the TURN ports to the internet exposes a UDP/TCP service that only answers authenticated requests, but it
  can still be probed and, with a leaked credential, used for free bandwidth until it expires. Prefer reaching it
  over Tailscale; for a public setup forward only 3478 (and 5349 with TLS) plus the relay range.

## Presence (online / last seen)

Presence is **opt-in and off by default** (`PATCH /users/me/settings {"share_presence": true}`), and the server
enforces it: for a user who has not opted in nothing is recorded (no `presence_last_seen_at`), no WebSocket
frame about them is ever sent, and `GET /users` returns `online: null, last_seen_at: null` for them. A device's
`last_seen_at` is presence too, so `GET /users/{id}/devices` shows other people the device's creation time instead
unless the owner opted in. Switching sharing off deletes the stored time and, if contacts had been told something,
sends them one frame saying "unknown" (`online: false, last_seen_at: null`, indistinguishable from a user who never
shared). Administrators still see each account's real last activity in `GET /admin/users` (operational data,
as before).

Trade-offs to be honest about: whoever opts in tells every user who shares a conversation with them (frames) and
in fact **every account on the server** (`GET /users` lists all users) when they connect and disconnect, which
reveals daily rhythms and whether a message is likely to be read soon. The debounce (10 s) only limits noise, it
does not hide anything. Presence follows the WebSocket, which the app keeps open only while it is in the
foreground. Contacts cannot opt out of *seeing* it except by ignoring the field, and the setting is per user, not
per contact. If that is too much for your family, simply leave it off: the default sends nothing.

## Backups and encryption

Server backups (SQLite online backup + attachments) contain accounts, password hashes, public keys, metadata
and **ciphertext only**. They cannot be used to read messages, and restoring a backup does not restore anyone's
ability to read old messages — that depends only on the phones. Keep backups private anyway: they contain
metadata and password hashes.

## Known limitations (be honest about these)

- Metadata (who, when, group names and members, receipts, sizes) is visible to the server.
- No sealed sender, no encrypted group metadata, no reproducible builds.
- Safety-number verification is manual: compare the 60 digits or scan the other phone's QR code in person.
- The first device list fetched for a user, and the first view of a conversation's members, are accepted as the
  baseline without a notice. A server that is already malicious at that moment is not detected unless you
  compare safety numbers.
- A server can withhold, delay or drop messages, and can show a message as deleted before you received it (a
  tombstone without content). It cannot forge, edit, delete or re-route content you did receive.
- Display names, avatars and device names shown in notices are server data.
- No message history transfer between devices.
- Rate limiting (including the per-username login counters) is per-process memory and resets on restart.
- Calls leak IP addresses to the TURN server and, without relay-only, to the other party (see "Calls").
- The security review in `HANDOVER.md` lists what was tested and what still needs an external review.
