"""Audit batch completeness and, only when passed, mark it completed."""

from __future__ import annotations

import argparse
import json
import os
from getpass import getpass

import pymysql


EXPECTED_DIMENSIONS = {"PERSON": 30, "ENTERPRISE": 30, "FAMILY": 30, "SOCIAL": 30}
MANDATORY_COUNTS = {
    "person": 30,
    "enterprise": 30,
    "person_profile": 30,
    "risk_preference": 30,
    "person_career": 30,
}
SOURCE_BEARING_TABLES = (
    "person_profile", "person_career", "person_enterprise_relation", "financial_fact",
    "product_holding", "risk_preference", "financial_event", "service_record",
    "customer_interaction_note", "enterprise_business", "enterprise_financial_metric",
    "enterprise_market_relation", "enterprise_event", "family_member",
    "person_family_relation", "succession_arrangement", "person_social_relation",
    "social_activity", "public_reputation", "reputation_risk",
)


def scalar(cursor, sql: str, params: tuple[object, ...] = ()) -> int:
    cursor.execute(sql, params)
    return int(cursor.fetchone()["value"])


def audit(cursor, batch_id: int) -> dict:
    cursor.execute(
        "SELECT import_status, record_count FROM import_batch WHERE import_batch_id = %s",
        (batch_id,),
    )
    batch = cursor.fetchone()
    if not batch:
        raise RuntimeError(f"未找到导入批次 {batch_id}。")
    cursor.execute(
        """
        SELECT data_dimension, COUNT(*) AS row_count
        FROM stg_import_row WHERE import_batch_id = %s
        GROUP BY data_dimension
        """,
        (batch_id,),
    )
    dimensions = {row["data_dimension"]: int(row["row_count"]) for row in cursor.fetchall()}
    source_documents = scalar(
        cursor, "SELECT COUNT(*) AS value FROM source_document WHERE import_batch_id = %s", (batch_id,)
    )
    open_issues = scalar(
        cursor,
        "SELECT COUNT(*) AS value FROM data_quality_issue WHERE import_batch_id = %s AND issue_status <> 'RESOLVED'",
        (batch_id,),
    )
    table_counts = {table: scalar(cursor, f"SELECT COUNT(*) AS value FROM {table}") for table in MANDATORY_COUNTS}
    broken_source_references = {}
    for table in SOURCE_BEARING_TABLES:
        broken_source_references[table] = scalar(
            cursor,
            f"""
            SELECT COUNT(*) AS value
            FROM {table} fact
            LEFT JOIN source_document source_doc ON source_doc.source_id = fact.source_id
            WHERE fact.source_id IS NULL OR source_doc.source_id IS NULL
            """,
        )
    coverage_ok = dimensions == EXPECTED_DIMENSIONS
    mandatory_ok = all(table_counts[table] >= expected for table, expected in MANDATORY_COUNTS.items())
    passed = (
        batch["record_count"] == 120
        and coverage_ok
        and source_documents == 1200
        and open_issues == 0
        and mandatory_ok
        and not any(broken_source_references.values())
    )
    return {
        "passed": passed,
        "import_status": batch["import_status"],
        "record_count": batch["record_count"],
        "dimension_rows": dimensions,
        "source_documents": source_documents,
        "open_quality_issues": open_issues,
        "mandatory_table_counts": table_counts,
        "broken_source_references": broken_source_references,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Audit batch data and optionally mark it completed.")
    parser.add_argument("--batch-id", type=int, default=4)
    parser.add_argument("--finalize", action="store_true", help="Mark a passed batch PARSED/COMPLETED.")
    parser.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "3306")))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "private_bank_agent"))
    parser.add_argument("--user", default=os.getenv("DB_USER", "root"))
    parser.add_argument("--password-env", default="DB_PASSWORD")
    args = parser.parse_args()

    password = os.getenv(args.password_env) or getpass(f"MySQL 用户 {args.user} 的密码：")
    connection = pymysql.connect(
        host=args.host, port=args.port, user=args.user, password=password,
        database=args.database, charset="utf8mb4", autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with connection.cursor() as cursor:
            result = audit(cursor, args.batch_id)
            result["finalized"] = False
            if args.finalize:
                if not result["passed"]:
                    raise RuntimeError("批次未通过验收，已回滚，未更新完成状态。")
                if result["import_status"] != "COMPLETED":
                    cursor.execute(
                        """
                        UPDATE stg_import_row
                        SET parse_status = 'PARSED', parse_message = '四维结构化解析及最终验收通过。'
                        WHERE import_batch_id = %s
                        """,
                        (args.batch_id,),
                    )
                    cursor.execute(
                        """
                        UPDATE import_batch
                        SET import_status = 'COMPLETED',
                            note = CONCAT(COALESCE(note, ''), '\\n最终验收通过：四维数据已结构化入库，来源可追溯。')
                        WHERE import_batch_id = %s
                        """,
                        (args.batch_id,),
                    )
                    result["finalized"] = True
                    result["import_status"] = "COMPLETED"
        connection.commit()
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
