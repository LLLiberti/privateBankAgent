"""Tests for the in-process batch job registry and executor."""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path

import pytest

from src.importing.neo4j_importer import ImportIssue, preflight_candidates
from src.models.graph_models import CandidateExtraction, GraphIssue
from src.pipeline.batch_kg_pipeline import BatchKGPipeline, atomic_write_json
from src.services.kg_batch_job_service import (
    BatchJobConflictError,
    BatchJobNotFoundError,
    BatchIssuesReadError,
    KGBatchJobService,
)
from src.services.kg_build_service import KGBuildResult


def manifest_for(options, statuses):
    return {
        "batch_id": options.batch_id,
        "mode": options.mode,
        "persons": {
            str(person_id): {"person_id": person_id, "status": status}
            for person_id, status in statuses.items()
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
        return manifest_for(options, self.statuses)


class ImmediatePipeline:
    def __init__(self, status: str) -> None:
        self.status = status
        self.calls = []
        self.lock = threading.Lock()

    def run(self, options):
        with self.lock:
            self.calls.append(options)
        return manifest_for(
            options,
            {person_id: self.status for person_id in options.person_ids},
        )


class ResumeAwarePipeline:
    def __init__(
        self,
        statuses,
        *,
        issues=None,
        retry_statuses=None,
        block_retry: bool = False,
        retry_error: Exception | None = None,
    ) -> None:
        self.statuses = statuses
        self.issues = issues
        self.retry_statuses = retry_statuses or {}
        self.block_retry = block_retry
        self.retry_error = retry_error
        self.calls = []
        self.retry_started = threading.Event()
        self.retry_release = threading.Event()

    def run(self, options):
        self.calls.append(options)
        if options.resume_manifest is None:
            manifest = manifest_for(options, self.statuses)
            manifest["output_root"] = str(options.output_root.resolve())
            batch_dir = (options.batch_root / options.batch_id).resolve()
            atomic_write_json(batch_dir / "manifest.json", manifest)
            if self.issues is not None:
                atomic_write_json(batch_dir / "batch_issues.json", self.issues)
            return manifest

        self.retry_started.set()
        if self.block_retry and not self.retry_release.wait(timeout=2):
            raise TimeoutError("test did not release retry")
        if self.retry_error is not None:
            raise self.retry_error

        manifest = json.loads(options.resume_manifest.read_text(encoding="utf-8"))
        for person_id in options.person_ids:
            manifest["persons"][str(person_id)]["status"] = self.retry_statuses.get(
                person_id,
                "SUCCESS",
            )
        atomic_write_json(options.resume_manifest, manifest)
        return manifest


class HoldingExecutor:
    def __init__(self) -> None:
        self.submissions = []

    def submit(self, function, *args):
        self.submissions.append((function, args))

    def shutdown(self, *, wait=True):
        return None


def test_submit_is_non_blocking_and_runs_existing_pipeline(tmp_path: Path) -> None:
    pipeline = BlockingPipeline(
        {1: "PREFLIGHT_PASSED", 2: "PREFLIGHT_PASSED", 3: "PREFLIGHT_PASSED"}
    )
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path / "batch_runs",
        job_id_factory=lambda: "job-one",
    )
    try:
        submitted = service.submit([3, 1, 1, 2], "DRY_RUN")

        assert submitted.status == "PENDING"
        assert submitted.person_ids == (1, 2, 3)
        assert pipeline.started.wait(timeout=1)
        running = service.get_job(submitted.job_id)
        assert running is not None
        assert running.status == "RUNNING"

        options = pipeline.calls[0]
        assert options.person_ids == (1, 2, 3)
        assert options.mode == "DRY_RUN"
        assert options.batch_id == submitted.job_id
        assert options.batch_root == (tmp_path / "batch_runs").resolve()
        assert options.output_root == (
            tmp_path / "batch_runs" / submitted.job_id / "persons"
        ).resolve()

        pipeline.release.set()
        completed = wait_for_status(service, submitted.job_id, "SUCCESS")
        assert completed.success == 3
        assert completed.failed == 0
        assert completed.pending == 0
        assert completed.processed == 3
    finally:
        pipeline.release.set()
        service.shutdown()


def test_partial_person_failure_becomes_partial_failed(tmp_path: Path) -> None:
    pipeline = ImmediatePipeline("SUCCESS")

    def run_with_partial(options):
        pipeline.calls.append(options)
        return manifest_for(
            options,
            {1: "SUCCESS", 2: "IMPORT_FAILED", 3: "SUCCESS"},
        )

    pipeline.run = run_with_partial
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path,
        job_id_factory=lambda: "job-partial",
    )
    try:
        submitted = service.submit([1, 2, 3], "EXECUTE")
        completed = wait_for_status(service, submitted.job_id, "PARTIAL_FAILED")
        assert completed.success == 2
        assert completed.failed == 1
        assert completed.pending == 0
    finally:
        service.shutdown()


