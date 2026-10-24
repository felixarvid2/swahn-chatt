"""JSON-lines logging to stdout. Nothing secret is ever passed to the logger by the app."""

import json
import logging
import sys
import time


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        out = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        extra = getattr(record, "fields", None)
        if extra:
            out.update(extra)
        if record.exc_info:
            out["exc"] = self.formatException(record.exc_info)
        return json.dumps(out, ensure_ascii=False, default=str)


def configure_logging(level: str = "INFO") -> None:
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
    root.addHandler(handler)
    root.setLevel(level.upper())
    # The access log would contain ?access_token= for WebSockets; we log requests ourselves.
    logging.getLogger("uvicorn.access").disabled = True
    logging.getLogger("httpx").setLevel(logging.WARNING)
