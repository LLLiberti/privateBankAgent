"""Neo4j Community client with lazy driver loading.

Importing this module never connects to Neo4j. The optional ``neo4j`` Python
package is imported only when :meth:`Neo4jClient.from_settings` is called.
"""

from __future__ import annotations

import re
import time
from typing import Any, Callable, Dict, List, Mapping, Optional

from src.utils.config import settings


def redact_neo4j_error(message: str, password: Optional[str] = None) -> str:
    """Remove credentials from a driver error before it is reported."""

    redacted = str(message)
    if password:
        redacted = redacted.replace(password, "***REDACTED***")
    redacted = re.sub(
        r"(?i)(password|passwd|pwd)\s*[=:]\s*[^\s,;]+",
        r"\1=***REDACTED***",
        redacted,
    )
    return re.sub(
        r"(?i)((?:neo4j|bolt)(?:\+s|\+ssc)?://[^:/\s]+:)[^@\s]+(@)",
        r"\1***REDACTED***\2",
        redacted,
    )


class Neo4jClientError(RuntimeError):
    """Safe client error whose message does not contain the password."""


class Neo4jClient:
    """Execute parameterized read and write transactions for one database."""

    def __init__(
        self,
        driver: Any,
        database: str,
        *,
        max_retries: int = 0,
        password_for_redaction: Optional[str] = None,
    ) -> None:
        self._driver = driver
        self.database = database
        self.max_retries = max_retries
        self._password_for_redaction = password_for_redaction

    @classmethod
    def from_settings(
        cls,
        config: Any = settings,
        *,
        driver_factory: Optional[Callable[..., Any]] = None,
    ) -> "Neo4jClient":
        config.validate_neo4j()
        if driver_factory is None:
            try:
                from neo4j import GraphDatabase
            except ImportError as exc:  # pragma: no cover - environment specific
                raise Neo4jClientError(
                    "缺少 Neo4j Python 驱动；请先安装 neo4j 包"
                ) from exc
            driver_factory = GraphDatabase.driver

        try:
            driver = driver_factory(
                config.neo4j_uri,
                auth=(config.neo4j_username, config.neo4j_password),
                connection_timeout=config.neo4j_connect_timeout_seconds,
            )
        except Exception as exc:
            raise Neo4jClientError(
                redact_neo4j_error(str(exc), config.neo4j_password)
            ) from exc

        return cls(
            driver,
            config.neo4j_database,
            max_retries=config.neo4j_max_retries,
            password_for_redaction=config.neo4j_password,
        )

    def read(
        self,
        cypher: str,
        parameters: Optional[Mapping[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        return self._run("read", cypher, parameters or {})

    def write(
        self,
        cypher: str,
        parameters: Optional[Mapping[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        return self._run("write", cypher, parameters or {})

    def test_connection(self) -> bool:
        records = self.read("RETURN 1 AS ok", {})
        return bool(records and records[0].get("ok") == 1)

    def close(self) -> None:
        self._driver.close()

    def __enter__(self) -> "Neo4jClient":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.close()

    def _run(
        self,
        access_mode: str,
        cypher: str,
        parameters: Mapping[str, Any],
    ) -> List[Dict[str, Any]]:
        last_error: Optional[Exception] = None
        for attempt in range(self.max_retries + 1):
            try:
                with self._driver.session(database=self.database) as session:
                    method_name = (
                        "execute_read" if access_mode == "read" else "execute_write"
                    )
                    transaction_method = getattr(session, method_name, None)
                    if transaction_method is None:
                        legacy_name = (
                            "read_transaction"
                            if access_mode == "read"
                            else "write_transaction"
                        )
                        transaction_method = getattr(session, legacy_name)
                    return transaction_method(
                        lambda tx: [
                            dict(record) for record in tx.run(cypher, parameters)
                        ]
                    )
            except Exception as exc:  # driver exception hierarchy is optional
                last_error = exc
                if attempt < self.max_retries:
                    time.sleep(min(0.25 * (2**attempt), 1.0))

        assert last_error is not None
        safe_message = redact_neo4j_error(
            str(last_error),
            self._password_for_redaction,
        )
        raise Neo4jClientError(safe_message) from last_error
