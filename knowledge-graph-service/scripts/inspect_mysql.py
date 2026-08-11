"""只读分析当前 MySQL 数据库，并生成知识图谱映射前的数据画像。

安全边界：
- 数据库连接只通过 ``get_mysql_connection`` 获取；
- 所有 SQL 在执行前都经过白名单校验；
- 不提交事务，不包含任何数据库写操作；
- 每张业务表最多读取 5 条样例；
- 样例中的敏感字段在写入本地文件前统一脱敏。

运行方式（请从项目根目录执行）：
    python -m scripts.inspect_mysql
"""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from datetime import date, datetime, time, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from src.database.mysql_client import get_mysql_connection


PROJECT_ROOT = Path(__file__).resolve().parents[1]
JSON_OUTPUT_PATH = PROJECT_ROOT / "output" / "mysql_schema.json"
REPORT_OUTPUT_PATH = PROJECT_ROOT / "docs" / "mysql_data_profile.md"
SAMPLE_LIMIT = 5


BUSINESS_TABLE_DESCRIPTIONS = {
    "import_batch": "每次导入任务的批次信息、状态与记录数",
    "stg_import_row": "Excel 原始行的暂存数据",
    "source_document": "单元格级来源证据：文件名、工作表、行列位置、原始文本",
    "data_quality_issue": "解析过程中的数据质量问题及其处理状态",
    "person": "企业家主体主表",
    "person_profile": "性别、籍贯、教育、个人画像等",
    "person_career": "职业经历与公开身份",
    "risk_preference": "风险等级、最大回撤等投资风险偏好",
    "financial_fact": "资产、负债、不动产、股权等财务事实",
    "product_holding": "现金、债券、基金、信托等产品持仓",
    "financial_event": "增持、减持、分红、偿债、赠与等资金事件",
    "service_record": "私银服务与客户经理服务记录",
    "customer_interaction_note": "客户沟通纪要、偏好和明确需求",
    "enterprise": "核心关联企业主数据",
    "person_enterprise_relation": "企业家与企业之间的职务、创始、控制等关系",
    "enterprise_business": "企业主营业务、产品与服务",
    "enterprise_financial_metric": "营收、利润、资产、负债、利润率等财务指标",
    "enterprise_market_relation": "企业的上游、下游、客户及竞争对手关系",
    "enterprise_event": "融资、回购、监管、经营、行业政策等事件",
    "family_member": "家庭成员；未公开姓名使用受保护别名",
    "person_family_relation": "企业家与家庭成员之间的亲属关系",
    "succession_arrangement": "教育保障、企业接班、财富传承安排",
    "social_organization": "商会、协会、基金会、高校等社会组织",
    "person_social_relation": "企业家在社会组织中的职务或成员关系",
    "social_activity": "公益、ESG、产学研等活动",
    "public_reputation": "荣誉、公开评价、媒体关注",
    "reputation_risk": "舆情与声誉风险线索；不是已证实结论",
}

SYSTEM_TABLE_NAMES = {
    "alembic_version",
    "django_migrations",
    "flyway_schema_history",
    "schema_migrations",
}
SYSTEM_TABLE_PREFIXES = (
    "mysql_",
    "sys_",
    "performance_schema_",
    "information_schema_",
)

NODE_TABLES = {
    "person": "Person",
    "enterprise": "Enterprise",
    "family_member": "FamilyMember",
    "social_organization": "Organization",
}
RELATION_TABLES = {
    "person_enterprise_relation",
    "enterprise_market_relation",
    "person_family_relation",
    "person_social_relation",
}
EVENT_TABLES = {
    "person_career",
    "financial_fact",
    "product_holding",
    "financial_event",
    "service_record",
    "enterprise_financial_metric",
    "enterprise_event",
    "succession_arrangement",
    "social_activity",
    "public_reputation",
    "reputation_risk",
}
MYSQL_ONLY_TABLES = {"import_batch", "source_document", "data_quality_issue"}
IGNORE_TABLES = {"stg_import_row"}

SENSITIVE_FIELD_PATTERNS = {
    "phone": re.compile(
        r"(^|_)(phone|phone_no|phone_number|mobile|mobile_no|mobile_number|telephone|tel|"
        r"contact_number|contact_phone)(_|$)|手机号|手机号码|联系电话|电话",
        re.IGNORECASE,
    ),
    "identity": re.compile(
        r"(^|_)(id_card|id_number|identity_card|identity_no|identity_number|certificate_no|"
        r"certificate_number|passport_no|passport_number)(_|$)"
        r"|身份证|证件号|护照号",
        re.IGNORECASE,
    ),
    "bank_card": re.compile(
        r"(^|_)(bank_card|bank_card_no|credit_card|debit_card|card_number|card_no|bank_account|"
        r"bank_account_no|account_number)(_|$)"
        r"|银行卡|银行账号|账户号码",
        re.IGNORECASE,
    ),
    "address": re.compile(
        r"(^|_)(address|full_address|home_address|contact_address|mailing_address|residential_address|"
        r"residence|registered_address)(_|$)|地址|住址|居住地|通讯地址|联系地址",
        re.IGNORECASE,
    ),
    "email": re.compile(r"(^|_)(email|e_mail)(_|$)|邮箱|电子邮件", re.IGNORECASE),
}

