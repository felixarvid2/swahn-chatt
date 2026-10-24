#!/usr/bin/env bash
# Guided installer for FamilyChat on a Raspberry Pi (Raspberry Pi OS 64-bit, also fine on other Debian-like arm64 hosts).
#
#   ./install-pi.sh                       asks what it needs
#   ./install-pi.sh --yes --access caddy --domain chat.example.org     no questions
#
# What it does: checks the machine (architecture, RAM, disk, Docker + compose plugin), creates .env from
# .env.example with a freshly generated FAMILYCHAT_SECRET_KEY, asks how the family reaches the server and whether to
# enable push, builds and starts the stack, waits until the backend is healthy and prints the first administrator
# invitation. It is safe to run again: an existing .env and its secret are never overwritten, and an
# invitation is only created when no administrator exists yet.
#
# Options (each can also be given as the environment variable in brackets):
#   --access MODE      how phones reach the server [FAMILYCHAT_INSTALL_ACCESS]:
#                        tailscale-host    Tailscale installed on this Pi, you run `tailscale serve` (recommended)
#                        tailscale-sidecar Tailscale in a container (compose profile `tailscale`)
#                        caddy             public domain with automatic HTTPS (compose profile `caddy`)
#                        none              local only (http://127.0.0.1:8000), decide later
#   --domain NAME      public DNS name for MODE caddy [FAMILYCHAT_INSTALL_DOMAIN]
#   --push-host NAME   host name of your ntfy server; enables push (profile `ntfy`) [FAMILYCHAT_INSTALL_PUSH_HOST]
#                        use "none" to say that you do not want push
#   --ts-hostname NAME node name for tailscale-sidecar (default familychat) [TS_HOSTNAME]
#   --ts-authkey KEY   Tailscale auth key for tailscale-sidecar [TS_AUTHKEY]
#   --install-docker   install Docker with get.docker.com if it is missing (otherwise it asks, or prints the command)
#   --yes, -y          never ask: use the options above and defaults, fail where an answer is required
#   --no-start         write .env and stop before building and starting
#   --allow-any-arch   continue on a machine that is not aarch64 (testing on a PC)
#   --help, -h
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

ACCESS="${FAMILYCHAT_INSTALL_ACCESS:-}"
DOMAIN="${FAMILYCHAT_INSTALL_DOMAIN:-}"
PUSH_HOST="${FAMILYCHAT_INSTALL_PUSH_HOST:-}"
TS_HOSTNAME_ARG="${TS_HOSTNAME:-}"
TS_AUTHKEY_ARG="${TS_AUTHKEY:-}"
ASSUME_YES=0
INSTALL_DOCKER=0
NO_START=0
ANY_ARCH=0

usage() { sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; }
say() { printf '\n==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --access) ACCESS="${2:?--access needs a value}"; shift 2 ;;
    --domain) DOMAIN="${2:?--domain needs a value}"; shift 2 ;;
    --push-host) PUSH_HOST="${2:?--push-host needs a value}"; shift 2 ;;
    --ts-hostname) TS_HOSTNAME_ARG="${2:?--ts-hostname needs a value}"; shift 2 ;;
    --ts-authkey) TS_AUTHKEY_ARG="${2:?--ts-authkey needs a value}"; shift 2 ;;
    --install-docker) INSTALL_DOCKER=1; shift ;;
    --yes | -y) ASSUME_YES=1; shift ;;
    --no-start) NO_START=1; shift ;;
    --allow-any-arch) ANY_ARCH=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

# --- prompts ------------------------------------------------------------------------------------------------------
interactive() { [ "$ASSUME_YES" = 0 ] && [ -t 0 ]; }

# ask VAR "question" [default]  -> sets VAR; non-interactive runs take the default
ask() {
  local var="$1" question="$2" default="${3:-}" answer=""
  if interactive; then
    if [ -n "$default" ]; then read -r -p "$question [$default] " answer; else read -r -p "$question " answer; fi
  fi
  printf -v "$var" '%s' "${answer:-$default}"
}

confirm() { # confirm "question"  -> true when the user typed y
  local answer=""
  interactive || return 1
  read -r -p "$1 [y/N] " answer
  [ "$answer" = y ] || [ "$answer" = Y ]
}

