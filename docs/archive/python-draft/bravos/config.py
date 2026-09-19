"""Paths, execution mode and guardrails.

Nothing here reads the network or a credential. `load()` touches the filesystem
only for the optional guardrails file, so tests can build a Config by hand.
"""

from __future__ import annotations

import enum
import json
from dataclasses import dataclass, field, replace
from pathlib import Path

from .errors import ConfigError, KillSwitchError

ETORO_HOST = "https://public-api.etoro.com"
BRAVOS_HOST = "https://bravosresearch.com"

#: Only these hosts may ever receive a credential.
ALLOWED_HOSTS = frozenset({"public-api.etoro.com", "bravosresearch.com"})


class Mode(enum.Enum):
    """Which eToro account the write paths address.

    DEMO is the default everywhere. REAL must be asked for explicitly on the
    command line; no config file, environment variable or code default may
    select it, so that a future session cannot flip it by accident.
    """

    DEMO = "demo"
    REAL = "real"

    @property
    def is_real(self) -> bool:
        return self is Mode.REAL


@dataclass(frozen=True)
class Guardrails:
    """Hard ceilings on a single run. A breach aborts; it never trims.

    Trimming an order to fit a limit would silently change the size the policy
    computed, which is exactly the class of surprise this project must not have.
    """

    #: Largest single order, in owner USD.
    max_order_usd: float = 750.0
    #: Largest total deployed across one run, in owner USD.
    max_run_deployment_usd: float = 2000.0
    #: Most orders submitted in one run, of any kind.
    max_orders_per_run: int = 6
    #: Refuse to act on a quote older than this.
    max_quote_age_seconds: int = 60
    #: Refuse to open when the computed size is under the broker minimum.
    min_order_usd: float = 10.0

    def validate(self) -> None:
        if self.max_order_usd <= 0 or self.max_run_deployment_usd <= 0:
            raise ConfigError("guardrail amounts must be positive")
        if self.max_order_usd > self.max_run_deployment_usd:
            raise ConfigError(
                "max_order_usd exceeds max_run_deployment_usd",
                max_order_usd=self.max_order_usd,
                max_run_deployment_usd=self.max_run_deployment_usd,
            )
        if self.max_orders_per_run <= 0:
            raise ConfigError("max_orders_per_run must be positive")
        if self.max_quote_age_seconds <= 0:
            raise ConfigError("max_quote_age_seconds must be positive")
        if self.min_order_usd < 0:
            raise ConfigError("min_order_usd must not be negative")

    @classmethod
    def from_file(cls, path: Path) -> "Guardrails":
        if not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ConfigError(f"guardrails file is not valid JSON: {exc}") from None
        if not isinstance(data, dict):
            raise ConfigError("guardrails file must contain a JSON object")
        known = {f.name for f in cls.__dataclass_fields__.values()}
        unknown = set(data) - known
        if unknown:
            # An unrecognised key is almost always a typo in a limit the owner
            # meant to tighten. Failing is the safe reading.
            raise ConfigError(f"unknown guardrail keys: {sorted(unknown)}")
        rails = cls(**data)
        rails.validate()
        return rails


@dataclass(frozen=True)
class Config:
    root: Path
    mode: Mode = Mode.DEMO
    guardrails: Guardrails = field(default_factory=Guardrails)

    # --- derived paths -------------------------------------------------
    @property
    def secrets_dir(self) -> Path:
        return self.root / "secrets"

    @property
    def state_dir(self) -> Path:
        return self.root / "state"

    @property
    def ledger_dir(self) -> Path:
        return self.state_dir / "bravos"

    @property
    def runs_dir(self) -> Path:
        return self.ledger_dir / "runs"

    @property
    def fixtures_dir(self) -> Path:
        return self.state_dir / "source-fixtures"

    @property
    def evidence_dir(self) -> Path:
        return self.state_dir / "evidence"

    @property
    def kill_file(self) -> Path:
        return self.ledger_dir / "KILL"

    @property
    def guardrails_file(self) -> Path:
        return self.root / "config" / "guardrails.json"

    # --- behaviour -----------------------------------------------------
    def check_kill_switch(self) -> None:
        """Abort if the owner has parked the system.

        Checked immediately before every write request, not only at startup, so
        creating the file mid-run stops the next order.
        """
        if self.kill_file.exists():
            raise KillSwitchError(
                "kill switch present; remove it to resume",
                path=str(self.kill_file),
            )

    def with_mode(self, mode: Mode) -> "Config":
        return replace(self, mode=mode)


def find_root(start: Path | None = None) -> Path:
    """Locate the repository root by walking up for a marker file."""
    here = (start or Path(__file__).resolve()).resolve()
    for candidate in [here, *here.parents]:
        if (candidate / "ARCHITECTURE.md").exists() and (candidate / "bravos").is_dir():
            return candidate
    raise ConfigError("could not locate the repository root", start=str(here))


def load(mode: Mode = Mode.DEMO, root: Path | None = None) -> Config:
    resolved = root or find_root()
    guardrails = Guardrails.from_file(resolved / "config" / "guardrails.json")
    guardrails.validate()
    return Config(root=resolved, mode=mode, guardrails=guardrails)
