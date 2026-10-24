# FamilyChat architecture

```
 Android phone (Kotlin)                         Raspberry Pi (Docker)
 ┌──────────────────────────────┐   HTTPS/WSS   ┌───────────────────────────────┐
 │ Compose UI ─ ViewModels      │ (Tailscale or │ FastAPI (uvicorn, 1 worker)   │
 │ Repositories (Room+SQLCipher)│  Caddy proxy) │  REST /api/v1  +  WS /api/v1/ws│
 │ :core:client  sync engine    │──────────────▶│  SQLAlchemy ─ SQLite (WAL)    │
 │ :core:crypto  libsignal/Tink │               │  attachments on disk volume   │
 │ :core:network Retrofit/OkHttp│◀── push ──────│  UnifiedPush POST (no content)│
 └──────────────────────────────┘   (ntfy)      └───────────────────────────────┘
```

## Principles

1. **The server is a mailbox, not a reader.** It authenticates people, enforces membership, stores public keys,
   opaque ciphertext and metadata, and fans events out to devices. It never decrypts.
2. **Pull is the source of truth, push is a hint.** Each device has a persistent event queue on the server
   (`events` table, cursor = event `seq`). The client pulls with `GET /sync?after=cursor` and acknowledges with
   `POST /sync/ack`. The WebSocket and push notifications only say "sync now". A dropped socket, a server
   restart or a week offline cost nothing but a longer sync.
3. **Everything is idempotent.** Message IDs are client-generated UUIDs and double as idempotency keys; events
   are re-delivered until acknowledged; the client upserts by ID. Duplicate sends, retries after timeouts and
   duplicate deliveries are harmless.
4. **Ordering by server sequence.** Messages get a server `seq` on arrival; clients sort by it, so clocks and
   out-of-order delivery do not matter. `client_ts` is display-only.
5. **Small and boring on the Pi.** One Python process, SQLite, files on disk. No Redis, no broker. The
   SQLAlchemy layer avoids SQLite-only SQL so PostgreSQL is a configuration change plus a migration run.

## Backend (`backend/`)

- Python 3.12, FastAPI, Pydantic v2, SQLAlchemy 2 (sync engine, endpoints run in the threadpool), Alembic,
  argon2-cffi, PyJWT, httpx (push), uvicorn.
- `app/main.py` app factory; `app/config.py` settings from `FAMILYCHAT_*` environment variables;
  `app/db.py` engine/session (SQLite WAL, `busy_timeout`, foreign keys on; PostgreSQL write lock, see
  "Sync cursor"); `app/models.py`; `app/request_id.py` (X-Request-ID middleware and log context);
  `app/openapi.py` (OpenAPI post-processing);
  `app/schemas.py`; `app/security.py` (Argon2id, JWT, token hashing); `app/ratelimit.py`;
  `app/routers/*` (auth, admin, users, devices, keys, conversations, messages, sync, attachments, ws, health);
  `app/services/*` (events fan-out, membership, delivery receipts, push, password reset, backup/verify, config check, maintenance);
  `app/hub.py` (in-memory WebSocket
  connection registry; one process, so no broker is needed); `app/cli.py` (invites, users, password reset, backup,
  verify-backup, restore, check-config, seed-dev).
- WebSocket fan-out works because there is exactly one worker. Running several workers would need a broker
  (Redis/Postgres LISTEN) — documented, deliberately not built.

### Data model

