"""Read-only MySQL repository for one person's knowledge-graph scope."""

from __future__ import annotations

import re
import sys
from typing import Any, Dict, Iterable, List, Mapping, Sequence, Set

from src.extraction.structured_mapper import ExtractionInput, ReadFailure


PERSON_QUERIES: Dict[str, str] = {
    "person": "SELECT * FROM person WHERE person_id = %s",
    "person_profile": "SELECT * FROM person_profile WHERE person_id = %s",
    "person_career": "SELECT * FROM person_career WHERE person_id = %s",
    "risk_preference": "SELECT * FROM risk_preference WHERE person_id = %s",
    "financial_fact": "SELECT * FROM financial_fact WHERE person_id = %s",
    "product_holding": "SELECT * FROM product_holding WHERE person_id = %s",
    "financial_event": "SELECT * FROM financial_event WHERE person_id = %s",
    "service_record": "SELECT * FROM service_record WHERE person_id = %s",
    "customer_interaction_note": (
        "SELECT * FROM customer_interaction_note WHERE person_id = %s"
    ),
    "person_enterprise_relation": (
        "SELECT * FROM person_enterprise_relation WHERE person_id = %s"
    ),
    "family_member": "SELECT * FROM family_member WHERE person_id = %s",
    "person_family_relation": (
        "SELECT * FROM person_family_relation WHERE person_id = %s"
    ),
    "succession_arrangement": (
        "SELECT * FROM succession_arrangement WHERE person_id = %s"
    ),
    "person_social_relation": (
        "SELECT * FROM person_social_relation WHERE person_id = %s"
    ),
    "social_activity": "SELECT * FROM social_activity WHERE person_id = %s",
    "public_reputation": "SELECT * FROM public_reputation WHERE person_id = %s",
    "reputation_risk": "SELECT * FROM reputation_risk WHERE person_id = %s",
}

ENTERPRISE_QUERIES: Dict[str, str] = {
    "enterprise": "SELECT * FROM enterprise WHERE enterprise_id = %s",
    "enterprise_business": (
        "SELECT * FROM enterprise_business WHERE enterprise_id = %s"
    ),
    "enterprise_financial_metric": (
        "SELECT * FROM enterprise_financial_metric WHERE enterprise_id = %s"
    ),
    "enterprise_market_relation": (
        "SELECT * FROM enterprise_market_relation WHERE enterprise_id = %s"
    ),
    "enterprise_event": "SELECT * FROM enterprise_event WHERE enterprise_id = %s",
}

ORGANIZATION_QUERY = (
    "SELECT * FROM social_organization WHERE social_organization_id = %s"
)
SOURCE_QUERY = "SELECT * FROM source_document WHERE source_id = %s"
IMPORT_BATCH_QUERY = "SELECT * FROM import_batch WHERE import_batch_id = %s"
DATA_QUALITY_QUERY = "SELECT * FROM data_quality_issue WHERE source_id = %s"
ALL_PERSON_IDS_QUERY = "SELECT person_id FROM person ORDER BY person_id"

ALLOWED_SQL: Set[str] = {
    *PERSON_QUERIES.values(),
    *ENTERPRISE_QUERIES.values(),
    ORGANIZATION_QUERY,
    SOURCE_QUERY,
    IMPORT_BATCH_QUERY,
    DATA_QUALITY_QUERY,
}

PRIMARY_KEYS: Dict[str, str] = {
    "person": "person_id",
    "person_profile": "person_id",
    "person_career": "career_id",
    "risk_preference": "risk_preference_id",
    "financial_fact": "financial_fact_id",
    "product_holding": "product_holding_id",
    "financial_event": "financial_event_id",
    "service_record": "service_record_id",
    "customer_interaction_note": "interaction_note_id",
    "person_enterprise_relation": "person_enterprise_relation_id",
    "family_member": "family_member_id",
    "person_family_relation": "person_family_relation_id",
    "succession_arrangement": "succession_arrangement_id",
    "person_social_relation": "person_social_relation_id",
    "social_activity": "social_activity_id",
    "public_reputation": "public_reputation_id",
    "reputation_risk": "reputation_risk_id",
    "enterprise": "enterprise_id",
    "enterprise_business": "enterprise_business_id",
    "enterprise_financial_metric": "enterprise_financial_metric_id",
    "enterprise_market_relation": "enterprise_market_relation_id",
    "enterprise_event": "enterprise_event_id",
    "social_organization": "social_organization_id",
    "source_document": "source_id",
    "import_batch": "import_batch_id",
    "data_quality_issue": "data_quality_issue_id",
}


class UnsafeSelectError(RuntimeError):
    """Raised before execution if SQL is not an exact approved SELECT."""


def redact_mysql_error(message: str) -> str:
    """Remove credentials from a MySQL error before it is recorded."""

    sanitized = re.sub(
        r"(?i)(password|passwd|pwd)\s*[=:]\s*[^\s,;]+",
        r"\1=***REDACTED***",
        str(message),
    )
    return re.sub(
        r"(?i)(mysql(?:\+\w+)?://[^:/\s]+:)[^@\s]+(@)",
        r"\1***REDACTED***\2",
        sanitized,
    )


