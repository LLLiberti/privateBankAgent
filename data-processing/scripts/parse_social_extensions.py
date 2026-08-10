"""Parse organizations, social activities, reputation, and risk from SOCIAL staging rows."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass

import pymysql


ROLE_FIELD = "1.社会职务和公开身份"
ORG_FIELD = "2.商会、协会、校友和行业组织信息"
CHARITY_FIELD = "3.公益慈善和ESG实践"
RESEARCH_FIELD = "4.产学研合作"
REPUTATION_FIELD = "5.荣誉、公开评价和媒体关注"
RISK_FIELD = "6.舆情及声誉风险线索"

ROLE_MARKERS = [
    "董事会主席", "理事会主席", "联合主席", "名誉会长", "副理事长", "副主席",
    "董事会成员", "副会长", "会长", "主席", "副理事", "理事", "校董",
    "代表", "顾问", "发起人", "董事", "成员",
]


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
        VALUES (%s, %s, %s, 'SOCIAL_FIELD_PARSE_EMPTY', 'MEDIUM', %s, 'OPEN')
        """,
        (batch_id, stg_row_id, source_id, f"未从 {field_name} 中识别出可结构化的社会维度记录。"),
    )


def normalized(text: str) -> str:
    return re.sub(r"[\s（）()、，,；;：:]", "", text).lower()[:255]


def organization_type(name: str) -> str:
    if "基金会" in name:
        return "FOUNDATION"
    if any(word in name for word in ("大学", "学院", "实验室", "研究院", "中科院", "达摩院")):
        return "ACADEMIC"
    if any(word in name for word in ("协会", "商会", "俱乐部", "联盟", "总会", "论坛")):
        return "INDUSTRY_ASSOCIATION"
    if any(word in name for word in ("人大", "联合国", "政府")):
        return "PUBLIC_INSTITUTION"
    return "OTHER"


def get_organization_id(cursor, name: str) -> int:
    cursor.execute(
        """
        INSERT INTO social_organization (organization_name, organization_type, normalized_name)
        VALUES (%s, %s, %s)
        ON DUPLICATE KEY UPDATE social_organization_id = LAST_INSERT_ID(social_organization_id)
        """,
        (name[:255], organization_type(name), normalized(name)),
    )
    return cursor.lastrowid


def organization_and_role(text: str) -> tuple[str, str | None]:
    for marker in ROLE_MARKERS:
        index = text.rfind(marker)
        if index > 0:
            return text[:index].strip("（）() "), marker
    return text.strip(), None


def parse_relations(cursor, person_id: int, raw: str, source_id: int, relation_type: str, seen: set[tuple[int, str, str | None]]) -> int:
    count = 0
    for part in re.split(r"[；;]", raw):
        text = part.strip()
        if not text:
            continue
        name, role = organization_and_role(text)
        if not name:
            continue
        key = (person_id, normalized(name), role)
        if key in seen:
            continue
        organization_id = get_organization_id(cursor, name)
        cursor.execute(
            """
            INSERT INTO person_social_relation
              (person_id, social_organization_id, relation_type, role_title, source_id,
               verification_status, raw_text)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION', %s)
            """,
            (person_id, organization_id, relation_type, role, source_id, text),
        )
        seen.add(key)
        count += 1
    return count


def parse_activities(cursor, person_id: int, raw: str, source_id: int, activity_type: str) -> int:
    count = 0
    for part in re.split(r"[；;]", raw):
        text = part.strip()
        if not text:
            continue
        effective_type = activity_type
        if activity_type == "CHARITY_ESG" and any(word in text for word in ("ESG", "碳中和", "碳排放")):
            effective_type = "ESG"
        cursor.execute(
            """
            INSERT INTO social_activity
              (person_id, activity_type, activity_name, activity_description, source_id,
               verification_status)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, effective_type, text[:255], text, source_id),
        )
        count += 1
    return count


def reputation_type(text: str) -> str:
    if any(word in text for word in ("媒体形象", "媒体关注", "公众视野")):
        return "MEDIA_ATTENTION"
    return "HONOR_OR_PUBLIC_EVALUATION"


def publisher(text: str) -> str | None:
    if "时代" in text:
        return "TIME"
    if "福布斯" in text:
        return "FORBES"
    if "CCTV" in text:
        return "CCTV"
    return None


def parse_reputation(cursor, person_id: int, raw: str, source_id: int) -> int:
    count = 0
    for part in re.split(r"[；;]", raw):
        text = part.strip()
        if not text:
            continue
        cursor.execute(
            """
            INSERT INTO public_reputation
              (person_id, reputation_type, title, publisher_name, description, source_id,
               verification_status)
            VALUES (%s, %s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, reputation_type(text), text[:500], publisher(text), text, source_id),
        )
        count += 1
    return count