| Table | Key columns |
|---|---|
| `users` | id, username (unique), display_name, password_hash, is_admin, avatar_attachment_id, created_at, disabled_at |
| `invitations` | id, code_hash (unique), note, is_admin, created_by, created_at, expires_at, used_at, used_by, revoked_at |
| `devices` | id, user_id, number (1..127, unique per user), name, created_at, last_seen_at, revoked_at, push_kind, push_endpoint, acked_cursor |
| `sessions` | id, user_id, device_id, refresh_token_hash (unique), created_at, expires_at, revoked_at |
| `session_tokens` | token_hash (PK), session_id — every refresh token a session issued, for reuse detection |
| `device_keys` | device_id (PK), registration_id, identity_key, signed_prekey_id/public/signature, pq_last_resort_id/public/signature, updated_at |
| `one_time_prekeys` | device_id, key_id, public_key (unique device_id+key_id) |
| `one_time_pq_prekeys` | device_id, key_id, public_key, signature |
| `password_resets` | id, user_id, code_hash (unique), created_by (NULL = CLI), created_at, expires_at (24 h), used_at, revoked_at |
| `conversations` | id, type, name, avatar_attachment_id, direct_key (unique, for direct pairs), created_by, created_at, updated_at, last_message_at, last_message_seq, disappearing_seconds (NULL or 300..31536000; server pruning only) |
| `conversation_members` | conversation_id, user_id, role, joined_at, read_seq |
| `messages` | id (client UUID), seq (unique, monotonic), conversation_id, sender_user_id, sender_device_id, kind (`message`, `edit`, `delete`, `reaction`), target_message_id, client_ts, server_ts, deleted_at, edited_at, expires_at (indexed; server_ts + the conversation's timer at insert, NULL = keep) |
| `message_envelopes` | message_id, device_id, type, body (ciphertext), delivered_at — **the message delivery state** |
| `message_attachments` | message_id, attachment_id |
| `attachments` | id, uploader_id, purpose, content_type, size, sha256, storage_path, created_at |
| `events` | seq (PK autoincrement = sync cursor), device_id, type, conversation_id, payload (JSON), created_at |
| `delivery_receipts` | message_id, user_id, delivered_at (one per recipient user, drives the `delivered` receipt) |
| `audit_events` | id (autoincrement), at, actor_user_id, action, target_user_id, target_id, ip, details (JSON; never secrets) |
| `counters` | name, value (used for monotonic message `seq`, works on SQLite and PostgreSQL) |

### Sync cursor and the database

The sync cursor is `events.seq`: a device that has processed event N asks for `seq > N`. That is only correct if a
lower `seq` never becomes visible after a higher one. **SQLite** guarantees it: every writing transaction starts
with `BEGIN IMMEDIATE`, so writers queue and `AUTOINCREMENT` numbers are handed out in commit order. **PostgreSQL**
allocates sequence values at insert time, so two concurrent transactions could commit in the opposite order and a
device would skip the event that committed late. The choice made here: every *writing* transaction takes one
transaction-scoped advisory lock (`pg_advisory_xact_lock`, `SET LOCAL lock_timeout = '10s'`) as its first
statement (`app/db.py`, same place as SQLite's `BEGIN IMMEDIATE`), so writers are serialised exactly as on SQLite and
commit order equals `seq` order. Read-only sessions (`get_read_db`) do not take it, so reads never wait. Why not a
lock row or "expose events only below the smallest in-flight seq": a lock row taken only at the first `emit` can
deadlock with row locks taken earlier in the same transaction (for example revoking a device), and hiding in-flight
seqs needs a bookkeeping table of open transactions; serialising whole transactions needs neither and matches the
behaviour the code was written against. Cost: no write concurrency, which one Pi with ten users never uses (Argon2
never runs inside a transaction, so the lock is held for milliseconds). The monotonic message `seq` uses the
`counters` row (`UPDATE ... SET value = value + 1` takes the row lock on both databases).
`tests/test_postgres.py` proves the ordering and shows what goes wrong without the lock.

Run the suite against PostgreSQL with `FAMILYCHAT_TEST_DATABASE_URL=postgresql+psycopg://user:pw@host:5432/postgres`
(a role that may create databases); tests that need the SQLite file itself are skipped there. SQLite stays the
default and the only backend `backup`/`restore`/`verify-backup` support; PostgreSQL is backed up with `pg_dump`.

Event fan-out: when something happens (message, membership change, read receipt…) the service writes one row
per affected device into `events` in the same transaction, then after commit tells the hub to send
`{"type":"sync"}` to connected devices and queues a content-free push to the rest.

## Android (`android/`)

Gradle modules (Kotlin 2, version catalog in `gradle/libs.versions.toml`):

| Module | Kind | Contents |
|---|---|---|
| `:core:model` | Kotlin/JVM | API DTOs (kotlinx.serialization), the encrypted `Content` format and padding |
| `:core:network` | Kotlin/JVM | Retrofit + OkHttp API client, token refresh `Authenticator`, WebSocket client with backoff |
| `:core:crypto` | Kotlin/JVM | libsignal wrapper: key generation, prekey bundles, session building, encrypt/decrypt fan-out, safety numbers; Tink attachment cipher. Store interfaces so Android and tests can supply storage |
| `:core:client` | Kotlin/JVM | The messaging engine: send pipeline (encrypt per device, 409 retry, idempotent outbox), sync loop (pull/ack, event handling), key top-up. Talks to storage through interfaces |
| `:app` | Android | Room + SQLCipher implementations of the stores, Hilt DI, Android Keystore, Compose/Material 3 UI, ViewModels (StateFlow), WorkManager (outbox and periodic sync), UnifiedPush receiver, MediaRecorder for voice |

Keeping the engine and crypto in plain JVM modules is what lets us test real two-user, end-to-end encrypted
messaging against a real backend in `./gradlew test`, without an emulator (`:core:client` integration test).
The JVM tests use `org.signal:libsignal-client` (Linux natives); the app uses `org.signal:libsignal-android`.

Client data flow:
- **Send:** UI → repository writes the message to Room as `pending` (outbox) → engine encrypts for every
  device and `POST /messages` → on `201/200` marks `sent` with the server `seq`; on network error WorkManager
  retries with the same message ID; on `409 mismatched_devices` refresh keys and retry.
- **Receive:** WebSocket `sync` hint, push, app start, or periodic WorkManager job → `GET /sync` → decrypt and
  upsert into Room → `POST /sync/ack` only after the batch is committed locally → UI observes Room flows.
- Unread counts and conversation order come from Room (`last_message_seq`), corrected by the server's
  `GET /conversations` on full refresh.

### App internals (`:app`)

- **Storage.** One Room database, encrypted with SQLCipher, holds `LocalStore` (messages, conversations, outbox, cursor),
  `ProtocolStorage` (libsignal identity, prekeys, sessions, pinned remote identities, all keyed by device UUID) and the tokens. The passphrase is
  32 random bytes, wrapped by a non-exportable AES-256-GCM key in the Android Keystore (StrongBox where available); the
  wrapped blob lives in `noBackupFilesDir`. The database is opened lazily on a background thread.
- **Atomic decrypt-and-store.** libsignal advances its ratchet while decrypting; `SyncProcessor` therefore decrypts and
  writes the plaintext inside one `LocalStore.blockingTransaction` on one thread, so a crash cannot leave an advanced
  ratchet without its message (the replay decrypts again). Lock order everywhere: crypto lock (`MessageCrypto.exclusive`)
  first, database transaction second. `RoomLocalStore` runs calls inline when the thread is already in a transaction.
- **Background.** WebSocket (`engine.startRealtime()`) only while the app is visible (`ProcessLifecycleOwner`);
  WorkManager: `OutboxWorker` (on every send and when connectivity returns), `SyncWorker` (expedited, started by a
  UnifiedPush wake-up) and `PeriodicSyncWorker` (15 minutes). Notifications are built locally from the decrypted
  messages after a sync, never from the push.

- The Room schema is at version 2 (a `drafts` table) with a tested `MIGRATION_1_2`. Schema changes always bump the
  version and ship a migration plus a `MigrationTest`, because installed phones keep their database.
- The conversation's message list is a Room `PagingSource` (Paging 3), newest first. Date separators and the
  unread divider are added with `insertSeparators`.

## Notifications: FCM vs self-hosted

| | Firebase Cloud Messaging | UnifiedPush (ntfy on the Pi) | Own foreground service + WebSocket |
|---|---|---|---|
| External dependency | Google, Firebase project, `google-services.json`, service-account key on the Pi | None (ntfy runs on the Pi; the phone needs the ntfy app as distributor) | None |
| Reliability in Doze / app killed | Best (high-priority FCM is exempt) | Good: one shared connection held by the distributor app; needs battery-optimisation exemption for ntfy | Good while the service runs, but needs a permanent notification, costs battery, and Android 14+ restricts foreground service types |
| Works without Google Play | No | Yes | Yes |
| Privacy | Google sees timing/device tokens | Stays on your network | Stays on your network |

**Decision:** UnifiedPush with ntfy is the default (self-hosted, no Google). The server POSTs only
`{"t":"sync"}` to the device's endpoint — no sender, no text. FCM can be added later behind the same
`push_kind` column; the UnifiedPush FCM distributor is also an option. In addition the app syncs on open,
keeps a WebSocket while in the foreground, and runs a 15-minute WorkManager periodic sync as a safety net.

## Networking: VPN-only vs public HTTPS

- **Tailscale (recommended):** the Pi is only reachable inside your tailnet. `tailscale serve` provides HTTPS
  with a valid `*.ts.net` certificate. Nothing is exposed to the internet, so brute-force and 0-day risk
  is minimal. Cost: every family phone needs the Tailscale app and stays logged in; push via ntfy also runs
  through the tailnet.
- **Public HTTPS (Caddy + domain + port 443 forwarded):** no VPN app needed and easier for less technical
  relatives, but the login API is on the internet (rate limiting and Argon2 help; keep the Pi patched).
  Caddy obtains Let's Encrypt certificates automatically. Never forward port 8000 (plain HTTP).