# --- .env helpers (never source .env: values are data) -----------------------------------------------------------------
env_get() { # env_get NAME -> current value, empty when unset
  { grep -E "^$1=" .env 2>/dev/null || true; } | tail -n 1 | cut -d= -f2- | tr -d '\r'
}

env_set() { # env_set NAME VALUE -> replace the last assignment (also commented-out defaults), or append
  local tmp
  tmp="$(mktemp)"
  NAME="$1" VALUE="$2" awk '
    BEGIN { n = ENVIRON["NAME"]; v = ENVIRON["VALUE"]; done = 0 }
    { lines[NR] = $0 }
    END {
      for (i = NR; i >= 1; i--) if (lines[i] ~ ("^" n "=")) { lines[i] = n "=" v; done = 1; break }
      for (i = 1; i <= NR; i++) print lines[i]
      if (!done) print n "=" v
    }' .env >"$tmp"
  cat "$tmp" >.env # keep the file's mode (600)
  rm -f "$tmp"
}

add_profile() { # add_profile NAME -> COMPOSE_PROFILES gains NAME once
  local cur
  cur="$(env_get COMPOSE_PROFILES)"
  case ",$cur," in
    *",$1,"*) ;;
    *) env_set COMPOSE_PROFILES "${cur:+$cur,}$1" ;;
  esac
}

# --- 1. checks ----------------------------------------------------------------------------------------------------
say "Checking this machine"
arch="$(uname -m)"
if [ "$arch" = aarch64 ] || [ "$arch" = arm64 ]; then
  echo "architecture: $arch (64-bit ARM)"
elif [ "$ANY_ARCH" = 1 ]; then
  warn "architecture is $arch, not aarch64; continuing because of --allow-any-arch"
else
  die "this installer targets Raspberry Pi OS 64-bit (aarch64), found $arch. A 32-bit OS cannot run the images; flash the 64-bit image. For another machine pass --allow-any-arch."
fi

mem_mb="$(awk '/^MemTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo 2>/dev/null || echo 0)"
echo "memory: ${mem_mb} MB"
if [ "$mem_mb" -lt 1500 ]; then warn "less than 1.5 GB RAM: the build may run out of memory; add swap or build elsewhere."; fi

disk_mb="$(df -Pm "$HERE" | awk 'NR==2 {print $4}')"
echo "free disk: ${disk_mb} MB"
if [ "$disk_mb" -lt 3000 ]; then
  die "less than 3 GB free on this disk; the image build and the data need room. Free some space and run again."
elif [ "$disk_mb" -lt 8000 ]; then
  warn "under 8 GB free: fine to start, but attachments will fill it. A USB SSD is recommended."
fi

command -v openssl >/dev/null 2>&1 || die "openssl is missing (sudo apt install openssl)"

# shellcheck disable=SC2016  # $USER is meant to expand later, in the user's shell
DOCKER_INSTALL_CMD='curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker "$USER"'
if ! command -v docker >/dev/null 2>&1; then
  warn "Docker is not installed."
  echo "The official install command is:  $DOCKER_INSTALL_CMD"
  if [ "$INSTALL_DOCKER" = 1 ] || confirm "Run it now? (downloads and runs a script from get.docker.com as root)"; then
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "$USER"
    sudo systemctl enable --now docker
    echo
    echo "Docker is installed. Log out and in again (so the docker group applies), then run ./install-pi.sh again."
    exit 0
  fi
  die "install Docker first (command above), then run this script again."
fi
if ! docker compose version >/dev/null 2>&1; then
  die "the Docker compose plugin is missing. On Raspberry Pi OS: sudo apt install docker-compose-plugin (or reinstall Docker with the command above)."
fi
echo "docker: $(docker --version)"
echo "compose: $(docker compose version --short 2>/dev/null || docker compose version)"
if [ "$NO_START" = 0 ] && ! docker info >/dev/null 2>&1; then
  die "cannot talk to the Docker daemon. Is it running (sudo systemctl start docker) and is your user in the docker group (sudo usermod -aG docker \$USER, then log out and in)?"
fi

