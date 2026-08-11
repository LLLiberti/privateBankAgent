"""Preflight and idempotent Neo4j import for local KG preview JSON files."""

from __future__ import annotations

import json
import math
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Set, Tuple, Type, TypeVar, get_args

from src.database.neo4j_client import redact_neo4j_error
from src.models.graph_models import (
    GraphEvent,
    GraphNode,
    GraphRelation,
    NodeType,
    RelationType,
)


ALLOWED_NODE_LABELS: Set[str] = set(get_args(NodeType))
NORMAL_NODE_LABELS = ALLOWED_NODE_LABELS - {"Event"}
ALLOWED_RELATION_TYPES: Set[str] = set(get_args(RelationType))
GRAPH_STATUSES = {"PREVIEW"}

CONSTRAINT_CYPHER = (
    "CREATE CONSTRAINT kg_entity_id IF NOT EXISTS "
    "FOR (n:KGEntity) REQUIRE n.entity_id IS UNIQUE"
)

TModel = TypeVar("TModel", GraphNode, GraphEvent, GraphRelation)


@dataclass(frozen=True)
class ImportIssue:
    code: str
    severity: str
    record_type: str
    record_id: Optional[str]
    message: str

    def as_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class PreflightResult:
    nodes: List[Dict[str, Any]] = field(default_factory=list)
    events: List[Dict[str, Any]] = field(default_factory=list)
    relations: List[Dict[str, Any]] = field(default_factory=list)
    issues: List[ImportIssue] = field(default_factory=list)
    normal_node_input_count: int = 0
    event_input_count: int = 0
    relation_input_count: int = 0
    pending_candidate_count: int = 0
    dangling_relation_count: int = 0
    invalid_type_count: int = 0
    graph_status: str = "PREVIEW"

    @property
    def has_errors(self) -> bool:
        return any(issue.severity == "ERROR" for issue in self.issues)


@dataclass
class ImportSummary:
    normal_node_input_count: int
    event_input_count: int
    relation_input_count: int
    merged_node_count: int = 0
    merged_event_count: int = 0
    merged_relation_count: int = 0
    skipped_dangling_relation_count: int = 0
    invalid_type_count: int = 0
    pending_candidate_count: int = 0
    graph_status: str = "PREVIEW"
    database: str = "neo4j"
    mysql_write_performed: bool = False
    llm_called: bool = False

    def as_dict(self) -> Dict[str, Any]:
        return asdict(self)


def _validate_model(model_class: Type[TModel], value: Mapping[str, Any]) -> TModel:
    validate = getattr(model_class, "model_validate", None)
    if callable(validate):
        return validate(value)
    return model_class.parse_obj(value)


def _json_text(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )


def _neo4j_value(value: Any) -> Any:
    """Convert one value into a Neo4j property-safe representation."""

    if value is None:
        return None
    if isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite floating-point values are not supported")
        return value
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return list(value)
    if isinstance(value, (Mapping, list, tuple)):
        return _json_text(value)
    raise ValueError(f"unsupported property value type: {type(value).__name__}")


def _has_complex_inner_property(properties: Any) -> bool:
    if not isinstance(properties, Mapping):
        return False
    return any(
        isinstance(value, Mapping)
        or (
            isinstance(value, (list, tuple))
            and not all(isinstance(item, str) for item in value)
        )
        for value in properties.values()
    )


def _candidate_properties(
    raw: Mapping[str, Any],
    *,
    entity_id: Optional[str],
    graph_status: str,
) -> Dict[str, Any]:
    """Preserve top-level data and safely flatten the candidate properties."""

    converted: Dict[str, Any] = {}
    for key, value in raw.items():
        if key == "properties" or value is None:
            continue
        converted_value = _neo4j_value(value)
        if converted_value is not None:
            converted[key] = converted_value

    nested = raw.get("properties")
    if isinstance(nested, Mapping):
        for key, value in nested.items():
            if value is None or key in converted:
                continue
            converted_value = _neo4j_value(value)
            if converted_value is not None:
                converted[key] = converted_value
        # Keep the original nested object losslessly under its original field
        # name; Neo4j stores it as JSON text because maps are not properties.
        converted["properties"] = _json_text(nested)
    elif nested is not None:
        converted["properties"] = _neo4j_value(nested)

    if entity_id is not None:
        converted["entity_id"] = entity_id
    converted["graph_status"] = graph_status
    return converted