def validate_fixed_select(sql: str, params: Sequence[Any]) -> None:
    if sql not in ALLOWED_SQL:
        raise UnsafeSelectError("SQL is not present in the fixed SELECT allowlist")
    normalized = " ".join(sql.strip().split())
    upper = normalized.upper()
    if not upper.startswith("SELECT "):
        raise UnsafeSelectError("only SELECT statements are permitted")
    if ";" in normalized or "--" in normalized or "/*" in normalized:
        raise UnsafeSelectError("semicolons and SQL comments are not permitted")
    forbidden = (
        "INSERT",
        "UPDATE",
        "DELETE",
        "CREATE",
        "ALTER",
        "DROP",
        "TRUNCATE",
        "REPLACE",
        "GRANT",
    )
    if any(re.search(rf"\b{word}\b", upper) for word in forbidden):
        raise UnsafeSelectError("a forbidden SQL keyword was detected")
    if upper.count("%S") != len(params):
        raise UnsafeSelectError("SQL parameter count does not match placeholders")
    if not params:
        raise UnsafeSelectError("all dry-run SELECT statements require parameters")


class ReadOnlyMySQLReader:
    """Read one person's graph scope using fixed parameterized SELECTs only."""

    def __init__(self, cursor: Any, person_id: int):
        self.cursor = cursor
        self.person_id = person_id
        self.data = ExtractionInput(person_id=person_id)

    def read(self) -> ExtractionInput:
        for table, sql in PERSON_QUERIES.items():
            self._add_rows(table, self._fetch(table, sql, (self.person_id,)))

        enterprise_ids = self._collect_numeric_ids(
            (
                row.get("enterprise_id")
                for table in ("person_enterprise_relation", "succession_arrangement")
                for row in self.data.records.get(table, [])
            ),
            "enterprise",
        )
        for enterprise_id in sorted(enterprise_ids):
            for table, sql in ENTERPRISE_QUERIES.items():
                self._add_rows(table, self._fetch(table, sql, (enterprise_id,)))

        organization_ids = self._collect_numeric_ids(
            (
                row.get("social_organization_id")
                for row in self.data.records.get("person_social_relation", [])
            ),
            "social_organization",
        )
        for organization_id in sorted(organization_ids):
            self._add_rows(
                "social_organization",
                self._fetch(
                    "social_organization",
                    ORGANIZATION_QUERY,
                    (organization_id,),
                ),
            )

        source_ids = self._collect_numeric_ids(
            (
                row.get("source_id")
                for rows in self.data.records.values()
                for row in rows
                if row.get("source_id") is not None
            ),
            "source_document",
        )
        for source_id in sorted(source_ids):
            source_rows = self._fetch("source_document", SOURCE_QUERY, (source_id,))
            self._add_rows("source_document", source_rows)
            for source in source_rows:
                self.data.sources[str(source["source_id"])] = source
            self._add_rows(
                "data_quality_issue",
                self._fetch("data_quality_issue", DATA_QUALITY_QUERY, (source_id,)),
            )

        import_batch_ids = self._collect_numeric_ids(
            (source.get("import_batch_id") for source in self.data.sources.values()),
            "import_batch",
        )
        for import_batch_id in sorted(import_batch_ids):
            batch_rows = self._fetch(
                "import_batch",
                IMPORT_BATCH_QUERY,
                (import_batch_id,),
            )
            self._add_rows("import_batch", batch_rows)
            for batch in batch_rows:
                self.data.import_batches[str(batch["import_batch_id"])] = batch

        return self.data

    def _fetch(
        self,
        table: str,
        sql: str,
        params: Sequence[Any],
    ) -> List[Dict[str, Any]]:
        try:
            validate_fixed_select(sql, params)
            self.cursor.execute(sql, tuple(params))
            return [dict(row) for row in self.cursor.fetchall()]
        except Exception as exc:
            message = redact_mysql_error(f"{type(exc).__name__}: {exc}")
            self.data.read_failures.append(
                ReadFailure(table_name=table, message=message)
            )
            print(f"[ERROR] 读取 {table} 失败：{message}", file=sys.stderr)
            return []

    def _add_rows(self, table: str, rows: Iterable[Mapping[str, Any]]) -> None:
        target = self.data.records.setdefault(table, [])
        primary_key = PRIMARY_KEYS[table]
        seen = {str(row.get(primary_key)) for row in target}
        for row in rows:
            key = str(row.get(primary_key))
            if key not in seen:
                target.append(dict(row))
                seen.add(key)

    def _collect_numeric_ids(self, values: Iterable[Any], table: str) -> Set[int]:
        identifiers: Set[int] = set()
        for value in values:
            if value is None:
                continue
            try:
                identifiers.add(int(value))
            except (TypeError, ValueError):
                self.data.read_failures.append(
                    ReadFailure(
                        table_name=table,
                        message=f"关联 ID 不是数据库要求的数值类型：{value!r}",
                    )
                )
        return identifiers


def _default_connection_factory() -> Any:
    # Import lazily so pure mapping/pipeline tests do not require a MySQL driver.
    from src.database.mysql_client import get_mysql_connection

    return get_mysql_connection()


def fetch_all_person_ids(connection_factory: Any = None) -> List[int]:
    """Read all person IDs using one fixed SELECT and no write statement."""

    factory = connection_factory or _default_connection_factory
    try:
        with factory() as connection:
            cursor = connection.cursor(dictionary=True)
            try:
                cursor.execute(ALL_PERSON_IDS_QUERY)
                rows = cursor.fetchall()
            finally:
                cursor.close()
    except Exception as exc:
        raise RuntimeError(
            redact_mysql_error(f"{type(exc).__name__}: {exc}")
        ) from exc

    identifiers: Set[int] = set()
    for row in rows:
        value = row.get("person_id") if isinstance(row, Mapping) else row[0]
        try:
            person_id = int(value)
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"person.person_id 不是数值：{value!r}") from exc
        if person_id <= 0:
            raise RuntimeError(f"person.person_id 必须大于 0：{person_id}")
        identifiers.add(person_id)
    return sorted(identifiers)