# --- 2. .env ------------------------------------------------------------------------------------------------------
say "Configuration (.env)"
EXISTING_ENV=0
if [ -f .env ]; then
  EXISTING_ENV=1
  echo ".env exists: keeping it. Only options you passed on the command line are applied."
else
  [ -f .env.example ] || die ".env.example not found in $HERE"
  (umask 077 && cp .env.example .env)
  echo "created .env from .env.example"
fi
chmod 600 .env

secret="$(env_get FAMILYCHAT_SECRET_KEY)"
if [ -z "$secret" ]; then
  env_set FAMILYCHAT_SECRET_KEY "$(openssl rand -base64 48 | tr -d '\n')"
  echo "generated FAMILYCHAT_SECRET_KEY"
elif [ "${#secret}" -lt 32 ]; then
  warn "FAMILYCHAT_SECRET_KEY in .env is shorter than 32 characters; the server will refuse to start. Replace it with: openssl rand -base64 48"
else
  echo "FAMILYCHAT_SECRET_KEY already set: not touched"
fi

# Questions are asked on a fresh install, or for anything given as an option on an existing one.
if [ "$EXISTING_ENV" = 0 ] || [ -n "$ACCESS$DOMAIN$PUSH_HOST$TS_AUTHKEY_ARG$TS_HOSTNAME_ARG" ]; then
  if [ -z "$ACCESS" ]; then
    if interactive; then
      cat <<'EOF'

How will the family's phones reach the server?
  1) Tailscale installed on this Pi, `tailscale serve` for HTTPS   (recommended; nothing is opened to the internet)
  2) Tailscale in a container (no host-level Tailscale; needs an auth key)
  3) Caddy with a public domain (Let's Encrypt; ports 80 and 443 must be forwarded)
  4) Local only for now (http://127.0.0.1:8000)
EOF
      choice=1
      ask choice "Choose 1-4:" 1
      case "$choice" in
        1) ACCESS=tailscale-host ;;
        2) ACCESS=tailscale-sidecar ;;
        3) ACCESS=caddy ;;
        4) ACCESS=none ;;
        *) die "please answer 1, 2, 3 or 4" ;;
      esac
    else
      ACCESS=none
    fi
  fi

  case "$ACCESS" in
    tailscale-host | none) ;;
    tailscale-sidecar)
      add_profile tailscale
      ask TS_HOSTNAME_ARG "Name of the Tailscale node (the address becomes https://NAME.<tailnet>.ts.net):" "${TS_HOSTNAME_ARG:-$(env_get TS_HOSTNAME)}"
      env_set TS_HOSTNAME "${TS_HOSTNAME_ARG:-familychat}"
      if [ -z "$TS_AUTHKEY_ARG" ] && [ -z "$(env_get TS_AUTHKEY)" ] && interactive; then
        echo "Create an auth key at https://login.tailscale.com/admin/settings/keys (leave empty to log in with a link from the container log instead)."
        read -r -s -p "Tailscale auth key: " TS_AUTHKEY_ARG
        echo
      fi
      [ -n "$TS_AUTHKEY_ARG" ] && env_set TS_AUTHKEY "$TS_AUTHKEY_ARG"
      ;;
    caddy)
      add_profile caddy
      if [ -z "$DOMAIN" ]; then ask DOMAIN "Public DNS name that points at this Pi (for example chat.example.org):"; fi
      [ -n "$DOMAIN" ] || die "Caddy needs a domain (--domain NAME)"
      env_set FAMILYCHAT_DOMAIN "$DOMAIN"
      ;;
    *) die "unknown access mode '$ACCESS' (tailscale-host, tailscale-sidecar, caddy or none)" ;;
  esac

  if [ -z "$PUSH_HOST" ] && interactive && [ "$ACCESS" != none ]; then
    echo
    echo "Push notifications need an ntfy server reachable by the phones over https (docs/RASPBERRY_PI_SETUP.md, section 7)."
    case "$ACCESS" in
      caddy) echo "Example host: ntfy.${DOMAIN:-example.org} (add a DNS record for it)." ;;
      *) echo "Example host: <node name>.<tailnet>.ts.net" ;;
    esac
    ask PUSH_HOST "Host name of your ntfy server (empty = no push for now):"
  fi
  if [ -n "$PUSH_HOST" ] && [ "$PUSH_HOST" != none ]; then
    add_profile ntfy
    env_set FAMILYCHAT_PUSH_ALLOWED_HOSTS "$PUSH_HOST"
    if [ "$ACCESS" = caddy ]; then
      env_set NTFY_DOMAIN "$PUSH_HOST"
      env_set NTFY_BASE_URL "https://$PUSH_HOST"
    else
      env_set NTFY_BASE_URL "https://$PUSH_HOST:8443" # the Tailscale serve port for ntfy
    fi
    echo "push: ntfy enabled for $PUSH_HOST"
  fi