def risk_level(text: str) -> str:
    if any(word in text for word in ("处罚", "调查", "诉讼", "被叫停", "数据安全", "反垄断")):
        return "MEDIUM"
    return "PENDING_VERIFICATION"


def parse_risks(cursor, person_id: int, raw: str, source_id: int) -> int:
    count = 0
    for part in re.split(r"[；;]", raw):
        text = part.strip()
        if not text:
            continue
        cursor.execute(
            """
            INSERT INTO reputation_risk
              (person_id, risk_topic, risk_level, risk_description, source_id, verification_status)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, text[:255], risk_level(text), text, source_id),
        )
        count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse social extension fields from staging rows.")
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
            for table in ("social_organization", "person_social_relation", "social_activity", "public_reputation", "reputation_risk"):
                cursor.execute(f"SELECT COUNT(*) AS row_count FROM {table}")
                if cursor.fetchone()["row_count"]:
                    raise RuntimeError(f"{table} 已存在数据；为避免重复写入，脚本停止执行。")
            cursor.execute(
                """
                SELECT s.stg_row_id, s.source_file_name, s.sheet_name, s.source_row_number,
                       s.raw_cells, p.person_id
                FROM stg_import_row s
                JOIN person p ON p.full_name = s.person_name
                WHERE s.import_batch_id = %s AND s.data_dimension = 'SOCIAL'
                ORDER BY s.source_sequence
                """,
                (args.batch_id,),
            )
            rows = cursor.fetchall()
            if len(rows) != 30:
                raise RuntimeError(f"待解析社会记录为 {len(rows)} 条，预期为 30 条。")
            summary = {"social_relations": 0, "social_activities": 0, "reputations": 0, "reputation_risks": 0, "quality_issues": 0}
            for row in rows:
                cells = json.loads(row["raw_cells"])
                sources = source_map(cursor, args.batch_id, row)
                seen: set[tuple[int, str, str | None]] = set()
                relation_count = parse_relations(cursor, row["person_id"], cells[ROLE_FIELD], sources[ROLE_FIELD], "PUBLIC_ROLE", seen)
                relation_count += parse_relations(cursor, row["person_id"], cells[ORG_FIELD], sources[ORG_FIELD], "ORGANIZATION_MEMBERSHIP", seen)
                summary["social_relations"] += relation_count
                if not relation_count:
                    add_issue(cursor, args.batch_id, row["stg_row_id"], sources[ROLE_FIELD], ROLE_FIELD)
                    summary["quality_issues"] += 1
                for field, activity_type in ((CHARITY_FIELD, "CHARITY_ESG"), (RESEARCH_FIELD, "RESEARCH_COLLABORATION")):
                    count = parse_activities(cursor, row["person_id"], cells[field], sources[field], activity_type)
                    summary["social_activities"] += count
                    if not count:
                        add_issue(cursor, args.batch_id, row["stg_row_id"], sources[field], field)
                        summary["quality_issues"] += 1
                reputation_count = parse_reputation(cursor, row["person_id"], cells[REPUTATION_FIELD], sources[REPUTATION_FIELD])
                summary["reputations"] += reputation_count
                if not reputation_count:
                    add_issue(cursor, args.batch_id, row["stg_row_id"], sources[REPUTATION_FIELD], REPUTATION_FIELD)
                    summary["quality_issues"] += 1
                risk_count = parse_risks(cursor, row["person_id"], cells[RISK_FIELD], sources[RISK_FIELD])
                summary["reputation_risks"] += risk_count
                if not risk_count:
                    add_issue(cursor, args.batch_id, row["stg_row_id"], sources[RISK_FIELD], RISK_FIELD)
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
