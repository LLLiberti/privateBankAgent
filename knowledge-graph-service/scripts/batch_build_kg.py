"""Batch entry point for serial KG extraction, preflight and import."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Optional, Sequence

from scripts.extract_kg_candidates import positive_person_id
from src.pipeline.batch_kg_pipeline import (
    BatchKGPipeline,
    BatchRunOptions,
    NoPersonsSelectedError,
    parse_person_ids_csv,
    redact_pipeline_error,
    select_person_ids,
)
from src.repositories.mysql_kg_repository import fetch_all_person_ids
from src.services.kg_build_service import KGBuildService


def positive_integer(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("参数必须是整数") from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("参数必须大于 0")
    return parsed


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="串行批量抽取、预检并可选导入 Neo4j Community"
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--execute", action="store_true")

    selector = parser.add_mutually_exclusive_group()
    selector.add_argument("--person-id", type=positive_person_id)
    selector.add_argument("--person-ids")
    selector.add_argument("--all-persons", action="store_true")

    parser.add_argument("--limit", type=positive_integer)
    parser.add_argument("--start-after-person-id", type=positive_person_id)
    parser.add_argument("--output-root", type=Path)
    parser.add_argument("--batch-root", type=Path, default=Path("output/batch_runs"))
    parser.add_argument("--batch-size", type=positive_integer, default=100)
    parser.add_argument("--resume-manifest", type=Path)
    parser.add_argument("--retry-failed-only", action="store_true")
    args = parser.parse_args(argv)

    has_selector = any((args.person_id, args.person_ids, args.all_persons))
    if args.resume_manifest is not None:
        if has_selector:
            parser.error("--resume-manifest 不能与人物选择参数同时使用")
        if args.limit is not None or args.start_after_person_id is not None:
            parser.error("续跑时不能重新应用 --limit 或 --start-after-person-id")
    elif not has_selector:
        parser.error("必须指定 --person-id、--person-ids 或 --all-persons")
    if args.retry_failed_only and args.resume_manifest is None:
        parser.error("--retry-failed-only 必须与 --resume-manifest 同时使用")
    return args


def _selected_ids(args: argparse.Namespace) -> list[int]:
    if args.resume_manifest is not None:
        return []
    if args.person_id is not None:
        values = [args.person_id]
    elif args.person_ids is not None:
        values = parse_person_ids_csv(args.person_ids)
    else:
        values = fetch_all_person_ids()
    return select_person_ids(
        values,
        start_after_person_id=args.start_after_person_id,
        limit=args.limit,
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        person_ids = _selected_ids(args)
        if args.resume_manifest is None and not person_ids:
            raise NoPersonsSelectedError("没有符合条件的 person 记录")
        options = BatchRunOptions(
            person_ids=person_ids,
            mode="EXECUTE" if args.execute else "DRY_RUN",
            output_root=(args.output_root or Path("output/persons")),
            batch_root=args.batch_root,
            batch_size=args.batch_size,
            resume_manifest=args.resume_manifest,
            retry_failed_only=args.retry_failed_only,
        )
        manifest = BatchKGPipeline(
            build_service=KGBuildService(),
        ).run(options)
    except Exception as exc:
        print(
            "批量知识图谱任务失败："
            + redact_pipeline_error(f"{type(exc).__name__}: {exc}"),
            file=sys.stderr,
        )
        return 1

    print(
        f"批量知识图谱任务完成：batch_id={manifest['batch_id']}，"
        f"mode={manifest['mode']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