def test_top_level_pipeline_exception_becomes_failed_and_is_redacted(
    tmp_path: Path,
) -> None:
    class FailingPipeline:
        def run(self, _options):
            raise RuntimeError("password=job-secret pipeline failed")

    service = KGBatchJobService(
        pipeline_factory=FailingPipeline,
        batch_root=tmp_path,
        job_id_factory=lambda: "job-failed",
    )
    try:
        submitted = service.submit([1], "EXECUTE")
        failed = wait_for_status(service, submitted.job_id, "FAILED")
        assert failed.finished_at is not None
        assert failed.error is not None
        assert "job-secret" not in failed.error
    finally:
        service.shutdown()


def test_two_submissions_have_isolated_ids_and_paths(tmp_path: Path) -> None:
    identifiers = iter(("job-a", "job-b"))
    pipeline = ImmediatePipeline("PREFLIGHT_PASSED")
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path / "batch_runs",
        job_id_factory=lambda: next(identifiers),
    )
    try:
        first = service.submit([1], "DRY_RUN")
        second = service.submit([1], "DRY_RUN")
        wait_for_status(service, first.job_id, "SUCCESS")
        wait_for_status(service, second.job_id, "SUCCESS")

        assert first.job_id != second.job_id
        options_by_id = {options.batch_id: options for options in pipeline.calls}
        assert options_by_id[first.job_id].output_root != options_by_id[second.job_id].output_root
        assert first.manifest_path != second.manifest_path
    finally:
        service.shutdown()


