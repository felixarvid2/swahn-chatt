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
  `{"v":1, "type":"text|edit|...", "text":..., "attachments":[{"id","key","sha256","size","mime","name",
  "kind":"image|video|voice|file","width","height","duration_ms","thumbnail"}], "reply_to":...}`.
- **Attachments** are encrypted on the phone with Google Tink streaming AEAD (`AES256_GCM_HKDF_4KB`) under a
  fresh random 32-byte key per file, then uploaded. The key, the ciphertext SHA-256, the real MIME type and the
  file name travel only inside the Signal-encrypted message. The server therefore cannot validate MIME types of
  message attachments; the client validates type and size after decryption and never trusts the server.
- **Corrupted or forged ciphertext** fails libsignal's MAC check or Tink's GCM tag check; the client shows
  "could not decrypt" for that message instead of crashing, and the error does not advance the ratchet.

## Identity verification

- Each device's identity key is trusted on first use and pinned locally. If a known device's key changes the
  message is rejected (libsignal `UntrustedIdentityException`) and the conversation shows a warning.
- When a contact adds a device (new phone, reinstall), the conversation shows "Anna added a new device".
- **Safety numbers:** the contact screen shows a 60-digit libsignal fingerprint per contact device; compare it
  in person or on a call and mark it verified. A later change of a verified device's key is blocked until the
  user re-verifies.

## Keys on the phone

- The local Room database (messages, contacts and the libsignal session/identity/prekey stores) is encrypted
  with SQLCipher. Its 256-bit passphrase is generated once and stored wrapped by an AES-GCM key in the Android
  Keystore (hardware-backed where available, non-exportable).
- Access and refresh tokens are stored in the same encrypted database.
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
- Passwords hashed with Argon2id (argon2-cffi defaults: t=3, m=64 MiB, p=4; tuned for a Pi 4).
- Short-lived JWT access tokens (15 min) + rotating refresh tokens with reuse detection. Every request checks
  that the session, device and user are still active.
- In-process rate limiting on login, invitation redemption, refresh and key fetching.
- Every conversation, message and attachment route checks membership; non-members get `404`.
- Attachments are stored under random UUID names outside any web root; client file names are never used on
  disk. Uploads are streamed with size limits and quotas; avatars are type-checked by magic bytes.
- The JWT secret comes from the environment (`FAMILYCHAT_SECRET_KEY`, at least 32 bytes); the server refuses
  to start in production without it. Nothing secret is committed.
- Push notifications (UnifiedPush) carry no content at all — only "something new, please sync". The server
  only POSTs to https endpoints, and `FAMILYCHAT_PUSH_ALLOWED_HOSTS` limits them to your ntfy host so a
  logged-in user cannot make the server call arbitrary URLs (SSRF).
- The API listens on `127.0.0.1` in the Docker deployment; only Tailscale or the HTTPS reverse proxy reaches it.

## Backups and encryption

Server backups (SQLite online backup + attachments) contain accounts, password hashes, public keys, metadata
and **ciphertext only**. They cannot be used to read messages, and restoring a backup does not restore anyone's
ability to read old messages — that depends only on the phones. Keep backups private anyway: they contain
metadata and password hashes.

## Known limitations (be honest about these)

- Metadata (who, when, group names and members, receipts, sizes) is visible to the server.
- No sealed sender, no encrypted group metadata, no reproducible builds.
- Safety-number verification is manual.
- No message history transfer between devices.
- Rate limiting is per-process memory and resets on restart.
- The security review in `HANDOVER.md` lists what was tested and what still needs an external review.
