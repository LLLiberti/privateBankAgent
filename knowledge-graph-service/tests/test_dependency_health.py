"""Mock-only tests for MySQL and Neo4j dependency health checks."""

from __future__ import annotations

import json
from contextlib import contextmanager

import pytest
from fastapi.testclient import TestClient

from src.api.dependencies import get_dependency_health_service
from src.api.main import create_app
from src.services.dependency_health_service import DependencyHealthService


class FakeCursor:
    def __init__(self, available: bool, state: dict) -> None:
        self.available = available
        self.state = state

    def execute(self, sql: str) -> None:
        self.state["mysql_sql"] = sql
        if not self.available:
            raise RuntimeError(
                "mysql://health-user:password=secret@private-host connection failed"
            )

    def fetchone(self):
        return (1,)

    def close(self) -> None:
        self.state["mysql_cursor_closed"] = True


class FakeConnection:
    def __init__(self, available: bool, state: dict) -> None:
        self.available = available
        self.state = state

    def cursor(self) -> FakeCursor:
        return FakeCursor(self.available, self.state)


class FakeNeo4jClient:
    def __init__(self, available: bool, state: dict) -> None:
        self.available = available
        self.state = state

    def test_connection(self) -> bool:
        self.state["neo4j_checked"] = True
        if not self.available:
            raise RuntimeError(
                "neo4j://health-user:password=secret@private-host traceback"
            )
        return True

    def close(self) -> None:
        self.state["neo4j_closed"] = True


def health_service(mysql_up: bool, neo4j_up: bool):
    state = {
        "mysql_connection_closed": False,
        "mysql_cursor_closed": False,
        "neo4j_closed": False,
    }

    @contextmanager
    def mysql_factory():
        try:
            yield FakeConnection(mysql_up, state)
        finally:
            state["mysql_connection_closed"] = True

    service = DependencyHealthService(
        mysql_connection_factory=mysql_factory,
        neo4j_client_factory=lambda: FakeNeo4jClient(neo4j_up, state),
    )
    return service, state


@pytest.mark.parametrize(
    ("mysql_up", "neo4j_up", "overall", "mysql_status", "neo4j_status"),
    [
        (True, True, "UP", "UP", "UP"),
        (False, True, "DEGRADED", "DOWN", "UP"),
        (True, False, "DEGRADED", "UP", "DOWN"),
        (False, False, "DEGRADED", "DOWN", "DOWN"),
    ],
)
def test_dependency_health_api_combinations_and_resource_closure(
    mysql_up,
    neo4j_up,
    overall,
    mysql_status,
    neo4j_status,
) -> None:
    service, state = health_service(mysql_up, neo4j_up)
    app = create_app()
    app.dependency_overrides[get_dependency_health_service] = lambda: service
    with TestClient(app) as client:
        response = client.get("/health/dependencies")
    app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json() == {
        "status": overall,
        "dependencies": {
            "mysql": {"status": mysql_status},
            "neo4j": {"status": neo4j_status},
        },
    }
    assert state["mysql_sql"] == "SELECT 1"
    assert state["mysql_cursor_closed"] is True
    assert state["mysql_connection_closed"] is True
    assert state["neo4j_checked"] is True
    assert state["neo4j_closed"] is True


def test_dependency_health_response_does_not_leak_connection_details() -> None:
    service, _state = health_service(False, False)
    app = create_app()
    app.dependency_overrides[get_dependency_health_service] = lambda: service
    with TestClient(app) as client:
        response = client.get("/health/dependencies")
    app.dependency_overrides.clear()

    body = json.dumps(response.json()).lower()
    assert response.status_code == 200
    for secret in (
        "secret",
        "password",
        "private-host",
        "health-user",
        "mysql://",
        "neo4j://",
        "traceback",
        "runtimeerror",
    ):
        assert secret not in body


def test_process_health_remains_independent_of_dependencies() -> None:
    class MustNotBeCalled:
        def check(self):
            raise AssertionError("/health called dependency health service")

    app = create_app()
    app.dependency_overrides[get_dependency_health_service] = MustNotBeCalled
    with TestClient(app) as client:
        response = client.get("/health")
    app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
