"""Transactionally import staging CSV artifacts into MySQL.

This importer deliberately avoids LOAD DATA so Chinese text, quotation marks and
newlines are passed to MySQL as bound parameters rather than parsed as CSV SQL.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
from getpass import getpass
from pathlib import Path
from typing import Any

import pymysql


EXPECTED_STAGING_ROWS = 120
EXPECTED_EVIDENCE_ROWS = 1200


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise FileNotFoundError(f"未找到导入文件：{path}")
    with path.open("r", encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def optional_date(value: str) -> str | None:
    return value.strip() or None


def staging_values(rows: list[dict[str, str]], batch_id: int) -> list[tuple[Any, ...]]:
    values: list[tuple[Any, ...]] = []
    for row in rows:
        json.loads(row["raw_cells"])
        values.append(
            (
                batch_id,
                row["data_dimension"],
                int(row["source_sequence"]),
                row["person_name"],
                row["core_enterprise_name"],
                row["source_file_name"],
                row["sheet_name"],
                int(row["source_row_number"]),
                row["raw_cells"],
                row["parse_status"],
                row["parse_message"] or None,
            )
        )
    return values


def evidence_values(rows: list[dict[str, str]], batch_id: int) -> list[tuple[Any, ...]]:
    return [
        (
            batch_id,
            row["file_name"],
            row["sheet_name"],
            int(row["source_row_number"]),
            row["column_name"],
            row["cell_reference"],
            row["original_text"] or None,
            row["source_level"],
            optional_date(row["source_date"]),
            row["source_locator"] or None,
        )
        for row in rows
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Safely load staging CSV data into MySQL.")
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "3306")))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "private_bank_agent"))
    parser.add_argument("--user", default=os.getenv("DB_USER", "root"))
    parser.add_argument("--password-env", default="DB_PASSWORD")
    parser.add_argument("--batch-name", default="entrepreneur-four-dimension-v1")
    parser.add_argument("--cleanup-batch-id", type=int, required=True)
    args = parser.parse_args()

    staging_rows = read_csv(args.input_dir / "stg_import_row.csv")
    evidence_rows = read_csv(args.input_dir / "source_documents.csv")
    if len(staging_rows) != EXPECTED_STAGING_ROWS or len(evidence_rows) != EXPECTED_EVIDENCE_ROWS:
        raise SystemExit(
            f"导入文件数量异常：暂存 {len(staging_rows)}/{EXPECTED_STAGING_ROWS}，"
            f"证据 {len(evidence_rows)}/{EXPECTED_EVIDENCE_ROWS}。"
        )

    password = os.getenv(args.password_env) or getpass(f"MySQL 用户 {args.user} 的密码：")
    connection = pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=password,
        database=args.database,
        charset="utf8mb4",
        autocommit=False,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT batch_name FROM import_batch WHERE import_batch_id = %s", (args.cleanup_batch_id,))
            old_batch = cursor.fetchone()
            if old_batch is None:
                raise RuntimeError(f"未找到待清理批次：{args.cleanup_batch_id}")

            cursor.execute("DELETE FROM data_quality_issue WHERE import_batch_id = %s", (args.cleanup_batch_id,))
            cursor.execute("DELETE FROM source_document WHERE import_batch_id = %s", (args.cleanup_batch_id,))
            cursor.execute("DELETE FROM stg_import_row WHERE import_batch_id = %s", (args.cleanup_batch_id,))
            cursor.execute("DELETE FROM import_batch WHERE import_batch_id = %s", (args.cleanup_batch_id,))

            cursor.execute(
                """
                INSERT INTO import_batch (batch_name, source_description, import_status, operator_name)
                VALUES (%s, %s, 'LOADING', %s)
                """,
                (args.batch_name, "四维企业家受控演示样例 Excel 参数化导入", "LOCAL_OPERATOR"),
            )
            batch_id = cursor.lastrowid

            cursor.executemany(
                """
                INSERT INTO source_document
                  (import_batch_id, file_name, sheet_name, source_row_number, column_name,
                   cell_reference, original_text, source_level, source_date, source_locator)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                evidence_values(evidence_rows, batch_id),
            )
            cursor.executemany(
                """
                INSERT INTO stg_import_row
                  (import_batch_id, data_dimension, source_sequence, person_name,
                   core_enterprise_name, source_file_name, sheet_name, source_row_number,
                   raw_cells, parse_status, parse_message)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                staging_values(staging_rows, batch_id),
            )

            cursor.execute("SELECT COUNT(*) FROM source_document WHERE import_batch_id = %s", (batch_id,))
            evidence_count = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM stg_import_row WHERE import_batch_id = %s", (batch_id,))
            staging_count = cursor.fetchone()[0]
            if staging_count != EXPECTED_STAGING_ROWS or evidence_count != EXPECTED_EVIDENCE_ROWS:
                raise RuntimeError(f"写入数量异常：暂存 {staging_count}，证据 {evidence_count}")

            cursor.execute(
                """
                UPDATE import_batch
                SET import_status = 'STAGED', record_count = %s
                WHERE import_batch_id = %s
                """,
                (staging_count, batch_id),
            )
        connection.commit()
        print(json.dumps({"import_batch_id": batch_id, "staging_rows": staging_count, "evidence_rows": evidence_count}, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
