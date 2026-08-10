"""Enhance parsing of enterprise financial text with approximate and multi-unit patterns."""

from __future__ import annotations

import argparse
import json
import os
import re
from getpass import getpass
from typing import Any

import pymysql


def amount_in_100m(number: str, unit: str) -> tuple[float, str]:
    value = float(number)
    if unit == "万亿元":
        return value * 10000, "CNY_100M"
    if unit == "亿元":
        return value, "CNY_100M"
    if unit == "万亿美元":
        return value * 10000, "USD_100M"
    if unit == "亿美元":
        return value, "USD_100M"
    raise ValueError(f"不支持的金额单位：{unit}")


def reporting_period(raw: str, position: int) -> str:
    prefix = raw[max(0, position - 36):position]
    years = re.findall(r"(20\d{2})年", prefix)
    return years[-1] if years else "2025"


def extract_amount_metrics(raw: str) -> list[tuple[str, str, float, str]]:
    # metric name, reporting period, value, unit
    patterns = [
        ("TOTAL_PREMIUM_INCOME", r"总保费收入(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("TOTAL_REVENUE", r"(?:总营收|营收)(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("NET_PROFIT", r"归母净利润(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("NET_PROFIT", r"(?<!归母)净利润(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("NET_PROFIT", r"净亏损(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("TOTAL_ASSETS", r"总资产(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("TOTAL_LIABILITIES", r"总负债(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("OPERATING_CASH_FLOW", r"经营现金流(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("CASH_RESERVES", r"现金储备(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("AUM", r"管理资产规模(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("OPERATING_PROFIT", r"经营利润(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
        ("RND_EXPENSE", r"研发投入(?:约|超|估算)?\s*([+-]?\d+(?:\.\d+)?)\s*(万亿美元|亿美元|万亿元|亿元)"),
    ]
    extracted: list[tuple[str, str, float, str]] = []
    seen: set[tuple[str, str]] = set()
    for metric_name, pattern in patterns:
        for match in re.finditer(pattern, raw):
            value, unit_name = amount_in_100m(match.group(1), match.group(2))
            if "净亏损" in match.group(0):
                value = -abs(value)
            key = (reporting_period(raw, match.start()), metric_name)
            if key not in seen:
                extracted.append((metric_name, key[0], value, unit_name))
                seen.add(key)
    return extracted


def extract_percent_metrics(raw: str) -> list[tuple[str, str, float, str]]:
    patterns = [
        ("GROSS_MARGIN", r"毛利率(?:约|超)?\s*([+-]?\d+(?:\.\d+)?)%"),
        ("NET_MARGIN", r"净利率(?:约|超)?\s*([+-]?\d+(?:\.\d+)?)%"),
        ("RND_RATIO", r"研发投入占比(?:约|超)?\s*([+-]?\d+(?:\.\d+)?)%"),
        ("OVERSEAS_REVENUE_RATIO", r"海外收入占比(?:约|超)?\s*([+-]?\d+(?:\.\d+)?)%"),
        ("INNOVATIVE_DRUG_REVENUE_RATIO", r"创新药收入占比(?:约|超)?\s*([+-]?\d+(?:\.\d+)?)%"),
    ]
    return [(name, reporting_period(raw, match.start()), float(match.group(1)), "PERCENT")
            for name, pattern in patterns for match in re.finditer(pattern, raw)]


def main() -> None:
    parser = argparse.ArgumentParser(description="Enhance enterprise financial metric parsing.")
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
            cursor.execute(
                """
                SELECT q.data_quality_issue_id, q.stg_row_id, q.source_id, s.person_name,
                       s.core_enterprise_name, s.raw_cells, e.enterprise_id
                FROM data_quality_issue q
                JOIN stg_import_row s ON s.stg_row_id = q.stg_row_id
                JOIN enterprise e ON e.enterprise_name = s.core_enterprise_name
                WHERE q.import_batch_id = %s
                  AND q.issue_type = 'FINANCIAL_METRIC_PARSE_EMPTY'
                  AND q.issue_status = 'OPEN'
                ORDER BY q.data_quality_issue_id
                """,
                (args.batch_id,),
            )
            rows = cursor.fetchall()
            if not rows:
                raise RuntimeError("未找到待增强解析的财务数据质量问题。")

            summary = {"companies": len(rows), "metrics_upserted": 0, "issues_resolved": 0, "issues_remaining": 0}
            for issue_id, stg_row_id, source_id, person_name, enterprise_name, raw_cells, enterprise_id in rows:
                raw = json.loads(raw_cells)["4.核心财务数据(2025年报)"]
                metrics = extract_amount_metrics(raw) + extract_percent_metrics(raw)
                if not metrics:
                    summary["issues_remaining"] += 1
                    continue
                for metric_name, period, value, unit in metrics:
                    cursor.execute(
                        """
                        INSERT INTO enterprise_financial_metric
                          (enterprise_id, reporting_period, metric_name, metric_value, unit_name, source_id, verification_status)
                        VALUES (%s, %s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')
                        ON DUPLICATE KEY UPDATE
                          metric_value = VALUES(metric_value), unit_name = VALUES(unit_name),
                          source_id = VALUES(source_id), verification_status = VALUES(verification_status)
                        """,
                        (enterprise_id, period, metric_name, value, unit, source_id),
                    )
                    summary["metrics_upserted"] += 1
                cursor.execute(
                    """
                    UPDATE data_quality_issue
                    SET issue_status = 'RESOLVED', resolved_by = 'financial_parser_v2',
                        resolved_at = NOW(), resolution_note = %s
                    WHERE data_quality_issue_id = %s
                    """,
                    (f"已增强解析 {len(metrics)} 条财务指标：{enterprise_name}", issue_id),
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
