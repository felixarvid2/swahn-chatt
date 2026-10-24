"""Time source (a module so tests can patch it)."""

import time


def now_ms() -> int:
    return int(time.time() * 1000)
