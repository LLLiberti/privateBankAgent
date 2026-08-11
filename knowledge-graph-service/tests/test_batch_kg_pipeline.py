"""Mock-only tests for the serial batch KG pipeline."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import pytest

from scripts.batch_build_kg import parse_args
from src.extraction.id_generator import (
    market_segment_node_id,
    reference_enterprise_node_id,
)
from src.repositories.mysql_kg_repository import (
    ALL_PERSON_IDS_QUERY,
    fetch_all_person_ids,
)
from src.importing.neo4j_importer import (
    ImportSummary,
    build_node_merge_cypher,
    preflight_candidates,
)
from src.models.graph_models import (
    CandidateExtraction,
    GraphEvent,
    GraphIssue,
    GraphNode,
    GraphRelation,
)
from src.pipeline.batch_kg_pipeline import (
    BatchKGPipeline,
    BatchRunOptions,
    NoPersonsSelectedError,
    atomic_write_json,
    parse_person_ids_csv,
    redact_pipeline_error,
    select_person_ids,
)


FIXED_TIME = datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)


def candidate(person_id: int, *, warning: bool = False, dangling: bool = False):
    person_node_id = f"person:{person_id}"
    event_id = f"event:{person_id}"
    issues = []
    if warning:
        issues.append(
            GraphIssue(
                issue_id=f"issue:{person_id}",
                reason="STRICT_FAMILY_VERIFICATION",
                severity="WARNING",
                message="本地测试 warning",
                person_id=str(person_id),
            )
        )
    return CandidateExtraction(
        person_id=str(person_id),
        nodes=[
            GraphNode(
                node_id=person_node_id,
                node_type="Person",
                name=f"人物{person_id}",
                properties={},
                person_id=str(person_id),
                source_id=f"source:{person_id}",
                verification_status="PENDING",
            )
        ],
        events=[
            GraphEvent(
                event_id=event_id,
                event_type="HONOR",
                subject_node_id=person_node_id,
                properties={},
                source_id=f"source:{person_id}",
                verification_status="PENDING",
            )
        ],
        relations=[
            GraphRelation(
                relation_id=f"relation:{person_id}",
                start_node_id=person_node_id,
                end_node_id="event:missing" if dangling else event_id,
                relation_type="HAS_EVENT",
                properties={},
                dimension="SOCIAL",
                source_id=f"source:{person_id}",
                verification_status="PENDING",
            )
        ],
        issues=issues,
    )


class FakeClient:
    database = "neo4j"

    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True


class RecordingImporter:
    def __init__(self, calls, should_fail=None):
        self.calls = calls
        self.should_fail = should_fail

    def import_candidates(self, preflight):
        person_entity = next(
            item["entity_id"]
            for item in preflight.nodes
            if item["label"] == "Person"
        )
        person_id = int(person_entity.split(":", 1)[1])
        self.calls.append(person_id)
        if self.should_fail == person_id:
            raise RuntimeError("password=local-secret simulated failure")
        return (
            ImportSummary(
                normal_node_input_count=preflight.normal_node_input_count,
                event_input_count=preflight.event_input_count,
                relation_input_count=preflight.relation_input_count,
                merged_node_count=len(preflight.nodes),
                merged_event_count=len(preflight.events),
                merged_relation_count=len(preflight.relations),
                pending_candidate_count=preflight.pending_candidate_count,
            ),
            [],
        )


def options(tmp_path: Path, person_ids, mode="DRY_RUN", **changes):
    value = BatchRunOptions(
        person_ids=person_ids,
        mode=mode,
        output_root=tmp_path / "persons",
        batch_root=tmp_path / "batch_runs",
        batch_id="batch_test",
    )
    for key, item in changes.items():
        setattr(value, key, item)
    return value


def pipeline(extractor, *, import_calls=None, client_calls=None, should_fail=None, writer=None):
    import_calls = import_calls if import_calls is not None else []
    client_calls = client_calls if client_calls is not None else []

    def client_factory():
        client_calls.append(True)
        return FakeClient()

    return BatchKGPipeline(
        extractor=extractor,
        neo4j_client_factory=client_factory,
        importer_factory=lambda client, size: RecordingImporter(
            import_calls, should_fail=should_fail
        ),
        clock=lambda: FIXED_TIME,
        manifest_writer=writer or atomic_write_json,
    )


def test_person_ids_parse_deduplicate_sort_start_and_limit():
    assert parse_person_ids_csv("3,1,2,3") == [1, 2, 3]
    assert select_person_ids([5, 2, 3, 2, 8], start_after_person_id=2, limit=2) == [3, 5]


def test_all_persons_uses_one_fixed_select_only():
    calls = []

    class Cursor:
        def execute(self, sql):
            calls.append(sql)

        def fetchall(self):
            return [{"person_id": 3}, {"person_id": 1}, {"person_id": 3}]

        def close(self):
            pass

    class Connection:
        def cursor(self, dictionary=False):
            assert dictionary is True
            return Cursor()

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

    assert fetch_all_person_ids(lambda: Connection()) == [1, 3]
    assert calls == [ALL_PERSON_IDS_QUERY]
    assert ALL_PERSON_IDS_QUERY.startswith("SELECT ")
    assert not any(word in ALL_PERSON_IDS_QUERY for word in ("INSERT", "UPDATE", "DELETE"))


def test_each_person_has_independent_non_overwriting_outputs(tmp_path):
    pipeline(lambda person_id: candidate(person_id)).run(options(tmp_path, [2, 1]))

    first = tmp_path / "persons/person_1"
    second = tmp_path / "persons/person_2"
    expected = {
        "kg_nodes.json",
        "kg_events.json",
        "kg_relations.json",
        "kg_issues.json",
        "extraction_summary.md",
        "neo4j_preflight_report.md",
        "neo4j_preflight_issues.json",
    }
    assert expected <= {path.name for path in first.iterdir()}
    assert expected <= {path.name for path in second.iterdir()}
    assert json.loads((first / "kg_nodes.json").read_text(encoding="utf-8"))[0]["node_id"] == "person:1"
    assert json.loads((second / "kg_nodes.json").read_text(encoding="utf-8"))[0]["node_id"] == "person:2"


def test_dry_run_never_creates_neo4j_client(tmp_path):
    client_calls = []
    pipeline(lambda person_id: candidate(person_id), client_calls=client_calls).run(
        options(tmp_path, [1], mode="DRY_RUN")
    )
    assert client_calls == []


def test_execute_imports_only_preflight_passed_people(tmp_path):
    calls = []
    result = pipeline(
        lambda person_id: candidate(person_id, dangling=person_id == 1),
        import_calls=calls,
    ).run(options(tmp_path, [1, 2], mode="EXECUTE"))

    assert calls == [2]
    assert result["persons"]["1"]["status"] == "PREFLIGHT_FAILED"
    assert result["persons"]["2"]["status"] == "SUCCESS"


def test_extraction_failure_does_not_stop_following_person(tmp_path):
    calls = []

    def extractor(person_id):
        calls.append(person_id)
        if person_id == 1:
            raise RuntimeError("local extraction failure")
        return candidate(person_id)

    result = pipeline(extractor).run(options(tmp_path, [1, 2]))
    assert calls == [1, 2]
    assert result["persons"]["1"]["status"] == "EXTRACTION_FAILED"
    assert result["persons"]["2"]["status"] == "PREFLIGHT_PASSED"


def test_import_failure_is_safe_and_following_person_continues(tmp_path):
    calls = []
    result = pipeline(
        lambda person_id: candidate(person_id),
        import_calls=calls,
        should_fail=1,
    ).run(options(tmp_path, [1, 2], mode="EXECUTE"))

    assert calls == [1, 2]
    assert result["persons"]["1"]["status"] == "IMPORT_FAILED"
    assert result["persons"]["2"]["status"] == "SUCCESS"
    issues = (tmp_path / "batch_runs/batch_test/batch_issues.json").read_text(encoding="utf-8")
    assert "local-secret" not in issues


def test_manifest_is_atomically_updated_during_each_person(tmp_path):
    snapshots = []

    def writer(path, value):
        snapshots.append(json.loads(json.dumps(value)))
        atomic_write_json(path, value)

    pipeline(lambda person_id: candidate(person_id), writer=writer).run(
        options(tmp_path, [1, 2])
    )
    statuses_seen = [
        snapshot["persons"]["1"]["status"] for snapshot in snapshots
    ]
    assert "EXTRACTING" in statuses_seen
    assert "EXTRACTED" in statuses_seen
    assert "PREFLIGHT_PASSED" in statuses_seen
    assert len(snapshots) > 2


def _write_resume_manifest(tmp_path, statuses):
    batch_dir = tmp_path / "batch_runs/batch_resume"
    manifest_path = batch_dir / "manifest.json"
    manifest = {
        "batch_id": "batch_resume",
        "mode": "EXECUTE",
        "created_at": FIXED_TIME.isoformat(),
        "updated_at": FIXED_TIME.isoformat(),
        "output_root": str(tmp_path / "persons"),
        "person_ids": sorted(statuses),
        "persons": {
            str(person_id): {
                "person_id": person_id,
                "status": status,
                "attempts": 1,
            }
            for person_id, status in statuses.items()
        },
        "mysql_write_performed": False,
        "llm_called": False,
    }
    atomic_write_json(manifest_path, manifest)
    return manifest_path


def test_resume_skips_success(tmp_path):
    manifest_path = _write_resume_manifest(
        tmp_path, {1: "SUCCESS", 2: "IMPORT_FAILED"}
    )
    called = []
    pipeline(lambda person_id: called.append(person_id) or candidate(person_id)).run(
        options(
            tmp_path,
            [],
            resume_manifest=manifest_path,
            batch_id=None,
        )
    )
    assert called == [2]


def test_retry_failed_only_processes_only_three_failure_statuses(tmp_path):
    manifest_path = _write_resume_manifest(
        tmp_path,
        {
            1: "SUCCESS",
            2: "EXTRACTION_FAILED",
            3: "PREFLIGHT_FAILED",
            4: "IMPORT_FAILED",
            5: "PENDING",
        },
    )
    called = []
    pipeline(lambda person_id: called.append(person_id) or candidate(person_id)).run(
        options(
            tmp_path,
            [],
            resume_manifest=manifest_path,
            retry_failed_only=True,
            batch_id=None,
        )
    )
    assert called == [2, 3, 4]


def test_shared_reference_ids_do_not_contain_person_id():
    enterprise_id = reference_enterprise_node_id("共享企业")
    segment_id = market_segment_node_id("企业客户")
    assert enterprise_id == reference_enterprise_node_id("共享企业")
    assert segment_id == market_segment_node_id("企业客户")
    assert "person" not in enterprise_id
    assert "person" not in segment_id


def test_batch_summary_counts_inputs_pending_and_reasons(tmp_path):
    pipeline(lambda person_id: candidate(person_id, warning=True)).run(
        options(tmp_path, [1, 2])
    )
    summary = (tmp_path / "batch_runs/batch_test/batch_summary.md").read_text(
        encoding="utf-8"
    )
    assert "目标人物数量：2" in summary
    assert "节点输入总数：2" in summary
    assert "Event 输入总数：2" in summary
    assert "关系输入总数：2" in summary
    assert "PENDING 候选总数：6" in summary
    assert "`STRICT_FAMILY_VERIFICATION`" in summary
    assert "是否修改 MySQL：否" in summary
    assert "是否调用大模型：否" in summary


def test_password_redaction():
    assert "secret-value" not in redact_pipeline_error(
        "password=secret-value neo4j://neo4j:secret-value@localhost"
    )


def test_no_person_records_has_clear_error(tmp_path):
    with pytest.raises(NoPersonsSelectedError, match="没有可处理"):
        pipeline(lambda person_id: candidate(person_id)).run(options(tmp_path, []))


def test_dry_run_and_execute_are_mutually_exclusive():
    with pytest.raises(SystemExit):
        parse_args(["--person-id", "1", "--dry-run", "--execute"])


def test_warning_does_not_block_import(tmp_path):
    calls = []
    result = pipeline(
        lambda person_id: candidate(person_id, warning=True),
        import_calls=calls,
    ).run(options(tmp_path, [1], mode="EXECUTE"))
    assert calls == [1]
    assert result["persons"]["1"]["status"] == "SUCCESS"


def test_identical_relation_duplicate_is_warning_and_auto_deduplicated():
    value = candidate(1)
    raw_relation = value.relations[0].as_dict()
    result = preflight_candidates(
        [item.as_dict() for item in value.nodes],
        [item.as_dict() for item in value.events],
        [raw_relation, dict(raw_relation)],
    )
    assert result.has_errors is False
    assert len(result.relations) == 1
    assert any(
        issue.code == "DUPLICATE_RELATION_CANDIDATE"
        and issue.severity == "WARNING"
        for issue in result.issues
    )


def test_conflicting_relation_duplicate_is_fatal():
    value = candidate(1)
    first = value.relations[0].as_dict()
    second = {**first, "end_node_id": "event:other"}
    result = preflight_candidates(
        [item.as_dict() for item in value.nodes],
        [item.as_dict() for item in value.events],
        [first, second],
    )
    assert result.has_errors is True
    assert any(issue.code == "DUPLICATE_RELATION_ID" for issue in result.issues)


def test_existing_import_cypher_uses_merge_not_create():
    cypher = build_node_merge_cypher("Person")
    assert "MERGE" in cypher
    assert "CREATE (" not in cypher
