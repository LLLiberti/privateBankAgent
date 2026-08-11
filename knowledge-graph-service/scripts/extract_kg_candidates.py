"""CLI adapter for one-person MySQL-to-KG candidate extraction.

Repository and service behavior lives under ``src``. This command remains a
permanent dry-run adapter and keeps its historical public imports for callers
that have not migrated yet.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

from src.repositories.mysql_kg_repository import (
    ALLOWED_SQL,
    ALL_PERSON_IDS_QUERY,
    DATA_QUALITY_QUERY,
    ENTERPRISE_QUERIES,
    IMPORT_BATCH_QUERY,
    ORGANIZATION_QUERY,
    PERSON_QUERIES,
    PRIMARY_KEYS,
    SOURCE_QUERY,
    ReadOnlyMySQLReader,
    UnsafeSelectError,
    fetch_all_person_ids,
    redact_mysql_error,
    validate_fixed_select,
)
from src.models.graph_models import CandidateExtraction
from src.services.kg_candidate_service import extract_candidates_for_person
from src.utils.kg_artifacts import (
    model_list,
    render_extraction_summary,
    write_candidate_outputs,
    write_json,
)


OUTPUT_FILENAMES = {
    "nodes": "kg_nodes_preview.json",
    "relations": "kg_relations_preview.json",
    "events": "kg_events_preview.json",
    "issues": "kg_issues_preview.json",
    "summary": "kg_extraction_summary.md",
}

# Compatibility alias for existing imports.
redact_error = redact_mysql_error
render_summary = render_extraction_summary


def positive_person_id(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            "--person-id 必须是数值型 MySQL person_id"
        ) from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("--person-id 必须大于 0")
    return parsed


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="只读提取一个 person_id 的知识图谱候选（永久 dry-run）"
    )
    parser.add_argument("--person-id", required=True, type=positive_person_id)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--dry-run",
        required=True,
        action="store_true",
        help="安全确认开关；缺少该参数时拒绝执行",
    )
    args = parser.parse_args(argv)
    if args.dry_run is not True:
        parser.error("本脚本只允许 --dry-run 模式")
    return args


def write_outputs(output_dir: Path, result: CandidateExtraction) -> None:
    """Backward-compatible single-person preview writer."""

    write_candidate_outputs(output_dir, result, OUTPUT_FILENAMES)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    result = extract_candidates_for_person(args.person_id)
    write_outputs(args.output_dir.resolve(), result)
    print(f"dry-run 完成：{args.output_dir.resolve()}")
    return 1 if any(issue.severity == "ERROR" for issue in result.issues) else 0


if __name__ == "__main__":
    raise SystemExit(main())
