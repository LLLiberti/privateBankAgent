"""Mock-only tests for the FastAPI adapter."""

from __future__ import annotations

import threading
import time
from types import SimpleNamespace
from typing import Any, Dict, List

import pytest
from fastapi.testclient import TestClient

from src.api.dependencies import get_kg_build_service
from src.api.dependencies import get_kg_batch_job_service
from src.api.main import create_app
from src.services.kg_batch_job_service import (
    BatchJobConflictError,
    BatchJobNotFoundError,
    BatchIssuesReadError,
    KGBatchJobService,
)
from src.services.kg_build_service import KGBuildResult


class BlockingPipeline:
    def __init__(self, statuses) -> None:
        self.statuses = statuses
        self.calls = []
        self.started = threading.Event()
        self.release = threading.Event()

    def run(self, options):
        self.calls.append(options)
        self.started.set()
        if not self.release.wait(timeout=2):
            raise TimeoutError("test did not release pipeline")
        return {
            "batch_id": options.batch_id,
            "mode": options.mode,
            "persons": {
                str(person_id): {"person_id": person_id, "status": status}
                for person_id, status in self.statuses.items()
            },
        }


def wait_for_status(service, job_id: str, expected: str, timeout: float = 2.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = service.get_job(job_id)
        if job is not None and job.status == expected:
            return job
        time.sleep(0.01)
    raise AssertionError(f"job {job_id} did not reach {expected}")


class MockKGBuildService:
    def __init__(self) -> None:
        self.calls: List[Dict[str, Any]] = []

    def build_person(self, person_id: int, execute: bool = True) -> KGBuildResult:
        self.calls.append({"person_id": person_id, "execute": execute})
        warning = {
            "stage": "EXTRACTION",
            "reason": "MAPPING_PENDING",
            "severity": "WARNING",
            "message": "mock warning",
        }
        import_result = None
        if execute:
            import_result = {
                "summary": {
                    "merged_node_count": 1,
                    "merged_event_count": 0,
                    "merged_relation_count": 0,
                    "pending_candidate_count": 1,
                    "graph_status": "PREVIEW",
                },
                "issues": [],
            }
        return KGBuildResult(
            person_id=person_id,
            status="SUCCESS" if execute else "PREFLIGHT_PASSED",
            node_count=1,
            relation_count=0,
            event_count=0,
            issue_count=1,
            issues=[warning],
            import_result=import_result,
        )


@pytest.fixture
def api_client():
    service = MockKGBuildService()
    app = create_app()
    app.dependency_overrides[get_kg_build_service] = lambda: service
    with TestClient(app) as client:
        yield client, service
    app.dependency_overrides.clear()


def test_health_returns_up(api_client) -> None:
    client, service = api_client

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
    assert service.calls == []


def test_dry_run_calls_service_without_execute(api_client) -> None:
    client, service = api_client

    response = client.post(
        "/api/v1/kg/persons/1/build",
        json={"mode": "DRY_RUN"},
    )

    assert response.status_code == 200
    assert service.calls == [{"person_id": 1, "execute": False}]
    body = response.json()
    assert body["status"] == "PREFLIGHT_PASSED"
    assert body["issue_count"] == 1
    assert body["issues"][0]["severity"] == "WARNING"
    assert body["import_result"] is None


def test_execute_calls_service_with_execute(api_client) -> None:
    client, service = api_client

    response = client.post(
        "/api/v1/kg/persons/1/build",
        json={"mode": "EXECUTE"},
    )

    assert response.status_code == 200
    assert service.calls == [{"person_id": 1, "execute": True}]
    body = response.json()
    assert body["status"] == "SUCCESS"
    assert body["import_result"]["summary"]["merged_node_count"] == 1


def test_invalid_mode_is_rejected_without_calling_service(api_client) -> None:
    client, service = api_client

    response = client.post(
        "/api/v1/kg/persons/1/build",
        json={"mode": "INVALID"},
    )

    assert response.status_code == 422
    assert service.calls == []


def test_batch_submit_is_non_blocking_and_status_reaches_success(tmp_path) -> None:
    pipeline = BlockingPipeline({1: "PREFLIGHT_PASSED", 2: "PREFLIGHT_PASSED"})
    jobs = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path / "batch_runs",
        job_id_factory=lambda: "api-job",
    )
    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = lambda: jobs
    try:
        with TestClient(app) as client:
            response = client.post(
                "/api/v1/kg/batches",
                json={"person_ids": [2, 1, 1], "mode": "DRY_RUN"},
            )

            assert response.status_code == 202
            assert response.json() == {"job_id": "api-job", "status": "PENDING"}
            assert pipeline.started.wait(timeout=1)
            assert pipeline.calls[0].person_ids == (1, 2)

            running = client.get("/api/v1/kg/batches/api-job")
            assert running.status_code == 200
            assert running.json()["status"] == "RUNNING"

            pipeline.release.set()
            wait_for_status(jobs, "api-job", "SUCCESS")
            completed = client.get("/api/v1/kg/batches/api-job")
            assert completed.status_code == 200
            assert completed.json()["status"] == "SUCCESS"
            assert completed.json()["success"] == 2
    finally:
        pipeline.release.set()
        jobs.shutdown()
        app.dependency_overrides.clear()


