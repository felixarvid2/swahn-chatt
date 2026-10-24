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

**Shortcut:** steps 2 to 4 and 6 (first administrator) are automated by `deployment/install-pi.sh`. Clone the
repository (step 3), then run `cd familychat/deployment && ./install-pi.sh`. It checks the architecture, RAM, disk and
Docker, writes `.env` with a generated secret (an existing `.env` and secret are never overwritten), asks how the
phones will reach the server (Tailscale on the host, Tailscale container, Caddy domain, or later) and whether to enable
push, builds, starts, waits until the backend is healthy and prints the first administrator invitation. It does not
install Docker unless you confirm (or pass `--install-docker`). `./install-pi.sh --help` lists the options for
unattended use. You then only do the Tailscale or router part of step 5 and set up backups (step 9).

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

### Option A2: Tailscale as a container (no Tailscale on the host)

The `tailscale` compose profile runs Tailscale as a sidecar container. It joins your tailnet as its own node and
serves HTTPS itself, so the Pi needs no host-level Tailscale and no `tailscale serve` command. Use it instead of
Option A, not together with it (same ports).

1. In the Tailscale admin console enable **MagicDNS** and **HTTPS Certificates** (DNS page) and create an **auth key**
   (Settings, Keys). It is only used for the first login.
2. In `.env` set `TS_AUTHKEY=tskey-auth-...` and, if you like, `TS_HOSTNAME=familychat` (the node name).
3. ```bash
   docker compose --profile tailscale up -d
   docker compose logs tailscale          # "Startup complete" / the node's address
   ```
   Add `COMPOSE_PROFILES=tailscale` (or `caddy,ntfy,tailscale`, ...) to `.env` to make the profile permanent, so that
   a plain `docker compose up -d` keeps it. Remove `TS_AUTHKEY` from `.env` after the first start; the node state is
   in the `tailscale-state` volume (do not delete it, or the node registers again under a new name).
4. The address is `https://<TS_HOSTNAME>.<tailnet>.ts.net` (port 443 -> `backend:8000`). `tailscale-serve.json`
   defines the mapping; the container fills in the node's own name. Without an auth key the container logs a login
   link instead (`docker compose logs tailscale`). Then continue at step 4 of Option A (phones, health check).

For push, the same node also serves **port 8443 -> `ntfy:80`**, so the ntfy URL is
`https://<TS_HOSTNAME>.<tailnet>.ts.net:8443`: set `NTFY_BASE_URL` to it and start the `ntfy` profile as in section 7;
you do not run `tailscale serve --https=8443`. One limit: the backend container itself is not on the tailnet, so it
reaches ntfy only if it can resolve and reach that name. If pushes never arrive, use a public ntfy host through Caddy
(section 7) or Option A with the host's Tailscale. This sidecar setup was parsed by `docker compose config` and the
image tag was pulled, but a real tailnet login has not been tested in this repository's environment.

### Option B: Caddy with a public domain

1. Get a domain (a dynamic DNS name works) and point an A/AAAA record at your home IP. Forward TCP 80 and 443
   (and UDP 443 for HTTP/3) from the router to the Pi.
