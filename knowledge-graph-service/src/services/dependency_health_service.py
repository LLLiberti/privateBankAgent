"""Lightweight availability checks for FastAPI runtime dependencies."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Callable

from src.database.mysql_client import get_mysql_connection
from src.database.neo4j_client import Neo4jClient
from src.utils.config import settings


LOGGER = logging.getLogger(__name__)


def _default_neo4j_client_factory() -> Neo4jClient:
    return Neo4jClient.from_settings(settings)


@dataclass(frozen=True)
class DependencyHealthResult:
    status: str
    mysql_status: str
    neo4j_status: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "dependencies": {
                "mysql": {"status": self.mysql_status},
                "neo4j": {"status": self.neo4j_status},
            },
        }


class DependencyHealthService:
    """Check MySQL and Neo4j without exposing connection details."""

    def __init__(
        self,
        *,
        mysql_connection_factory: Callable[[], Any] = get_mysql_connection,
        neo4j_client_factory: Callable[[], Any] = _default_neo4j_client_factory,
    ) -> None:
        self.mysql_connection_factory = mysql_connection_factory
        self.neo4j_client_factory = neo4j_client_factory

    def check(self) -> DependencyHealthResult:
        mysql_status = "UP" if self.check_mysql() else "DOWN"
        neo4j_status = "UP" if self.check_neo4j() else "DOWN"
        overall = (
            "UP"
            if mysql_status == "UP" and neo4j_status == "UP"
            else "DEGRADED"
        )
        return DependencyHealthResult(
            status=overall,
            mysql_status=mysql_status,
            neo4j_status=neo4j_status,
        )

    def check_mysql(self) -> bool:
        try:
            with self.mysql_connection_factory() as connection:
                cursor = connection.cursor()
                try:
                    cursor.execute("SELECT 1")
                    return cursor.fetchone() is not None
                finally:
                    cursor.close()
        except Exception:
            LOGGER.warning("MySQL dependency health check is DOWN")
            return False

    def check_neo4j(self) -> bool:
        client = None
        available = False
        try:
            client = self.neo4j_client_factory()
            available = bool(client.test_connection())
        except Exception:
            LOGGER.warning("Neo4j dependency health check is DOWN")
        finally:
            if client is not None:
                try:
                    client.close()
                except Exception:
                    LOGGER.warning("Neo4j dependency health client close failed")
                    available = False
        return available
