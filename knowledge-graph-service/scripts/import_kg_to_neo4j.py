"""Validate or import local KG candidate previews into Neo4j Community.

Dry-run never constructs a Neo4j driver. Execute always repeats preflight
before opening a connection.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, List, Optional, Sequence, Tuple

from src.database.neo4j_client import Neo4jClient, redact_neo4j_error
from src.importing.neo4j_importer import (
    ImportIssue,
    ImportSummary,
    Neo4jImporter,
    PreflightResult,
    load_json_array,
    preflight_candidates,
    render_import_summary,
    render_preflight_report,
)
from src.utils.config import settings


PREFLIGHT_REPORT = "kg_neo4j_preflight_report.md"
PREFLIGHT_ISSUES = "kg_neo4j_preflight_issues.json"
IMPORT_SUMMARY = "kg_neo4j_import_summary.md"
IMPORT_ISSUES = "kg_neo4j_import_issues.json"


def positive_batch_size(value: str) -> int:
    try:
        size = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--batch-size 必须是整数") from exc
    if size <= 0:
        raise argparse.ArgumentTypeError("--batch-size 必须大于 0")
    return size


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="预检或幂等导入知识图谱候选到 Neo4j Community"
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true", help="只读取和校验 JSON")
    mode.add_argument("--execute", action="store_true", help="预检通过后执行导入")
    mode.add_argument(
        "--test-connection",
        action="store_true",
        help="仅执行 RETURN 1 AS ok",
    )
    parser.add_argument("--nodes", type=Path)
    parser.add_argument("--events", type=Path)
    parser.add_argument("--relations", type=Path)
    parser.add_argument("--graph-status", choices=["PREVIEW"], default="PREVIEW")
    parser.add_argument("--batch-size", type=positive_batch_size, default=100)
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="报告目录；默认使用 --nodes 的父目录",
    )
    args = parser.parse_args(argv)

    if not args.test_connection:
        missing = [
            flag
            for flag, value in (
                ("--nodes", args.nodes),
                ("--events", args.events),
                ("--relations", args.relations),
            )
            if value is None
        ]
        if missing:
            parser.error("缺少候选输入文件参数：" + ", ".join(missing))
    return args


def _atomic_write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(path)


def _write_json(path: Path, value: Any) -> None:
    _atomic_write_text(
        path,
        json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n",
    )


def _output_dir(args: argparse.Namespace) -> Path:
    if args.output_dir is not None:
        return args.output_dir.resolve()
    assert args.nodes is not None
    return args.nodes.resolve().parent


def run_preflight_from_files(args: argparse.Namespace) -> PreflightResult:
    nodes, node_issues = load_json_array(args.nodes.resolve(), "node")
    events, event_issues = load_json_array(args.events.resolve(), "event")
    relations, relation_issues = load_json_array(
        args.relations.resolve(),
        "relation",
    )
    return preflight_candidates(
        nodes,
        events,
        relations,
        graph_status=args.graph_status,
        initial_issues=[*node_issues, *event_issues, *relation_issues],
    )


def write_preflight_outputs(output_dir: Path, result: PreflightResult) -> None:
    _atomic_write_text(output_dir / PREFLIGHT_REPORT, render_preflight_report(result))
    _write_json(
        output_dir / PREFLIGHT_ISSUES,
        [issue.as_dict() for issue in result.issues],
    )


def _empty_import_summary(result: PreflightResult, database: str) -> ImportSummary:
    return ImportSummary(
        normal_node_input_count=result.normal_node_input_count,
        event_input_count=result.event_input_count,
        relation_input_count=result.relation_input_count,
        skipped_dangling_relation_count=result.dangling_relation_count,
        invalid_type_count=result.invalid_type_count,
        pending_candidate_count=result.pending_candidate_count,
        graph_status=result.graph_status,
        database=database,
    )


def write_import_outputs(
    output_dir: Path,
    summary: ImportSummary,
    issues: Sequence[ImportIssue],
) -> None:
    _atomic_write_text(output_dir / IMPORT_SUMMARY, render_import_summary(summary))
    _write_json(output_dir / IMPORT_ISSUES, [issue.as_dict() for issue in issues])


def test_connection() -> int:
    """Run exactly one read-only Cypher statement and no write transaction."""

    try:
        with Neo4jClient.from_settings(settings) as client:
            ok = client.test_connection()
            database = client.database
    except Exception as exc:
        print(
            "Neo4j 连接失败：" + redact_neo4j_error(f"{type(exc).__name__}: {exc}"),
            file=sys.stderr,
        )
        return 1
    print(f"Neo4j 连接成功：{ok}；database={database}")
    return 0 if ok else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    if args.test_connection:
        return test_connection()

    output_dir = _output_dir(args)
    preflight = run_preflight_from_files(args)
    write_preflight_outputs(output_dir, preflight)

    if args.dry_run:
        print(f"Neo4j dry-run 预检完成：{output_dir}")
        return 1 if preflight.has_errors else 0

    # Execute repeats preflight above and must not connect if fatal errors exist.
    if preflight.has_errors:
        issue = ImportIssue(
            "EXECUTE_BLOCKED_BY_PREFLIGHT",
            "ERROR",
            "batch",
            None,
            "预检存在严重错误，未连接 Neo4j，未执行任何写入",
        )
        write_import_outputs(
            output_dir,
            _empty_import_summary(preflight, settings.neo4j_database),
            [*preflight.issues, issue],
        )
        print("预检未通过，execute 已阻止", file=sys.stderr)
        return 2

    try:
        with Neo4jClient.from_settings(settings) as client:
            summary, issues = Neo4jImporter(
                client,
                batch_size=args.batch_size,
            ).import_candidates(preflight)
    except Exception as exc:
        issue = ImportIssue(
            "IMPORT_ABORTED",
            "ERROR",
            "batch",
            None,
            "导入中止：" + redact_neo4j_error(f"{type(exc).__name__}: {exc}"),
        )
        write_import_outputs(
            output_dir,
            _empty_import_summary(preflight, settings.neo4j_database),
            [*preflight.issues, issue],
        )
        print("Neo4j 导入失败，详情已写入问题报告", file=sys.stderr)
        return 1

    write_import_outputs(output_dir, summary, issues)
    print(f"Neo4j 导入完成：{output_dir}")
    return 1 if any(issue.severity == "ERROR" for issue in issues) else 0


if __name__ == "__main__":
    raise SystemExit(main())
