"""Unit tests for the reusable single-person KG build service."""

from __future__ import annotations

from src.importing.neo4j_importer import ImportSummary
from src.models.graph_models import CandidateExtraction, GraphNode
from src.services.kg_build_service import KGBuildService


def candidate(person_id: int) -> CandidateExtraction:
    return CandidateExtraction(
        person_id=str(person_id),
        nodes=[
            GraphNode(
                node_id=f"person:{person_id}",
                node_type="Person",
                person_id=str(person_id),
                verification_status="PENDING",
            )
        ],
    )


class FakeClient:
    database = "neo4j"

    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


class FakeImporter:
    def __init__(self, calls, client, batch_size) -> None:
        self.calls = calls
        self.client = client
        self.batch_size = batch_size

    def import_candidates(self, preflight):
        self.calls.append((self.client, self.batch_size, preflight))
        return (
            ImportSummary(
                normal_node_input_count=preflight.normal_node_input_count,
                event_input_count=preflight.event_input_count,
                relation_input_count=preflight.relation_input_count,
                merged_node_count=len(preflight.nodes),
                merged_event_count=len(preflight.events),
                merged_relation_count=len(preflight.relations),
            ),
            list(preflight.issues),
        )


def test_build_person_dry_run_returns_structured_result_without_neo4j() -> None:
    client_calls = []
    statuses = []
    service = KGBuildService(
        extractor=candidate,
        neo4j_client_factory=lambda: client_calls.append(True),
    )

    result = service.build_person(1, execute=False, status_callback=statuses.append)

    assert result.as_dict() == {
        "person_id": 1,
        "status": "PREFLIGHT_PASSED",
        "node_count": 1,
        "relation_count": 0,
        "event_count": 0,
        "issue_count": 0,
        "issues": [],
        "import_result": None,
    }
    assert statuses == ["EXTRACTED", "PREFLIGHT_PASSED"]
    assert client_calls == []


def test_build_person_execute_imports_and_closes_client() -> None:
    calls = []
    client = FakeClient()
    service = KGBuildService(
        extractor=candidate,
        neo4j_client_factory=lambda: client,
        importer_factory=lambda current_client, size: FakeImporter(
            calls,
            current_client,
            size,
        ),
    )

    result = service.build_person(1, execute=True, batch_size=25)

    assert result.status == "SUCCESS"
    assert result.import_result is not None
    assert result.import_result["summary"]["merged_node_count"] == 1
    assert len(calls) == 1
    assert calls[0][1] == 25
    assert client.closed is True


def test_build_person_extraction_failure_is_structured_and_redacted() -> None:
    def fail(_person_id: int):
        raise RuntimeError("password=service-secret failed")

    service = KGBuildService(extractor=fail)
    result = service.build_person(1)

    assert result.status == "EXTRACTION_FAILED"
    assert result.issue_count == 1
    assert result.issues[0]["code"] == "EXTRACTION_FAILED"
    assert "service-secret" not in result.issues[0]["message"]
    assert result.preflight_result is not None
    assert result.preflight_result.has_errors is True