2. In `.env` set `FAMILYCHAT_DOMAIN=chat.example.org` (and `NTFY_DOMAIN`, see below).
3. ```bash
   docker compose --profile caddy up -d
   docker compose logs -f caddy      # watch it obtain the certificate
   ```
   Caddy terminates TLS, proxies `/api/v1/ws` WebSockets and obtains/renews Let's Encrypt certificates. Keep the
   `caddy-data` volume; deleting it forces re-issuing and can hit Let's Encrypt rate limits. Caddy caps request
   bodies at 9 MB, except attachment uploads: those may be `CADDY_MAX_UPLOAD` (default `110MB`). If you raise
   `FAMILYCHAT_MAX_ATTACHMENT_MB` above 100, raise `CADDY_MAX_UPLOAD` in `.env` too (a bit above it).
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
docker compose exec backend python -m app.cli remove-user <username>   # API access ends at once; open sockets close within ~1 min
docker compose exec backend python -m app.cli check-config            # effective settings + warnings; exit 1 on errors
```

**Forgotten password.** Issue a one-time code (valid 24 hours) and give it to the person over a channel you trust:

```bash
docker compose exec backend python -m app.cli reset-password <username>   # the code is the last line
```

Administrators can do the same in the app (`POST /api/v1/admin/users/{id}/password-reset`). The person enters the
code and a new password in the app (*Forgot password* / `POST /api/v1/auth/password/reset`). This signs them out
everywhere: every old device of that account is removed (its keys and queued messages are deleted) and the phone
that used the code becomes a new device. Their contacts will see a "new device" notice, and old messages cannot be
read on the new device. That is the same as a lost phone and intended. The code never appears in the audit log
(`audit` shows `password_reset_issued` and `password_reset_used`).

`check-config` is worth running after every change to `.env`: it prints the settings the server will use (never
the secret key or database password) and warns about: production without `FAMILYCHAT_PUSH_ALLOWED_HOSTS`, a
missing or short secret key (an error in production), API docs exposed in production, `FAMILYCHAT_BEHIND_PROXY=true`
without `FAMILYCHAT_TRUSTED_PROXIES`, push over plain http in production and low disk space.

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
5. **Set `FAMILYCHAT_PUSH_ALLOWED_HOSTS` in `.env`** to the host name of your ntfy server (for example
   `pi.tailnet-name.ts.net`; comma separated for several). It is required: while it is empty the production
   server refuses to register push endpoints (`409 push_not_configured`), logs a warning at startup and sends no
   push. With it set, endpoints on any other host are refused, so the server cannot be abused to send requests
   to arbitrary URLs. IP addresses as host names are refused unless you list that exact address. Restart the
   backend after changing it (`docker compose up -d`) and let the phones register again (reopen the app).

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

The Caddy and ntfy images are pinned to specific versions in `docker-compose.yml`; they do not update by
themselves. To upgrade one, change its tag deliberately (read the release notes), then `docker compose pull && docker compose up -d`.
The backend container has a read-only root filesystem: anything you copy in or out by hand must go through
`/data` (the volume), not `/tmp` (a small tmpfs); `backup.sh` and `restore.sh` already do.

If an upgrade misbehaves: `git checkout <previous commit>`, `docker compose up -d --build`, and if the new
version changed the database, `./restore.sh` the backup taken in step one (migrations only move forward).

## 9. Backups and restore

`./backup.sh [dest-dir]` asks the running container for one consistent snapshot (`sqlite3` online backup API, so
it is safe while the server is in use), copies the `.tar.gz` to the host (default `deployment/backups/`, mode 600),
verifies it can be read and keeps the newest 14 (`FAMILYCHAT_BACKUP_KEEP=30` in `.env` or in the environment changes
that; the older `KEEP=30 ./backup.sh` still works; `./backup.sh --prune` only applies the retention rule). The
archive contains:
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

or with cron, see `deployment/systemd/crontab.example`. A backup on the same SD card does not survive the card, so
copy it elsewhere. `backup.sh` can do that with [rclone](https://rclone.org) (installed on the Pi, not in Docker):

```bash
sudo apt install rclone        # or: curl https://rclone.org/install.sh | sudo bash
rclone config                  # as the user that runs the backup: first a normal remote (Drive, S3, SFTP, ...),
                               # then a "crypt" remote layered on top of it, e.g. named familychat-crypt
