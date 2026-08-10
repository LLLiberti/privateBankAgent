"""Parse structured personal-extension facts from PERSON staging rows."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass

import pymysql


PERSON_COLUMNS = [
    "2.职业经历和公开身份", "3.资产负债概况", "4.产品持仓和到期情况",
    "6.交易和大额资金变动", "7.历史服务记录", "8.客户经理纪要和客户明确表达",
]


def amount(text: str) -> float | None:
    match = re.search(r"([+-]?\d+(?:\.\d+)?)亿元", text)
    return float(match.group(1)) if match else None


def source_map(cursor, batch_id: int, row: dict) -> dict[str, int]:
    cursor.execute(
        """
        SELECT column_name, source_id FROM source_document
        WHERE import_batch_id = %s AND file_name = %s AND sheet_name = %s
          AND source_row_number = %s
        """,
        (batch_id, row["source_file_name"], row["sheet_name"], row["source_row_number"]),
    )
    return {item["column_name"]: item["source_id"] for item in cursor.fetchall()}


def add_quality_issue(cursor, batch_id: int, stg_row_id: int, source_id: int, field_name: str) -> None:
    cursor.execute(
        """
        INSERT INTO data_quality_issue
          (import_batch_id, stg_row_id, source_id, issue_type, severity, issue_message, issue_status)
        VALUES (%s, %s, %s, 'PERSONAL_FIELD_PARSE_EMPTY', 'MEDIUM', %s, 'OPEN')
        """,
        (batch_id, stg_row_id, source_id, f"未从 {field_name} 中识别出可结构化的个人维度记录。"),
    )


def parse_assets(cursor, person_id: int, raw: str, source_id: int) -> int:
    patterns = [
        ("TOTAL_ASSET", "TOTAL_ASSET", r"(?:个人)?总资产约?\s*([\d.]+)亿元", None),
        ("EQUITY", "EQUITY", r"(?:企业)?股权(?:占比约?\d+%[；;，,]?\s*)?约?\s*([\d.]+)亿元", None),
        ("INVESTABLE_FINANCIAL_ASSET", "OTHER", r"可投资金融资产约?\s*([\d.]+)亿元", None),
        ("REAL_ESTATE", "REAL_ESTATE", r"不动产约?\s*([\d.]+)亿元", None),
        ("MOVABLE_ASSET", "OTHER", r"(?:动产|艺术品收藏|私人飞机、游艇)约?\s*([\d.]+)亿元", None),
        ("TOTAL_DEBT", "OTHER", r"(?:个人)?负债约?\s*([\d.]+)亿元", None),
        ("LIQUID_ASSET_RATIO", "CASH", r"流动性资产占比约?\s*([\d.]+)%", "PERCENT"),
    ]
    count = 0
    for category, asset_type, pattern, unit in patterns:
        match = re.search(pattern, raw)
        if not match:
            continue
        value = float(match.group(1))
        cursor.execute(
            """
            INSERT INTO financial_fact
              (person_id, fact_category, asset_type, amount, currency_code, percentage,
               estimate_flag, description, source_id, verification_status)
            VALUES (%s, %s, %s, %s, 'CNY', %s, TRUE, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, category, asset_type, None if unit == "PERCENT" else value,
             value if unit == "PERCENT" else None, raw, source_id),
        )
        count += 1
    return count


def parse_holdings(cursor, person_id: int, raw: str, source_id: int) -> int:
    type_map = [
        ("现金及活期", "CASH"), ("现金管理", "CASH"), ("固定收益", "FIXED_INCOME"),
        ("债券", "FIXED_INCOME"), ("权益", "FUND"), ("私募", "PRIVATE_EQUITY"),
        ("家族信托", "TRUST"), ("慈善信托", "TRUST"), ("不动产", "REAL_ESTATE"),
    ]
    count = 0
    for part in re.split(r"[；;]", raw):
        product_type = next((kind for keyword, kind in type_map if keyword in part), None)
        value = amount(part)
        if not product_type or value is None:
            continue
        cursor.execute(
            """
            INSERT INTO product_holding
              (person_id, product_type, amount, currency_code, holding_description,
               source_id, verification_status)
            VALUES (%s, %s, %s, 'CNY', %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, product_type, value, part.strip(), source_id),
        )
        count += 1
    return count


def parse_events(cursor, person_id: int, raw: str, source_id: int) -> int:
    type_map = [("减持", "DIVESTMENT"), ("赎回", "DIVESTMENT"), ("追加", "INVESTMENT"),
                ("投资", "INVESTMENT"), ("捐赠", "DONATION"), ("注入", "DONATION"),
                ("转配", "INVESTMENT")]
    count = 0
    for part in re.split(r"[；;]", raw):
        event_type = next((kind for keyword, kind in type_map if keyword in part), None)
        value = amount(part)
        if not event_type or value is None:
            continue
        cursor.execute(
            """
            INSERT INTO financial_event
              (person_id, event_type, amount, currency_code, event_description,
               source_id, verification_status)
            VALUES (%s, %s, %s, 'CNY', %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, event_type, value, part.strip(), source_id),
        )
        count += 1
    return count