def _record_id(value: Any, field_name: str) -> Optional[str]:
    if not isinstance(value, Mapping):
        return None
    identifier = value.get(field_name)
    if identifier is None or not str(identifier).strip():
        return None
    return str(identifier)


def _duplicates(records: Sequence[Any], id_field: str) -> Set[str]:
    identifiers = [
        identifier
        for identifier in (_record_id(item, id_field) for item in records)
        if identifier is not None
    ]
    return {key for key, count in Counter(identifiers).items() if count > 1}


def _classify_relation_duplicates(
    records: Sequence[Any],
) -> Tuple[Set[str], Set[str]]:
    """Return (identical duplicate IDs, conflicting duplicate IDs)."""

    signatures: Dict[str, Set[str]] = defaultdict(set)
    counts: Counter[str] = Counter()
    for item in records:
        identifier = _record_id(item, "relation_id")
        if identifier is None or not isinstance(item, Mapping):
            continue
        counts[identifier] += 1
        signatures[identifier].add(_json_text(item))
    identical = {
        identifier
        for identifier, count in counts.items()
        if count > 1 and len(signatures[identifier]) == 1
    }
    conflicting = {
        identifier
        for identifier, count in counts.items()
        if count > 1 and len(signatures[identifier]) > 1
    }
    return identical, conflicting


