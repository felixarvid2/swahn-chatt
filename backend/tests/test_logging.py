"""WebSocket access tokens in the query string must never reach the logs (uvicorn logs the full path)."""

import io
import json
import logging
import logging.config
from pathlib import Path

from app.logging_setup import RedactQueryFilter, configure_logging

SECRET_TOKEN = "eyJhbGciOiJIUzI1NiJ9.SECRET-PAYLOAD.SECRET-SIG"
UVICORN_FMT = '%s - "WebSocket %s" [accepted]'  # what uvicorn's websockets/wsproto implementations emit


def _emit_like_uvicorn() -> None:
    log = logging.getLogger("uvicorn.error")
    log.info(UVICORN_FMT, "10.0.0.2:5555", f"/api/v1/ws?access_token={SECRET_TOKEN}")
    log.info('%s - "WebSocket %s" 403', "10.0.0.2:5555", f"/api/v1/ws?foo=1&access_token={SECRET_TOKEN}")
    log.info("connection open")


def _restore(root_handlers, root_level, saved):
    root = logging.getLogger()
    root.handlers[:] = root_handlers
    root.setLevel(root_level)
    for name, (handlers, level, propagate, disabled, filters) in saved.items():
        lg = logging.getLogger(name)
        lg.handlers[:] = handlers
        lg.setLevel(level)
        lg.propagate = propagate
        lg.disabled = disabled
        lg.filters[:] = filters


def _snapshot():
    names = ("uvicorn", "uvicorn.error", "uvicorn.access")
    saved = {
        n: (
            list(logging.getLogger(n).handlers),
            logging.getLogger(n).level,
            logging.getLogger(n).propagate,
            logging.getLogger(n).disabled,
            list(logging.getLogger(n).filters),
        )
        for n in names
    }
    return list(logging.getLogger().handlers), logging.getLogger().level, saved


def test_filter_redacts_query_strings_and_tokens():
    f = RedactQueryFilter()
    rec = logging.LogRecord(
        "x", logging.INFO, __file__, 1, UVICORN_FMT, ("1.2.3.4:1", f"/ws?access_token={SECRET_TOKEN}"), None
    )
    assert f.filter(rec)
    assert SECRET_TOKEN not in rec.getMessage()
    assert rec.getMessage() == '1.2.3.4:1 - "WebSocket /ws?[redacted]" [accepted]'
    plain = logging.LogRecord("x", logging.INFO, __file__, 1, "100 percent fine, why? ok", (), None)
    f.filter(plain)
    assert plain.getMessage() == "100 percent fine, why? ok"
    bare = logging.LogRecord("x", logging.INFO, __file__, 1, "got access_token=%s here", (SECRET_TOKEN,), None)
    f.filter(bare)
    assert SECRET_TOKEN not in bare.getMessage()


def test_configure_logging_redacts_uvicorn_error_records(capsys):
    state = _snapshot()
    try:
        for h in list(logging.getLogger().handlers):
            if getattr(h, "_swahnchat", False):
                logging.getLogger().removeHandler(h)
        configure_logging("INFO")
        _emit_like_uvicorn()
        out = capsys.readouterr().out
        assert "WebSocket" in out and "[accepted]" in out
        assert SECRET_TOKEN not in out and "access_token" not in out
    finally:
        _restore(*state)


def test_uvicorn_log_config_redacts_uvicorn_error_records(monkeypatch):
    """The production path: uvicorn --log-config uvicorn_log.json (handler on the `uvicorn` logger)."""
    state = _snapshot()
    try:
        cfg = json.loads((Path(__file__).parent.parent / "uvicorn_log.json").read_text())
        buf = io.StringIO()
        cfg["handlers"]["stdout"]["stream"] = buf
        logging.config.dictConfig(cfg)
        _emit_like_uvicorn()
        out = buf.getvalue()
        assert "[accepted]" in out and "connection open" in out
        assert SECRET_TOKEN not in out and "access_token" not in out
    finally:
        _restore(*state)