LONG_TEXT_TYPE_RE = re.compile(r"\b(text|mediumtext|longtext|json|blob)\b", re.IGNORECASE)
MULTI_VALUE_NAME_RE = re.compile(
    r"members?|participants?|relations?|events?|history|tags?|keywords?|items?|list|"
    r"成员|参与者|关系|事件|历史|标签|列表|明细",
    re.IGNORECASE,
)
AUDIT_FIELD_RE = re.compile(
    r"^(created_at|updated_at|deleted_at|created_by|updated_by|version|remark|remarks)$",
    re.IGNORECASE,
)


class UnsafeQueryError(RuntimeError):
    """SQL 不满足本脚本的只读白名单时抛出。"""


def redact_error_message(message: str) -> str:
    """清理异常字符串中可能意外出现的口令，不读取或输出配置中的密码。"""

    sanitized = re.sub(
        r"(?i)(password|passwd|pwd)\s*[=:]\s*[^\s,;]+",
        r"\1=***REDACTED***",
        message,
    )
    sanitized = re.sub(
        r"(?i)(mysql(?:\+\w+)?://[^:/\s]+:)[^@\s]+(@)",
        r"\1***REDACTED***\2",
        sanitized,
    )
    return sanitized


def validate_read_only_sql(sql: str) -> str:
    """严格限制可执行语句，额外阻止 SELECT 的文件写入和加锁变体。"""

    normalized = " ".join(sql.strip().split())
    if not normalized:
        raise UnsafeQueryError("拒绝执行空 SQL")
    if ";" in normalized or "--" in normalized or "/*" in normalized or "*/" in normalized:
        raise UnsafeQueryError("SQL 不允许包含分号或注释")

    upper = normalized.upper()
    allowed = (
        upper.startswith("SELECT "),
        upper == "SHOW TABLES" or upper.startswith("SHOW TABLES "),
        upper.startswith("SHOW CREATE TABLE "),
        upper.startswith("SHOW INDEX "),
        upper.startswith("DESCRIBE "),
        upper.startswith("EXPLAIN "),
    )
    if not any(allowed):
        first_word = upper.split(maxsplit=1)[0]
        raise UnsafeQueryError(f"SQL 类型不在只读白名单中：{first_word}")

    if upper.startswith("SELECT "):
        dangerous_select_fragments = (
            " INTO OUTFILE ",
            " INTO DUMPFILE ",
            " FOR UPDATE",
            " LOCK IN SHARE MODE",
        )
        padded = f" {upper} "
        if any(fragment in padded for fragment in dangerous_select_fragments):
            raise UnsafeQueryError("拒绝执行带文件写入或锁定语义的 SELECT")

        forbidden_words = (
            "INSERT",
            "UPDATE",
            "DELETE",
            "CREATE",
            "ALTER",
            "DROP",
            "TRUNCATE",
            "GRANT",
            "REPLACE",
        )
        for word in forbidden_words:
            if re.search(rf"\b{word}\b", upper):
                raise UnsafeQueryError(f"SELECT 中出现禁止关键字：{word}")

    return normalized


def quote_identifier(identifier: str) -> str:
    """将服务端返回的表名安全地引用为 MySQL 标识符。"""

    if "\x00" in identifier:
        raise ValueError("标识符包含 NUL 字符")
    return "`" + identifier.replace("`", "``") + "`"


def record_error(
    errors: list[dict[str, Any]],
    context: str,
    sql: str | None,
    exc: BaseException,
) -> None:
    """同时写入机器可读错误列表和标准错误，确保异常不会被静默忽略。"""

    safe_message = redact_error_message(f"{type(exc).__name__}: {exc}")
    entry = {
        "occurred_at": datetime.now(timezone.utc).isoformat(),
        "context": context,
        "statement_type": sql.strip().split(maxsplit=1)[0].upper() if sql else None,
        "message": safe_message,
    }
    errors.append(entry)
    print(f"[ERROR] {context}: {safe_message}", file=sys.stderr)


def fetch_all(
    cursor: Any,
    sql: str,
    errors: list[dict[str, Any]],
    context: str,
    params: Sequence[Any] | None = None,
) -> list[dict[str, Any]]:
    """校验并执行只读查询；失败时显式记录，然后返回空列表供后续继续画像。"""

    try:
        validate_read_only_sql(sql)
        cursor.execute(sql, params or ())
        rows = cursor.fetchall()
        return [dict(row) for row in rows]
    except Exception as exc:  # 每类驱动异常都必须落入报告
        record_error(errors, context, sql, exc)
        return []


def json_safe(value: Any) -> Any:
    """将 MySQL 驱动值转换为可安全写入 JSON 的表示。"""

    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, (bytes, bytearray, memoryview)):
        return f"<binary:{len(value)} bytes>"
    return str(value)


def sensitive_kind(field_name: str) -> str | None:
    for kind, pattern in SENSITIVE_FIELD_PATTERNS.items():
        if pattern.search(field_name):
            return kind
    return None


def mask_sensitive_value(value: Any, kind: str) -> Any:
    """按敏感信息类型保留极少量格式线索，同时隐藏可识别内容。"""

    if value is None:
        return None
    text = str(json_safe(value))
    if not text:
        return text
    if kind == "email" and "@" in text:
        local, domain = text.rsplit("@", 1)
        return f"{local[:1]}***@{domain}"
    if kind == "address":
        return f"{text[:2]}***"
    if kind == "bank_card":
        return f"{text[:4]}***{text[-4:]}" if len(text) > 8 else "***MASKED***"
    if kind in {"phone", "identity"}:
        return f"{text[:3]}***{text[-2:]}" if len(text) > 5 else "***MASKED***"
    return "***MASKED***"


