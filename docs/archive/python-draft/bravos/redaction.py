"""The single place secrets are scrubbed from anything that leaves the process.

Every credential loaded by `bravos.secrets` registers itself here. Every log
line, exception rendering and report string passes through `redact`. Nothing
else in the codebase is allowed to format a credential.

Deliberate design notes:

- Registration is global and append-only. Secrets live for the process lifetime
  and there is no unregister, so there is no window where a value is live but
  unregistered.
- Values shorter than MIN_LENGTH are refused. Redacting a 3-character string
  would corrupt unrelated output and hide real information.
- `redact` also scrubs the URL-encoded and lowercase forms, because credentials
  reach us as header values and can be echoed back in either shape.
"""

from __future__ import annotations

import urllib.parse
from typing import Any, Iterable

MIN_LENGTH = 8
MASK = "***REDACTED***"

_registry: set[str] = set()


def register(value: str | None) -> None:
    """Add a secret to the scrub list. Safe to call repeatedly."""
    if not value:
        return
    text = str(value).strip()
    if len(text) < MIN_LENGTH:
        # Too short to scrub safely. Refuse loudly rather than silently
        # corrupting every log line that happens to contain the substring.
        raise ValueError(
            f"refusing to register a secret shorter than {MIN_LENGTH} characters"
        )
    for variant in (text, text.lower(), urllib.parse.quote(text, safe="")):
        _registry.add(variant)


def registered_count() -> int:
    """Number of scrub variants held. Used by tests, never prints values."""
    return len(_registry)


def redact(value: Any) -> str:
    """Return `value` as text with every registered secret masked."""
    text = value if isinstance(value, str) else repr(value)
    if not _registry:
        return text
    # Longest first, so a secret that contains another is masked whole.
    for secret in sorted(_registry, key=len, reverse=True):
        if secret and secret in text:
            text = text.replace(secret, MASK)
    return text


def redact_mapping(mapping: dict) -> dict:
    """Redact a header/param mapping, masking known-sensitive keys entirely."""
    sensitive = {"x-api-key", "x-user-key", "authorization", "cookie", "set-cookie",
                 "password", "passwd", "token"}
    out: dict = {}
    for key, raw in mapping.items():
        if str(key).lower() in sensitive:
            out[key] = MASK
        else:
            out[key] = redact(raw)
    return out


def assert_clean(text: str, *, where: str = "output") -> None:
    """Raise if any registered secret survived into `text`.

    Used at the boundaries that write to disk or to the terminal, so a missed
    redaction fails the run instead of leaking.
    """
    for secret in _registry:
        if secret and secret in text:
            raise AssertionError(f"credential material reached {where}")


def scrub_iterable(values: Iterable[Any]) -> list[str]:
    return [redact(v) for v in values]
