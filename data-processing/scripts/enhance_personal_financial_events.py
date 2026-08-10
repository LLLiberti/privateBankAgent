"""Resolve open personal financial-event parse gaps without rewriting prior records."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass

import pymysql


EVENT_RULES = [
    ("分红", "DIVIDEND_INCOME"),
    ("增持", "INVESTMENT"),
    ("购买", "INVESTMENT"),
    ("投资", "INVESTMENT"),
    ("赠与", "ASSET_GIFT"),
    ("处置", "DIVESTMENT"),
    ("减持", "DIVESTMENT"),
    ("偿还", "DEBT_REPAYMENT"),
    ("置换", "ASSET_RESTRUCTURING"),
]


def amount_in_100m(text: str) -> float | None:
    match = re.search(r"([+-]?\d+(?:\.\d+)?)\s*(亿元|万元)", text)
    if not match:
        return None
    value = float(match.group(1))
    return value if match.group(2) == "亿元" else value / 10000


def main() -> None:
    parser = argparse.ArgumentParser(description="Resolve open personal financial-event parse issues.")
    parser.add_argument("--batch-id", type=int, default=4)
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
            cursor.execute(
                """
                SELECT q.data_quality_issue_id, q.source_id, p.person_id, p.full_name, d.original_text
                FROM data_quality_issue q
                JOIN stg_import_row s ON s.stg_row_id = q.stg_row_id
                JOIN person p ON p.full_name = s.person_name
                JOIN source_document d ON d.source_id = q.source_id
                WHERE q.import_batch_id = %s
                  AND q.issue_type = 'PERSONAL_FIELD_PARSE_EMPTY'
                  AND q.issue_status = 'OPEN'
                  AND d.column_name LIKE '%%交易和大额资金变动%%'
                ORDER BY q.data_quality_issue_id
                """,
                (args.batch_id,),
            )
            issues = cursor.fetchall()
            if not issues:
                print(json.dumps({"resolved_issues": 0, "financial_events": 0, "message": "no open event issues"}, ensure_ascii=False))
                return

            total_events = 0
            resolved_issues = 0
            for issue in issues:
                inserted = 0
                for part in re.split(r"[；;]", issue["original_text"]):
                    event_type = next((kind for keyword, kind in EVENT_RULES if keyword in part), None)
                    amount = amount_in_100m(part)
                    if not event_type or amount is None:
                        continue
                    cursor.execute(
                        """
                        INSERT INTO financial_event
                          (person_id, event_type, amount, currency_code, event_description,
                           source_id, verification_status)
                        VALUES (%s, %s, %s, 'CNY', %s, %s, 'PENDING_CONFIRMATION')
                        """,
                        (issue["person_id"], event_type, amount, part.strip(), issue["source_id"]),
                    )
                    inserted += 1
                if inserted:
                    cursor.execute(
                        """
                        UPDATE data_quality_issue
                        SET issue_status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
                            resolution_note = '补充财务事件动作词与万元金额换算规则后已解析。'
                        WHERE data_quality_issue_id = %s
                        """,
                        (issue["data_quality_issue_id"],),
                    )
                    total_events += inserted
                    resolved_issues += 1
            connection.commit()
            print(json.dumps({"resolved_issues": resolved_issues, "financial_events": total_events}, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
