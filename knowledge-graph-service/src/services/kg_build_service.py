"""Reusable service for the complete single-person KG build workflow."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Sequence

from src.database.neo4j_client import Neo4jClient, redact_neo4j_error
from src.importing.neo4j_importer import (
    ImportIssue,
    ImportSummary,
    Neo4jImporter,
    PreflightResult,
    preflight_candidates,
)
from src.models.graph_models import CandidateExtraction
from src.repositories.mysql_kg_repository import redact_mysql_error
from src.utils.config import settings
from src.utils.kg_artifacts import model_list


BuildStatusCallback = Callable[[str], None]


def redact_build_error(value: Any) -> str:
    return redact_mysql_error(redact_neo4j_error(str(value)))


def _import_issue_key(issue: ImportIssue) -> tuple[Any, ...]:
    return (
        issue.code,
        issue.severity,
        issue.record_type,
        issue.record_id,
        issue.message,
    )


@dataclass
class KGBuildResult:
    """Structured result returned by ``KGBuildService.build_person``."""

    person_id: int
    status: str
    node_count: int
    relation_count: int
    event_count: int
    issue_count: int
    issues: List[Dict[str, Any]] = field(default_factory=list)
    import_result: Optional[Dict[str, Any]] = None
    candidates: CandidateExtraction = field(
        default_factory=lambda: CandidateExtraction(person_id="")
    )
    preflight_result: Optional[PreflightResult] = None
    import_summary: Optional[ImportSummary] = None
    import_issues: List[ImportIssue] = field(default_factory=list)
    error: Optional[str] = None

    def as_dict(self) -> Dict[str, Any]:
        """Return the stable public result without internal model objects."""

        return {
            "person_id": self.person_id,
            "status": self.status,
            "node_count": self.node_count,
            "relation_count": self.relation_count,
            "event_count": self.event_count,
            "issue_count": self.issue_count,
            "issues": list(self.issues),
            "import_result": self.import_result,
        }


class KGBuildService:
    """Build one person's graph from MySQL and optionally import it to Neo4j."""

    def __init__(
        self,
        *,
        extractor: Optional[Callable[[int], CandidateExtraction]] = None,
        preflight: Callable[..., PreflightResult] = preflight_candidates,
        neo4j_client_factory: Callable[[], Any] = (
            lambda: Neo4jClient.from_settings(settings)
        ),
        importer_factory: Callable[[Any, int], Any] = (
            lambda client, batch_size: Neo4jImporter(
                client,
                batch_size=batch_size,
            )
        ),
        default_batch_size: int = 100,
    ) -> None:
        if default_batch_size <= 0:
            raise ValueError("default_batch_size must be greater than zero")
        self.extractor = extractor
        self.preflight = preflight
        self.neo4j_client_factory = neo4j_client_factory
        self.importer_factory = importer_factory
        self.default_batch_size = default_batch_size

    def build_person(
        self,
        person_id: int,
        execute: bool = True,
        *,
        batch_size: Optional[int] = None,
        status_callback: Optional[BuildStatusCallback] = None,
    ) -> KGBuildResult:
        """Run extraction, mapping, preflight and optional Neo4j import."""

        if isinstance(person_id, bool) or not isinstance(person_id, int) or person_id <= 0:
            raise ValueError("person_id 必须是正整数")
        effective_batch_size = batch_size or self.default_batch_size
        if effective_batch_size <= 0:
            raise ValueError("batch_size must be greater than zero")

        extraction_result: CandidateExtraction
        service_issues: List[Dict[str, Any]] = []
        try:
            extraction_result = self._extract(person_id)
        except Exception as exc:
            safe_error = redact_build_error(f"{type(exc).__name__}: {exc}")
            extraction_result = CandidateExtraction(person_id=str(person_id))
            service_issues.append(
                {
                    "stage": "EXTRACTION",
                    "code": "EXTRACTION_FAILED",
                    "severity": "ERROR",
                    "record_type": "person",
                    "record_id": str(person_id),
                    "message": safe_error,
                }
            )
            failed_preflight = self.preflight(
                *self._raw_candidates(extraction_result),
                graph_status="PREVIEW",
                initial_issues=[
                    ImportIssue(
                        "EXTRACTION_FAILED",
                        "ERROR",
                        "batch",
                        str(person_id),
                        "抽取阶段失败，禁止导入当前人物",
                    )
                ],
            )
            self._notify(status_callback, "EXTRACTION_FAILED")
            return self._result(
                person_id,
                "EXTRACTION_FAILED",
                extraction_result,
                failed_preflight,
                service_issues=service_issues,
                error=safe_error,
            )

        extraction_issues = [
            {"stage": "EXTRACTION", **issue.as_dict()}
            for issue in extraction_result.issues
        ]
        service_issues.extend(extraction_issues)
        raw = self._raw_candidates(extraction_result)

        if any(issue.severity == "ERROR" for issue in extraction_result.issues):
            failed_preflight = self.preflight(
                *raw,
                graph_status="PREVIEW",
                initial_issues=[
                    ImportIssue(
                        "EXTRACTION_FAILED",
                        "ERROR",
                        "batch",
                        str(person_id),
                        "抽取阶段失败，禁止导入当前人物",
                    )
                ],
            )
            self._notify(status_callback, "EXTRACTION_FAILED")
            return self._result(
                person_id,
                "EXTRACTION_FAILED",
                extraction_result,
                failed_preflight,
                service_issues=service_issues,
                error="候选抽取包含 ERROR issue",
            )

        self._notify(status_callback, "EXTRACTED")
        preflight = self.preflight(*raw, graph_status="PREVIEW")
        service_issues.extend(self._normalized_import_issues("PREFLIGHT", preflight.issues))
        if preflight.has_errors:
            self._notify(status_callback, "PREFLIGHT_FAILED")
            return self._result(
                person_id,
                "PREFLIGHT_FAILED",
                extraction_result,
                preflight,
                service_issues=service_issues,
                error="Neo4j 本地预检存在 fatal issue",
            )

        self._notify(status_callback, "PREFLIGHT_PASSED")
        if not execute:
            return self._result(
                person_id,
                "PREFLIGHT_PASSED",
                extraction_result,
                preflight,
                service_issues=service_issues,
            )

        # Preserve the current safety behavior: repeat preflight immediately
        # before constructing a Neo4j driver.
        execute_preflight = self.preflight(*raw, graph_status="PREVIEW")
        if execute_preflight.has_errors:
            existing = {_import_issue_key(issue) for issue in preflight.issues}
            service_issues.extend(
                self._normalized_import_issues(
                    "PREFLIGHT",
                    [
                        issue
                        for issue in execute_preflight.issues
                        if _import_issue_key(issue) not in existing
                    ],
                )
            )
            self._notify(status_callback, "PREFLIGHT_FAILED")
            return self._result(
                person_id,
                "PREFLIGHT_FAILED",
                extraction_result,
                execute_preflight,
                service_issues=service_issues,
                error="execute 前复检存在 fatal issue",
            )

        self._notify(status_callback, "IMPORTING")
        client: Any = None
        import_summary: Optional[ImportSummary] = None
        import_issues: List[ImportIssue] = []
        import_error: Optional[str] = None
        try:
            client = self.neo4j_client_factory()
            importer = self.importer_factory(client, effective_batch_size)
            import_summary, import_issues = importer.import_candidates(
                execute_preflight
            )
        except Exception as exc:
            import_error = redact_build_error(f"{type(exc).__name__}: {exc}")
            import_issues.append(
                ImportIssue(
                    "IMPORT_FAILED",
                    "ERROR",
                    "batch",
                    str(person_id),
                    import_error,
                )
            )
        finally:
            if client is not None and callable(getattr(client, "close", None)):
                try:
                    client.close()
                except Exception as exc:
                    close_error = redact_build_error(f"{type(exc).__name__}: {exc}")
                    import_issues.append(
                        ImportIssue(
                            "NEO4J_CLOSE_FAILED",
                            "ERROR",
                            "batch",
                            str(person_id),
                            close_error,
                        )
                    )
                    import_error = close_error

        preflight_issue_keys = {
            _import_issue_key(issue) for issue in execute_preflight.issues
        }
        import_only_issues = [
            issue
            for issue in import_issues
            if _import_issue_key(issue) not in preflight_issue_keys
        ]
        service_issues.extend(
            self._normalized_import_issues("IMPORT", import_only_issues)
        )
        status = (
            "IMPORT_FAILED"
            if any(issue.severity == "ERROR" for issue in import_issues)
            else "SUCCESS"
        )
        if status == "IMPORT_FAILED" and import_error is None:
            import_error = "Neo4j 导入包含 ERROR issue"
        self._notify(status_callback, status)
        return self._result(
            person_id,
            status,
            extraction_result,
            execute_preflight,
            service_issues=service_issues,
            import_summary=import_summary,
            import_issues=import_issues,
            error=import_error,
        )

    def _extract(self, person_id: int) -> CandidateExtraction:
        if self.extractor is not None:
            return self.extractor(person_id)
        from src.services.kg_candidate_service import extract_candidates_for_person

        return extract_candidates_for_person(person_id)

    @staticmethod
    def _notify(
        callback: Optional[BuildStatusCallback],
        status: str,
    ) -> None:
        if callback is not None:
            callback(status)

    @staticmethod
    def _raw_candidates(
        result: CandidateExtraction,
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
        return (
            model_list(result.nodes),
            model_list(result.events),
            model_list(result.relations),
        )

    @staticmethod
    def _normalized_import_issues(
        stage: str,
        issues: Sequence[ImportIssue],
    ) -> List[Dict[str, Any]]:
        return [
            {"stage": stage, **issue.as_dict()}
            for issue in issues
        ]

    @staticmethod
    def _result(
        person_id: int,
        status: str,
        candidates: CandidateExtraction,
        preflight_result: Optional[PreflightResult],
        *,
        service_issues: Sequence[Dict[str, Any]],
        import_summary: Optional[ImportSummary] = None,
        import_issues: Optional[Sequence[ImportIssue]] = None,
        error: Optional[str] = None,
    ) -> KGBuildResult:
        normalized_import_issues = list(import_issues or [])
        import_result: Optional[Dict[str, Any]] = None
        if import_summary is not None or normalized_import_issues:
            import_result = {
                "summary": import_summary.as_dict() if import_summary else None,
                "issues": [issue.as_dict() for issue in normalized_import_issues],
            }
        issues = list(service_issues)
        return KGBuildResult(
            person_id=person_id,
            status=status,
            node_count=len(candidates.nodes),
            relation_count=len(candidates.relations),
            event_count=len(candidates.events),
            issue_count=len(issues),
            issues=issues,
            import_result=import_result,
            candidates=candidates,
            preflight_result=preflight_result,
            import_summary=import_summary,
            import_issues=normalized_import_issues,
            error=error,
        )