def redact_sensitive_content(value: Any) -> Any:
    """补充按内容脱敏，防止原文/备注字段中夹带未显式命名的敏感信息。"""

    if not isinstance(value, str) or not value:
        return value
    redacted = value
    redacted = re.sub(
        r"(?<!\d)(\d{6})(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])"
        r"(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?!\d)",
        r"\1************",
        redacted,
    )
    redacted = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "1**********", redacted)
    redacted = re.sub(r"(?<!\d)\d{15}(?!\d)", "***************", redacted)
    redacted = re.sub(r"(?<!\d)\d{16,19}(?!\d)", "****************", redacted)
    redacted = re.sub(
        r"(?i)([A-Z0-9._%+-])([A-Z0-9._%+-]*)(@[A-Z0-9.-]+\.[A-Z]{2,})",
        r"\1***\3",
        redacted,
    )
    redacted = re.sub(
        r"((?:家庭|居住|户籍|联系|通讯|公司)?地址|住址)\s*[:：]\s*[^,，;；\n]{3,}",
        r"\1：***MASKED***",
        redacted,
    )
    return redacted


def sanitize_sample_row(row: Mapping[str, Any]) -> dict[str, Any]:
    sanitized: dict[str, Any] = {}
    for field_name, raw_value in row.items():
        kind = sensitive_kind(field_name)
        value = mask_sensitive_value(raw_value, kind) if kind else json_safe(raw_value)
        value = redact_sensitive_content(value)
        if isinstance(value, str) and len(value) > 500:
            value = value[:500] + "…<truncated>"
        sanitized[field_name] = value
    return sanitized


def classify_table_scope(table_name: str) -> tuple[str, str]:
    lowered = table_name.lower()
    if lowered in SYSTEM_TABLE_NAMES or lowered.startswith(SYSTEM_TABLE_PREFIXES):
        return "SYSTEM", "匹配迁移框架或 MySQL 系统表命名规则"
    if lowered in BUSINESS_TABLE_DESCRIPTIONS:
        return "BUSINESS", "数据说明中已登记的业务或业务追溯表"
    return "BUSINESS", "当前业务库中的未登记表，按业务候选表纳入分析"


def classify_table_for_graph(table_name: str) -> dict[str, Any]:
    lowered = table_name.lower()
    if lowered in NODE_TABLES:
        return {
            "classification": "NODE",
            "node_candidate": NODE_TABLES[lowered],
            "reason": "具有独立主数据身份，适合作为图节点",
        }
    if lowered in RELATION_TABLES or lowered.endswith("_relation"):
        return {
            "classification": "RELATION",
            "node_candidate": None,
            "reason": "连接两个或多个实体，适合作为图关系",
        }
    if lowered in EVENT_TABLES or lowered.endswith("_event"):
        return {
            "classification": "EVENT",
            "node_candidate": "Event",
            "reason": "具有时点、期间或可追溯事实语义，候选为事件节点",
        }
    if lowered == "person_profile":
        return {
            "classification": "PROPERTY",
            "node_candidate": "Person",
            "reason": "优先作为 Person 的扩展属性；复杂画像字段仍需解析",
        }
    if lowered in MYSQL_ONLY_TABLES:
        return {
            "classification": "MYSQL_ONLY",
            "node_candidate": None,
            "reason": "属于导入、来源证据或治理数据，优先继续保存在 MySQL",
        }
    if lowered in IGNORE_TABLES:
        return {
            "classification": "IGNORE",
            "node_candidate": None,
            "reason": "暂存原始行，本期不直接进入知识图谱",
        }
    return {
        "classification": "NEED_ANALYSIS",
        "node_candidate": None,
        "reason": "未命中已知映射规则，需要结合业务语义进一步判断",
    }