def preflight_candidates(
    raw_nodes: Sequence[Any],
    raw_events: Sequence[Any],
    raw_relations: Sequence[Any],
    *,
    graph_status: str = "PREVIEW",
    initial_issues: Optional[Iterable[ImportIssue]] = None,
) -> PreflightResult:
    """Validate candidate records without creating a Neo4j connection."""

    result = PreflightResult(
        normal_node_input_count=len(raw_nodes),
        event_input_count=len(raw_events),
        relation_input_count=len(raw_relations),
        graph_status=graph_status,
        issues=list(initial_issues or []),
    )
    if graph_status not in GRAPH_STATUSES:
        result.issues.append(
            ImportIssue(
                "INVALID_GRAPH_STATUS",
                "ERROR",
                "batch",
                None,
                "第一版仅允许 graph_status=PREVIEW",
            )
        )

    for duplicate in sorted(_duplicates(raw_nodes, "node_id")):
        result.issues.append(
            ImportIssue("DUPLICATE_NODE_ID", "ERROR", "node", duplicate, "node_id 重复")
        )
    for duplicate in sorted(_duplicates(raw_events, "event_id")):
        result.issues.append(
            ImportIssue("DUPLICATE_EVENT_ID", "ERROR", "event", duplicate, "event_id 重复")
        )
    identical_relations, conflicting_relations = _classify_relation_duplicates(
        raw_relations
    )
    for duplicate in sorted(conflicting_relations):
        result.issues.append(
            ImportIssue(
                "DUPLICATE_RELATION_ID",
                "ERROR",
                "relation",
                duplicate,
                "relation_id 重复",
            )
        )
    for duplicate in sorted(identical_relations):
        result.issues.append(
            ImportIssue(
                "DUPLICATE_RELATION_CANDIDATE",
                "WARNING",
                "relation",
                duplicate,
                "内容相同的重复关系候选已在预检中自动去重",
            )
        )

    valid_node_ids: Set[str] = set()
    valid_event_ids: Set[str] = set()

    for raw in raw_nodes:
        identifier = _record_id(raw, "node_id")
        if not isinstance(raw, Mapping):
            result.issues.append(
                ImportIssue("INVALID_RECORD", "ERROR", "node", None, "节点记录必须是 JSON 对象")
            )
            continue
        if raw.get("node_type") not in NORMAL_NODE_LABELS:
            result.invalid_type_count += 1
            result.issues.append(
                ImportIssue(
                    "INVALID_NODE_TYPE",
                    "ERROR",
                    "node",
                    identifier,
                    "node_type 不在普通节点白名单中",
                )
            )
            continue
        try:
            model = _validate_model(GraphNode, raw)
            prepared = {
                "entity_id": model.node_id,
                "label": model.node_type,
                "properties": _candidate_properties(
                    model.as_dict(),
                    entity_id=model.node_id,
                    graph_status=graph_status,
                ),
            }
        except Exception as exc:
            result.issues.append(
                ImportIssue(
                    "NODE_VALIDATION_FAILED",
                    "ERROR",
                    "node",
                    identifier,
                    f"节点校验或属性转换失败：{type(exc).__name__}",
                )
            )
            continue
        if _has_complex_inner_property(raw.get("properties")):
            result.issues.append(
                ImportIssue(
                    "COMPLEX_PROPERTY_SERIALIZED",
                    "WARNING",
                    "node",
                    identifier,
                    "复杂节点属性将确定性序列化为 JSON 字符串",
                )
            )
        result.nodes.append(prepared)
        valid_node_ids.add(model.node_id)
        result.pending_candidate_count += model.verification_status == "PENDING"

    for raw in raw_events:
        identifier = _record_id(raw, "event_id")
        if not isinstance(raw, Mapping):
            result.issues.append(
                ImportIssue("INVALID_RECORD", "ERROR", "event", None, "事件记录必须是 JSON 对象")
            )
            continue
        try:
            model = _validate_model(GraphEvent, raw)
            prepared = {
                "entity_id": model.event_id,
                "label": "Event",
                "properties": _candidate_properties(
                    model.as_dict(),
                    entity_id=model.event_id,
                    graph_status=graph_status,
                ),
                "subject_node_id": model.subject_node_id,
            }
        except Exception as exc:
            result.issues.append(
                ImportIssue(
                    "EVENT_VALIDATION_FAILED",
                    "ERROR",
                    "event",
                    identifier,
                    f"事件校验或属性转换失败：{type(exc).__name__}",
                )
            )
            continue
        if _has_complex_inner_property(raw.get("properties")):
            result.issues.append(
                ImportIssue(
                    "COMPLEX_PROPERTY_SERIALIZED",
                    "WARNING",
                    "event",
                    identifier,
                    "复杂事件属性将确定性序列化为 JSON 字符串",
                )
            )
        result.events.append(prepared)
        valid_event_ids.add(model.event_id)
        result.pending_candidate_count += model.verification_status == "PENDING"

    conflicts = valid_node_ids & valid_event_ids
    for entity_id in sorted(conflicts):
        result.issues.append(
            ImportIssue(
                "ENTITY_ID_CONFLICT",
                "ERROR",
                "entity",
                entity_id,
                "同一 entity_id 同时用于普通节点和 Event",
            )
        )

    all_entity_ids = valid_node_ids | valid_event_ids
    for event in result.events:
        if event["subject_node_id"] not in valid_node_ids:
            result.issues.append(
                ImportIssue(
                    "EVENT_SUBJECT_NOT_FOUND",
                    "ERROR",
                    "event",
                    event["entity_id"],
                    "Event 的 subject_node_id 不存在于普通节点输入中",
                )
            )

    seen_relation_ids: Set[str] = set()
    for raw in raw_relations:
        identifier = _record_id(raw, "relation_id")
        if identifier in identical_relations and identifier in seen_relation_ids:
            continue
        if identifier is not None:
            seen_relation_ids.add(identifier)
        if not isinstance(raw, Mapping):
            result.issues.append(
                ImportIssue("INVALID_RECORD", "ERROR", "relation", None, "关系记录必须是 JSON 对象")
            )
            continue
        relation_type = raw.get("relation_type")
        if relation_type not in ALLOWED_RELATION_TYPES:
            result.invalid_type_count += 1
            result.issues.append(
                ImportIssue(
                    "INVALID_RELATION_TYPE",
                    "ERROR",
                    "relation",
                    identifier,
                    "relation_type 不在固定白名单中",
                )
            )
            continue
        try:
            model = _validate_model(GraphRelation, raw)
            prepared = {
                "relation_id": model.relation_id,
                "start_node_id": model.start_node_id,
                "end_node_id": model.end_node_id,
                "relation_type": model.relation_type,
                "properties": _candidate_properties(
                    model.as_dict(),
                    entity_id=None,
                    graph_status=graph_status,
                ),
            }
        except Exception as exc:
            result.issues.append(
                ImportIssue(
                    "RELATION_VALIDATION_FAILED",
                    "ERROR",
                    "relation",
                    identifier,
                    f"关系校验或属性转换失败：{type(exc).__name__}",
                )
            )
            continue
        result.pending_candidate_count += model.verification_status == "PENDING"
        missing = [
            endpoint
            for endpoint in (model.start_node_id, model.end_node_id)
            if endpoint not in all_entity_ids
        ]
        if missing:
            result.dangling_relation_count += 1
            result.issues.append(
                ImportIssue(
                    "DANGLING_RELATION",
                    "ERROR",
                    "relation",
                    model.relation_id,
                    "关系端点不在本批节点或 Event 输入中，禁止导入当前人物",
                )
            )
            continue
        if _has_complex_inner_property(raw.get("properties")):
            result.issues.append(
                ImportIssue(
                    "COMPLEX_PROPERTY_SERIALIZED",
                    "WARNING",
                    "relation",
                    identifier,
                    "复杂关系属性将确定性序列化为 JSON 字符串",
                )
            )
        result.relations.append(prepared)

    return result


