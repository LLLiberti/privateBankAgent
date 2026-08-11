"""Serial extraction, preflight and optional Neo4j import orchestration."""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence

from src.database.neo4j_client import Neo4jClient, redact_neo4j_error
from src.importing.neo4j_importer import (
    ImportIssue,
    Neo4jImporter,
    PreflightResult,
    preflight_candidates,
    render_import_summary,
    render_preflight_report,
)
from src.models.graph_models import CandidateExtraction
from src.repositories.mysql_kg_repository import redact_mysql_error
from src.services.kg_build_service import KGBuildResult, KGBuildService
from src.utils.config import settings
from src.utils.kg_artifacts import model_list, write_candidate_outputs


PERSON_FILENAMES = {
    "nodes": "kg_nodes.json",
    "events": "kg_events.json",
    "relations": "kg_relations.json",
    "issues": "kg_issues.json",
    "summary": "extraction_summary.md",
}
PREFLIGHT_REPORT = "neo4j_preflight_report.md"
PREFLIGHT_ISSUES = "neo4j_preflight_issues.json"
IMPORT_REPORT = "neo4j_import_summary.md"
IMPORT_ISSUES = "neo4j_import_issues.json"

FAILED_STATUSES = {"EXTRACTION_FAILED", "PREFLIGHT_FAILED", "IMPORT_FAILED"}
ALL_STATUSES = {
    "PENDING",
    "EXTRACTING",
    "EXTRACTED",
    "PREFLIGHT_PASSED",
    "PREFLIGHT_FAILED",
    "IMPORTING",
    "SUCCESS",
    "EXTRACTION_FAILED",
    "IMPORT_FAILED",
}


class NoPersonsSelectedError(ValueError):
    """Raised when filtering leaves no person IDs to process."""


@dataclass
class BatchRunOptions:
    person_ids: Sequence[int]
    mode: str
    output_root: Path = Path("output/persons")
    batch_root: Path = Path("output/batch_runs")
    batch_size: int = 100
    batch_id: Optional[str] = None
    resume_manifest: Optional[Path] = None
    retry_failed_only: bool = False


def parse_person_ids_csv(value: str) -> List[int]:
    identifiers: List[int] = []
    for token in value.split(","):
        token = token.strip()
        if not token:
            raise ValueError("--person-ids 包含空值")
        try:
            person_id = int(token)
        except ValueError as exc:
            raise ValueError(f"person_id 不是数值：{token!r}") from exc
        if person_id <= 0:
            raise ValueError(f"person_id 必须大于 0：{person_id}")
        identifiers.append(person_id)
    return sorted(set(identifiers))


def select_person_ids(
    values: Iterable[int],
    *,
    start_after_person_id: Optional[int] = None,
    limit: Optional[int] = None,
) -> List[int]:
    identifiers = sorted({int(value) for value in values})
    if any(value <= 0 for value in identifiers):
        raise ValueError("person_id 必须为正整数")
    if start_after_person_id is not None:
        identifiers = [value for value in identifiers if value > start_after_person_id]
    if limit is not None:
        if limit <= 0:
            raise ValueError("--limit 必须大于 0")
        identifiers = identifiers[:limit]
    return identifiers


def select_resume_targets(
    persons: Mapping[str, Mapping[str, Any]],
    retry_failed_only: bool,
) -> List[int]:
    """Select resume targets using the pipeline's existing status semantics."""

    targets = []
    for key, record in persons.items():
        status = str(record.get("status", "PENDING"))
        if status == "SUCCESS":
            continue
        if retry_failed_only and status not in FAILED_STATUSES:
            continue
        targets.append(int(key))
    return sorted(targets)


def atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def atomic_write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(value, encoding="utf-8")
    temporary.replace(path)


def redact_pipeline_error(value: Any) -> str:
    return redact_mysql_error(redact_neo4j_error(str(value)))


def _now() -> datetime:
    return datetime.now().astimezone()


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds")