def parse_services(cursor, person_id: int, raw: str, source_id: int) -> int:
    records = [("PRIVATE_BANKING", r"私银客户年限：?(\d+(?:\.\d+)?)年"),
               ("DEDICATED_MANAGER", r"专属客户经理服务：?(\d+(?:\.\d+)?)年")]
    count = 0
    for service_type, pattern in records:
        match = re.search(pattern, raw)
        if not match:
            continue
        cursor.execute(
            """
            INSERT INTO service_record
              (person_id, service_type, service_years, service_description,
               source_id, verification_status)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, service_type, float(match.group(1)), raw, source_id),
        )
        count += 1
    return count


def parse_notes(cursor, person_id: int, raw: str, source_id: int) -> int:
    count = 0
    for part in re.split(r"[；;]", raw):
        text = part.strip()
        if not text:
            continue
        note_type = "GENERAL_NOTE"
        if any(keyword in text for keyword in ("明确表示", "重点关注", "需要")):
            note_type = "EXPLICIT_NEED"
        elif any(keyword in text for keyword in ("偏好", "喜欢", "不喜", "重视")):
            note_type = "PREFERENCE"
        elif "沟通" in text:
            note_type = "COMMUNICATION_STYLE"
        cursor.execute(
            """
            INSERT INTO customer_interaction_note
              (person_id, note_type, note_text, is_explicit_expression,
               source_id, verification_status)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, note_type, text, note_type == "EXPLICIT_NEED", source_id),
        )
        count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse personal-extension fields for all staged persons.")
    parser.add_argument("--batch-id", type=int, default=4)
    parser.add_argument("--host", default=os.getenv("DB_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "3306")))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "private_bank_agent"))
    parser.add_argument("--user", default=os.getenv("DB_USER", "root"))
    parser.add_argument("--password-env", default="DB_PASSWORD")
    args = parser.parse_args()

    password = os.getenv(args.password_env) or getpass(f"MySQL 用户 {args.user} 的密码：")
    connection = pymysql.connect(host=args.host, port=args.port, user=args.user, password=password,
                                 database=args.database, charset="utf8mb4", autocommit=False,
                                 cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS row_count FROM person_career")
            if cursor.fetchone()["row_count"] > 0:
                raise RuntimeError("个人扩展字段已存在数据；脚本停止以避免重复写入。")
            cursor.execute(
                """
                SELECT s.stg_row_id, s.person_name, s.source_file_name, s.sheet_name,
                       s.source_row_number, s.raw_cells, p.person_id, s.core_enterprise_name
                FROM stg_import_row s JOIN person p ON p.full_name = s.person_name
                WHERE s.import_batch_id = %s AND s.data_dimension = 'PERSON'
                ORDER BY s.source_sequence
                """,
                (args.batch_id,),
            )
            rows = cursor.fetchall()
            if len(rows) != 30:
                raise RuntimeError(f"待解析个人记录为 {len(rows)} 条，预期为 30 条。")
            summary = {"careers": 0, "financial_facts": 0, "holdings": 0, "events": 0,
                       "service_records": 0, "interaction_notes": 0, "quality_issues": 0}
            for row in rows:
                cells = json.loads(row["raw_cells"])
                sources = source_map(cursor, args.batch_id, row)

                career_raw = cells["2.职业经历和公开身份"]
                career_source = sources["2.职业经历和公开身份"]
                title_match = re.search(r"(?:现任|任)([^；;]{0,40}(?:董事长|CEO|首席执行官|总裁|创始人|合伙人))", career_raw)
                cursor.execute(
                    """
                    INSERT INTO person_career
                      (person_id, organization_name, position_title, career_description,
                       source_id, verification_status)
                    VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
                    """,
                    (row["person_id"], row["core_enterprise_name"],
                     title_match.group(1).strip() if title_match else None, career_raw, career_source),
                )
                summary["careers"] += 1

                asset_source = sources["3.资产负债概况"]
                asset_count = parse_assets(cursor, row["person_id"], cells["3.资产负债概况"], asset_source)
                summary["financial_facts"] += asset_count
                if asset_count == 0:
                    add_quality_issue(cursor, args.batch_id, row["stg_row_id"], asset_source, "3.资产负债概况")
                    summary["quality_issues"] += 1

                holding_source = sources["4.产品持仓和到期情况"]
                holding_count = parse_holdings(cursor, row["person_id"], cells["4.产品持仓和到期情况"], holding_source)
                summary["holdings"] += holding_count
                if holding_count == 0:
                    add_quality_issue(cursor, args.batch_id, row["stg_row_id"], holding_source, "4.产品持仓和到期情况")
                    summary["quality_issues"] += 1

                event_source = sources["6.交易和大额资金变动"]
                event_count = parse_events(cursor, row["person_id"], cells["6.交易和大额资金变动"], event_source)
                summary["events"] += event_count
                if event_count == 0:
                    add_quality_issue(cursor, args.batch_id, row["stg_row_id"], event_source, "6.交易和大额资金变动")
                    summary["quality_issues"] += 1

                service_source = sources["7.历史服务记录"]
                service_count = parse_services(cursor, row["person_id"], cells["7.历史服务记录"], service_source)
                summary["service_records"] += service_count
                if service_count == 0:
                    add_quality_issue(cursor, args.batch_id, row["stg_row_id"], service_source, "7.历史服务记录")
                    summary["quality_issues"] += 1

                note_source = sources["8.客户经理纪要和客户明确表达"]
                summary["interaction_notes"] += parse_notes(cursor, row["person_id"], cells["8.客户经理纪要和客户明确表达"], note_source)
        connection.commit()
        print(json.dumps(summary, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
