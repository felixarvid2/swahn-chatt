# Swahn Chat handover

> Formerly FamilyChat; renamed to Swahn Chat in v0.5.0.

Last updated: 2026-10-24. Branch `ccr-187bef76-iz1nsw` (PR #54: first version; PR #55: security fixes).

## State in one paragraph

The code for all phases is written: backend, Docker deployment, the Android core modules and the Android app. Real
end-to-end encrypted messaging between independent clients is proven by automated tests against a real running
backend. **Nothing has been run on a physical phone, an emulator or a Raspberry Pi yet.** An internal security
review has been done and its findings are fixed (below); an external review has not. The build is therefore **not production-ready**. It has no "insecure" plaintext mode:
E2EE is on from the first build, and the server rejects nothing but also understands nothing about message content.

## Verified (actually executed in this session)

| Check | Result |
|---|---|
| Backend pytest (`backend/`, `.venv/bin/python -m pytest`) | 189 passed: auth, invitations, rate limits, admin removal, conversation authorization, group membership changes, keys, delivery, duplicate suppression, concurrent sends, offline sync, edits/deletes, receipts, attachments (limits, quota, checksum, unauthorized access, Range), push (content-free, host allow-list), migrations (upgrade, empty autogenerate diff, downgrade), backup/restore (including corrupted archives), plaintext-free storage |
| `ruff check` / `ruff format` | clean |
| `pip-audit -r requirements.txt` | no known vulnerabilities |
| Docker image (amd64) | builds, runs as non-root, migrates on start, healthy; compose restart and down/up keep data; `backup.sh`/`restore.sh` round-trip; Caddy (TLS + WebSocket) and ntfy profiles start |
| Android `./gradlew build` | passes, including lint and R8 `assembleRelease`. Unit tests: `:core:model` 12, `:core:network` 32, `:core:crypto` 42 (real libsignal on the JVM: sessions, multi-device, out-of-order, corrupted/truncated/garbage ciphertext, replay, identity change, padding, attachment tamper/wrong key, safety numbers), `:core:client` 59 (incl. 24 malicious-server attack tests), `:app` 166 (Room store contracts, transactions, token storage, Keystore vault logic, notifications, ViewModels, Robolectric screenshots) |
| `android/scripts/run-e2e.sh` | `TwoUserEndToEndTest` 8/8 (incl. encrypted delete) and `RoomEngineE2ETest` 1/1 against a real backend. These cover real-time delivery both ways, receipts, edit/delete, 30-message offline catch-up without duplicates, an idempotent duplicate send, an encrypted attachment round-trip, group member removal, a second device, and a raw scan of the server's SQLite file + WAL that finds no plaintext |
| Batch 8 (2026-10-26) | `./gradlew check` passes (unit tests, lint, detekt); `run-e2e.sh`: `TwoUserEndToEndTest` 20/20 (new: reset with an admin code, rename own device, admin calls, push test, delete account); `assembleDebugAndroidTest` and `assembleQaAndroidTest -PtestBuildType=qa` compile; release and qa APKs contain `assets/dexopt/baseline.prof` |
| Toolchain upgrade (2026-10-26) | Gradle 9.8.2, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1, compileSdk 37 (target 35): unit tests, detekt and lint pass (app 872 tests, 1 skipped as before); `run-e2e.sh` 20/20 + 1/1; `assembleDebugAndroidTest` and `assembleQaAndroidTest -PtestBuildType=qa` compile, qa mapping keeps the instrumented tests' members; release APK 53 MB with `assets/dexopt/baseline.prof` and stripped natives (NDK pinned to 27.0.12077973) |
| APKs | debug `android/app/build/outputs/apk/debug/app-debug.apk` (51 MB), release `.../release/app-release.apk` (29 MB, R8, signed with the debug key) |

## Not verified

- **On a phone:** SQLCipher native loading, Android Keystore wrapping, R8 keep rules at runtime, UnifiedPush
  delivery with the app killed and the device in Doze, WorkManager timing, notifications, the microphone and
  camera flows, and the photo picker. The Keystore error handling (lost key vs. temporary error) is only tested against a
  software wrapper. Robolectric tests use plain SQLite. Do the first-phone check at the end of
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

## Security review (2026-10-24)

Two independent reviews were run: the client assumed a malicious server, the backend assumed outside attackers,
malicious members and revoked devices. Everything found is fixed and covered by tests:

- **Critical (client):** trust was tied to the server-assigned device number, so a server could silently insert
  or swap devices (a MITM, even for verified contacts). Now trust is per device UUID with pinned keys. Every new
  device is announced, and new devices of verified contacts and of your own account are blocked until approved.
- **Client (medium):** content is now bound to its conversation and message id. Deletes are encrypted and
  authenticated. Group member changes are announced.
- **Client (low):** send-side identity changes are surfaced; attachment names are sanitised for sharing; a
  transient Keystore error no longer wipes the database.
- **Backend:** an unauthenticated memory DoS via the JSON body-limit exemption; Argon2 running under the SQLite
  write lock; refresh reuse detection escaped by double rotation; one-time prekey draining; no per-account
  password-guess limit; WebSocket flooding and a revocation race; push allow-list failing open; tokens in logs;
  unchecked attachment paths; Docker hardening.

Behaviour change to know: after logging in on a second phone, approve it on the first one
(Inställningar → Säkerhetsnummer för mina enheter) before the first phone sends to it.
This is the owner's decision (2026-10-24): keep the protection unconditional for the own account, even when
none of the own devices is verified. It stops a server from silently adding a listening device to the account.
Do not relax it without asking.

## Batch 8 (items 92-100 and reset with a code)

Onboarding guide, battery-optimisation exemption, diagnostics screen, troubleshooting log export, outbox, "Mina enheter" with rename,
delete account, admin view and "Glömt lösenordet?". See `docs/ARCHITECTURE.md` (batch 8), `docs/SECURITY.md` (troubleshooting log and admin
view) and the user and operator guides.

**Baseline profile (item 96) is hand-written, not measured.** `android/app/src/main/baseline-prof.txt` holds wildcard rules for the app's
packages and the androidx paths the start path uses; `androidx.profileinstaller` installs it from the release and qa APKs
(`assets/dexopt/baseline.prof`). Nothing was measured, because that needs a Macrobenchmark run on a device or emulator. To generate a
measured one later, add a `:baselineprofile` module (`androidx.baselineprofile` plugin, `BaselineProfileRule` that starts the app, scrolls
the list, opens a chat and sends a message), run `generateBaselineProfile` on an API 33+ device, and replace the file.

## Known limitations and outstanding issues

- Metadata is visible to the server: who, when, group names and members, receipts and sizes. Group names and
  user avatars are not E2EE.
- A new device cannot read older messages, because there is no history transfer. A passphrase-protected local
  history export/import is the next feature to build.
- Safety numbers are per device and manual.
- Rate limits (including per-username login lockout) live in process memory and reset on restart. Anyone who
  knows a username can block new logins to it for up to an hour.
- The first device list per user is an unverified baseline; compare safety numbers to rule out a server that
  was malicious from the start.
- Delivered ciphertext stays in the server DB. Pruning delivered envelopes is a cheap hardening step.
- A permanently rejected edit or delete can leave the local view diverged. Normal messages get FAILED + retry.
- Notifications are not updated when a message is edited. GIFs are sent as still JPEGs. A voice recording is
  discarded when the app goes to the background.
- The debug build's network security config allows cleartext platform-wide. The app's own URL check restricts it
  to localhost and private LAN ranges. Release builds are HTTPS-only.
- Build sandbox quirk: Maven Central returned random 404/429 here, so a local retrying mirror
  (`/root/maven-mirror`, outside the repo) was used. A normal machine does not need it.

## Calls status (parts A and B)

- **Part A** (merged): call signalling over encrypted `call` messages, `CallManager` state machine, WebRTC audio, TURN credentials.
- **Part B** (branch `calls-part-b`): video, audio routing (speaker/earpiece/headset, proximity), incoming-call notification with ringtone,
  Answer/Decline and full-screen intent, ring worker, `CallService` (foreground service), `CallActivity`/`CallScreen` over the lock screen,
  call buttons in direct conversations, ongoing-call bar, settings "Koppla samtal via servern" and the full-screen row, local call history
  notices, the BUSY-from-own-device fix and the test that an unapproved device cannot ring. See `docs/ARCHITECTURE.md` "Calls (client)",
  `docs/SECURITY.md` "Calls (the app)" and `docs/ANVANDARGUIDE.md` "Samtal".
- **Verified in the sandbox:** unit tests (core and app, Robolectric), lint, detekt, `run-e2e.sh` against a real backend, R8 release and qa builds (the qa APK contains `org.webrtc` and `libjingle_peerconnection_so.so` for arm64-v8a and x86_64), and the instrumented tests *compile* (debug and qa); they were not run.
- **Not verified (needs a phone or emulator):** everything with real audio and video: the loopback test `WebRtcLoopbackTest` (voice and video),
  ringing with the app killed and in Doze, the full-screen intent on Android 14+, the foreground service types on Android 14/15, the camera,
  Bluetooth routing, the proximity sensor, the ringtone with ringer mode and do-not-disturb.
- **Known gaps:** no Telecom/`ConnectionService` integration (no system call log, no car/Bluetooth call controls); no voice-to-video upgrade in a
  call; the ring worker can run as ordinary work when the expedited quota is spent; the caller's picture is not shown in the notification.

## Next steps (in order)

1. Install the debug APK on two phones and do the first-phone check (end of ANDROID_SETUP.md §8), then fix what breaks (SQLCipher, R8, push).
2. Build and run the Docker stack on the Pi 4/5. Set up `tailscale serve`, ntfy, `SWAHNCHAT_PUSH_ALLOWED_HOSTS`
   and the nightly backup timer. Run a restore drill.
3. Test notification delivery with the app closed and the phone idle (`adb shell dumpsys deviceidle force-idle`).
4. Create a real release keystore (kept out of git) and build a signed release.
5. Get an external security review of the crypto integration (`android/core/crypto`, `DeviceDirectory`,
   `SyncProcessor`, `SendPipeline`) and the server authorization code.
6. Then decide on production readiness. The open items above must be accepted or fixed first.