class BatchKGPipeline:
    """Process people serially while persisting restartable state."""

    def __init__(
        self,
        *,
        build_service: Optional[KGBuildService] = None,
        extractor: Optional[Callable[[int], CandidateExtraction]] = None,
        preflight: Callable[..., PreflightResult] = preflight_candidates,
        neo4j_client_factory: Callable[[], Any] = lambda: Neo4jClient.from_settings(settings),
        importer_factory: Callable[[Any, int], Any] = (
            lambda client, batch_size: Neo4jImporter(client, batch_size=batch_size)
        ),
        clock: Callable[[], datetime] = _now,
        manifest_writer: Callable[[Path, Any], None] = atomic_write_json,
    ) -> None:
        self.build_service = build_service or KGBuildService(
            extractor=extractor,
            preflight=preflight,
            neo4j_client_factory=neo4j_client_factory,
            importer_factory=importer_factory,
        )
        self.preflight = preflight
        self.clock = clock
        self.manifest_writer = manifest_writer

    def run(self, options: BatchRunOptions) -> Dict[str, Any]:
        if options.mode not in {"DRY_RUN", "EXECUTE"}:
            raise ValueError("mode must be DRY_RUN or EXECUTE")
        if options.batch_size <= 0:
            raise ValueError("batch_size must be greater than zero")
        run_started = self.clock()

        if options.resume_manifest is not None:
            manifest_path = options.resume_manifest.resolve()
            manifest = self._load_manifest(manifest_path)
            batch_id = str(manifest["batch_id"])
            output_root = Path(str(manifest["output_root"])).resolve()
            batch_dir = manifest_path.parent
            manifest["mode"] = options.mode
            manifest["last_run_started_at"] = _iso(run_started)
        else:
            person_ids = select_person_ids(options.person_ids)
            if not person_ids:
                raise NoPersonsSelectedError("没有可处理的 person 记录")
            batch_id = options.batch_id or run_started.strftime("batch_%Y%m%d_%H%M%S")
            output_root = options.output_root.resolve()
            batch_dir = (options.batch_root / batch_id).resolve()
            manifest_path = batch_dir / "manifest.json"
            if manifest_path.exists():
                raise FileExistsError(
                    f"批次目录已存在，拒绝覆盖：{batch_dir}；"
                    "如需继续该批次请使用 --resume。"
                )
            manifest = self._new_manifest(
                batch_id,
                options.mode,
                person_ids,
                output_root,
                run_started,
            )

        persons = manifest.get("persons")
        if not isinstance(persons, dict) or not persons:
            raise NoPersonsSelectedError("manifest 中没有可处理的 person 记录")
        targets = select_resume_targets(persons, options.retry_failed_only)
        if options.resume_manifest is None:
            targets = sorted(int(value) for value in persons)
        if not targets:
            raise NoPersonsSelectedError("没有符合当前续跑条件的 person 记录")

        batch_dir.mkdir(parents=True, exist_ok=True)
        existing_issues = self._load_batch_issues(batch_dir / "batch_issues.json")
        target_set = set(targets)

        def belongs_to_target(item: Mapping[str, Any]) -> bool:
            raw_person_id = item.get("person_id")
            try:
                return int(raw_person_id) in target_set
            except (TypeError, ValueError):
                return raw_person_id in target_set

        batch_issues = [
            item
            for item in existing_issues
            if not belongs_to_target(item)
        ]
        self._save_manifest(manifest_path, manifest)

        for person_id in targets:
            record = persons[str(person_id)]
            person_dir = output_root / f"person_{person_id}"
            record["output_dir"] = str(person_dir)
            record["attempts"] = int(record.get("attempts", 0)) + 1
            self._set_status(manifest_path, manifest, record, "EXTRACTING")

            def update_service_status(status: str) -> None:
                self._set_status(manifest_path, manifest, record, status)

            result = self.build_service.build_person(
                person_id,
                execute=options.mode == "EXECUTE",
                batch_size=options.batch_size,
                status_callback=update_service_status,
            )
            self._persist_person_result(person_dir, result)
            record.update(self._candidate_counts(result.candidates))
            if result.preflight_result is not None:
                record["pending_candidate_count"] = (
                    result.preflight_result.pending_candidate_count
                )
            if result.import_summary is not None:
                record["merged_node_count"] = result.import_summary.merged_node_count
                record["merged_event_count"] = result.import_summary.merged_event_count
                record["merged_relation_count"] = (
                    result.import_summary.merged_relation_count
                )

            batch_issues.extend(
                self._candidate_issue(person_id, issue.as_dict())
                for issue in result.candidates.issues
            )
            if result.status == "EXTRACTION_FAILED":
                extraction_failures = [
                    issue
                    for issue in result.issues
                    if issue.get("stage") == "EXTRACTION"
                    and issue.get("code") == "EXTRACTION_FAILED"
                ]
                batch_issues.extend(
                    self._service_issue(person_id, issue)
                    for issue in extraction_failures
                )
            elif result.preflight_result is not None:
                batch_issues.extend(
                    self._preflight_issue(person_id, issue)
                    for issue in result.preflight_result.issues
                )
            batch_issues.extend(
                self._preflight_issue(person_id, issue, stage="IMPORT")
                for issue in result.import_issues
                if issue.severity == "ERROR"
            )

            if result.error:
                record["last_error"] = result.error
            else:
                record.pop("last_error", None)
            self._set_status(manifest_path, manifest, record, result.status)

        run_finished = self.clock()
        manifest["updated_at"] = _iso(run_finished)
        manifest["last_run_finished_at"] = _iso(run_finished)
        self._save_manifest(manifest_path, manifest)
        atomic_write_json(batch_dir / "batch_issues.json", batch_issues)
        atomic_write_text(
            batch_dir / "batch_summary.md",
            self._render_batch_summary(
                manifest,
                batch_issues,
                run_started,
                run_finished,
            ),
        )
        return manifest

    def _new_manifest(
        self,
        batch_id: str,
        mode: str,
        person_ids: Sequence[int],
        output_root: Path,
        started: datetime,
    ) -> Dict[str, Any]:
        return {
            "batch_id": batch_id,
            "mode": mode,
            "created_at": _iso(started),
            "updated_at": _iso(started),
            "output_root": str(output_root),
            "person_ids": list(person_ids),
            "persons": {
                str(person_id): {
                    "person_id": person_id,
                    "status": "PENDING",
                    "attempts": 0,
                    "output_dir": str(output_root / f"person_{person_id}"),
                }
                for person_id in person_ids
            },
            "mysql_write_performed": False,
            "llm_called": False,
        }

    @staticmethod
    def _load_manifest(path: Path) -> Dict[str, Any]:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not value.get("batch_id"):
            raise ValueError("resume manifest 格式无效")
        return value

    @staticmethod
    def _load_batch_issues(path: Path) -> List[Dict[str, Any]]:
        if not path.exists():
            return []
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, list) else []

    @staticmethod
    def _resume_targets(
        persons: Mapping[str, Mapping[str, Any]],
        retry_failed_only: bool,
    ) -> List[int]:
        return select_resume_targets(persons, retry_failed_only)

    def _save_manifest(self, path: Path, manifest: Dict[str, Any]) -> None:
        manifest["updated_at"] = _iso(self.clock())
        self.manifest_writer(path, manifest)

    def _set_status(
        self,
        manifest_path: Path,
        manifest: Dict[str, Any],
        record: Dict[str, Any],
        status: str,
    ) -> None:
        if status not in ALL_STATUSES:
            raise ValueError(f"unsupported status: {status}")
        record["status"] = status
        record["updated_at"] = _iso(self.clock())
        self._save_manifest(manifest_path, manifest)

    @staticmethod
    def _raw_candidates(
        result: CandidateExtraction,
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
        return model_list(result.nodes), model_list(result.events), model_list(result.relations)

    @staticmethod
    def _candidate_counts(result: CandidateExtraction) -> Dict[str, int]:
        return {
            "node_count": len(result.nodes),
            "event_count": len(result.events),
            "relation_count": len(result.relations),
            "issue_count": len(result.issues),
            "pending_candidate_count": sum(
                item.verification_status == "PENDING"
                for item in [*result.nodes, *result.events, *result.relations]
            ),
        }

    @staticmethod
    def _write_person_candidates(
        person_dir: Path,
        result: CandidateExtraction,
    ) -> None:
        write_candidate_outputs(person_dir, result, PERSON_FILENAMES)

    def _persist_person_result(
        self,
        person_dir: Path,
        result: KGBuildResult,
    ) -> None:
        self._write_person_candidates(person_dir, result.candidates)
        if result.preflight_result is not None:
            self._write_preflight(person_dir, result.preflight_result)
        if result.import_summary is not None:
            self._write_import(
                person_dir,
                result.import_summary,
                result.import_issues,
            )
        elif result.import_issues:
            atomic_write_json(
                person_dir / IMPORT_ISSUES,
                [issue.as_dict() for issue in result.import_issues],
            )

    def _write_extraction_failed_preflight(
        self,
        person_dir: Path,
        result: CandidateExtraction,
    ) -> None:
        preflight = self.preflight(
            *self._raw_candidates(result),
            graph_status="PREVIEW",
            initial_issues=[
                ImportIssue(
                    "EXTRACTION_FAILED",
                    "ERROR",
                    "batch",
                    str(result.person_id),
                    "抽取阶段失败，禁止导入当前人物",
                )
            ],
        )
        self._write_preflight(person_dir, preflight)

    @staticmethod
    def _write_preflight(person_dir: Path, result: PreflightResult) -> None:
        atomic_write_text(person_dir / PREFLIGHT_REPORT, render_preflight_report(result))
        atomic_write_json(
            person_dir / PREFLIGHT_ISSUES,
            [issue.as_dict() for issue in result.issues],
        )

    @staticmethod
    def _write_import(person_dir: Path, summary: Any, issues: Sequence[ImportIssue]) -> None:
        atomic_write_text(person_dir / IMPORT_REPORT, render_import_summary(summary))
        atomic_write_json(
            person_dir / IMPORT_ISSUES,
            [issue.as_dict() for issue in issues],
        )

    @staticmethod
    def _candidate_issue(person_id: int, issue: Mapping[str, Any]) -> Dict[str, Any]:
        return {
            "person_id": person_id,
            "stage": "EXTRACTION",
            "reason": issue.get("reason", "EXTRACTION_ISSUE"),
            "severity": issue.get("severity", "WARNING"),
            "message": issue.get("message", ""),
            "source_table": issue.get("source_table"),
            "source_pk": issue.get("source_pk"),
        }

    @staticmethod
    def _service_issue(person_id: int, issue: Mapping[str, Any]) -> Dict[str, Any]:
        return {
            "person_id": person_id,
            "stage": issue.get("stage", "BUILD"),
            "reason": issue.get("code", issue.get("reason", "BUILD_FAILED")),
            "severity": issue.get("severity", "ERROR"),
            "message": issue.get("message", ""),
            "record_type": issue.get("record_type"),
            "record_id": issue.get("record_id"),
        }

    @staticmethod
    def _preflight_issue(
        person_id: int,
        issue: ImportIssue,
        *,
        stage: str = "PREFLIGHT",
    ) -> Dict[str, Any]:
        return {
            "person_id": person_id,
            "stage": stage,
            "reason": issue.code,
            "severity": issue.severity,
            "message": issue.message,
            "record_type": issue.record_type,
            "record_id": issue.record_id,
        }

    @staticmethod
    def _batch_issue(
        person_id: int,
        stage: str,
        reason: str,
        severity: str,
        error: Any,
    ) -> Dict[str, Any]:
        return {
            "person_id": person_id,
            "stage": stage,
            "reason": reason,
            "severity": severity,
            "message": redact_pipeline_error(f"{type(error).__name__}: {error}"),
        }

    @staticmethod
    def _render_batch_summary(
        manifest: Mapping[str, Any],
        issues: Sequence[Mapping[str, Any]],
        started: datetime,
        finished: datetime,
    ) -> str:
        persons = list(manifest["persons"].values())
        statuses = Counter(str(item.get("status")) for item in persons)
        reason_counts = Counter(str(item.get("reason")) for item in issues)
        extracted_statuses = {
            "EXTRACTED",
            "PREFLIGHT_PASSED",
            "PREFLIGHT_FAILED",
            "IMPORTING",
            "IMPORT_FAILED",
            "SUCCESS",
        }
        preflight_passed = {"PREFLIGHT_PASSED", "IMPORTING", "IMPORT_FAILED", "SUCCESS"}
        duration = max((finished - started).total_seconds(), 0.0)
        lines = [
            "# 知识图谱批次构建摘要",
            "",
            f"- batch_id：`{manifest['batch_id']}`",
            f"- 模式：`{manifest['mode']}`",
            f"- 目标人物数量：{len(persons)}",
            f"- 成功抽取人数：{sum(item.get('status') in extracted_statuses for item in persons)}",
            f"- 预检通过人数：{sum(item.get('status') in preflight_passed for item in persons)}",
            f"- 成功导入人数：{statuses['SUCCESS']}",
            f"- 抽取失败人数：{statuses['EXTRACTION_FAILED']}",
            f"- 预检失败人数：{statuses['PREFLIGHT_FAILED']}",
            f"- 导入失败人数：{statuses['IMPORT_FAILED']}",
            f"- 节点输入总数：{sum(int(item.get('node_count', 0)) for item in persons)}",
            f"- Event 输入总数：{sum(int(item.get('event_count', 0)) for item in persons)}",
            f"- 关系输入总数：{sum(int(item.get('relation_count', 0)) for item in persons)}",
            f"- issue 总数：{len(issues)}",
            f"- PENDING 候选总数：{sum(int(item.get('pending_candidate_count', 0)) for item in persons)}",
            f"- 开始时间：{_iso(started)}",
            f"- 结束时间：{_iso(finished)}",
            f"- 总耗时：{duration:.3f} 秒",
            "- 是否修改 MySQL：否",
            "- 是否调用大模型：否",
            "",
            "## issue reason 统计",
            "",
            "| reason | 数量 |",
            "| --- | ---: |",
        ]
        if reason_counts:
            lines.extend(
                f"| `{reason}` | {count} |"
                for reason, count in sorted(reason_counts.items())
            )
        else:
            lines.append("| — | 0 |")
        lines.append("")
        return "\n".join(lines)
