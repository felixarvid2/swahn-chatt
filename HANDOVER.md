# FamilyChat handover

Last updated: 2026-10-24. Branch `ccr-187bef76-iz1nsw`, PR #54 in felixarvid2/test.

## State in one paragraph

The code for all phases is written: backend, Docker deployment, the Android core modules and the Android app. Real
end-to-end encrypted messaging between independent clients is proven by automated tests against a real running
backend. **Nothing has been run on a physical phone, an emulator or a Raspberry Pi yet**, and there has been no
external security review. The build is therefore **not production-ready**. It has no "insecure" plaintext mode:
E2EE is on from the first build, and the server rejects nothing but also understands nothing about message content.

## Verified (actually executed in this session)

| Check | Result |
|---|---|
| Backend pytest (`backend/`, `.venv/bin/python -m pytest`) | 121 passed: auth, invitations, rate limits, admin removal, conversation authorization, group membership changes, keys, delivery, duplicate suppression, concurrent sends, offline sync, edits/deletes, receipts, attachments (limits, quota, checksum, unauthorized access, Range), push (content-free, host allow-list), migrations (upgrade, empty autogenerate diff, downgrade), backup/restore (including corrupted archives), plaintext-free storage |
| `ruff check` / `ruff format` | clean |
| `pip-audit -r requirements.txt` | no known vulnerabilities |
| Docker image (amd64) | builds, runs as non-root, migrates on start, healthy; compose restart and down/up keep data; `backup.sh`/`restore.sh` round-trip; Caddy (TLS + WebSocket) and ntfy profiles start |
| Android `./gradlew build` | passes, including lint and R8 `assembleRelease`. Unit tests: `:core:model` 11, `:core:network` 32, `:core:crypto` 36 (real libsignal on the JVM: sessions, multi-device, out-of-order, corrupted/truncated/garbage ciphertext, replay, identity change, padding, attachment tamper/wrong key, safety numbers), `:core:client` 36, `:app` 154 (Room store contracts, transactions, token storage, Keystore vault logic, notifications, ViewModels, Robolectric screenshots) |
| `android/scripts/run-e2e.sh` | `TwoUserEndToEndTest` 7/7 and `RoomEngineE2ETest` 1/1 against a real backend. These cover real-time delivery both ways, receipts, edit/delete, 30-message offline catch-up without duplicates, an idempotent duplicate send, an encrypted attachment round-trip, group member removal, a second device, and a raw scan of the server's SQLite file + WAL that finds no plaintext |
| APKs | debug `android/app/build/outputs/apk/debug/app-debug.apk` (51 MB), release `.../release/app-release.apk` (29 MB, R8, signed with the debug key) |

## Not verified

- **On a phone:** SQLCipher native loading, Android Keystore wrapping, R8 keep rules at runtime, UnifiedPush
  delivery with the app killed and the device in Doze, WorkManager timing, notifications, the microphone and
  camera flows, and the photo picker. Robolectric tests use plain SQLite. Do the first-phone check at the end of
  `docs/ANDROID_SETUP.md` §8 first.
- **ARM64 Docker image:** not built (this build machine has no binfmt/QEMU). All 33 pinned Python packages
  resolve as aarch64 cp312 wheels and the base image is multi-arch, but run `docker compose build` on the Pi and
  watch it.
- **PostgreSQL:** never run. The events cursor assumes a single writer (SQLite); Postgres would need
  sequence/locking review.
- **Push through Tailscale from inside the container** (MagicDNS resolution) has not been tried; see
  RASPBERRY_PI_SETUP.md.

## Architecture decisions (details in docs/)

1. **libsignal 0.86.5** (AGPL-3.0) for E2EE, with per-device identities and pairwise fan-out to every device of
   every member. This also handles group membership changes securely. The whole project is AGPL-3.0 as a result.
2. **The server is a mailbox.** Per-device event queues with pull + ack are the source of truth. WebSocket and
   push are only hints. Client-generated message UUIDs are the idempotency keys.
3. **SQLite + one uvicorn worker.** The WebSocket hub lives in memory. Several workers would need a broker.
4. **UnifiedPush/ntfy instead of FCM**, so there is no Google dependency. The 15-minute periodic sync is the
   safety net.
5. **Tailscale is the recommended network.** Caddy with public HTTPS is the alternative.
6. **Android core is plain JVM modules**, so crypto and the sync engine are tested without an emulator. The app
   uses `libsignal-android` and `tink-android`; the JVM tests use `libsignal-client` and `tink`.
7. Decrypt and the message write run in one Room transaction, so a crash cannot lose a message whose ratchet
   step was already stored.

## Known limitations and outstanding issues

- Metadata is visible to the server: who, when, group names and members, receipts and sizes. Group names and
  user avatars are not E2EE.
- A new device cannot read older messages, because there is no history transfer. A passphrase-protected local
  history export/import is the next feature to build.
- Safety numbers are per device and manual. There is no QR scanning UI yet; the scannable bytes are computed but
  not rendered.
- Rate limits live in process memory and reset on restart.
- Delivered ciphertext stays in the server DB. Pruning delivered envelopes is a cheap hardening step.
- A permanently rejected edit or delete can leave the local view diverged. Normal messages get FAILED + retry.
- Notifications are not updated when a message is edited. GIFs are sent as still JPEGs. A voice recording is
  discarded when the app goes to the background.
- The debug build's network security config allows cleartext platform-wide. The app's own URL check restricts it
  to localhost and private LAN ranges. Release builds are HTTPS-only.
- Build sandbox quirk: Maven Central returned random 404/429 here, so a local retrying mirror
  (`/root/maven-mirror`, outside the repo) was used. A normal machine does not need it.

## Next steps (in order)

1. Install the debug APK on two phones and do the first-phone check (end of ANDROID_SETUP.md §8), then fix what breaks (SQLCipher, R8, push).
2. Build and run the Docker stack on the Pi 4/5. Set up `tailscale serve`, ntfy, `FAMILYCHAT_PUSH_ALLOWED_HOSTS`
   and the nightly backup timer. Run a restore drill.
3. Test notification delivery with the app closed and the phone idle (`adb shell dumpsys deviceidle force-idle`).
4. Create a real release keystore (kept out of git) and build a signed release.
5. Get a security review of the crypto integration (`android/core/crypto`, `SyncProcessor`, `SendPipeline`) and
   the server authorization code.
6. Then decide on production readiness. The open items above must be accepted or fixed first.