def test_get_issues_reads_artifact_and_applies_four_filters(tmp_path: Path) -> None:
    issues = [
        {
            "person_id": 1,
            "stage": "EXTRACTION",
            "reason": "MAPPING_PENDING",
            "severity": "WARNING",
            "message": "mapping warning",
            "source_table": "person",
            "source_pk": 1,
        },
        {
            "person_id": 1,
            "stage": "PREFLIGHT",
            "reason": "UNKNOWN_ENUM",
            "severity": "ERROR",
            "message": "enum error",
            "record_type": "node",
            "record_id": "node-1",
        },
        {
            "person_id": 2,
            "stage": "PREFLIGHT",
            "reason": "MAPPING_PENDING",
            "severity": "WARNING",
            "message": "password=secret-value",
        },
    ]
    pipeline = ResumeAwarePipeline({1: "SUCCESS", 2: "SUCCESS"}, issues=issues)
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path / "batch_runs",
        job_id_factory=lambda: "issues-job",
    )
    try:
        submitted = service.submit([1, 2], "EXECUTE")
        wait_for_status(service, submitted.job_id, "SUCCESS")
        issues_path = submitted.manifest_path.parent / "batch_issues.json"
        original = issues_path.read_text(encoding="utf-8")

        assert len(service.get_issues(submitted.job_id)) == 3
        assert len(service.get_issues(submitted.job_id, person_id=1)) == 2
        assert len(service.get_issues(submitted.job_id, severity="warning")) == 2
        assert len(service.get_issues(submitted.job_id, stage="preflight")) == 2
        assert len(service.get_issues(submitted.job_id, reason="mapping_pending")) == 2
        combined = service.get_issues(
            submitted.job_id,
            person_id=1,
            severity="WARNING",
            stage="EXTRACTION",
            reason="MAPPING_PENDING",
        )
        assert combined == [
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
        assert "secret-value" not in service.get_issues(submitted.job_id)[2]["message"]
        assert issues_path.read_text(encoding="utf-8") == original
    finally:
        service.shutdown()


def test_get_issues_reads_format_written_by_real_batch_pipeline(tmp_path: Path) -> None:
    class ProductionIssueBuildService:
        def build_person(
            self,
            person_id,
            execute=True,
            *,
            batch_size=100,
            status_callback=None,
        ):
            candidates = CandidateExtraction(
                person_id=str(person_id),
                issues=[
                    GraphIssue(
                        issue_id="issue:mapping",
                        reason="MAPPING_PENDING",
                        severity="WARNING",
                        message="mapping warning",
                        source_table="enterprise",
                        source_pk="1",
                        person_id=str(person_id),
                    ),
                    GraphIssue(
                        issue_id="issue:duplicate",
                        reason="DUPLICATE_RELATION_CANDIDATE",
                        severity="INFO",
                        message="duplicate relation",
                        source_table="person_career",
                        source_pk="2",
                        person_id=str(person_id),
                    ),
                ],
            )
            preflight = preflight_candidates(
                [],
                [],
                [],
                graph_status="PREVIEW",
                initial_issues=[
                    ImportIssue(
                        "UNKNOWN_ENUM",
                        "WARNING",
                        "node",
                        "node-1",
                        "unknown enum warning",
                    )
                ],
            )
            return KGBuildResult(
                person_id=person_id,
                status="PREFLIGHT_PASSED",
                node_count=0,
                relation_count=0,
                event_count=0,
                issue_count=2,
                candidates=candidates,
                preflight_result=preflight,
            )

    service = KGBatchJobService(
        pipeline_factory=lambda: BatchKGPipeline(
            build_service=ProductionIssueBuildService()
        ),
        batch_root=tmp_path / "batch_runs",
        job_id_factory=lambda: "real-pipeline-format",
    )
    try:
        submitted = service.submit([1], "DRY_RUN")
        wait_for_status(service, submitted.job_id, "SUCCESS")

        issues_path = submitted.manifest_path.parent / "batch_issues.json"
        raw_issues = json.loads(issues_path.read_text(encoding="utf-8"))
        assert isinstance(raw_issues, list)
        assert len(raw_issues) == 3

        all_issues = service.get_issues(submitted.job_id)
        assert len(all_issues) == 3
        assert {issue["reason"] for issue in all_issues} == {
            "MAPPING_PENDING",
            "DUPLICATE_RELATION_CANDIDATE",
            "UNKNOWN_ENUM",
        }
        assert len(service.get_issues(submitted.job_id, person_id=1)) == 3
        assert len(service.get_issues(submitted.job_id, severity="WARNING")) == 2
        assert len(service.get_issues(submitted.job_id, stage="PREFLIGHT")) == 1
        assert len(service.get_issues(submitted.job_id, reason="MAPPING_PENDING")) == 1
        assert len(
            service.get_issues(
                submitted.job_id,
                person_id=1,
                severity="WARNING",
                stage="EXTRACTION",
                reason="MAPPING_PENDING",
            )
        ) == 1

    finally:
        service.shutdown()


def test_existing_invalid_issue_structure_is_not_silently_empty(tmp_path: Path) -> None:
    pipeline = ResumeAwarePipeline({1: "SUCCESS"}, issues=[])
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path,
        job_id_factory=lambda: "invalid-issues-job",
    )
    try:
        submitted = service.submit([1], "EXECUTE")
        wait_for_status(service, submitted.job_id, "SUCCESS")
        atomic_write_json(
            submitted.manifest_path.parent / "batch_issues.json",
            {"issues": []},
        )

        with pytest.raises(BatchIssuesReadError) as error:
            service.get_issues(submitted.job_id)
        assert error.value.code == "BATCH_ISSUES_INVALID_FORMAT"
    finally:
        service.shutdown()


def test_get_issues_returns_empty_for_missing_artifact_and_rejects_unknown_job(
    tmp_path: Path,
) -> None:
    pipeline = ResumeAwarePipeline({1: "SUCCESS"})
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path,
        job_id_factory=lambda: "no-issues-job",
    )
    try:
        submitted = service.submit([1], "EXECUTE")
        wait_for_status(service, submitted.job_id, "SUCCESS")
        assert service.get_issues(submitted.job_id) == []
        with pytest.raises(BatchJobNotFoundError):
            service.get_issues("missing")
    finally:
        service.shutdown()