@pytest.mark.parametrize(
    "payload",
    [
        {"person_ids": [], "mode": "DRY_RUN"},
        {"person_ids": [0, 1], "mode": "DRY_RUN"},
        {"person_ids": [-1], "mode": "EXECUTE"},
        {"person_ids": ["1"], "mode": "DRY_RUN"},
        {"person_ids": [True], "mode": "DRY_RUN"},
        {"person_ids": [1], "mode": "INVALID"},
    ],
)
def test_invalid_batch_request_is_rejected(payload) -> None:
    app = create_app()

    class MustNotBeCalled:
        def submit(self, *_args, **_kwargs):
            raise AssertionError("invalid request reached job service")

    app.dependency_overrides[get_kg_batch_job_service] = MustNotBeCalled
    with TestClient(app) as client:
        response = client.post("/api/v1/kg/batches", json=payload)
    app.dependency_overrides.clear()

    assert response.status_code == 422


def test_unknown_batch_job_returns_structured_404(tmp_path) -> None:
    jobs = KGBatchJobService(batch_root=tmp_path)
    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = lambda: jobs
    try:
        with TestClient(app) as client:
            response = client.get("/api/v1/kg/batches/missing")
        assert response.status_code == 404
        assert response.json() == {
            "detail": {
                "code": "JOB_NOT_FOUND",
                "message": "未找到批量任务：missing",
            }
        }
    finally:
        jobs.shutdown()
        app.dependency_overrides.clear()


def test_batch_issues_endpoint_returns_filtered_dto() -> None:
    class IssueJobs:
        def __init__(self) -> None:
            self.calls = []

        def get_issues(self, job_id, **filters):
            self.calls.append((job_id, filters))
            return [
                {
                    "person_id": 1,
                    "stage": "EXTRACTION",
                    "reason": "MAPPING_PENDING",
                    "severity": "WARNING",
                    "message": "mapping warning",
                    "source_table": "person",
                    "source_pk": "1",
                }
            ]

    jobs = IssueJobs()
    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = lambda: jobs
    with TestClient(app) as client:
        response = client.get(
            "/api/v1/kg/batches/issues-job/issues",
            params={
                "person_id": 1,
                "severity": "WARNING",
                "stage": "EXTRACTION",
                "reason": "MAPPING_PENDING",
            },
        )
    app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json() == {
        "job_id": "issues-job",
        "total": 1,
        "issues": [
            {
                "person_id": 1,
                "stage": "EXTRACTION",
                "reason": "MAPPING_PENDING",
                "severity": "WARNING",
                "message": "mapping warning",
                "source_table": "person",
                "source_pk": "1",
            }
        ],
    }
    assert jobs.calls == [
        (
            "issues-job",
            {
                "person_id": 1,
                "severity": "WARNING",
                "stage": "EXTRACTION",
                "reason": "MAPPING_PENDING",
            },
        )
    ]


def test_batch_issues_endpoint_handles_empty_and_missing_job() -> None:
    class IssueJobs:
        def get_issues(self, job_id, **_filters):
            if job_id == "missing":
                raise BatchJobNotFoundError(job_id)
            return []

    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = IssueJobs
    with TestClient(app) as client:
        empty = client.get("/api/v1/kg/batches/empty/issues")
        missing = client.get("/api/v1/kg/batches/missing/issues")
    app.dependency_overrides.clear()

    assert empty.status_code == 200
    assert empty.json() == {"job_id": "empty", "total": 0, "issues": []}
    assert missing.status_code == 404
    assert missing.json()["detail"]["code"] == "JOB_NOT_FOUND"


def test_batch_issues_endpoint_returns_structured_parse_error() -> None:
    class IssueJobs:
        def get_issues(self, _job_id, **_filters):
            raise BatchIssuesReadError(
                "BATCH_ISSUES_INVALID_FORMAT",
                "批次 issues 文件结构无效",
            )

    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = IssueJobs
    with TestClient(app) as client:
        response = client.get("/api/v1/kg/batches/broken/issues")
    app.dependency_overrides.clear()

    assert response.status_code == 500
    assert response.json() == {
        "detail": {
            "code": "BATCH_ISSUES_INVALID_FORMAT",
            "message": "批次 issues 文件结构无效",
        }
    }


def test_retry_failed_endpoint_returns_same_job_without_waiting() -> None:
    class RetryJobs:
        def retry_failed(self, job_id):
            return SimpleNamespace(
                job_id=job_id,
                status="PENDING",
                retry_person_ids=(2, 4),
            )

    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = RetryJobs
    with TestClient(app) as client:
        response = client.post("/api/v1/kg/batches/retry-job/retry-failed")
    app.dependency_overrides.clear()

    assert response.status_code == 202
    assert response.json() == {
        "job_id": "retry-job",
        "status": "PENDING",
        "retry_person_ids": [2, 4],
    }


@pytest.mark.parametrize(
    ("job_id", "error", "expected_status", "expected_code"),
    [
        ("missing", BatchJobNotFoundError("missing"), 404, "JOB_NOT_FOUND"),
        (
            "running",
            BatchJobConflictError("JOB_STILL_RUNNING", "still running"),
            409,
            "JOB_STILL_RUNNING",
        ),
        (
            "successful",
            BatchJobConflictError("NO_FAILED_PERSONS", "nothing to retry"),
            409,
            "NO_FAILED_PERSONS",
        ),
    ],
)
def test_retry_failed_endpoint_maps_job_errors(
    job_id,
    error,
    expected_status,
    expected_code,
) -> None:
    class RetryJobs:
        def retry_failed(self, _job_id):
            raise error

    app = create_app()
    app.dependency_overrides[get_kg_batch_job_service] = RetryJobs
    with TestClient(app) as client:
        response = client.post(f"/api/v1/kg/batches/{job_id}/retry-failed")
    app.dependency_overrides.clear()

    assert response.status_code == expected_status
    assert response.json()["detail"]["code"] == expected_code