def build_node_merge_cypher(label: str) -> str:
    if label not in ALLOWED_NODE_LABELS:
        raise ValueError("node label is not allowlisted")
    return (
        f"UNWIND $rows AS row\n"
        f"MERGE (n:KGEntity:{label} {{entity_id: row.entity_id}})\n"
        "SET n += row.properties\n"
        "RETURN collect(n.entity_id) AS merged_ids"
    )


def build_relation_merge_cypher(relation_type: str) -> str:
    if relation_type not in ALLOWED_RELATION_TYPES:
        raise ValueError("relation type is not allowlisted")
    return (
        "UNWIND $rows AS row\n"
        "MATCH (a:KGEntity {entity_id: row.start_node_id})\n"
        "MATCH (b:KGEntity {entity_id: row.end_node_id})\n"
        f"MERGE (a)-[r:{relation_type} {{relation_id: row.relation_id}}]->(b)\n"
        "SET r += row.properties\n"
        "RETURN collect(r.relation_id) AS merged_ids"
    )


def _chunks(values: Sequence[Dict[str, Any]], size: int) -> Iterable[List[Dict[str, Any]]]:
    for index in range(0, len(values), size):
        yield list(values[index : index + size])


class Neo4jImporter:
    """Import one successful preflight result using batch transactions."""

    def __init__(self, client: Any, *, batch_size: int = 100) -> None:
        if batch_size <= 0:
            raise ValueError("batch_size must be greater than zero")
        self.client = client
        self.batch_size = batch_size

    def import_candidates(
        self,
        preflight: PreflightResult,
    ) -> Tuple[ImportSummary, List[ImportIssue]]:
        if preflight.has_errors:
            raise ValueError("preflight contains fatal errors; import is blocked")

        issues = list(preflight.issues)
        summary = ImportSummary(
            normal_node_input_count=preflight.normal_node_input_count,
            event_input_count=preflight.event_input_count,
            relation_input_count=preflight.relation_input_count,
            skipped_dangling_relation_count=preflight.dangling_relation_count,
            invalid_type_count=preflight.invalid_type_count,
            pending_candidate_count=preflight.pending_candidate_count,
            graph_status=preflight.graph_status,
            database=self.client.database,
        )

        # Required order: constraint, ordinary nodes, Event nodes, relations.
        self.client.write(CONSTRAINT_CYPHER, {})

        by_label: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for node in preflight.nodes:
            by_label[node["label"]].append(node)
        for label in sorted(by_label):
            rows = [
                {"entity_id": item["entity_id"], "properties": item["properties"]}
                for item in by_label[label]
            ]
            merged, row_issues = self._write_rows(
                build_node_merge_cypher(label),
                rows,
                id_field="entity_id",
                record_type="node",
            )
            summary.merged_node_count += merged
            issues.extend(row_issues)

        event_rows = [
            {"entity_id": item["entity_id"], "properties": item["properties"]}
            for item in preflight.events
        ]
        merged, row_issues = self._write_rows(
            build_node_merge_cypher("Event"),
            event_rows,
            id_field="entity_id",
            record_type="event",
        )
        summary.merged_event_count += merged
        issues.extend(row_issues)

        by_type: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for relation in preflight.relations:
            by_type[relation["relation_type"]].append(relation)
        for relation_type in sorted(by_type):
            rows = [
                {
                    "relation_id": item["relation_id"],
                    "start_node_id": item["start_node_id"],
                    "end_node_id": item["end_node_id"],
                    "properties": item["properties"],
                }
                for item in by_type[relation_type]
            ]
            merged, row_issues = self._write_rows(
                build_relation_merge_cypher(relation_type),
                rows,
                id_field="relation_id",
                record_type="relation",
            )
            summary.merged_relation_count += merged
            runtime_dangling = sum(
                issue.code == "ENDPOINT_NOT_FOUND_AT_IMPORT"
                for issue in row_issues
            )
            summary.skipped_dangling_relation_count += runtime_dangling
            issues.extend(row_issues)

        return summary, issues

    def _write_rows(
        self,
        cypher: str,
        rows: Sequence[Dict[str, Any]],
        *,
        id_field: str,
        record_type: str,
    ) -> Tuple[int, List[ImportIssue]]:
        merged_count = 0
        issues: List[ImportIssue] = []
        for batch in _chunks(rows, self.batch_size):
            try:
                records = self.client.write(cypher, {"rows": batch})
                merged_ids = self._merged_ids(records)
                merged_count += len(merged_ids)
                requested_ids = {str(row[id_field]) for row in batch}
                for missing_id in sorted(requested_ids - merged_ids):
                    code = (
                        "ENDPOINT_NOT_FOUND_AT_IMPORT"
                        if record_type == "relation"
                        else "MERGE_RESULT_MISSING"
                    )
                    issues.append(
                        ImportIssue(
                            code,
                            "ERROR",
                            record_type,
                            missing_id,
                            "Neo4j 未返回该记录的 MERGE 结果",
                        )
                    )
            except Exception as batch_error:
                # The failed transaction is rolled back. Retry one record per
                # transaction so a bad record cannot suppress unrelated rows.
                for row in batch:
                    record_id = str(row[id_field])
                    try:
                        records = self.client.write(cypher, {"rows": [row]})
                        merged_ids = self._merged_ids(records)
                        if record_id in merged_ids:
                            merged_count += 1
                        else:
                            code = (
                                "ENDPOINT_NOT_FOUND_AT_IMPORT"
                                if record_type == "relation"
                                else "MERGE_RESULT_MISSING"
                            )
                            issues.append(
                                ImportIssue(
                                    code,
                                    "ERROR",
                                    record_type,
                                    record_id,
                                    "Neo4j 未返回该记录的 MERGE 结果",
                                )
                            )
                    except Exception as row_error:
                        issues.append(
                            ImportIssue(
                                "IMPORT_RECORD_FAILED",
                                "ERROR",
                                record_type,
                                record_id,
                                "单条 MERGE 失败："
                                + redact_neo4j_error(
                                    f"{type(row_error).__name__}: {row_error}"
                                ),
                            )
                        )
                if not batch:
                    issues.append(
                        ImportIssue(
                            "IMPORT_BATCH_FAILED",
                            "ERROR",
                            record_type,
                            None,
                            redact_neo4j_error(str(batch_error)),
                        )
                    )
        return merged_count, issues

    @staticmethod
    def _merged_ids(records: Sequence[Mapping[str, Any]]) -> Set[str]:
        if not records:
            return set()
        values = records[0].get("merged_ids", [])
        if not isinstance(values, list):
            return set()
        return {str(value) for value in values if value is not None}


