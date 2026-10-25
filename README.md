# Swahn Chat

> Formerly FamilyChat; renamed to Swahn Chat in v0.5.0.

A private, self-hosted messenger for a family (5–15 people). The server is a Raspberry Pi; the client is an Android
app. Messages, edits and attachments are **end-to-end encrypted on the phones with the Signal protocol (libsignal)**.
The Pi stores accounts, public keys, metadata and ciphertext. It cannot read message contents.

Status: **feature complete, not yet production-ready.** Every automated test passes, but nothing has been tested on
a real phone or on a Raspberry Pi yet. See [HANDOVER.md](HANDOVER.md) for exactly what is verified and what is not.

```
swahn-chatt/
├── android/      Kotlin app: :app (Compose UI) + :core:{model,network,crypto,client}
├── backend/      FastAPI server, Alembic migrations, pytest suite, Dockerfile
├── deployment/   docker-compose.yml (+ Caddy and ntfy profiles), .env.example, backup/restore scripts
└── docs/         ARCHITECTURE, API, SECURITY, ANDROID_SETUP, RASPBERRY_PI_SETUP, ANVANDARGUIDE, DRIFTHANDBOK (Swedish)
```

## How it fits together

- The phone encrypts every message separately for each device of each member (libsignal PQXDH + Double Ratchet)
  and posts the ciphertexts to `POST /api/v1/messages`.
- The server checks membership and the device set, stores the envelopes and writes one event per recipient
  device into that device's queue.
- Phones pull their queue with `GET /api/v1/sync` and acknowledge it, so delivery survives restarts and long
  offline periods. A WebSocket (app open) or UnifiedPush/ntfy (app closed) only says "sync now". Pushes carry
  no content.
- Attachments are encrypted on the phone (Tink streaming AES-GCM) before upload. Their keys travel inside the
  encrypted message.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/SECURITY.md](docs/SECURITY.md),
[docs/API.md](docs/API.md) (the running server also serves OpenAPI at `/api/v1/openapi.json`).

## Quick start (development)

```bash
# Backend: Python 3.12
cd backend
python3.12 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
scripts/dev-server.sh                      # migrations + server on http://0.0.0.0:8000
SWAHNCHAT_ENV=development SWAHNCHAT_DATA_DIR=data-dev .venv/bin/python -m app.cli create-invite --admin
SWAHNCHAT_ENV=development SWAHNCHAT_DATA_DIR=data-dev .venv/bin/python -m app.cli seed-dev   # alice, bob, carol
.venv/bin/python -m pytest                 # backend tests

# Android: JDK 17+, Android SDK 35 (+ NDK for stripped release builds)
cd ../android
./gradlew assembleDebug                    # app/build/outputs/apk/debug/app-debug.apk
./gradlew build                            # all unit, Robolectric and screenshot tests + lint + release build
./scripts/run-e2e.sh                       # starts a real backend; two users exchange E2EE messages
```

`make help` lists shortcuts for all of this (`make dev`, `make test-backend`, `make lint`, `make e2e`, `make apk`, `make up`,
`make backup`, `make invite`, ...). CI runs in `.github/workflows/*.yml`.

A debug build may talk plain `http://` to a LAN or localhost address. It then shows a red "Osäker anslutning
(endast utveckling)" banner. Release builds accept HTTPS only.

## Production on a Raspberry Pi

```bash
cd deployment
cp .env.example .env        # set SWAHNCHAT_SECRET_KEY=$(openssl rand -base64 48)
docker compose up -d --build
docker compose exec backend python -m app.cli create-invite --admin   # first account
```

On a fresh Raspberry Pi OS 64-bit, `deployment/install-pi.sh` does these steps interactively (checks, `.env` with a generated
secret, build, start, first admin invitation).

Reach the server through Tailscale (`tailscale serve`, or the `tailscale` compose profile; recommended) or Caddy with a public domain. Never expose
port 8000 directly. The full guide, including backups, upgrades and push, is in
[docs/RASPBERRY_PI_SETUP.md](docs/RASPBERRY_PI_SETUP.md).

## Family accounts

There is no open signup. An admin creates single-use invitation codes, either in the app (Inställningar → Skapa inbjudan)
or with `docker compose exec backend python -m app.cli create-invite`. The family member installs the APK,
enters the server address and redeems the code with their own username and password. See
[docs/ANDROID_SETUP.md](docs/ANDROID_SETUP.md).

## License

AGPL-3.0, because libsignal is AGPL-3.0 (see [docs/SECURITY.md](docs/SECURITY.md)).
