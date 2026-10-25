#!/bin/bash
# Entrypoint of the `turn` compose service (runs inside the coturn image as the unprivileged user "nobody").
# Builds /tmp/turnserver.conf = turnserver.conf + settings from the environment, then starts turnserver.
# The shared secret only lives in the container environment and in that file on a tmpfs; it is never printed.
set -eu

: "${TURN_SECRET:?TURN_SECRET (FAMILYCHAT_TURN_SECRET in .env) must be set}"
if [ "${#TURN_SECRET}" -lt 32 ]; then
  echo "TURN_SECRET must be at least 32 characters (openssl rand -base64 48)" >&2
  exit 1
fi
case "$TURN_SECRET${TURN_REALM:-}${TURN_EXTERNAL_IP:-}" in *$'\n'*) echo "no newlines in TURN_* values" >&2; exit 1 ;; esac

conf=/tmp/turnserver.conf
umask 077
cp /etc/coturn/turnserver.conf "$conf"
{
  echo
  echo "# --- from the environment"
  printf 'static-auth-secret=%s\n' "$TURN_SECRET"
  printf 'realm=%s\n' "${TURN_REALM:-familychat}"
  # Behind NAT: the public address (or PUBLIC/PRIVATE) that clients must send media to.
  if [ -n "${TURN_EXTERNAL_IP:-}" ]; then printf 'external-ip=%s\n' "$TURN_EXTERNAL_IP"; fi
  # Phones on the tailnet reach each other through the relay only when it may send to 100.64.0.0/10. That also lets
  # whoever holds a credential reach other tailnet hosts through this server, so it is denied unless you opt in.
  if [ "${TURN_ALLOW_TAILNET_PEERS:-false}" != "true" ]; then
    echo "denied-peer-ip=100.64.0.0-100.127.255.255"
    echo "denied-peer-ip=fd7a:115c:a1e0::-fd7a:115c:a1e0:ffff:ffff:ffff:ffff:ffff"
  fi
  if [ -r /certs/fullchain.pem ] && [ -r /certs/privkey.pem ]; then
    echo "tls-listening-port=5349"
    echo "cert=/certs/fullchain.pem"
    echo "pkey=/certs/privkey.pem"
  else
    echo "no-tls"
  fi
} >>"$conf"

exec turnserver -c "$conf"