else
  ACCESS="$(
    profiles="$(env_get COMPOSE_PROFILES)"
    case ",$profiles," in *,caddy,*) echo caddy ;; *,tailscale,*) echo tailscale-sidecar ;; *) echo existing ;; esac
  )"
fi

if [ "$NO_START" = 1 ]; then
  say "Stopped before starting (--no-start). .env is ready in $HERE"
  exit 0
fi

# --- 3. build and start -------------------------------------------------------------------------------------------
say "Building and starting (the first build takes several minutes on a Pi)"
docker compose up -d --build

say "Waiting for the backend to become healthy"
cid="$(docker compose ps -q backend)"
healthy=0
for _ in $(seq 1 90); do
  state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)"
  if [ "$state" = healthy ]; then healthy=1; break; fi
  sleep 2
done
if [ "$healthy" != 1 ]; then
  docker compose logs --tail 40 backend >&2 || true
  die "the backend did not become healthy within 3 minutes (last state: $state). See the log above; usually a missing or short FAMILYCHAT_SECRET_KEY."
fi
echo "backend is healthy"

# --- 4. first administrator ---------------------------------------------------------------------------------------
say "First administrator"
admins="$(docker compose exec -T backend python -m app.cli list-users 2>/dev/null | grep -c ' admin ' || true)"
if [ "${admins:-0}" -gt 0 ]; then
  echo "an administrator already exists: no new invitation created."
  echo "Create one when needed:  docker compose exec backend python -m app.cli create-invite --admin"
else
  invite="$(docker compose exec -T backend python -m app.cli create-invite --admin --note "install-pi.sh" 2>/dev/null | tail -n 1 | tr -d '\r')"
  [ -n "$invite" ] || die "could not create the invitation; try: docker compose exec backend python -m app.cli create-invite --admin"
  echo
  echo "  Administrator invitation code (valid 72 hours, works once):"
  echo
  echo "      $invite"
fi

# --- 5. what is left for you --------------------------------------------------------------------------------------
say "Next steps"
case "$ACCESS" in
  tailscale-host)
    cat <<'EOF'
Install Tailscale on this Pi, enable MagicDNS and HTTPS certificates in the admin console, then:
  curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
  sudo tailscale serve --bg --https=443 http://127.0.0.1:8000
EOF
    if [ -n "$PUSH_HOST" ] && [ "$PUSH_HOST" != none ]; then echo "  sudo tailscale serve --bg --https=8443 http://127.0.0.1:2586   # ntfy"; fi
    ;;
  tailscale-sidecar)
    echo "The Tailscale container joins your tailnet by itself when TS_AUTHKEY was given. Otherwise open the login link:"
    echo "  docker compose logs tailscale | grep -i https://login"
    echo "Enable MagicDNS and HTTPS certificates in the Tailscale admin console. The address is https://<TS_HOSTNAME>.<tailnet>.ts.net"
    ;;
  caddy)
    echo "Forward TCP 80/443 (and UDP 443) to this Pi; Caddy gets the certificate for https://$(env_get FAMILYCHAT_DOMAIN) by itself."
    ;;
  *)
    echo "The server listens on http://127.0.0.1:8000 only. Put HTTPS in front of it: docs/RASPBERRY_PI_SETUP.md, section 5."
    ;;
esac
echo
echo "Open the app, enter the server address, choose the invitation tab and paste the code."
echo "Nightly backups: docs/RASPBERRY_PI_SETUP.md, section 9 (deployment/systemd/)."
