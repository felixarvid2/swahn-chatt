# Running FamilyChat on a Raspberry Pi

This guide takes a fresh Raspberry Pi to a running, backed-up FamilyChat server. Architecture and trade-offs are
in [ARCHITECTURE.md](ARCHITECTURE.md), the security model in [SECURITY.md](SECURITY.md).

What you need: a Raspberry Pi 4 or 5 (2 GB RAM is enough), a good microSD card or, better, a USB SSD, and the
family's Android phones. Everything runs in Docker; the backend is one Python process plus a SQLite file.

## 1. Prepare Raspberry Pi OS (64-bit)

1. Flash **Raspberry Pi OS Lite (64-bit)** with Raspberry Pi Imager. In the Imager's settings set a hostname
   (for example `familychat`), your user, your SSH key or password, Wi-Fi if needed and your time zone.
   The image must be 64-bit (`uname -m` prints `aarch64`).
2. Boot, `ssh` in and update:
   ```bash
   sudo apt update && sudo apt full-upgrade -y
   sudo apt install -y git unattended-upgrades
   sudo dpkg-reconfigure -plow unattended-upgrades    # security updates install themselves
   ```
3. Give the Pi a fixed address in your router (DHCP reservation). Only needed for the public-HTTPS option.
4. Prefer an SSD over an SD card: SQLite writes and attachments wear cards out.

## 2. Install Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"      # log out and in again afterwards
docker compose version                # the compose plugin is included
sudo systemctl enable docker          # start Docker (and so FamilyChat) after a reboot
```

## 3. Get the code and configure it

```bash
git clone <your repository url> familychat
cd familychat/deployment
cp .env.example .env
chmod 600 .env
```

Edit `.env`. The one required value is the signing secret:

```bash
sed -i "s|^FAMILYCHAT_SECRET_KEY=.*|FAMILYCHAT_SECRET_KEY=$(openssl rand -base64 48)|" .env
```

(or run `openssl rand -base64 48` yourself and paste it). The server refuses to start in production without a
secret of at least 32 bytes. Every other variable is documented in `.env.example`. Never commit `.env`.

## 4. Start it

```bash
docker compose up -d --build         # the first build takes a few minutes on a Pi
docker compose ps                    # STATUS should become "healthy"
curl http://127.0.0.1:8000/readyz    # {"status":"ready"}
```

The container applies database migrations itself on every start (`alembic upgrade head`) and then runs one
uvicorn worker. It listens on `127.0.0.1:8000` only: plain HTTP is never exposed to the network. Next you put
HTTPS in front of it, either with Tailscale (recommended) or with Caddy.

## 5. Make it reachable over HTTPS

| | Tailscale (recommended) | Caddy + public domain |
|---|---|---|
| Who can reach the login API | Only devices in your tailnet | Anyone on the internet |
| Phones need | The Tailscale app, signed in | Nothing extra |
| Certificate | `*.ts.net`, issued by Tailscale | Let's Encrypt, automatic |
| Router | Nothing to forward | Forward ports 80 and 443 to the Pi, need a domain |
| Risk if something is wrong | Very low | Brute force and 0-days are your problem: keep the Pi patched (rate limiting and Argon2id help) |

### Option A: Tailscale (recommended)

1. Create a Tailscale account, then on the Pi:
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up
   ```
2. In the Tailscale admin console enable **MagicDNS** and **HTTPS Certificates** (DNS page).
3. Serve the backend over HTTPS to the tailnet. `tailscale serve` keeps running in the background and survives
   reboots:
   ```bash
   sudo tailscale serve --bg --https=443 http://127.0.0.1:8000
   tailscale serve status
   ```
   The server is now at `https://<hostname>.<tailnet>.ts.net` (shown in the status output). Use that URL in the app.
4. Install Tailscale on every family phone, sign in to the same tailnet (invite each person to it or share the Pi
   with their account via *Share node*), and keep it connected.
