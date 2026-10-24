"""JSON-lines logging to stdout. Nothing secret is ever passed to the logger by the app."""

import json
import logging
import re
import sys
import time

from app.request_id import current_request_id

_URL_QUERY = re.compile(r"(/[^\s\"?]*)\?[^\s\"]*")
_TOKEN_PARAM = re.compile(r"(access_token|refresh_token|token)=[^&\s\"]+", re.IGNORECASE)


class RedactQueryFilter(logging.Filter):
    """Strip URL query strings from log records (uvicorn.error logs `WebSocket /ws?access_token=... [accepted]`)."""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            text = record.getMessage()
        except Exception:
            return True
        if "?" in text or "token=" in text.lower():
            record.msg = _TOKEN_PARAM.sub(r"\1=[redacted]", _URL_QUERY.sub(r"\1?[redacted]", text))
            record.args = None
        return True


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        out = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        request_id = current_request_id()
        if request_id:
            out["request_id"] = request_id
        extra = getattr(record, "fields", None)
        if extra:
            out.update(extra)
        if record.exc_info:
            out["exc"] = self.formatException(record.exc_info)
        return json.dumps(out, ensure_ascii=False, default=str)


def configure_logging(level: str = "INFO") -> None:
    for name in ("uvicorn", "uvicorn.error"):
        lg = logging.getLogger(name)
        if not any(isinstance(f, RedactQueryFilter) for f in lg.filters):
            lg.addFilter(RedactQueryFilter())
    root = logging.getLogger()
    for h in list(root.handlers):
        if getattr(h, "_familychat", False):
            root.removeHandler(h)
    if any(isinstance(h.formatter, JsonFormatter) for h in root.handlers):
        root.setLevel(level.upper())  # e.g. uvicorn's --log-config already installed the JSON handler
        logging.getLogger("uvicorn.access").disabled = True
        return
    handler = logging.StreamHandler(sys.stdout)
    handler._familychat = True  # type: ignore[attr-defined]
    handler.setFormatter(JsonFormatter())
    handler.addFilter(RedactQueryFilter())
    root.addHandler(handler)
    root.setLevel(level.upper())
    # The access log would contain ?access_token= for WebSockets; we log requests ourselves.
    logging.getLogger("uvicorn.access").disabled = True
    logging.getLogger("httpx").setLevel(logging.WARNING)
