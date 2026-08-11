"""In-process background jobs backed by the existing batch KG pipeline."""

from __future__ import annotations

import json
import logging
import uuid
from concurrent.futures import Executor, ThreadPoolExecutor
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from threading import RLock
from typing import Any, Callable, Dict, Mapping, Optional, Sequence

from src.pipeline.batch_kg_pipeline import (
    FAILED_STATUSES,
    BatchKGPipeline,
    BatchRunOptions,
    redact_pipeline_error,
    select_resume_targets,
)
from src.services.kg_build_service import KGBuildService


LOGGER = logging.getLogger(__name__)
JOB_STATUSES = {"PENDING", "RUNNING", "SUCCESS", "PARTIAL_FAILED", "FAILED"}


class BatchJobNotFoundError(LookupError):
    """Raised when an API-visible in-process batch job does not exist."""


class BatchJobConflictError(RuntimeError):
    """Raised when the requested operation conflicts with current job state."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class BatchIssuesReadError(RuntimeError):
    """Raised when an existing batch issues artifact cannot be parsed safely."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _default_pipeline_factory() -> BatchKGPipeline:
    return BatchKGPipeline(build_service=KGBuildService())


@dataclass(frozen=True)
class BatchJobSnapshot:
    job_id: str
    batch_id: str
    status: str
    person_ids: tuple[int, ...]
    mode: str
    created_at: datetime
    started_at: Optional[datetime]
    finished_at: Optional[datetime]
    manifest_path: Path
    total: int
    success: int
    failed: int
    pending: int
    processed: int
    error: Optional[str] = None


@dataclass(frozen=True)
class BatchRetrySubmission:
    job_id: str
    status: str
    retry_person_ids: tuple[int, ...]