def test_retry_failed_is_non_blocking_and_reuses_resume_options(tmp_path: Path) -> None:
    pipeline = ResumeAwarePipeline(
        {
            1: "SUCCESS",
            2: "IMPORT_FAILED",
            3: "SUCCESS",
            4: "PREFLIGHT_FAILED",
        },
        block_retry=True,
    )
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path / "batch_runs",
        job_id_factory=lambda: "retry-job",
    )
    try:
        submitted = service.submit([1, 2, 3, 4], "EXECUTE")
        wait_for_status(service, submitted.job_id, "PARTIAL_FAILED")

        retry = service.retry_failed(submitted.job_id)

        assert retry.job_id == submitted.job_id
        assert retry.status == "PENDING"
        assert retry.retry_person_ids == (2, 4)
        assert pipeline.retry_started.wait(timeout=1)
        assert service.get_job(submitted.job_id).status == "RUNNING"

        options = pipeline.calls[1]
        assert options.person_ids == (2, 4)
        assert options.mode == "EXECUTE"
        assert options.resume_manifest == submitted.manifest_path
        assert options.retry_failed_only is True

        pipeline.retry_release.set()
        completed = wait_for_status(service, submitted.job_id, "SUCCESS")
        assert completed.success == 4
        manifest = json.loads(submitted.manifest_path.read_text(encoding="utf-8"))
        assert manifest["persons"]["1"]["status"] == "SUCCESS"
        assert manifest["persons"]["3"]["status"] == "SUCCESS"
    finally:
        pipeline.retry_release.set()
        service.shutdown()


def test_retry_inherits_dry_run_mode(tmp_path: Path) -> None:
    pipeline = ResumeAwarePipeline({1: "PREFLIGHT_FAILED"})
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path,
        job_id_factory=lambda: "retry-dry-run",
    )
    try:
        submitted = service.submit([1], "DRY_RUN")
        wait_for_status(service, submitted.job_id, "FAILED")
        retry = service.retry_failed(submitted.job_id)
        wait_for_status(service, submitted.job_id, "SUCCESS")
        assert retry.retry_person_ids == (1,)
        assert pipeline.calls[1].mode == "DRY_RUN"
    finally:
        service.shutdown()


def test_retry_rejects_pending_running_no_failures_and_unknown_job(
    tmp_path: Path,
) -> None:
    holding = HoldingExecutor()
    pending_service = KGBatchJobService(
        pipeline_factory=lambda: ImmediatePipeline("SUCCESS"),
        batch_root=tmp_path / "pending",
        executor=holding,
        job_id_factory=lambda: "pending-job",
    )
    pending = pending_service.submit([1], "EXECUTE")
    with pytest.raises(BatchJobConflictError) as pending_error:
        pending_service.retry_failed(pending.job_id)
    assert pending_error.value.code == "JOB_STILL_RUNNING"

    running_pipeline = BlockingPipeline({1: "SUCCESS"})
    running_service = KGBatchJobService(
        pipeline_factory=lambda: running_pipeline,
        batch_root=tmp_path / "running",
        job_id_factory=lambda: "running-job",
    )
    successful_pipeline = ResumeAwarePipeline({1: "SUCCESS"})
    successful_service = KGBatchJobService(
        pipeline_factory=lambda: successful_pipeline,
        batch_root=tmp_path / "successful",
        job_id_factory=lambda: "successful-job",
    )
    try:
        running = running_service.submit([1], "EXECUTE")
        assert running_pipeline.started.wait(timeout=1)
        with pytest.raises(BatchJobConflictError) as running_error:
            running_service.retry_failed(running.job_id)
        assert running_error.value.code == "JOB_STILL_RUNNING"

        successful = successful_service.submit([1], "EXECUTE")
        wait_for_status(successful_service, successful.job_id, "SUCCESS")
        with pytest.raises(BatchJobConflictError) as no_failed_error:
            successful_service.retry_failed(successful.job_id)
        assert no_failed_error.value.code == "NO_FAILED_PERSONS"

        with pytest.raises(BatchJobNotFoundError):
            successful_service.retry_failed("missing")
    finally:
        running_pipeline.release.set()
        pending_service.shutdown()
        running_service.shutdown()
        successful_service.shutdown()


def test_retry_top_level_exception_marks_same_job_failed_and_redacts_error(
    tmp_path: Path,
) -> None:
    pipeline = ResumeAwarePipeline(
        {1: "IMPORT_FAILED"},
        retry_error=RuntimeError("password=retry-secret traceback detail"),
    )
    service = KGBatchJobService(
        pipeline_factory=lambda: pipeline,
        batch_root=tmp_path,
        job_id_factory=lambda: "retry-error-job",
    )
    try:
        submitted = service.submit([1], "EXECUTE")
        wait_for_status(service, submitted.job_id, "FAILED")
        retry = service.retry_failed(submitted.job_id)
        failed = wait_for_status(service, retry.job_id, "FAILED")
        assert failed.error is not None
        assert "retry-secret" not in failed.error
    finally:
        service.shutdown()
