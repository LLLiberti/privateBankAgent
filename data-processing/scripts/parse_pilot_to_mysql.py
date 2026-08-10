"""Parse a controlled three-person staging pilot into standard MySQL tables."""

from __future__ import annotations

import argparse
import json
import os
import re
from datetime import date
from getpass import getpass
from typing import Any

import pymysql


PILOT_PERSONS = ("马化腾", "马云", "雷军")


def parse_key_values(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for item in re.split(r"[；;]\s*", text):
        if "：" in item:
            key, value = item.split("：", 1)
            result[key.strip()] = value.strip()
    return result


def parse_date(value: str | None) -> date | None:
    if not value:
        return None
    match = re.search(r"(\d{4})[-年](\d{1,2})[-月](\d{1,2})", value)
    if not match:
        return None
    return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))


def parse_year(value: str | None) -> int | None:
    if not value:
        return None
    match = re.search(r"(19|20)\d{2}", value)
    return int(match.group(0)) if match else None


def parse_decimal(value: str | None) -> float | None:
    if not value:
        return None
    match = re.search(r"([\d,]+(?:\.\d+)?)", value)
    return float(match.group(1).replace(",", "")) if match else None


def parse_stock_code(enterprise_name: str) -> str | None:
    match = re.search(r"[（(]([0-9]{4,6}\.[A-Za-z]+)[）)]", enterprise_name)
    return match.group(1).upper() if match else None


def normalized(value: str) -> str:
    return re.sub(r"\s+", "", value).lower()


def source_id(cursor: Any, batch_id: int, row: dict[str, Any], column_name: str) -> int:
    cursor.execute(
        """
        SELECT source_id FROM source_document
        WHERE import_batch_id = %s AND file_name = %s AND sheet_name = %s
          AND source_row_number = %s AND column_name = %s
        """,
        (batch_id, row["source_file_name"], row["sheet_name"], row["source_row_number"], column_name),
    )
    found = cursor.fetchone()
    if found is None:
        raise RuntimeError(f"未找到证据：{row['person_name']} / {column_name}")
    return found[0]


