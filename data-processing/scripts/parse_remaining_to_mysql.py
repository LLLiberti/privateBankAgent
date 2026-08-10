"""Parse the remaining 27 entrepreneurs from staging into standard MySQL tables."""

from __future__ import annotations

import argparse
import json
import os
from getpass import getpass
from typing import Any

import pymysql

from parse_pilot_to_mysql import (
    insert_enterprise,
    insert_financial_metrics,
    insert_person,
    insert_profile,
    insert_relation,
    insert_risk_preference,
    source_id,
)


EXPECTED_REMAINING_PERSONS = 27


def staging_row(cursor: Any, batch_id: int, person_name: str, dimension: str) -> dict[str, Any]:
    cursor.execute(
        """
        SELECT stg_row_id, source_file_name, sheet_name, source_row_number, person_name,
               core_enterprise_name, raw_cells
        FROM stg_import_row
        WHERE import_batch_id = %s AND person_name = %s AND data_dimension = %s
        """,
        (batch_id, person_name, dimension),
    )
    row = cursor.fetchone()
    if row is None:
        raise RuntimeError(f"缺少暂存记录：{person_name} / {dimension}")
    return {
        "stg_row_id": row[0],
        "source_file_name": row[1],
        "sheet_name": row[2],
        "source_row_number": row[3],
        "person_name": row[4],
        "core_enterprise_name": row[5],
        "raw_cells": json.loads(row[6]),
    }


def quality_issue(cursor: Any, batch_id: int, stg_row_id: int, source: int | None, issue_type: str, severity: str, message: str) -> None:
    cursor.execute(
        """
        INSERT INTO data_quality_issue
          (import_batch_id, stg_row_id, source_id, issue_type, severity, issue_message, issue_status)
        VALUES (%s, %s, %s, %s, %s, %s, 'OPEN')
        """,
        (batch_id, stg_row_id, source, issue_type, severity, message),
    )


def remaining_person_names(cursor: Any, batch_id: int) -> list[str]:
    cursor.execute(
        """
        SELECT s.person_name
        FROM stg_import_row s
        LEFT JOIN person p ON p.full_name = s.person_name
        WHERE s.import_batch_id = %s AND s.data_dimension = 'PERSON' AND p.person_id IS NULL
        ORDER BY s.source_sequence
        """,
        (batch_id,),
    )
    return [row[0] for row in cursor.fetchall()]


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse the remaining entrepreneurs from staging.")
    parser.add_argument("--batch-id", type=int, default=4)
    parser.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "3306")))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "private_bank_agent"))
    parser.add_argument("--user", default=os.getenv("DB_USER", "root"))
    parser.add_argument("--password-env", default="DB_PASSWORD")
    args = parser.parse_args()

    password = os.getenv(args.password_env) or getpass(f"MySQL 用户 {args.user} 的密码：")
    connection = pymysql.connect(host=args.host, port=args.port, user=args.user, password=password,
                                 database=args.database, charset="utf8mb4", autocommit=False)
    try:
        with connection.cursor() as cursor:
            names = remaining_person_names(cursor, args.batch_id)
            if len(names) != EXPECTED_REMAINING_PERSONS:
                raise RuntimeError(f"待解析人物数为 {len(names)}，预期为 {EXPECTED_REMAINING_PERSONS}；已停止以避免重复写入。")

            summary = {"persons": 0, "enterprises": 0, "profiles": 0, "risk_preferences": 0,
                       "relations": 0, "financial_metrics": 0, "quality_issues": 0}
            for name in names:
                person_row = staging_row(cursor, args.batch_id, name, "PERSON")
                enterprise_row = staging_row(cursor, args.batch_id, name, "ENTERPRISE")
                person_id = insert_person(cursor, person_row)
                enterprise_source = source_id(cursor, args.batch_id, enterprise_row, "1.工商注册信息")
                enterprise_id = insert_enterprise(cursor, enterprise_row, enterprise_source)
                profile_source = source_id(cursor, args.batch_id, person_row, "1.客户基本信息")
                risk_source = source_id(cursor, args.batch_id, person_row, "5.风险偏好")
                relation_source = source_id(cursor, args.batch_id, person_row, "核心关联企业")
                metric_source = source_id(cursor, args.batch_id, enterprise_row, "4.核心财务数据(2025年报)")

                insert_profile(cursor, person_id, person_row, profile_source)
                insert_risk_preference(cursor, person_id, person_row, risk_source)
                insert_relation(cursor, person_id, enterprise_id, relation_source, name, enterprise_row)
                metric_count = insert_financial_metrics(cursor, enterprise_id, enterprise_row, metric_source)

                summary["persons"] += 1
                summary["enterprises"] += 1
                summary["profiles"] += 1
                summary["risk_preferences"] += 1
                summary["relations"] += 1
                summary["financial_metrics"] += metric_count

                if metric_count == 0:
                    quality_issue(cursor, args.batch_id, enterprise_row["stg_row_id"], metric_source,
                                  "FINANCIAL_METRIC_PARSE_EMPTY", "MEDIUM",
                                  f"{name} 的企业财务字段未识别出可入库指标。")
                    summary["quality_issues"] += 1

                registration = enterprise_row["raw_cells"]["1.工商注册信息"]
                if f"董事长：{name}" not in registration:
                    quality_issue(cursor, args.batch_id, person_row["stg_row_id"], relation_source,
                                  "CORE_RELATION_NEEDS_CONFIRMATION", "LOW",
                                  f"{name} 与核心企业仅识别为 CORE_ASSOCIATED，需人工确认具体职务或控制关系。")
                    summary["quality_issues"] += 1
        connection.commit()
        print(json.dumps(summary, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
