"""Credential access. Load-only, self-registering with the redactor.

`Secret` deliberately hides its value from `repr`, `str` and f-strings. Reading
it requires calling `.reveal()`, which makes every use site greppable:

    grep -rn "reveal()" bravos/

Any occurrence outside `bravos/http.py`, `bravos/etoro/client.py` and
`bravos/source/fetch.py` is a defect.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .errors import SecretsError
from .redaction import MASK, register

# Layout on disk, relative to `secrets/`.
AGENT_PUBLIC_KEY = Path("etoro-bravos-agent/bravos-public-key.txt")
AGENT_PRIVATE_KEY = Path("etoro-bravos-agent/bravos-private-key.txt")
OWNER_PRIVATE_KEY = Path("etoro-main-readonly/private-key.txt")
BRAVOS_USERNAME = Path("bravos/username.txt")
BRAVOS_PASSWORD = Path("bravos/password.txt")


@dataclass(frozen=True)
class Secret:
    """A credential that refuses to render itself."""

    name: str
    _value: str

    def reveal(self) -> str:
        return self._value

    def __str__(self) -> str:
        return f"<{self.name}:{MASK}>"

    def __repr__(self) -> str:
        return f"Secret(name={self.name!r}, value={MASK})"

    def __format__(self, spec: str) -> str:
        return str(self)


def _read(secrets_dir: Path, relative: Path, name: str) -> Secret:
    path = secrets_dir / relative
    if not path.exists():
        raise SecretsError(
            "credential file is missing",
            name=name,
            expected=str(relative),
        )
    value = path.read_text(encoding="utf-8").strip()
    if not value:
        raise SecretsError("credential file is empty", name=name, expected=str(relative))
    register(value)
    return Secret(name=name, _value=value)


@dataclass(frozen=True)
class EtoroCredentials:
    """Two distinct identities against one application key.

    `agent_user_key` carries real trading read/write scope and addresses the
    Agent Portfolio. `owner_user_key` is read-only and is the only source of
    truth for the owner's real allocation. They must never be interchanged: a
    write attempted with the owner key would fail, and a funding figure read
    from the agent key would be the internal virtual balance, not real money.
    """

    api_key: Secret
    agent_user_key: Secret
    owner_user_key: Secret | None

    @classmethod
    def load(cls, secrets_dir: Path, *, require_owner: bool = True) -> "EtoroCredentials":
        api_key = _read(secrets_dir, AGENT_PUBLIC_KEY, "etoro-application-key")
        agent = _read(secrets_dir, AGENT_PRIVATE_KEY, "etoro-agent-user-key")
        owner: Secret | None
        try:
            owner = _read(secrets_dir, OWNER_PRIVATE_KEY, "etoro-owner-user-key")
        except SecretsError:
            if require_owner:
                raise
            owner = None
        return cls(api_key=api_key, agent_user_key=agent, owner_user_key=owner)


@dataclass(frozen=True)
class BravosCredentials:
    username: Secret
    password: Secret

    @classmethod
    def load(cls, secrets_dir: Path) -> "BravosCredentials":
        return cls(
            username=_read(secrets_dir, BRAVOS_USERNAME, "bravos-username"),
            password=_read(secrets_dir, BRAVOS_PASSWORD, "bravos-password"),
        )