def staging_row(cursor: Any, batch_id: int, person_name: str, dimension: str) -> dict[str, Any]:
    cursor.execute(
        """
        SELECT source_file_name, sheet_name, source_row_number, person_name,
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
        "source_file_name": row[0],
        "sheet_name": row[1],
        "source_row_number": row[2],
        "person_name": row[3],
        "core_enterprise_name": row[4],
        "raw_cells": json.loads(row[5]),
    }


def pilot_exists(cursor: Any, names: tuple[str, ...]) -> bool:
    placeholders = ",".join(["%s"] * len(names))
    cursor.execute(f"SELECT COUNT(*) FROM person WHERE full_name IN ({placeholders})", names)
    return cursor.fetchone()[0] > 0


def insert_person(cursor: Any, row: dict[str, Any]) -> int:
    name = row["person_name"]
    cursor.execute(
        "INSERT INTO person (full_name, normalized_name, verification_status) VALUES (%s, %s, 'UNVERIFIED')",
        (name, normalized(name)),
    )
    return cursor.lastrowid


def insert_enterprise(cursor: Any, row: dict[str, Any], source: int) -> int:
    values = parse_key_values(row["raw_cells"]["1.工商注册信息"])
    enterprise_name = row["core_enterprise_name"]
    cursor.execute(
        """
        INSERT INTO enterprise
          (enterprise_name, normalized_name, stock_code, registration_date, registration_place,
           listing_date, headquarters, employee_count, industry_name, verification_status)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'UNVERIFIED')
        """,
        (
            enterprise_name,
            normalized(enterprise_name),
            parse_stock_code(enterprise_name),
            parse_date(values.get("成立日期")),
            values.get("注册地"),
            parse_date(values.get("上市日期")),
            values.get("总部") or values.get("办公地址"),
            int(parse_decimal(values.get("员工数"))) if parse_decimal(values.get("员工数")) else None,
            values.get("所属行业"),
        ),
    )
    return cursor.lastrowid


def insert_profile(cursor: Any, person_id: int, row: dict[str, Any], source: int) -> None:
    values = parse_key_values(row["raw_cells"]["1.客户基本信息"])
    cursor.execute(
        """
        INSERT INTO person_profile
          (person_id, gender, birth_date, birth_year, native_place, birth_place, marital_status,
           education_level, school_name, residence, health_summary, source_id, verification_status)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'UNVERIFIED')
        """,
        (
            person_id, values.get("性别"), parse_date(values.get("出生年月")),
            parse_year(values.get("出生年月")), values.get("籍贯"), values.get("出生地"),
            values.get("婚姻状况"), values.get("学历"), values.get("毕业院校"),
            values.get("常住地"), values.get("健康状况"), source,
        ),
    )


def insert_risk_preference(cursor: Any, person_id: int, row: dict[str, Any], source: int) -> None:
    raw = row["raw_cells"]["5.风险偏好"]
    risk_match = re.search(r"[（(](C[1-5])[）)]", raw)
    drawdown_match = re.search(r"最大回撤约?\s*(\d+(?:\.\d+)?)%", raw)
    horizon_match = re.search(r"投资期限偏好([^；;]+)", raw)
    liquidity_match = re.search(r"对流动性要求([^；;]+)", raw)
    actual_match = re.search(r"实际投资偏([^；;]+)", raw)
    cursor.execute(
        """
        INSERT INTO risk_preference
          (person_id, risk_level, actual_preference, max_drawdown, investment_horizon,
           liquidity_requirement, preference_description, source_id, verification_status)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'UNVERIFIED')
        """,
        (
            person_id, risk_match.group(1) if risk_match else "UNKNOWN",
            actual_match.group(1).strip() if actual_match else None,
            float(drawdown_match.group(1)) if drawdown_match else None,
            horizon_match.group(1).strip() if horizon_match else None,
            liquidity_match.group(1).strip() if liquidity_match else None,
            raw, source,
        ),
    )


def insert_relation(cursor: Any, person_id: int, enterprise_id: int, source: int, person_name: str, enterprise_row: dict[str, Any]) -> None:
    registration = enterprise_row["raw_cells"]["1.工商注册信息"]
    relation_type = "CHAIRPERSON" if f"董事长：{person_name}" in registration else "CORE_ASSOCIATED"
    cursor.execute(
        """
        INSERT INTO person_enterprise_relation
          (person_id, enterprise_id, relation_type, is_core_relation, source_id, source_level,
           verification_status, raw_text)
        VALUES (%s, %s, %s, TRUE, %s, 'S0', 'UNVERIFIED', %s)
        """,
        (person_id, enterprise_id, relation_type, source, registration),
    )


def insert_financial_metrics(cursor: Any, enterprise_id: int, row: dict[str, Any], source: int) -> int:
    raw = row["raw_cells"]["4.核心财务数据(2025年报)"]
    metrics = {
        "TOTAL_REVENUE": r"总营收\s*([\d.]+)亿元",
        "NET_PROFIT": r"归母净利润\s*([\d.]+)亿元",
        "GROSS_MARGIN": r"毛利率\s*([\d.]+)%",
        "NET_MARGIN": r"净利率\s*([\d.]+)%",
        "TOTAL_ASSETS": r"总资产\s*([\d.]+)亿元",
        "TOTAL_LIABILITIES": r"总负债\s*([\d.]+)亿元",
        "OPERATING_CASH_FLOW": r"经营现金流\s*([\d.]+)亿元",
    }
    inserted = 0
    for name, pattern in metrics.items():
        match = re.search(pattern, raw)
        if not match:
            continue
        unit = "PERCENT" if "MARGIN" in name else "CNY_100M"
        cursor.execute(
            """
            INSERT INTO enterprise_financial_metric
              (enterprise_id, reporting_period, metric_name, metric_value, unit_name, source_id, verification_status)
            VALUES (%s, '2025', %s, %s, %s, %s, 'UNVERIFIED')
            """,
            (enterprise_id, name, float(match.group(1)), unit, source),
        )
        inserted += 1
    return inserted


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse the three-person structured-data pilot.")
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
            if pilot_exists(cursor, PILOT_PERSONS):
                raise RuntimeError("试点人物已经存在于标准表；为避免重复写入，脚本已停止。")

            summary = {"persons": 0, "enterprises": 0, "profiles": 0, "risk_preferences": 0, "relations": 0, "financial_metrics": 0}
            for name in PILOT_PERSONS:
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
                summary["financial_metrics"] += insert_financial_metrics(cursor, enterprise_id, enterprise_row, metric_source)
                summary["persons"] += 1
                summary["enterprises"] += 1
                summary["profiles"] += 1
                summary["risk_preferences"] += 1
                summary["relations"] += 1
        connection.commit()
        print(json.dumps(summary, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