5. Check from a phone browser: `https://<hostname>.<tailnet>.ts.net/healthz` shows `{"status":"ok"}`.

Notes: `tailscale serve` talks to the container through Docker's bridge, so the backend sees the bridge address as
the peer. `.env` already trusts `172.16.0.0/12` for `X-Forwarded-For`; if the proxy sends no such header all
family members share one rate-limit bucket (login: 10 attempts per minute in total), which is fine for a family.
Do not use `tailscale funnel` for this: it would put the login API on the public internet.

### Option B: Caddy with a public domain

1. Get a domain (a dynamic DNS name works) and point an A/AAAA record at your home IP. Forward TCP 80 and 443
   (and UDP 443 for HTTP/3) from the router to the Pi.
2. In `.env` set `FAMILYCHAT_DOMAIN=chat.example.org` (and `NTFY_DOMAIN`, see below).
3. ```bash
   docker compose --profile caddy up -d
   docker compose logs -f caddy      # watch it obtain the certificate
   ```
   Caddy terminates TLS, proxies `/api/v1/ws` WebSockets and obtains/renews Let's Encrypt certificates. Keep the
   `caddy-data` volume; deleting it forces re-issuing and can hit Let's Encrypt rate limits.
4. Never forward port 8000.

You can run Tailscale and Caddy side by side, but choose one address for the app.

## 6. Create the first administrator and the family

The first account is an administrator invited from the command line:

```bash
docker compose exec backend python -m app.cli create-invite --admin --note "me"
```

The code (like `K7M2P-9XQ4W-HT5RB-3NZ8D`) is the last line of output and is valid for 72 hours. Open the app,
enter the server URL, choose *Redeem invitation* and pick a username and password. Further invitations:

```bash
docker compose exec backend python -m app.cli create-invite --note "Grandma" --hours 168
```

Administrators can also create and revoke invitations in the app. Each code works once. Other commands:

```bash
docker compose exec backend python -m app.cli list-users
docker compose exec backend python -m app.cli remove-user <username>   # kills their sessions immediately
```

Every login on a new phone is a new *device* with new encryption keys. Messages sent before a device existed
cannot be read on it; that is how end-to-end encryption works here (see SECURITY.md).

## 7. Push notifications with ntfy (UnifiedPush)

Without push the app only syncs when it is open or every 15 minutes. With push the server sends a content-free
`{"t":"sync"}` to the phone, which then fetches the messages. No Google services are involved.

1. Pick the public URL of your ntfy server. It must be https and reachable from the phones **and** from the backend
   container:
   - Tailscale: `https://<hostname>.<tailnet>.ts.net:8443`
   - Caddy: `https://ntfy.example.org` (add a DNS record for it and set `NTFY_DOMAIN=ntfy.example.org`)
2. Set `NTFY_BASE_URL` in `.env` to that URL, then:
   ```bash
   docker compose --profile ntfy up -d          # add --profile caddy when using Caddy
   sudo tailscale serve --bg --https=8443 http://127.0.0.1:2586      # Tailscale option only
   curl https://<ntfy url>/v1/health                                   # {"healthy":true}
   ```
3. On each phone install the **ntfy** app (F-Droid or Play Store). In its settings set the *default server* to
   your `NTFY_BASE_URL` and exempt the app from battery optimisation. In FamilyChat choose ntfy as the
   UnifiedPush distributor; the app registers its endpoint (`https://.../up…`) with `PUT /devices/me/push`.
4. The backend only sends to `https` endpoints (`FAMILYCHAT_PUSH_ALLOW_HTTP=false`), with a 5 second timeout, and
   never blocks a request on it. Failures are logged without the endpoint URL.

The backend container must be able to resolve and reach the host in the registered endpoint. With public DNS
(Caddy option) this just works. With a `*.ts.net` name the container has to resolve MagicDNS through the host; if
pushes never arrive, check `docker compose logs backend | grep push` and test `docker compose exec backend python -c
"import urllib.request;print(urllib.request.urlopen('https://<ntfy host>/v1/health').read())"`. This path has not
been verified on real hardware in this repository's test environment.