```

Use a **crypt** remote: the archive holds metadata and password hashes, and crypt encrypts file names and content
before they leave the Pi (keep the crypt passwords somewhere other than the Pi). Then set in `.env`:

```bash
FAMILYCHAT_BACKUP_RCLONE_REMOTE=familychat-crypt:familychat-backups
```

After each successful local backup `backup.sh` runs `rclone copy <backup dir> <remote>` (new archives only; older ones
that are missing remotely are uploaded too). If the upload fails the run exits with status 3, so the systemd unit shows
as failed, but the local backup stays. The remote is not pruned automatically: use a bucket lifecycle rule or
`rclone delete <remote> --min-age 60d`. Test it once with `./backup.sh` and `rclone ls <remote>`.

Restore, for example onto a new Pi after step 4:

```bash
./restore.sh /path/to/familychat-backup-20260101-031500.tar.gz
```

It stops the backend, verifies every checksum in the manifest (a damaged or modified archive is rejected and
nothing is changed), moves any existing data to `/data/pre-restore-<time>` inside the volume, restores, and
starts the backend again. Delete the `pre-restore-*` folder when you are happy:
`docker compose exec backend sh -c 'rm -rf /data/pre-restore-*'`. The CLI equivalent is
`python -m app.cli restore <archive> [--force]`; without `--force` it refuses to touch existing data.

`./backup.sh` and `restore.sh` verify archives themselves; to check one you already have, anywhere, without
touching any data:

```bash
docker compose exec backend python -m app.cli verify-backup /data/backups/familychat-backup-....tar.gz
```

It checks the entry names, the manifest and every checksum, runs SQLite's `integrity_check` on a temporary copy and
validates the attachment paths stored in the database; exit status 0 means the archive is complete and restorable,
1 means it is not (the reason is printed). Run it on the copy you keep off the Pi.

Test your restore once, before you need it.

**Monthly automatic check.** `./backup.sh --verify-latest [dir]` runs that same verification on the newest archive in
the backup directory (it streams the file from the host into the container, so no path juggling) and exits non-zero
if the archive is damaged, missing, or older than `FAMILYCHAT_BACKUP_MAX_AGE_DAYS` (default 3: the nightly backup has
stopped). A systemd timer runs it on the first of every month; a failure shows in `systemctl --failed`:

```bash
sudo cp deployment/systemd/familychat-verify-backup.{service,timer} /etc/systemd/system/   # edit User= and paths first
sudo systemctl daemon-reload && sudo systemctl enable --now familychat-verify-backup.timer
sudo systemctl start familychat-verify-backup.service && journalctl -u familychat-verify-backup -n 20
```

To be told about a failure, see `deployment/systemd/familychat-notify-failure@.service` (optional `OnFailure=` hook
that posts to a private ntfy topic). Day-to-day operation in Swedish: [DRIFTHANDBOK.md](DRIFTHANDBOK.md).

## 10. Logs, health and reboots

```bash
docker compose logs -f backend          # JSON lines; tokens, passwords and message bodies are never logged
docker compose logs --since 1h backend | grep -i error
curl -s http://127.0.0.1:8000/healthz   # process alive
curl -s http://127.0.0.1:8000/readyz    # ready only if the database answers, is migrated and the disk has room
docker compose ps                       # health status from the container healthcheck
```

### Monitoring

`/readyz` answers `503` with a `code` when something needs your attention: `migration_mismatch` (the database is
not at the version this image expects: run the upgrade steps in section 8), `low_disk` (less than
`FAMILYCHAT_MIN_FREE_DISK_MB`, default 200, free on the data volume: free space or enlarge the disk before
SQLite fails writes) or `database_unavailable`. Point an uptime monitor at it. `/healthz` only says the process
is alive.

Administrators get the numbers from `GET /api/v1/admin/stats` (needs an admin access token, so use the admin's
phone or a tool that can log in): counts of users, devices, conversations, messages, undelivered envelopes and
attachments, the size of the database and the attachments folder, free disk space, the server version and
migration, and uptime. `GET /api/v1/admin/users` shows each person's last activity, device count and
storage use; `GET /api/v1/admin/audit` or the command below shows the security log.

```bash
docker compose exec backend python -m app.cli audit --limit 50   # logins, invitations, device and user changes
```

A lost or stolen phone: an administrator revokes it with `DELETE /api/v1/admin/users/{user_id}/devices/{device_id}`
(the owner can do the same from another device). Delivered messages are deleted from the server after
`FAMILYCHAT_ENVELOPE_RETENTION_DAYS` (30) days and audit entries after `FAMILYCHAT_AUDIT_RETENTION_DAYS` (180);
both run in the hourly maintenance job. When the server restarts, open apps are disconnected with code 1001 and
reconnect by themselves.

Every response carries an `X-Request-ID` header and error bodies a `request_id`; the same ID is in all log lines
of that request (`docker compose logs backend | grep <id>`), so when someone reports an error, ask for the ID.

Logs rotate automatically (10 MB x 5 files per service). All services use `restart: unless-stopped`, Docker starts
at boot and `tailscale serve --bg` is remembered by Tailscale, so after `sudo reboot` everything comes back by
itself: check with `docker compose ps` and `tailscale serve status`. Pull the power cord less often than that:
SQLite in WAL mode survives it, but an SSD survives it better than an SD card.

## 11. Optional: PostgreSQL instead of SQLite

SQLite is the default and is enough for a family. PostgreSQL is supported (the test suite runs against both), for
example when the data lives on a server that already runs it. The image already contains the driver. Do **not**
edit `docker-compose.yml`; add an override file next to it, `deployment/docker-compose.postgres.yml`:

```yaml
services:
  postgres:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: familychat
      POSTGRES_DB: familychat
      POSTGRES_PASSWORD: ${FAMILYCHAT_POSTGRES_PASSWORD:?set FAMILYCHAT_POSTGRES_PASSWORD in .env}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U familychat -d familychat"]
      interval: 10s
      timeout: 5s
      retries: 5
    security_opt: ["no-new-privileges:true"]
    # no "ports": only the backend container reaches it, over the compose network
  backend:
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      FAMILYCHAT_DATABASE_URL: postgresql+psycopg://familychat:${FAMILYCHAT_POSTGRES_PASSWORD}@postgres:5432/familychat
volumes:
  postgres-data:
```

Put `FAMILYCHAT_POSTGRES_PASSWORD=<long random letters and digits>` in `.env` (avoid characters that need URL
escaping: `@ : / ? #`), then start with both files:

```bash
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up -d --build
# make it permanent: COMPOSE_FILE=docker-compose.yml:docker-compose.postgres.yml in .env
docker compose exec backend python -m app.cli check-config     # shows the database URL without the password
```

The entrypoint runs `alembic upgrade head` against PostgreSQL on every start, like for SQLite. Things that differ:

- There is **no migration of existing SQLite data** to PostgreSQL. Decide before the family starts using it.
- `backup.sh`, `restore.sh` and the CLI `backup`/`restore`/`verify-backup` handle SQLite only. Back up PostgreSQL
  with `docker compose exec -T postgres pg_dump -U familychat familychat | gzip > familychat-db-$(date +%F).sql.gz`
  **and** the attachments in the `familychat-data` volume (`/data/attachments`), taken at about the same time.
- `GET /admin/stats` reports `db_bytes: null`; `/readyz` and the migration check work as before.
- All writing transactions are serialised with an advisory lock so that the sync cursor stays correct (see
  ARCHITECTURE.md, "Sync cursor and the database"); a request that cannot get it within 10 s fails with a server
  error and the client retries. Keep one backend worker, as always.

## Troubleshooting

| Symptom | Look at |
|---|---|
| Container restarts immediately | `docker compose logs backend`: usually a missing or short `FAMILYCHAT_SECRET_KEY` |
| App says token expired in a loop | The phone's clock is wrong, or `FAMILYCHAT_SECRET_KEY` changed (log in again) |
| `429 rate_limited` | Too many attempts from one address (IPv6: one /64 prefix), or too many failed logins for that username (10 per hour, 30 per day, from anywhere); wait for `Retry-After` seconds. The counters are in memory: restarting the backend clears them |
| `409 device_limit` at login | The account has `FAMILYCHAT_MAX_DEVICES_PER_USER` (default 10) active devices; remove an old one from a signed-in device (`DELETE /devices/{id}`) or with `remove-user` as an admin |
| Revoked device still shows connected | The CLI cannot reach the running server's sockets; they close within `FAMILYCHAT_WS_REVALIDATE_SECONDS` (60 s) |
| No push notifications | ntfy health URL, battery optimisation for the ntfy app, `docker compose logs backend \| grep push` |
| Disk filling up | `docker system df`; attachments live in the `familychat-data` volume (per-user quota `FAMILYCHAT_USER_QUOTA_MB`) |