def load_json_array(path: Path, record_type: str) -> Tuple[List[Any], List[ImportIssue]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return [], [
            ImportIssue("FILE_NOT_FOUND", "ERROR", record_type, None, f"输入文件不存在：{path}")
        ]
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return [], [
            ImportIssue(
                "INVALID_JSON",
                "ERROR",
                record_type,
                None,
                f"无法读取 JSON 数组：{type(exc).__name__}",
            )
        ]
    if not isinstance(value, list):
        return [], [
            ImportIssue("INVALID_JSON_ROOT", "ERROR", record_type, None, "JSON 顶层必须是数组")
        ]
    return value, []


def render_preflight_report(result: PreflightResult) -> str:
    errors = sum(issue.severity == "ERROR" for issue in result.issues)
    warnings = sum(issue.severity == "WARNING" for issue in result.issues)
    return "\n".join(
        [
            "# Neo4j 导入预检报告",
            "",
            f"- 预检结果：{'不通过' if result.has_errors else '通过'}",
            f"- 普通节点输入：{result.normal_node_input_count}",
            f"- Event 输入：{result.event_input_count}",
            f"- 关系输入：{result.relation_input_count}",
            f"- 可导入普通节点：{len(result.nodes)}",
            f"- 可导入 Event：{len(result.events)}",
            f"- 可导入关系：{len(result.relations)}",
            f"- 悬空关系：{result.dangling_relation_count}",
            f"- 非法类型：{result.invalid_type_count}",
            f"- PENDING 候选：{result.pending_candidate_count}",
            f"- ERROR：{errors}",
            f"- WARNING：{warnings}",
            f"- graph_status：`{result.graph_status}`",
            "- Neo4j 连接：未在预检中创建",
            "- MySQL 写操作：无",
            "- 大模型调用：无",
            "",
        ]
    )


def render_import_summary(summary: ImportSummary) -> str:
    return "\n".join(
        [
            "# Neo4j 知识图谱导入摘要",
            "",
            f"- 普通节点输入数：{summary.normal_node_input_count}",
            f"- Event 输入数：{summary.event_input_count}",
            f"- 关系输入数：{summary.relation_input_count}",
            f"- 成功 MERGE 普通节点数：{summary.merged_node_count}",
            f"- 成功 MERGE Event 数：{summary.merged_event_count}",
            f"- 成功 MERGE 关系数：{summary.merged_relation_count}",
            f"- 跳过的悬空关系数：{summary.skipped_dangling_relation_count}",
            f"- 非法类型数：{summary.invalid_type_count}",
            f"- PENDING 候选数：{summary.pending_candidate_count}",
            f"- graph_status：`{summary.graph_status}`",
            f"- Neo4j database：`{summary.database}`",
            f"- 是否发生 MySQL 写操作：{'是' if summary.mysql_write_performed else '否'}",
            f"- 是否调用大模型：{'是' if summary.llm_called else '否'}",
            "",
        ]
    )
