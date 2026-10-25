"""TURN REST API credentials (draft-uberti-behave-turn-rest, coturn `use-auth-secret`): username "<expiry>:<user_id>",
credential base64(HMAC-SHA1(shared secret, username)). The credential is derived on demand; nothing is stored."""

import base64
import hashlib
import hmac

from app import clock
from app.config import Settings

USERNAME_WINDOW_S = 600


def turn_credentials(settings: Settings, user_id: str) -> dict:
    stun = settings.stun_url_list
    turn = settings.turn_url_list
    ttl = settings.turn_ttl_seconds
    if not turn:  # no relay configured: clients still try host/STUN candidates
        return {"urls": stun, "username": None, "credential": None, "ttl": ttl}
    # The expiry is rounded to a 10-minute window, so a user gets the same username (and coturn's `user-quota` counts
    # per user, not per credential) for every fetch within a window.
    now = clock.now_ms() // 1000
    username = f"{(now // USERNAME_WINDOW_S + 1) * USERNAME_WINDOW_S + ttl}:{user_id}"
    mac = hmac.new(settings.turn_secret.encode(), username.encode(), hashlib.sha1).digest()  # coturn mandates SHA-1
    return {"urls": stun + turn, "username": username, "credential": base64.b64encode(mac).decode(), "ttl": ttl}