class KGBatchJobService:
    """Submit and inspect lightweight in-process batch jobs."""

    def __init__(
        self,
        *,
        pipeline_factory: Callable[[], Any] = _default_pipeline_factory,
        batch_root: Path = Path("output/batch_runs"),
        executor: Optional[Executor] = None,
        max_workers: int = 2,
        clock: Callable[[], datetime] = _utc_now,
        job_id_factory: Callable[[], str] = lambda: str(uuid.uuid4()),
    ) -> None:
        if max_workers <= 0:
            raise ValueError("max_workers must be greater than zero")
        self.pipeline_factory = pipeline_factory
        self.batch_root = batch_root.resolve()
        self.executor = executor or ThreadPoolExecutor(
            max_workers=max_workers,
            thread_name_prefix="kg-batch",
        )
        self.clock = clock
        self.job_id_factory = job_id_factory
        self._jobs: Dict[str, BatchJobSnapshot] = {}
        self._lock = RLock()

    def submit(self, person_ids: Sequence[int], mode: str) -> BatchJobSnapshot:
        normalized_ids = self._normalize_person_ids(person_ids)
        if mode not in {"DRY_RUN", "EXECUTE"}:
            raise ValueError("mode must be DRY_RUN or EXECUTE")

        created_at = self.clock()
        with self._lock:
            job_id = self._new_job_id_locked()
            manifest_path = self.batch_root / job_id / "manifest.json"
            snapshot = BatchJobSnapshot(
                job_id=job_id,
                batch_id=job_id,
                status="PENDING",
                person_ids=tuple(normalized_ids),
                mode=mode,
                created_at=created_at,
                started_at=None,
                finished_at=None,
                manifest_path=manifest_path,
                total=len(normalized_ids),
                success=0,
                failed=0,
                pending=len(normalized_ids),
                processed=0,
            )
            self._jobs[job_id] = snapshot

        # Return the immutable PENDING snapshot even if the worker starts at once.
        try:
            self.executor.submit(self._run_job, job_id)
        except Exception as exc:
            safe_error = redact_pipeline_error(f"{type(exc).__name__}: {exc}")
            self._mark_failed(job_id, safe_error)
            raise RuntimeError("无法提交批量后台任务") from exc
        return snapshot

    def get_job(self, job_id: str) -> Optional[BatchJobSnapshot]:
        with self._lock:
            snapshot = self._jobs.get(job_id)
        if snapshot is None:
            return None

        manifest = self._load_manifest(snapshot.manifest_path)
        if manifest is None:
            return snapshot
        counts = self._counts_from_manifest(manifest, snapshot.mode, snapshot.total)
        return replace(snapshot, **counts)

    def get_issues(
        self,
        job_id: str,
        *,
        person_id: Optional[int] = None,
        severity: Optional[str] = None,
        stage: Optional[str] = None,
        reason: Optional[str] = None,
    ) -> list[Dict[str, Any]]:
        """Read and filter the existing batch issue artifact without rebuilding."""

        with self._lock:
            snapshot = self._jobs.get(job_id)
        if snapshot is None:
            raise BatchJobNotFoundError(job_id)

        issues_path = snapshot.manifest_path.parent / "batch_issues.json"
        issues = self._load_batch_issues(issues_path)
        return [
            issue
            for issue in issues
            if self._issue_matches(
                issue,
                person_id=person_id,
                severity=severity,
                stage=stage,
                reason=reason,
            )
        ]

    def retry_failed(self, job_id: str) -> BatchRetrySubmission:
        """Queue an in-place retry using the pipeline's resume/retry mechanism."""

        with self._lock:
            current = self._jobs.get(job_id)
            if current is None:
                raise BatchJobNotFoundError(job_id)
            if current.status in {"PENDING", "RUNNING"}:
                raise BatchJobConflictError(
                    "JOB_STILL_RUNNING",
                    "批量任务尚未结束，当前不能重试失败客户",
                )

            manifest = self._load_manifest(current.manifest_path)
            raw_persons = manifest.get("persons") if manifest is not None else None
            retry_person_ids = (
                select_resume_targets(raw_persons, retry_failed_only=True)
                if isinstance(raw_persons, Mapping)
                else []
            )
            if not retry_person_ids:
                raise BatchJobConflictError(
                    "NO_FAILED_PERSONS",
                    "当前批次没有可重试的失败客户",
                )

            pending = replace(
                current,
                status="PENDING",
                started_at=None,
                finished_at=None,
                error=None,
            )
            self._jobs[job_id] = pending

        try:
            self.executor.submit(
                self._run_retry,
                job_id,
                tuple(retry_person_ids),
            )
        except Exception as exc:
            safe_error = redact_pipeline_error(f"{type(exc).__name__}: {exc}")
            self._mark_failed(job_id, safe_error)
            raise RuntimeError("无法提交失败客户重试任务") from exc

        return BatchRetrySubmission(
            job_id=job_id,
            status="PENDING",
            retry_person_ids=tuple(retry_person_ids),
        )

    def shutdown(self, *, wait: bool = True) -> None:
        shutdown = getattr(self.executor, "shutdown", None)
        if callable(shutdown):
            shutdown(wait=wait)

    def _run_job(self, job_id: str) -> None:
        with self._lock:
            current = self._jobs[job_id]
            self._jobs[job_id] = replace(
                current,
                status="RUNNING",
                started_at=self.clock(),
            )

        try:
            with self._lock:
                current = self._jobs[job_id]
            job_dir = self.batch_root / current.batch_id
            options = BatchRunOptions(
                person_ids=current.person_ids,
                mode=current.mode,
                output_root=job_dir / "persons",
                batch_root=self.batch_root,
                batch_id=current.batch_id,
            )
            manifest = self.pipeline_factory().run(options)
            self._complete_job(job_id, manifest)
        except Exception as exc:
            safe_error = redact_pipeline_error(f"{type(exc).__name__}: {exc}")
            LOGGER.error("KG batch job %s failed: %s", job_id, safe_error)
            self._mark_failed(job_id, safe_error)

    def _run_retry(self, job_id: str, retry_person_ids: tuple[int, ...]) -> None:
        with self._lock:
            current = self._jobs[job_id]
            self._jobs[job_id] = replace(
                current,
                status="RUNNING",
                started_at=self.clock(),
            )

        try:
            with self._lock:
                current = self._jobs[job_id]
            options = BatchRunOptions(
                person_ids=retry_person_ids,
                mode=current.mode,
                resume_manifest=current.manifest_path,
                retry_failed_only=True,
            )
            manifest = self.pipeline_factory().run(options)
            self._complete_job(job_id, manifest)
        except Exception as exc:
            safe_error = redact_pipeline_error(f"{type(exc).__name__}: {exc}")
            LOGGER.error("KG batch retry %s failed: %s", job_id, safe_error)
            self._mark_failed(job_id, safe_error)

    def _complete_job(self, job_id: str, manifest: Mapping[str, Any]) -> None:
        with self._lock:
            current = self._jobs[job_id]
        counts = self._counts_from_manifest(
            manifest,
            current.mode,
            current.total,
        )
        final_status = self._final_status(counts)
        with self._lock:
            latest = self._jobs[job_id]
            self._jobs[job_id] = replace(
                latest,
                status=final_status,
                finished_at=self.clock(),
                **counts,
            )

    def _mark_failed(self, job_id: str, safe_error: str) -> None:
        with self._lock:
            current = self._jobs[job_id]
        manifest = self._load_manifest(current.manifest_path)
        counts = (
            self._counts_from_manifest(manifest, current.mode, current.total)
            if manifest is not None
            else {
                "total": current.total,
                "success": current.success,
                "failed": current.failed,
                "pending": current.pending,
                "processed": current.processed,
            }
        )
        with self._lock:
            latest = self._jobs[job_id]
            self._jobs[job_id] = replace(
                latest,
                status="FAILED",
                finished_at=self.clock(),
                error=safe_error,
                **counts,
            )

    def _new_job_id_locked(self) -> str:
        for _ in range(10):
            job_id = str(self.job_id_factory()).strip()
            if job_id and job_id not in self._jobs:
                return job_id
        raise RuntimeError("无法生成唯一 job_id")

    @staticmethod
    def _normalize_person_ids(person_ids: Sequence[int]) -> list[int]:
        values = list(person_ids)
        if not values:
            raise ValueError("person_ids 至少包含一个元素")
        if any(
            isinstance(person_id, bool)
            or not isinstance(person_id, int)
            or person_id <= 0
            for person_id in values
        ):
            raise ValueError("person_id 必须是正整数")
        return sorted(set(values))

    @staticmethod
    def _load_manifest(path: Path) -> Optional[Mapping[str, Any]]:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, OSError, UnicodeError, json.JSONDecodeError):
            return None
        return value if isinstance(value, Mapping) else None

    @staticmethod
    def _load_batch_issues(path: Path) -> list[Dict[str, Any]]:
        if not path.exists():
            return []
        try:
            raw_json = path.read_text(encoding="utf-8")
        except FileNotFoundError:
            # The pipeline may not have published its atomic artifact yet.
            return []
        except (OSError, UnicodeError) as exc:
            LOGGER.error(
                "Unable to read batch issues artifact %s: %s",
                path,
                redact_pipeline_error(f"{type(exc).__name__}: {exc}"),
            )
            raise BatchIssuesReadError(
                "BATCH_ISSUES_READ_FAILED",
                "批次 issues 文件读取失败",
            ) from exc

        try:
            value = json.loads(raw_json)
        except json.JSONDecodeError as exc:
            LOGGER.error("Invalid JSON in batch issues artifact %s", path)
            raise BatchIssuesReadError(
                "BATCH_ISSUES_INVALID_JSON",
                "批次 issues 文件不是合法 JSON",
            ) from exc
        if not isinstance(value, list):
            LOGGER.error(
                "Invalid batch issues root in %s: expected list, got %s",
                path,
                type(value).__name__,
            )
            raise BatchIssuesReadError(
                "BATCH_ISSUES_INVALID_FORMAT",
                "批次 issues 文件结构无效：根节点必须是数组",
            )

        issues = []
        for index, raw_issue in enumerate(value):
            if not isinstance(raw_issue, Mapping):
                raise KGBatchJobService._invalid_issue_record(path, index)
            try:
                person_id = int(raw_issue.get("person_id"))
            except (TypeError, ValueError):
                raise KGBatchJobService._invalid_issue_record(path, index)
            if person_id <= 0:
                raise KGBatchJobService._invalid_issue_record(path, index)
            required_fields = ("stage", "reason", "severity", "message")
            if any(raw_issue.get(field) is None for field in required_fields):
                raise KGBatchJobService._invalid_issue_record(path, index)

            issue = {
                "person_id": person_id,
                "stage": str(raw_issue.get("stage", "")),
                "reason": str(raw_issue.get("reason", "")),
                "severity": str(raw_issue.get("severity", "")),
                "message": redact_pipeline_error(raw_issue.get("message", "")),
            }
            for field in ("source_table", "source_pk", "record_type", "record_id"):
                if raw_issue.get(field) is not None:
                    issue[field] = str(raw_issue[field])
            issues.append(issue)
        return issues

    @staticmethod
    def _invalid_issue_record(path: Path, index: int) -> BatchIssuesReadError:
        LOGGER.error("Invalid batch issue record at %s index %d", path, index)
        return BatchIssuesReadError(
            "BATCH_ISSUES_INVALID_FORMAT",
            f"批次 issues 文件中的第 {index} 条记录结构无效",
        )

    @staticmethod
    def _issue_matches(
        issue: Mapping[str, Any],
        *,
        person_id: Optional[int],
        severity: Optional[str],
        stage: Optional[str],
        reason: Optional[str],
    ) -> bool:
        if person_id is not None and issue.get("person_id") != person_id:
            return False
        for field, expected in (
            ("severity", severity),
            ("stage", stage),
            ("reason", reason),
        ):
            if expected is not None and str(issue.get(field, "")).casefold() != (
                expected.strip().casefold()
            ):
                return False
        return True

    @staticmethod
    def _counts_from_manifest(
        manifest: Mapping[str, Any],
        mode: str,
        fallback_total: int,
    ) -> Dict[str, int]:
        raw_persons = manifest.get("persons")
        persons = list(raw_persons.values()) if isinstance(raw_persons, Mapping) else []
        total = len(persons) if persons else fallback_total
        successful_statuses = (
            {"PREFLIGHT_PASSED", "SUCCESS"}
            if mode == "DRY_RUN"
            else {"SUCCESS"}
        )
        success = sum(
            str(person.get("status")) in successful_statuses
            for person in persons
            if isinstance(person, Mapping)
        )
        failed = sum(
            str(person.get("status")) in FAILED_STATUSES
            for person in persons
            if isinstance(person, Mapping)
        )
        processed = success + failed
        return {
            "total": total,
            "success": success,
            "failed": failed,
            "pending": max(total - processed, 0),
            "processed": processed,
        }

    @staticmethod
    def _final_status(counts: Mapping[str, int]) -> str:
        total = int(counts["total"])
        success = int(counts["success"])
        failed = int(counts["failed"])
        if total > 0 and success == total:
            return "SUCCESS"
        if success > 0 and failed > 0:
            return "PARTIAL_FAILED"
        return "FAILED"