ntfy accepts anonymous publishers by default; the UnifiedPush topics are long random strings. If you expose ntfy
publicly, read the ntfy documentation on access control.

## 8. Upgrading

```bash
cd ~/familychat/deployment
./backup.sh                          # always back up first
git pull
docker compose up -d --build         # rebuilds the image; migrations run automatically on start
docker compose ps && curl -s http://127.0.0.1:8000/readyz
docker image prune -f                # drop old image layers
```

If an upgrade misbehaves: `git checkout <previous commit>`, `docker compose up -d --build`, and if the new
version changed the database, `./restore.sh` the backup taken in step one (migrations only move forward).

## 9. Backups and restore

`./backup.sh [dest-dir]` asks the running container for one consistent snapshot (`sqlite3` online backup API, so
it is safe while the server is in use), copies the `.tar.gz` to the host (default `deployment/backups/`, mode 600),
verifies it can be read and keeps the newest 14 (`KEEP=30 ./backup.sh` changes that). The archive contains:
`familychat.db`, all attachments and a `manifest.json` with counts and SHA-256 checksums.

**What a backup contains:** accounts, password hashes, public keys, conversation membership, message metadata and
**ciphertext only**. Nobody can read messages from it, and restoring does not give anyone back messages they can no
longer decrypt on their phones. It still holds metadata and password hashes, so keep it private and, when copying
it off the Pi, store it somewhere you trust (see SECURITY.md, "Backups and encryption").

Nightly backups with systemd (edit the paths and user in the files first):

```bash
sudo cp deployment/systemd/familychat-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now familychat-backup.timer
systemctl list-timers familychat-backup.timer
```

or with cron, see `deployment/systemd/crontab.example`. Copy the backup directory to another machine
(`rsync`, a USB disk, ...): a backup on the same SD card does not survive the card.

Restore, for example onto a new Pi after step 4:

```bash
./restore.sh /path/to/familychat-backup-20260101-031500.tar.gz
```

It stops the backend, verifies every checksum in the manifest (a damaged or modified archive is rejected and
nothing is changed), moves any existing data to `/data/pre-restore-<time>` inside the volume, restores, and
starts the backend again. Delete the `pre-restore-*` folder when you are happy:
`docker compose exec backend rm -rf /data/pre-restore-*`. The CLI equivalent is
`python -m app.cli restore <archive> [--force]`; without `--force` it refuses to touch existing data.

Test your restore once, before you need it.

## 10. Logs, health and reboots

```bash
docker compose logs -f backend          # JSON lines; tokens, passwords and message bodies are never logged
docker compose logs --since 1h backend | grep -i error
curl -s http://127.0.0.1:8000/healthz   # process alive
curl -s http://127.0.0.1:8000/readyz    # database answers (503 otherwise)
docker compose ps                       # health status from the container healthcheck
```

Logs rotate automatically (10 MB x 5 files per service). All services use `restart: unless-stopped`, Docker starts
at boot and `tailscale serve --bg` is remembered by Tailscale, so after `sudo reboot` everything comes back by
itself: check with `docker compose ps` and `tailscale serve status`. Pull the power cord less often than that:
SQLite in WAL mode survives it, but an SSD survives it better than an SD card.

## Troubleshooting

| Symptom | Look at |
|---|---|
| Container restarts immediately | `docker compose logs backend`: usually a missing or short `FAMILYCHAT_SECRET_KEY` |
| App says token expired in a loop | The phone's clock is wrong, or `FAMILYCHAT_SECRET_KEY` changed (log in again) |
| `429 rate_limited` | Too many logins from one address; wait for `Retry-After` seconds |
| No push notifications | ntfy health URL, battery optimisation for the ntfy app, `docker compose logs backend \| grep push` |
| Disk filling up | `docker system df`; attachments live in the `familychat-data` volume (per-user quota `FAMILYCHAT_USER_QUOTA_MB`) |
