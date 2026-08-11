"""File rendering helpers shared by CLI and batch orchestration."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping

from src.models.graph_models import CandidateExtraction


def model_list(records: Iterable[Any]) -> List[Dict[str, Any]]:
    return [record.as_dict() for record in records]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def render_extraction_summary(
    result: CandidateExtraction,
    *,
    issues_filename: str = "kg_issues_preview.json",
) -> str:
    pending_candidates = sum(
        item.verification_status == "PENDING"
        for item in [*result.nodes, *result.relations, *result.events]
    )
    manual_issues = sum(issue.requires_manual_confirmation for issue in result.issues)
    parse_failures = sum(
        issue.reason in {"PARSE_FAILED", "INVALID_VALUE", "UNKNOWN_CURRENCY"}
        for issue in result.issues
    )
    unmatched = sum(issue.reason == "UNMATCHED_RELATION" for issue in result.issues)
    counts = result.field_treatment_counts

    lines = [
        "# 知识图谱候选抽取 dry-run 摘要",
        "",
        f"- 生成时间（UTC）：{datetime.now(timezone.utc).isoformat()}",
        f"- person_id：`{result.person_id}`",
        "- 模式：`dry-run=true`",
        "- MySQL 写操作：无",
        "- Neo4j 连接/写入：无",
        "- 百炼 API 调用：无",
        "",
        "## MySQL 读取范围",
        "",
        "| 表 | 读取记录数 |",
        "|---|---:|",
    ]
    for table, count in sorted(result.table_record_counts.items()):
        lines.append(f"| `{table}` | {count} |")
    if not result.table_record_counts:
        lines.append("| — | 0 |")

    lines.extend(
        [
            "",
            "## 候选产出",
            "",
            f"- 节点：{len(result.nodes)}",
            f"- 关系：{len(result.relations)}",
            f"- 事件：{len(result.events)}",
            f"- 问题：{len(result.issues)}",
            "",
            "## 字段处理统计",
            "",
            "> 以下为实际读取记录中非空字段值的出现次数，不是数据库列数。",
            "",
            f"- DIRECT 字段值：{counts.get('DIRECT', 0)}",
            f"- RULE 字段值：{counts.get('RULE', 0)}",
            f"- MYSQL_ONLY 字段值：{counts.get('MYSQL_ONLY', 0)}",
            f"- 等待 LLM 处理字段值：{counts.get('LLM', 0)}",
            f"- MAPPING_PENDING 字段值：{counts.get('PENDING', 0)}",
            f"- IGNORE 字段值：{counts.get('IGNORE', 0)}",
            f"- 明确跳过的表：{', '.join(result.skipped_tables) or '无'}",
            "",
            "## 校验和待办",
            "",
            f"- PENDING 候选数量：{pending_candidates}",
            f"- 需人工确认的问题数量：{manual_issues}",
            f"- 待人工确认总量（PENDING 候选 + 人工问题）：{pending_candidates + manual_issues}",
            f"- 解析失败数量：{parse_failures}",
            f"- 未匹配关系数量：{unmatched}",
            "",
            "所有 LLM/PENDING 字段、解析失败、缺少来源和未匹配关系均保存在 "
            f"`{issues_filename}`，未被静默丢弃。",
            "",
        ]
    )
    return "\n".join(lines)


def write_candidate_outputs(
    output_dir: Path,
    result: CandidateExtraction,
    filenames: Mapping[str, str],
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(output_dir / filenames["nodes"], model_list(result.nodes))
    write_json(output_dir / filenames["relations"], model_list(result.relations))
    write_json(output_dir / filenames["events"], model_list(result.events))
    write_json(output_dir / filenames["issues"], model_list(result.issues))
    summary_path = output_dir / filenames["summary"]
    temporary = summary_path.with_suffix(summary_path.suffix + ".tmp")
    temporary.write_text(
        render_extraction_summary(result, issues_filename=filenames["issues"]),
        encoding="utf-8",
    )
    temporary.replace(summary_path)
