"""Typed failures.

Every failure carries a stable `code`. Codes are matched in tests and printed to
the owner; message text may change, codes may not.

`Blocker` is deliberately separate from `BravosError`: a blocker is an expected,
recorded outcome ("this article is unparseable, so decisions depending on it are
suspended"), not a crash. The run continues and reports it. Anything that is a
genuine fault raises a `BravosError` subclass instead.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping


class BravosError(Exception):
    """Base class. Never raised directly; always a subclass with a code."""

    code = "BRAVOS_ERROR"

    def __init__(self, message: str = "", **context: Any) -> None:
        self.context = context
        super().__init__(message or self.code)

    def __str__(self) -> str:  # pragma: no cover - trivial
        base = super().__str__()
        if not self.context:
            return f"[{self.code}] {base}"
        detail = " ".join(f"{k}={v!r}" for k, v in sorted(self.context.items()))
        return f"[{self.code}] {base} ({detail})"


class ConfigError(BravosError):
    code = "CONFIG_INVALID"


class SecretsError(BravosError):
    code = "SECRETS_UNAVAILABLE"


class TransportError(BravosError):
    """Network-level failure: DNS, TLS, timeout, connection reset."""

    code = "TRANSPORT_FAILED"


class HttpError(BravosError):
    """A response arrived with a non-success status."""

    code = "HTTP_ERROR"

    def __init__(self, message: str = "", *, status: int = 0, **context: Any) -> None:
        self.status = status
        super().__init__(message, status=status, **context)


class SourceError(BravosError):
    code = "SOURCE_FAILED"


class ExtractionError(BravosError):
    """The article did not present the labelled fields we require."""

    code = "EXTRACTION_AMBIGUOUS"


class PolicyError(BravosError):
    """The evidence violates an invariant the policy assumes."""

    code = "POLICY_VIOLATION"


class GuardrailError(BravosError):
    """A configured limit would be breached. Always aborts the run."""

    code = "GUARDRAIL_BREACHED"


class ApprovalError(BravosError):
    code = "APPROVAL_INVALID"


class ExecutionError(BravosError):
    code = "EXECUTION_FAILED"


class KillSwitchError(BravosError):
    code = "KILL_SWITCH_ENGAGED"


@dataclass(frozen=True)
class Blocker:
    """A recorded, expected obstruction. Not an exception."""

    code: str
    subject: str
    detail: str
    context: Mapping[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict:
        return {
            "code": self.code,
            "subject": self.subject,
            "detail": self.detail,
            "context": dict(self.context),
        }

    def __str__(self) -> str:  # pragma: no cover - trivial
        return f"{self.code} on {self.subject}: {self.detail}"
