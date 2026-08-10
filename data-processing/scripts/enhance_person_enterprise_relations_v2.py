"""Resolve open core-relation quality issues using person career and enterprise ownership evidence."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass

import pymysql


def ownership_ratio(text: str, person: str) -> float | None:
    match = re.search(re.escape(person) + r"持股约?\s*(\d+(?:\.\d+)?)%", text)
    return float(match.group(1)) if match else None


def voting_ratio(text: str) -> float | None:
    match = re.search(r"(?:拥有约?|投票权占比约?)(\d+(?:\.\d+)?)%投票权?", text)
    return float(match.group(1)) if match else None


def relation_type(career: str, registration: str, ownership: str) -> tuple[str, str] | None:
    evidence = "；".join([career, registration, ownership])
    if "创办" in evidence or "创始人" in evidence:
        return "FOUNDER", "创始人"
    if "董事长" in evidence:
        return "CHAIRPERSON", "董事长"
    if "实际控制人" in evidence or "绝对控制" in evidence:
        return "CONTROLLING_SHAREHOLDER", "实际控制人"
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Resolve evidence-backed core enterprise relations.")
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
            cursor.execute(
                """
                SELECT q.data_quality_issue_id, s.person_name, s.raw_cells AS person_raw_cells,
                       se.source_file_name, se.sheet_name, se.source_row_number, se.raw_cells AS enterprise_raw_cells,
                       p.person_id, e.enterprise_id, per.person_enterprise_relation_id
                FROM data_quality_issue q
                JOIN stg_import_row s ON s.stg_row_id = q.stg_row_id
                JOIN stg_import_row se ON se.import_batch_id = s.import_batch_id
                  AND se.person_name = s.person_name AND se.data_dimension = 'ENTERPRISE'
                JOIN person p ON p.full_name = s.person_name
                JOIN enterprise e ON e.enterprise_name = s.core_enterprise_name
                JOIN person_enterprise_relation per
                  ON per.person_id = p.person_id AND per.enterprise_id = e.enterprise_id
                 AND per.is_core_relation = TRUE
                WHERE q.import_batch_id = %s
                  AND q.issue_type = 'CORE_RELATION_NEEDS_CONFIRMATION'
                  AND q.issue_status = 'OPEN'
                ORDER BY q.data_quality_issue_id
                """,
                (args.batch_id,),
            )
            rows = cursor.fetchall()
            if not rows:
                raise RuntimeError("未找到待增强的核心关系问题。")

            summary = {"relations_updated": 0, "control_relations_added": 0, "issues_resolved": 0, "issues_remaining": 0}
            for row in rows:
                person_cells = json.loads(row["person_raw_cells"])
                enterprise_cells = json.loads(row["enterprise_raw_cells"])
                career = person_cells.get("2.职业经历和公开身份", "")
                registration = enterprise_cells.get("1.工商注册信息", "")
                ownership = enterprise_cells.get("2.股权结构与实际控制关系", "")
                role = relation_type(career, registration, ownership)
                if role is None:
                    summary["issues_remaining"] += 1
                    continue

                cursor.execute(
                    """
                    SELECT source_id FROM source_document
                    WHERE import_batch_id = %s AND file_name = %s AND sheet_name = %s
                      AND source_row_number = %s AND column_name = '2.股权结构与实际控制关系'
                    """,
                    (args.batch_id, row["source_file_name"], row["sheet_name"], row["source_row_number"]),
                )
                evidence = cursor.fetchone()
                if evidence is None:
                    raise RuntimeError(f"缺少股权关系证据：{row['person_name']}")

                source_id = evidence["source_id"]
                relation, title = role
                share = ownership_ratio(ownership, row["person_name"])
                voting = voting_ratio(ownership)
                cursor.execute(
                    """
                    UPDATE person_enterprise_relation
                    SET relation_type = %s, title = %s, ownership_percentage = %s,
                        voting_right_percentage = %s, source_id = %s, raw_text = %s,
                        verification_status = 'PENDING_CONFIRMATION'
                    WHERE person_enterprise_relation_id = %s
                    """,
                    (relation, title, share, voting, source_id, ownership, row["person_enterprise_relation_id"]),
                )
                summary["relations_updated"] += 1

                has_control = any(token in ownership for token in ("实际控制人", "绝对控制", "控制公司", "控制权"))
                if has_control and relation != "CONTROLLING_SHAREHOLDER":
                    cursor.execute(
                        """
                        SELECT COUNT(*) AS relation_count FROM person_enterprise_relation
                        WHERE person_id = %s AND enterprise_id = %s
                          AND relation_type = 'CONTROLLING_SHAREHOLDER'
                        """,
                        (row["person_id"], row["enterprise_id"]),
                    )
                    if cursor.fetchone()["relation_count"] == 0:
                        cursor.execute(
                            """
                            INSERT INTO person_enterprise_relation
                              (person_id, enterprise_id, relation_type, ownership_percentage,
                               voting_right_percentage, is_core_relation, source_id, source_level,
                               verification_status, raw_text)
                            VALUES (%s, %s, 'CONTROLLING_SHAREHOLDER', %s, %s, FALSE, %s, 'S0',
                                    'PENDING_CONFIRMATION', %s)
                            """,
                            (row["person_id"], row["enterprise_id"], share, voting, source_id, ownership),
                        )
                        summary["control_relations_added"] += 1

                cursor.execute(
                    """
                    UPDATE data_quality_issue
                    SET issue_status = 'RESOLVED', resolved_by = 'relation_parser_v2',
                        resolved_at = NOW(), resolution_note = %s
                    WHERE data_quality_issue_id = %s
                    """,
                    (f"已依据职业经历与股权文本识别为 {relation}：{row['person_name']}", row["data_quality_issue_id"]),
                )
                summary["issues_resolved"] += 1
        connection.commit()
        print(json.dumps(summary, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
