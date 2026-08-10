"""Parse business, market, and event facts from ENTERPRISE staging rows."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass

import pymysql


BUSINESS_FIELD = "3.主营业务及产品服务"
CAPITAL_FIELD = "5.融资/担保/质押/资本运作"
MARKET_FIELD = "6.上下游及主要竞争对手"
EVENT_FIELD = "7.工商/司法/监管/经营事件"
POLICY_FIELD = "8.行业与政策资料"


def split_top_level(text: str, separators: str) -> list[str]:
    """Split only when not inside Chinese/ASCII parentheses."""
    parts: list[str] = []
    current: list[str] = []
    depth = 0
    for char in text:
        if char in "(（":
            depth += 1
        elif char in ")）" and depth:
            depth -= 1
        if char in separators and depth == 0:
            value = "".join(current).strip()
            if value:
                parts.append(value)
            current = []
        else:
            current.append(char)
    value = "".join(current).strip()
    if value:
        parts.append(value)
    return parts


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


def add_issue(cursor, batch_id: int, stg_row_id: int, source_id: int, field_name: str) -> None:
    cursor.execute(
        """
        INSERT INTO data_quality_issue
          (import_batch_id, stg_row_id, source_id, issue_type, severity, issue_message, issue_status)
        VALUES (%s, %s, %s, 'ENTERPRISE_FIELD_PARSE_EMPTY', 'MEDIUM', %s, 'OPEN')
        """,
        (batch_id, stg_row_id, source_id, f"未从 {field_name} 中识别出可结构化的企业维度记录。"),
    )


def parse_business(cursor, enterprise_id: int, raw: str, source_id: int) -> int:
    count = 0
    for segment in split_top_level(raw, "；;"):
        if "核心产品" in segment or "核心服务" in segment:
            line = "CORE_PRODUCTS"
            description = segment.split("：", 1)[-1].strip()
            cursor.execute(
                """
                INSERT INTO enterprise_business
                  (enterprise_id, business_line, business_description, source_id, verification_status)
                VALUES (%s, %s, %s, %s, 'PENDING_CONFIRMATION')
                """,
                (enterprise_id, line, description, source_id),
            )
            count += 1
            continue
        for item in split_top_level(segment, "、，,"):
            cursor.execute(
                """
                INSERT INTO enterprise_business
                  (enterprise_id, business_line, business_description, source_id, verification_status)
                VALUES (%s, %s, %s, %s, 'PENDING_CONFIRMATION')
                """,
                (enterprise_id, item[:255], item, source_id),
            )
            count += 1
    return count


def clean_counterpart(value: str) -> str:
    return re.sub(r"[（(].*?[）)]", "", value).strip(" 、，,。")


def parse_market(cursor, enterprise_id: int, raw: str, source_id: int) -> int:
    label_map = {"上游": "UPSTREAM", "下游": "DOWNSTREAM", "竞争对手": "COMPETITOR"}
    count = 0
    for segment in split_top_level(raw, "；;"):
        label = next((name for name in label_map if segment.startswith(f"{name}：") or segment.startswith(f"{name}:")), None)
        if not label:
            continue
        value = re.split(r"[：:]", segment, maxsplit=1)[1]
        for item in split_top_level(value, "、，,"):
            counterpart = clean_counterpart(item)
            if not counterpart:
                continue
            cursor.execute(
                """
                INSERT INTO enterprise_market_relation
                  (enterprise_id, counterpart_name, relation_type, relation_description,
                   source_id, verification_status)
                VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
                """,
                (enterprise_id, counterpart[:255], label_map[label], item, source_id),
            )
            count += 1
    return count


def event_type(text: str, source_kind: str) -> str:
    if source_kind == "POLICY":
        return "INDUSTRY_POLICY" if "政策导向" in text else "INDUSTRY_TREND"
    if any(word in text for word in ("处罚", "监管", "整改", "诉讼", "司法", "调查", "合规")):
        return "REGULATORY_OR_LEGAL"
    if any(word in text for word in ("高管", "董事长", "任董事会", "管理层", "连任")):
        return "CORPORATE_GOVERNANCE"
    if source_kind == "CAPITAL" or any(word in text for word in ("回购", "融资", "质押", "增发", "分拆", "投资", "收购", "并购", "担保", "分红")):
        return "CAPITAL_OPERATION"
    return "OPERATING_EVENT"


def event_risk_level(text: str) -> str:
    if any(word in text for word in ("处罚", "诉讼", "司法", "调查", "质押", "整改", "承压")):
        return "MEDIUM"
    return "PENDING_VERIFICATION"


def parse_events(cursor, enterprise_id: int, raw: str, source_id: int, source_kind: str) -> int:
    count = 0
    for item in split_top_level(raw, "；;"):
        if not item or item.startswith("无"):
            continue
        cursor.execute(
            """
            INSERT INTO enterprise_event
              (enterprise_id, event_type, risk_level, event_description, source_id, verification_status)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (enterprise_id, event_type(item, source_kind), event_risk_level(item), item, source_id),
        )
        count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse enterprise extension fields from staging rows.")
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
            for table in ("enterprise_business", "enterprise_market_relation", "enterprise_event"):
                cursor.execute(f"SELECT COUNT(*) AS row_count FROM {table}")
                if cursor.fetchone()["row_count"]:
                    raise RuntimeError(f"{table} 已存在数据；为避免重复写入，脚本停止执行。")
            cursor.execute(
                """
                SELECT s.stg_row_id, s.source_file_name, s.sheet_name, s.source_row_number,
                       s.raw_cells, e.enterprise_id
                FROM stg_import_row s
                JOIN enterprise e ON e.enterprise_name = s.core_enterprise_name
                WHERE s.import_batch_id = %s AND s.data_dimension = 'ENTERPRISE'
                ORDER BY s.source_sequence
                """,
                (args.batch_id,),
            )
            rows = cursor.fetchall()
            if len(rows) != 30:
                raise RuntimeError(f"待解析企业记录为 {len(rows)} 条，预期为 30 条。")
            summary = {"businesses": 0, "market_relations": 0, "events": 0, "quality_issues": 0}
            for row in rows:
                cells = json.loads(row["raw_cells"])
                sources = source_map(cursor, args.batch_id, row)
                business_count = parse_business(cursor, row["enterprise_id"], cells[BUSINESS_FIELD], sources[BUSINESS_FIELD])
                summary["businesses"] += business_count
                if not business_count:
                    add_issue(cursor, args.batch_id, row["stg_row_id"], sources[BUSINESS_FIELD], BUSINESS_FIELD)
                    summary["quality_issues"] += 1
                market_count = parse_market(cursor, row["enterprise_id"], cells[MARKET_FIELD], sources[MARKET_FIELD])
                summary["market_relations"] += market_count
                if not market_count:
                    add_issue(cursor, args.batch_id, row["stg_row_id"], sources[MARKET_FIELD], MARKET_FIELD)
                    summary["quality_issues"] += 1
                event_count = 0
                for field, source_kind in ((CAPITAL_FIELD, "CAPITAL"), (EVENT_FIELD, "EVENT"), (POLICY_FIELD, "POLICY")):
                    parsed = parse_events(cursor, row["enterprise_id"], cells[field], sources[field], source_kind)
                    event_count += parsed
                    if not parsed:
                        add_issue(cursor, args.batch_id, row["stg_row_id"], sources[field], field)
                        summary["quality_issues"] += 1
                summary["events"] += event_count
        connection.commit()
        print(json.dumps(summary, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
