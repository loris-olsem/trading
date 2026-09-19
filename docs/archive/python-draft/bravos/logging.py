"""Run logging. Everything printed or written goes through redaction.

Two sinks, always both:
  - the terminal, so the owner sees the run as it happens;
  - a per-run log file under state/bravos/runs/, so an assistant with no network
    can audit afterwards what actually happened.
"""

from __future__ import annotations

import io
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import TextIO

from .redaction import assert_clean, redact

LEVELS = ("DEBUG", "INFO", "WARN", "ERROR")


class RunLog:
    def __init__(self, path: Path | None = None, *, stream: TextIO | None = None,
                 min_level: str = "INFO") -> None:
        self.path = path
        self.stream = stream if stream is not None else sys.stdout
        self.min_level = min_level
        self._handle: TextIO | None = None
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            self._handle = path.open("a", encoding="utf-8")

    def _emit(self, level: str, message: str) -> None:
        if LEVELS.index(level) < LEVELS.index(self.min_level):
            return
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        line = f"{stamp} {level:<5} {redact(message)}"
        assert_clean(line, where="log")
        print(line, file=self.stream, flush=True)
        if self._handle is not None:
            self._handle.write(line + "\n")
            self._handle.flush()

    def debug(self, message: str) -> None:
        self._emit("DEBUG", message)

    def info(self, message: str) -> None:
        self._emit("INFO", message)

    def warn(self, message: str) -> None:
        self._emit("WARN", message)

    def error(self, message: str) -> None:
        self._emit("ERROR", message)

    def banner(self, message: str) -> None:
        """A visually unmissable notice. Used for REAL-money mode."""
        bar = "=" * 72
        for line in (bar, message, bar):
            self._emit("WARN", line)

    def close(self) -> None:
        if self._handle is not None:
            self._handle.close()
            self._handle = None

    def __enter__(self) -> "RunLog":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


# A sink that swallows everything. io.StringIO keeps this portable; "/dev/null"
# does not exist on the owner's Windows host.
NULL_LOG = RunLog(stream=io.StringIO(), min_level="ERROR")
