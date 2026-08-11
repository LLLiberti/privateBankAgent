"""Local-only tests for Neo4j preflight and import behavior."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from scripts import import_kg_to_neo4j as cli
from src.database.neo4j_client import Neo4jClient, redact_neo4j_error
from src.importing.neo4j_importer import (
    CONSTRAINT_CYPHER,
    Neo4jImporter,
    build_node_merge_cypher,
    build_relation_merge_cypher,
    preflight_candidates,
)


def node(node_id: str = "person:1", **changes):
    value = {
        "node_id": node_id,
        "node_type": "Person",
        "name": "测试人物",
        "properties": {},
        "verification_status": "PENDING",
    }
    value.update(changes)
    return value


def event(event_id: str = "event:1", **changes):
    value = {
        "event_id": event_id,
        "event_type": "HONOR",
        "subject_node_id": "person:1",
        "properties": {},
        "verification_status": "PENDING",
    }
    value.update(changes)
    return value


def relation(relation_id: str = "relation:1", **changes):
    value = {
        "relation_id": relation_id,
        "start_node_id": "person:1",
        "end_node_id": "event:1",
        "relation_type": "HAS_EVENT",
        "properties": {},
        "dimension": "SOCIAL",
        "verification_status": "PENDING",
    }
    value.update(changes)
    return value


class RecordingClient:
    database = "neo4j"

    def __init__(self):
        self.calls = []

    def write(self, cypher, parameters):
        self.calls.append((cypher, parameters))
        rows = parameters.get("rows", [])
        merged_ids = [
            row.get("entity_id", row.get("relation_id"))
            for row in rows
        ]
        return [{"merged_ids": merged_ids}] if rows else []


def test_normal_node_becomes_kg_entity_with_fixed_label():
    result = preflight_candidates([node()], [], [])
    prepared = result.nodes[0]
    assert prepared["entity_id"] == "person:1"
    assert prepared["label"] == "Person"
    cypher = build_node_merge_cypher(prepared["label"])
    assert "MERGE (n:KGEntity:Person" in cypher


def test_event_becomes_kg_entity_event_and_uses_event_id():
    result = preflight_candidates([node()], [event()], [])
    prepared = result.events[0]
    assert prepared["entity_id"] == "event:1"
    assert prepared["label"] == "Event"
    assert prepared["properties"]["event_type"] == "HONOR"
    assert ":KGEntity:Event" in build_node_merge_cypher("Event")


@pytest.mark.parametrize(
    ("nodes", "events", "relations", "expected_code"),
    [
        ([node(), node()], [], [], "DUPLICATE_NODE_ID"),
        ([node()], [event(), event()], [], "DUPLICATE_EVENT_ID"),
    ],
)
def test_duplicate_ids_are_fatal(nodes, events, relations, expected_code):
    result = preflight_candidates(nodes, events, relations)
    assert result.has_errors
    assert expected_code in {issue.code for issue in result.issues}


def test_identical_relation_duplicates_are_deduplicated_with_warning():
    result = preflight_candidates(
        [node()],
        [event()],
        [relation(), relation()],
    )
    assert result.has_errors is False
    assert len(result.relations) == 1
    assert "DUPLICATE_RELATION_CANDIDATE" in {
        issue.code for issue in result.issues
    }


def test_conflicting_relation_duplicates_are_fatal():
    result = preflight_candidates(
        [node()],
        [event()],
        [relation(), relation(end_node_id="event:other")],
    )
    assert result.has_errors is True
    assert "DUPLICATE_RELATION_ID" in {issue.code for issue in result.issues}


def test_dangling_relation_is_reported_and_not_prepared_for_import():
    result = preflight_candidates(
        [node()],
        [],
        [relation(end_node_id="organization:missing")],
    )
    assert result.relations == []
    assert result.has_errors is True
    assert result.dangling_relation_count == 1
    assert "DANGLING_RELATION" in {issue.code for issue in result.issues}


def test_illegal_relation_type_is_rejected_before_cypher_generation():
    result = preflight_candidates(
        [node()],
        [event()],
        [relation(relation_type="MALICIOUS_TYPE")],
    )
    assert result.has_errors
    assert result.invalid_type_count == 1
    with pytest.raises(ValueError):
        build_relation_merge_cypher("HAS_EVENT] DELETE r //")


def test_allowlisted_relation_type_generates_parameterized_merge():
    cypher = build_relation_merge_cypher("HAS_EVENT")
    assert "MERGE (a)-[r:HAS_EVENT" in cypher
    assert "$rows" in cypher
    assert "CREATE (" not in cypher


def test_repeated_import_uses_merge_for_nodes_events_and_relations():
    result = preflight_candidates([node()], [event()], [relation()])
    client = RecordingClient()
    importer = Neo4jImporter(client)
    importer.import_candidates(result)
    importer.import_candidates(result)
    data_queries = [query for query, _ in client.calls if query != CONSTRAINT_CYPHER]
    assert data_queries
    assert all("MERGE" in query for query in data_queries)
    assert all("CREATE (" not in query for query in data_queries)


def test_pending_status_is_preserved_in_properties():
    result = preflight_candidates([node()], [event()], [relation()])
    values = [
        result.nodes[0]["properties"]["verification_status"],
        result.events[0]["properties"]["verification_status"],
        result.relations[0]["properties"]["verification_status"],
    ]
    assert values == ["PENDING", "PENDING", "PENDING"]


def test_nested_properties_are_losslessly_serialized_without_eval():
    nested = {"risk": {"level": "high"}, "tags": [{"name": "a"}]}
    result = preflight_candidates([node(properties=nested)], [], [])
    properties = result.nodes[0]["properties"]
    assert json.loads(properties["risk"]) == {"level": "high"}
    assert json.loads(properties["tags"]) == [{"name": "a"}]
    assert json.loads(properties["properties"]) == nested
    assert "COMPLEX_PROPERTY_SERIALIZED" in {
        issue.code for issue in result.issues
    }


def test_error_redaction_does_not_expose_password():
    secret = "do-not-print-this"
    message = redact_neo4j_error(
        f"neo4j://neo4j:{secret}@localhost password={secret}",
        secret,
    )
    assert secret not in message
    assert "***REDACTED***" in message


def test_dry_run_does_not_construct_neo4j_client(tmp_path, monkeypatch):
    paths = {}
    for key, value in (
        ("nodes", [node()]),
        ("events", [event()]),
        ("relations", [relation()]),
    ):
        path = tmp_path / f"{key}.json"
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        paths[key] = path

    def fail_if_called(*args, **kwargs):
        raise AssertionError("dry-run must not construct a Neo4j client")

    monkeypatch.setattr(cli.Neo4jClient, "from_settings", fail_if_called)
    exit_code = cli.main(
        [
            "--nodes",
            str(paths["nodes"]),
            "--events",
            str(paths["events"]),
            "--relations",
            str(paths["relations"]),
            "--output-dir",
            str(tmp_path),
            "--dry-run",
        ]
    )
    assert exit_code == 0


def test_connection_probe_uses_only_return_one(monkeypatch):
    client = Neo4jClient(driver=None, database="neo4j")
    calls = []

    def fake_read(cypher, parameters):
        calls.append((cypher, parameters))
        return [{"ok": 1}]

    monkeypatch.setattr(client, "read", fake_read)
    assert client.test_connection() is True
    assert calls == [("RETURN 1 AS ok", {})]


def test_execute_order_is_constraint_nodes_events_relations():
    result = preflight_candidates([node()], [event()], [relation()])
    client = RecordingClient()
    Neo4jImporter(client).import_candidates(result)
    queries = [query for query, _ in client.calls]
    assert queries[0] == CONSTRAINT_CYPHER
    assert ":KGEntity:Person" in queries[1]
    assert ":KGEntity:Event" in queries[2]
    assert "[r:HAS_EVENT" in queries[3]


def test_single_record_failure_is_reported_without_losing_other_rows():
    class OneBadClient(RecordingClient):
        def write(self, cypher, parameters):
            rows = parameters.get("rows", [])
            if any(row.get("entity_id") == "person:bad" for row in rows):
                raise RuntimeError("bad local mock row")
            return super().write(cypher, parameters)

    result = preflight_candidates(
        [node("person:1"), node("person:bad")],
        [],
        [],
    )
    summary, issues = Neo4jImporter(OneBadClient(), batch_size=100).import_candidates(result)
    assert summary.merged_node_count == 1
    assert any(
        issue.code == "IMPORT_RECORD_FAILED" and issue.record_id == "person:bad"
        for issue in issues
    )