def build_indexes(index_rows: Sequence[Mapping[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, dict[str, Any]] = {}
    for row in index_rows:
        name = str(row.get("Key_name") or row.get("key_name") or "")
        if not name:
            continue
        entry = grouped.setdefault(
            name,
            {
                "name": name,
                "unique": not bool(row.get("Non_unique", row.get("non_unique", 1))),
                "columns": [],
            },
        )
        sequence = int(row.get("Seq_in_index", row.get("seq_in_index", 0)) or 0)
        column = row.get("Column_name", row.get("column_name"))
        entry["columns"].append((sequence, column))

    primary: list[dict[str, Any]] = []
    unique: list[dict[str, Any]] = []
    normal: list[dict[str, Any]] = []
    for name, entry in grouped.items():
        entry["columns"] = [value for _, value in sorted(entry["columns"])]
        if name.upper() == "PRIMARY":
            primary.append(entry)
        elif entry["unique"]:
            unique.append(entry)
        else:
            normal.append(entry)
    return {"primary": primary, "unique": unique, "normal": normal}


def detect_value_format(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "BOOLEAN"
    if isinstance(value, (int, float, Decimal)):
        return "NUMBER"
    if isinstance(value, (datetime, date, time)):
        return "TEMPORAL"
    if isinstance(value, (bytes, bytearray, memoryview)):
        return "BINARY"

    text = str(value).strip()
    if not text:
        return "EMPTY_STRING"
    if re.fullmatch(r"[-+]?\d+(?:\.\d+)?", text):
        return "NUMERIC_STRING"
    if re.fullmatch(r"\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:[ T].*)?", text):
        return "DATE_STRING"
    if re.fullmatch(r"\d{8}", text):
        return "COMPACT_DATE_OR_NUMBER"
    if re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", text):
        return "EMAIL_STRING"
    if re.fullmatch(r"[+\d][\d\s()-]{5,}", text):
        return "PHONE_LIKE_STRING"
    if text[:1] in "[{":
        try:
            json.loads(text)
            return "JSON_STRING"
        except json.JSONDecodeError:
            return "MALFORMED_JSON_LIKE_STRING"
    if re.search(r"[,，;；|、\n]", text):
        return "DELIMITED_STRING"
    return "PLAIN_STRING"


def analyze_fields(
    columns: Sequence[Mapping[str, Any]],
    raw_samples: Sequence[Mapping[str, Any]],
    indexes: Mapping[str, Sequence[Mapping[str, Any]]],
    foreign_keys: Sequence[Mapping[str, Any]],
    table_graph_classification: str,
) -> list[dict[str, Any]]:
    primary_columns = {
        column
        for index in indexes.get("primary", [])
        for column in index.get("columns", [])
    }
    unique_columns = {
        column
        for index in indexes.get("unique", [])
        if len(index.get("columns", [])) == 1
        for column in index.get("columns", [])
    }
    foreign_key_columns = {fk.get("column_name") for fk in foreign_keys}
    sample_size = len(raw_samples)
    analyses: list[dict[str, Any]] = []

    for column in columns:
        name = str(column.get("Field", column.get("field", "")))
        type_name = str(column.get("Type", column.get("type", "")))
        values = [row.get(name) for row in raw_samples]
        non_null_values = [value for value in values if value is not None]
        null_count = sample_size - len(non_null_values)
        null_rate = round(null_count / sample_size, 4) if sample_size else None
        formats = sorted({detect_value_format(value) for value in non_null_values})
        max_text_length = max((len(str(value)) for value in non_null_values), default=0)
        long_text = bool(LONG_TEXT_TYPE_RE.search(type_name)) or max_text_length >= 200
        multi_value = bool(MULTI_VALUE_NAME_RE.search(name)) or any(
            detect_value_format(value) in {"JSON_STRING", "DELIMITED_STRING"}
            for value in non_null_values
        )
        sensitive = sensitive_kind(name)
        inconsistent = len(formats) > 1
        mostly_null = null_rate is not None and null_rate >= 0.6
        scalar_type = not re.search(r"\b(blob|binary|varbinary)\b", type_name, re.IGNORECASE)
        directly_mappable = bool(
            scalar_type
            and not sensitive
            and not long_text
            and not multi_value
            and not inconsistent
        )

        if table_graph_classification == "IGNORE":
            kg_classification = "IGNORE"
            kg_reason = "所属暂存表本期不进入知识图谱"
        elif sensitive:
            kg_classification = "MYSQL_ONLY"
            kg_reason = "敏感字段不直接进入知识图谱"
        elif name in foreign_key_columns:
            kg_classification = "RELATION"
            kg_reason = "外键字段可用于连接图实体"
        elif long_text or multi_value or inconsistent:
            kg_classification = "NEED_ANALYSIS"
            kg_reason = "长文本、多值或格式差异需要进一步解析"
        elif AUDIT_FIELD_RE.match(name):
            kg_classification = "MYSQL_ONLY"
            kg_reason = "审计或数据库管理字段优先保留在 MySQL"
        else:
            kg_classification = "PROPERTY"
            kg_reason = "标量字段可作为节点、关系或事件属性候选"

        analyses.append(
            {
                "name": name,
                "type": type_name,
                "nullable": str(column.get("Null", column.get("null", ""))).upper() == "YES",
                "default": (
                    mask_sensitive_value(column.get("Default", column.get("default")), sensitive)
                    if sensitive and column.get("Default", column.get("default")) is not None
                    else redact_sensitive_content(json_safe(column.get("Default", column.get("default"))))
                ),
                "key": column.get("Key", column.get("key")),
                "extra": column.get("Extra", column.get("extra")),
                "is_primary_key": name in primary_columns,
                "is_single_column_unique": name in unique_columns,
                "is_foreign_key": name in foreign_key_columns,
                "sensitive_kind": sensitive,
                "sample_non_null_count": len(non_null_values),
                "sample_null_rate": null_rate,
                "sample_distinct_count": len({str(json_safe(value)) for value in non_null_values}),
                "observed_formats": formats,
                "max_observed_text_length": max_text_length,
                "contains_long_text": long_text,
                "may_contain_multiple_entities_relations_or_events": multi_value,
                "has_many_nulls_in_sample": mostly_null,
                "format_may_be_inconsistent": inconsistent,
                "directly_mappable": directly_mappable,
                "kg_classification": kg_classification,
                "kg_reason": kg_reason,
            }
        )
    return analyses


def stable_identifier_candidates(
    indexes: Mapping[str, Sequence[Mapping[str, Any]]],
    fields: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    constrained_columns: set[str] = set()
    for index in list(indexes.get("primary", [])) + list(indexes.get("unique", [])):
        columns = list(index.get("columns", []))
        constrained_columns.update(str(column) for column in columns)
        candidates.append(
            {
                "columns": columns,
                "basis": "PRIMARY KEY" if index.get("name", "").upper() == "PRIMARY" else "UNIQUE INDEX",
                "confidence": "HIGH",
            }
        )

    for field in fields:
        name = str(field.get("name", ""))
        if name not in constrained_columns and (name == "id" or name.endswith("_id")):
            candidates.append(
                {
                    "columns": [name],
                    "basis": "ID 命名但无主键/唯一约束",
                    "confidence": "LOW",
                }
            )
    return candidates


def first_row_value(row: Mapping[str, Any]) -> Any:
    return next(iter(row.values()), None)


def inspect_table(
    cursor: Any,
    table_name: str,
    errors: list[dict[str, Any]],
) -> dict[str, Any]:
    quoted_table = quote_identifier(table_name)
    graph = classify_table_for_graph(table_name)

    create_rows = fetch_all(
        cursor,
        f"SHOW CREATE TABLE {quoted_table}",
        errors,
        f"获取表 {table_name} 的建表语句",
    )
    create_sql = None
    if create_rows:
        values = list(create_rows[0].values())
        create_sql = values[1] if len(values) > 1 else values[0]

    columns = fetch_all(
        cursor,
        f"DESCRIBE {quoted_table}",
        errors,
        f"获取表 {table_name} 的字段结构",
    )
    index_rows = fetch_all(
        cursor,
        f"SHOW INDEX FROM {quoted_table}",
        errors,
        f"获取表 {table_name} 的索引",
    )
    indexes = build_indexes(index_rows)

    foreign_key_rows = fetch_all(
        cursor,
        """
        SELECT
            CONSTRAINT_NAME AS constraint_name,
            COLUMN_NAME AS column_name,
            REFERENCED_TABLE_NAME AS referenced_table_name,
            REFERENCED_COLUMN_NAME AS referenced_column_name
        FROM information_schema.KEY_COLUMN_USAGE
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = %s
          AND REFERENCED_TABLE_NAME IS NOT NULL
        ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
        """,
        errors,
        f"获取表 {table_name} 的外键",
        (table_name,),
    )

    count_rows = fetch_all(
        cursor,
        f"SELECT COUNT(*) AS total_records FROM {quoted_table}",
        errors,
        f"统计表 {table_name} 的记录数",
    )
    total_records = count_rows[0].get("total_records") if count_rows else None

    raw_samples = fetch_all(
        cursor,
        f"SELECT * FROM {quoted_table} LIMIT {SAMPLE_LIMIT}",
        errors,
        f"读取表 {table_name} 的最多 {SAMPLE_LIMIT} 条样例",
    )
    sanitized_samples = [sanitize_sample_row(row) for row in raw_samples]
    fields = analyze_fields(columns, raw_samples, indexes, foreign_key_rows, graph["classification"])
    field_names = {field["name"] for field in fields}

    return {
        "table_name": table_name,
        "business_meaning": BUSINESS_TABLE_DESCRIPTIONS.get(
            table_name,
            "未在数据说明中登记，需要业务人员补充确认",
        ),
        "kg_classification": graph,
        "create_table_sql": create_sql,
        "columns": fields,
        "indexes": indexes,
        "foreign_keys": foreign_key_rows,
        "total_records": json_safe(total_records),
        "sample_limit": SAMPLE_LIMIT,
        "sample_size": len(sanitized_samples),
        "sample_records": sanitized_samples,
        "required_field_presence": {
            "person_id": "person_id" in field_names,
            "enterprise_id": "enterprise_id" in field_names,
            "source_id": "source_id" in field_names,
            "verification_status": "verification_status" in field_names,
        },
        "stable_identifier_candidates": stable_identifier_candidates(indexes, fields),
        "directly_mappable_fields": [field["name"] for field in fields if field["directly_mappable"]],
        "long_text_fields": [field["name"] for field in fields if field["contains_long_text"]],
        "multi_entity_relation_event_fields": [
            field["name"]
            for field in fields
            if field["may_contain_multiple_entities_relations_or_events"]
        ],
        "mostly_null_fields_in_sample": [
            field["name"] for field in fields if field["has_many_nulls_in_sample"]
        ],
        "inconsistent_format_fields_in_sample": [
            field["name"] for field in fields if field["format_may_be_inconsistent"]
        ],
    }


def derive_table_relationships(tables: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    relationships: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str, str]] = set()

    for table in tables:
        source_table = str(table["table_name"])
        for foreign_key in table.get("foreign_keys", []):
            target_table = str(foreign_key.get("referenced_table_name"))
            source_column = str(foreign_key.get("column_name"))
            target_column = str(foreign_key.get("referenced_column_name"))
            key = (source_table, source_column, target_table, target_column)
            if key not in seen:
                seen.add(key)
                relationships.append(
                    {
                        "source_table": source_table,
                        "source_column": source_column,
                        "target_table": target_table,
                        "target_column": target_column,
                        "basis": "FOREIGN_KEY",
                        "confidence": "HIGH",
                    }
                )

    id_usage: dict[str, list[str]] = defaultdict(list)
    for table in tables:
        table_name = str(table["table_name"])
        for column in table.get("columns", []):
            column_name = str(column.get("name", ""))
            if column_name.endswith("_id") and column_name != "id":
                id_usage[column_name].append(table_name)

    for column_name, table_names in sorted(id_usage.items()):
        unique_tables = sorted(set(table_names))
        if len(unique_tables) < 2:
            continue
        for index, source_table in enumerate(unique_tables):
            for target_table in unique_tables[index + 1 :]:
                forward = (source_table, column_name, target_table, column_name)
                reverse = (target_table, column_name, source_table, column_name)
                if forward in seen or reverse in seen:
                    continue
                seen.add(forward)
                relationships.append(
                    {
                        "source_table": source_table,
                        "source_column": column_name,
                        "target_table": target_table,
                        "target_column": column_name,
                        "basis": "SHARED_ID_NAME",
                        "confidence": "MEDIUM",
                    }
                )
    return relationships


def summarize_graph_candidates(tables: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    available = {str(table["table_name"]) for table in tables}

    def existing(names: Sequence[str]) -> list[str]:
        return [name for name in names if name in available]

    by_classification: dict[str, list[str]] = defaultdict(list)
    for table in tables:
        classification = str(table["kg_classification"]["classification"])
        by_classification[classification].append(str(table["table_name"]))

    return {
        "candidate_nodes": {
            "Person": existing(["person", "person_profile"]),
            "Enterprise": existing(["enterprise"]),
            "FamilyProfile": existing(
                ["family_member", "person_family_relation", "succession_arrangement"]
            ),
            "FamilyMember": existing(["family_member"]),
            "Organization": existing(["social_organization"]),
            "Event": sorted(by_classification.get("EVENT", [])),
        },
        "tables_by_classification": {
            category: sorted(by_classification.get(category, []))
            for category in (
                "NODE",
                "RELATION",
                "EVENT",
                "PROPERTY",
                "MYSQL_ONLY",
                "NEED_ANALYSIS",
                "IGNORE",
            )
        },
    }


def inspect_database() -> dict[str, Any]:
    errors: list[dict[str, Any]] = []
    result: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "safety": {
            "database_mode": "READ_ONLY_BY_QUERY_ALLOWLIST",
            "sample_limit_per_table": SAMPLE_LIMIT,
            "sensitive_samples_masked": True,
            "database_write_statements": False,
        },
        "database": {"name": None, "mysql_version": None},
        "all_tables": [],
        "business_tables": [],
        "system_tables": [],
        "table_relationships": [],
        "knowledge_graph_summary": {
            "candidate_nodes": {},
            "tables_by_classification": {},
        },
        "errors": errors,
    }

    try:
        with get_mysql_connection() as connection:
            cursor = connection.cursor(dictionary=True)
            try:
                overview_rows = fetch_all(
                    cursor,
                    "SELECT DATABASE() AS database_name, VERSION() AS mysql_version",
                    errors,
                    "查询当前数据库名称和 MySQL 版本",
                )
                if overview_rows:
                    result["database"] = {
                        "name": overview_rows[0].get("database_name"),
                        "mysql_version": overview_rows[0].get("mysql_version"),
                    }

                table_rows = fetch_all(cursor, "SHOW TABLES", errors, "列出当前数据库全部表")
                table_names = sorted(
                    str(first_row_value(row))
                    for row in table_rows
                    if first_row_value(row) is not None
                )
                for table_name in table_names:
                    scope, reason = classify_table_scope(table_name)
                    result["all_tables"].append(
                        {"table_name": table_name, "scope": scope, "reason": reason}
                    )
                    if scope == "SYSTEM":
                        result["system_tables"].append(table_name)

                business_names = [
                    item["table_name"] for item in result["all_tables"] if item["scope"] == "BUSINESS"
                ]
                for table_name in business_names:
                    result["business_tables"].append(inspect_table(cursor, table_name, errors))

                result["table_relationships"] = derive_table_relationships(result["business_tables"])
                result["knowledge_graph_summary"] = summarize_graph_candidates(
                    result["business_tables"]
                )
            finally:
                cursor.close()
    except Exception as exc:
        record_error(errors, "建立或关闭 MySQL 只读分析连接", None, exc)

    return result


def markdown_cell(value: Any) -> str:
    if value is None:
        return "—"
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def comma_list(values: Iterable[Any]) -> str:
    rendered = [str(value) for value in values]
    return "、".join(rendered) if rendered else "无"


def render_markdown(profile: Mapping[str, Any]) -> str:
    tables = list(profile.get("business_tables", []))
    errors = list(profile.get("errors", []))
    all_tables = list(profile.get("all_tables", []))
    expected_missing = sorted(set(BUSINESS_TABLE_DESCRIPTIONS) - {t["table_name"] for t in tables})

    lines = [
        "# MySQL 数据画像与知识图谱映射初步分析",
        "",
        f"> 生成时间（UTC）：{profile.get('generated_at')}  ",
        f"> 每表样例上限：{SAMPLE_LIMIT}。样例统计仅用于初筛，不能替代全量质量评估。  ",
        "> 报告中的手机号、证件号、银行卡号、地址、邮箱等敏感样例已经脱敏；姓名未强制脱敏。",
        "",
        "## 1. 数据库整体概况",
        "",
        f"- 当前数据库：`{markdown_cell(profile.get('database', {}).get('name'))}`",
        f"- MySQL 版本：`{markdown_cell(profile.get('database', {}).get('mysql_version'))}`",
        f"- 表总数：{len(all_tables)}",
        f"- 业务/候选业务表：{len(tables)}",
        f"- 系统表：{len(profile.get('system_tables', []))}",
        f"- 查询异常：{len(errors)}",
        "",
        "| 表名 | 范围 | 判定依据 |",
        "|---|---|---|",
    ]
    for table in all_tables:
        lines.append(
            f"| `{markdown_cell(table['table_name'])}` | {table['scope']} | {markdown_cell(table['reason'])} |"
        )
    if not all_tables:
        lines.append("| — | — | 未获取到表；请检查文末错误记录 |")

    lines.extend(["", "## 2. 各表业务含义", "", "| 表名 | 业务含义 | 图谱初步分类 | 候选节点 |", "|---|---|---|---|"])
    for table in tables:
        graph = table["kg_classification"]
        lines.append(
            f"| `{table['table_name']}` | {markdown_cell(table['business_meaning'])} | "
            f"{graph['classification']} | {markdown_cell(graph.get('node_candidate'))} |"
        )
    if not tables:
        lines.append("| — | 未获取到业务表 | — | — |")

    lines.extend(["", "## 3. 各表字段说明", ""])
    for table in tables:
        lines.extend(
            [
                f"### `{table['table_name']}`",
                "",
                f"- 业务含义：{table['business_meaning']}",
                f"- 总记录数：{markdown_cell(table.get('total_records'))}",
                f"- 样例数：{table.get('sample_size', 0)} / {SAMPLE_LIMIT}",
                f"- 主键：{comma_list(index['columns'] for index in table['indexes']['primary'])}",
                f"- 唯一索引：{comma_list(index['name'] + '(' + ', '.join(index['columns']) + ')' for index in table['indexes']['unique'])}",
                f"- 普通索引：{comma_list(index['name'] + '(' + ', '.join(index['columns']) + ')' for index in table['indexes']['normal'])}",
                "",
                "| 字段 | 类型 | 可空 | 默认值 | 键 | 图谱分类 | 样例空值率 | 格式 | 说明 |",
                "|---|---|---:|---|---|---|---:|---|---|",
            ]
        )
        for field in table["columns"]:
            null_rate = field["sample_null_rate"]
            rate_text = "—" if null_rate is None else f"{null_rate:.0%}"
            flags = []
            if field["is_primary_key"]:
                flags.append("主键")
            if field["is_single_column_unique"]:
                flags.append("唯一")
            if field["is_foreign_key"]:
                flags.append("外键")
            if field["sensitive_kind"]:
                flags.append("敏感")
            if field["contains_long_text"]:
                flags.append("长文本")
            if field["may_contain_multiple_entities_relations_or_events"]:
                flags.append("可能多值")
            lines.append(
                f"| `{markdown_cell(field['name'])}` | `{markdown_cell(field['type'])}` | "
                f"{'是' if field['nullable'] else '否'} | {markdown_cell(field['default'])} | "
                f"{comma_list(flags)} | {field['kg_classification']} | {rate_text} | "
                f"{comma_list(field['observed_formats'])} | {markdown_cell(field['kg_reason'])} |"
            )

        lines.extend(["", "外键：", ""])
        if table["foreign_keys"]:
            for fk in table["foreign_keys"]:
                lines.append(
                    f"- `{fk.get('column_name')}` → `{fk.get('referenced_table_name')}.{fk.get('referenced_column_name')}` "
                    f"（约束 `{fk.get('constraint_name')}`）"
                )
        else:
            lines.append("- 未发现显式外键。")
        lines.append("")

    lines.extend(["## 4. 表之间的关联关系", ""])
    relationships = profile.get("table_relationships", [])
    if relationships:
        lines.extend(["| 来源 | 目标 | 依据 | 置信度 |", "|---|---|---|---|"])
        for relation in relationships:
            lines.append(
                f"| `{relation['source_table']}.{relation['source_column']}` | "
                f"`{relation['target_table']}.{relation['target_column']}` | "
                f"{relation['basis']} | {relation['confidence']} |"
            )
    else:
        lines.append("未发现显式外键或可由同名 `*_id` 推断的跨表关联。")

    lines.extend(["", "## 5. 数据质量问题", ""])
    quality_items: list[str] = []
    for table in tables:
        if table["mostly_null_fields_in_sample"]:
            quality_items.append(
                f"`{table['table_name']}` 的样例高空值字段：{comma_list(table['mostly_null_fields_in_sample'])}。"
            )
        if table["inconsistent_format_fields_in_sample"]:
            quality_items.append(
                f"`{table['table_name']}` 的样例格式可能不统一：{comma_list(table['inconsistent_format_fields_in_sample'])}。"
            )
        if table["multi_entity_relation_event_fields"]:
            quality_items.append(
                f"`{table['table_name']}` 可能包含多实体/关系/事件的字段：{comma_list(table['multi_entity_relation_event_fields'])}。"
            )
    lines.extend(f"- {item}" for item in quality_items)
    if not quality_items:
        lines.append("- 在最多 5 条/表的样例中未命中高空值、格式混用或多值启发式规则；仍需全量统计验证。")

    lines.extend(["", "## 6. 稳定 ID 情况", ""])
    for table in tables:
        candidates = table["stable_identifier_candidates"]
        if candidates:
            text = "；".join(
                f"{'+'.join(item['columns'])}（{item['basis']}，{item['confidence']}）" for item in candidates
            )
        else:
            text = "未发现"
        lines.append(f"- `{table['table_name']}`：{text}")

    lines.extend(["", "## 7. 节点、关系、事件候选", ""])
    for category in ("NODE", "RELATION", "EVENT", "PROPERTY", "NEED_ANALYSIS", "IGNORE"):
        matching = [table["table_name"] for table in tables if table["kg_classification"]["classification"] == category]
        lines.append(f"- {category}：{comma_list(f'`{name}`' for name in matching)}")
    family_profile_sources = [
        table["table_name"]
        for table in tables
        if table["table_name"] in {"family_member", "person_family_relation", "succession_arrangement"}
    ]
    lines.append(
        f"- FamilyProfile 尚无明确同名主表，可优先由 {comma_list(f'`{name}`' for name in family_profile_sources)} 聚合形成只读图视图。"
    )

    lines.extend(["", "## 8. 应继续保留在 MySQL 中的数据", ""])
    mysql_only_tables = [
        table["table_name"]
        for table in tables
        if table["kg_classification"]["classification"] in {"MYSQL_ONLY", "IGNORE"}
    ]
    sensitive_fields = [
        f"{table['table_name']}.{field['name']}"
        for table in tables
        for field in table["columns"]
        if field["kg_classification"] == "MYSQL_ONLY"
    ]
    lines.extend(
        [
            f"- 导入、溯源、治理及暂存表：{comma_list(f'`{name}`' for name in mysql_only_tables)}。",
            f"- 敏感或审计字段：{comma_list(f'`{name}`' for name in sensitive_fields)}。",
            "- 原始长文本、原始证据和完整服务记录建议保留 MySQL 为事实来源，Neo4j 仅保存必要摘要及来源引用。",
        ]
    )

    lines.extend(["", "## 9. 当前数据对知识图谱构建的缺口", ""])
    gaps: list[str] = []
    if expected_missing:
        gaps.append(f"数据说明中的表尚未在当前库中发现：{comma_list(expected_missing)}。")
    no_stable_id = [table["table_name"] for table in tables if not table["stable_identifier_candidates"]]
    if no_stable_id:
        gaps.append(f"缺少受约束稳定 ID 的表：{comma_list(no_stable_id)}。")
    relation_without_fk = [
        table["table_name"]
        for table in tables
        if table["kg_classification"]["classification"] == "RELATION" and not table["foreign_keys"]
    ]
    if relation_without_fk:
        gaps.append(f"关系候选表缺少显式外键：{comma_list(relation_without_fk)}。")
    if any(table["multi_entity_relation_event_fields"] for table in tables):
        gaps.append("部分字段可能混合多个实体、关系或事件，尚缺少拆分规则、实体消歧和时间标准化策略。")
    if errors:
        gaps.append("存在查询异常，相关结构或统计信息不完整，需先依据错误记录修复权限或兼容性问题。")
    if not gaps:
        gaps.append("当前最多 5 条/表的样例不足以证明全量唯一性、完整性和格式一致性。")
    lines.extend(f"- {gap}" for gap in gaps)

    lines.extend(
        [
            "",
            "## 10. 下一步建议",
            "",
            "1. 由业务人员确认未登记表含义、图谱使用边界及敏感字段最小化原则。",
            "2. 以主键/唯一索引为首选图节点键；仅有命名线索的 `*_id` 在映射前验证全量唯一性与非空率。",
            "3. 优先落地 Person、Enterprise、FamilyMember、Organization 四类主节点及显式关系表。",
            "4. 将事件类表统一为 Event 模型，明确事件类型、发生时间、主体、客体、金额/指标和来源证据。",
            "5. 对长文本与多值字段设计可审计的解析流程，保留原文 `source_id`，并记录解析版本与置信度。",
            "6. 在正式同步前建立字段级映射清单、脱敏策略、增量键、删除/失效语义和 Neo4j 唯一约束。",
            "",
            "## 查询异常记录",
            "",
        ]
    )
    if errors:
        lines.extend(["| 时间（UTC） | 上下文 | 语句类型 | 错误 |", "|---|---|---|---|"])
        for error in errors:
            lines.append(
                f"| {markdown_cell(error.get('occurred_at'))} | {markdown_cell(error.get('context'))} | "
                f"{markdown_cell(error.get('statement_type'))} | {markdown_cell(error.get('message'))} |"
            )
    else:
        lines.append("无查询异常。")

    return "\n".join(lines) + "\n"


def write_outputs(profile: Mapping[str, Any]) -> None:
    """仅写本地分析产物；不对数据库执行任何写操作。"""

    JSON_OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    JSON_OUTPUT_PATH.write_text(
        json.dumps(profile, ensure_ascii=False, indent=2, default=json_safe) + "\n",
        encoding="utf-8",
    )
    REPORT_OUTPUT_PATH.write_text(render_markdown(profile), encoding="utf-8")


def main() -> int:
    profile = inspect_database()
    write_outputs(profile)
    print(f"机器可读结果：{JSON_OUTPUT_PATH}")
    print(f"分析报告：{REPORT_OUTPUT_PATH}")
    if profile.get("errors"):
        print(f"分析完成，但记录了 {len(profile['errors'])} 个查询异常。", file=sys.stderr)
        return 1
    print("只读分析完成，未记录查询异常。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
