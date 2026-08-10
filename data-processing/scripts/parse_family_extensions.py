"""Parse family members and succession-related arrangements from FAMILY staging rows."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass

import pymysql


MEMBER_FIELD = "1.经授权或经人工确认的家庭成员"
RELATION_FIELD = "2.家庭关系"
EDUCATION_FIELD = "3.子女教育和家庭保障信息"
SUCCESSION_FIELD = "4.家族企业接班信息"
WEALTH_FIELD = "5.家庭资产和传承相关信息"

RELATION_MAP = {
    "父亲": "FATHER", "母亲": "MOTHER", "妻子": "SPOUSE", "丈夫": "SPOUSE",
    "儿子": "SON", "女儿": "DAUGHTER", "姐姐": "SIBLING", "妹妹": "SIBLING",
    "哥哥": "SIBLING", "弟弟": "SIBLING", "兄弟": "SIBLING", "姐妹": "SIBLING",
}


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
        VALUES (%s, %s, %s, 'FAMILY_FIELD_PARSE_EMPTY', 'MEDIUM', %s, 'OPEN')
        """,
        (batch_id, stg_row_id, source_id, f"未从 {field_name} 中识别出可结构化的家庭维度记录。"),
    )


def member_identity(relation_type: str, payload: str, ordinal: int) -> tuple[str | None, str | None]:
    candidate = re.split(r"[（(，,]", payload, maxsplit=1)[0].strip()
    if not candidate or candidate in {"1人", "一人"} or "未公开" in candidate or "信息较少" in candidate:
        return None, f"{relation_type}_{ordinal}"
    return candidate[:128], None


def parse_members(cursor, person_id: int, raw: str, source_id: int) -> tuple[int, int]:
    member_count = 0
    relation_count = 0
    ordinal_by_type: dict[str, int] = {}
    for item in re.split(r"[；;]", raw):
        item = item.strip()
        if not item or "：" not in item:
            continue
        label, payload = item.split("：", 1)
        relation_type = RELATION_MAP.get(label.strip())
        if not relation_type:
            continue
        ordinal_by_type[relation_type] = ordinal_by_type.get(relation_type, 0) + 1
        member_name, protected_alias = member_identity(relation_type, payload, ordinal_by_type[relation_type])
        cursor.execute(
            """
            INSERT INTO family_member
              (person_id, member_name, protected_alias, public_disclosure_level, member_description,
               source_id, verification_status)
            VALUES (%s, %s, %s, 'RESTRICTED', %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, member_name, protected_alias, item, source_id),
        )
        family_member_id = cursor.lastrowid
        cursor.execute(
            """
            INSERT INTO person_family_relation
              (person_id, family_member_id, relation_type, relation_description,
               source_id, verification_status)
            VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
            """,
            (person_id, family_member_id, relation_type, item, source_id),
        )
        member_count += 1
        relation_count += 1
    return member_count, relation_count


def succession_status(raw: str) -> str:
    if any(word in raw for word in ("暂无明确", "无明确", "暂未", "未进入")):
        return "NO_FORMAL_SUCCESSOR"
    if any(word in raw for word in ("已接班", "已确定", "明确接班")):
        return "SUCCESSOR_IDENTIFIED"
    return "PENDING_CONFIRMATION"


def governance_model(raw: str, arrangement_kind: str) -> str | None:
    if arrangement_kind == "FAMILY_PROTECTION_AND_EDUCATION":
        if "教育信托" in raw:
            return "EDUCATION_TRUST_AND_INSURANCE"
        if "保险" in raw:
            return "INSURANCE_PROTECTION"
    if arrangement_kind == "WEALTH_SUCCESSION":
        if "家族信托" in raw or "信托" in raw:
            return "FAMILY_TRUST"
        if "遗嘱" in raw:
            return "WILL_AND_INHERITANCE_PLAN"
    if arrangement_kind == "CORPORATE_SUCCESSION":
        if "职业经理人" in raw:
            return "PROFESSIONAL_MANAGER_GOVERNANCE"
        if "合伙人制度" in raw:
            return "PARTNERSHIP_GOVERNANCE"
        if "家族" in raw:
            return "FAMILY_GOVERNANCE"
    return None


def add_arrangement(cursor, person_id: int, enterprise_id: int | None, kind: str, raw: str, source_id: int) -> None:
    status = succession_status(raw) if kind == "CORPORATE_SUCCESSION" else kind
    cursor.execute(
        """
        INSERT INTO succession_arrangement
          (person_id, enterprise_id, arrangement_status, governance_model, arrangement_description,
           source_id, verification_status)
        VALUES (%s, %s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
        """,
        (person_id, enterprise_id, status, governance_model(raw, kind), raw, source_id),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse family extension fields from staging rows.")
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
            for table in ("family_member", "person_family_relation", "succession_arrangement"):
                cursor.execute(f"SELECT COUNT(*) AS row_count FROM {table}")
                if cursor.fetchone()["row_count"]:
                    raise RuntimeError(f"{table} 已存在数据；为避免重复写入，脚本停止执行。")
            cursor.execute(
                """
                SELECT s.stg_row_id, s.source_file_name, s.sheet_name, s.source_row_number,
                       s.raw_cells, p.person_id, e.enterprise_id
                FROM stg_import_row s
                JOIN person p ON p.full_name = s.person_name
                LEFT JOIN enterprise e ON e.enterprise_name = s.core_enterprise_name
                WHERE s.import_batch_id = %s AND s.data_dimension = 'FAMILY'
                ORDER BY s.source_sequence
                """,
                (args.batch_id,),
            )
            rows = cursor.fetchall()
            if len(rows) != 30:
                raise RuntimeError(f"待解析家庭记录为 {len(rows)} 条，预期为 30 条。")
            summary = {"family_members": 0, "family_relations": 0, "arrangements": 0, "quality_issues": 0}
            for row in rows:
                cells = json.loads(row["raw_cells"])
                sources = source_map(cursor, args.batch_id, row)
                members, relations = parse_members(cursor, row["person_id"], cells[MEMBER_FIELD], sources[MEMBER_FIELD])
                summary["family_members"] += members
                summary["family_relations"] += relations
                if not members:
                    add_issue(cursor, args.batch_id, row["stg_row_id"], sources[MEMBER_FIELD], MEMBER_FIELD)
                    summary["quality_issues"] += 1
                for field, kind, enterprise_id in (
                    (EDUCATION_FIELD, "FAMILY_PROTECTION_AND_EDUCATION", None),
                    (SUCCESSION_FIELD, "CORPORATE_SUCCESSION", row["enterprise_id"]),
                    (WEALTH_FIELD, "WEALTH_SUCCESSION", None),
                ):
                    raw = cells[field]
                    if raw.strip():
                        add_arrangement(cursor, row["person_id"], enterprise_id, kind, raw, sources[field])
                        summary["arrangements"] += 1
                    else:
                        add_issue(cursor, args.batch_id, row["stg_row_id"], sources[field], field)
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
