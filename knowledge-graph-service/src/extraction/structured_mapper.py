"""Map real MySQL records to DIRECT/RULE graph candidates.

This module never opens a database connection.  It accepts records read by
the CLI's strictly read-only reader and applies only the decisions documented
in ``docs/mysql_to_neo4j_mapping.md``.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Set, Tuple

from src.extraction.id_generator import (
    enterprise_node_id,
    event_id,
    family_member_node_id,
    family_profile_node_id,
    issue_id,
    market_segment_node_id,
    organization_node_id,
    person_node_id,
    reference_enterprise_node_id,
    relation_id,
)
from src.extraction.rule_parser import (
    MaritalStatusConflictError,
    MissingAmountUnitError,
    RuleParseError,
    UnknownCurrencyError,
    clean_enterprise_name,
    clean_text,
    contains_speculation,
    is_empty_or_invalid,
    normalize_date,
    normalize_currency,
    normalize_organization_name,
    normalize_marital_status,
    parse_employee_count,
    parse_money,
    parse_percentage,
    parse_stock_code,
    parse_year,
    safe_preview,
)
from src.models.graph_models import (
    CandidateExtraction,
    GraphEvent,
    GraphIssue,
    GraphNode,
    GraphRelation,
)


NODE_TYPES: Set[str] = {
    "Person",
    "Enterprise",
    "FamilyProfile",
    "FamilyMember",
    "Organization",
    "MarketSegment",
    "Event",
}

RELATION_TYPES: Set[str] = {
    "WORKS_AT",
    "CHAIRMAN_OF",
    "CEO_OF",
    "FOUNDED",
    "HOLDS_SHARE",
    "CONTROLS",
    "OWNS",
    "GUARANTEES",
    "SUPPLY_CHAIN",
    "COMPETES_WITH",
    "HAS_UPSTREAM",
    "HAS_DOWNSTREAM",
    "FAMILY_OF",
    "HAS_FAMILY_PROFILE",
    "MEMBER_OF",
    "WORKS_FOR",
    "CHARITY_COOPERATION",
    "ACADEMIC_COOPERATION",
    "PARTICIPATED_IN",
    "HAS_EVENT",
    "RELATED_TO",
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
    "enterprise": "enterprise_id",
    "person_enterprise_relation": "person_enterprise_relation_id",
    "enterprise_business": "enterprise_business_id",
    "enterprise_financial_metric": "enterprise_financial_metric_id",
    "enterprise_market_relation": "enterprise_market_relation_id",
    "enterprise_event": "enterprise_event_id",
    "family_member": "family_member_id",
    "person_family_relation": "person_family_relation_id",
    "succession_arrangement": "succession_arrangement_id",
    "social_organization": "social_organization_id",
    "person_social_relation": "person_social_relation_id",
    "social_activity": "social_activity_id",
    "public_reputation": "public_reputation_id",
    "reputation_risk": "reputation_risk_id",
    "source_document": "source_id",
    "import_batch": "import_batch_id",
    "data_quality_issue": "data_quality_issue_id",
}

MYSQL_ONLY_TABLES = {
    "risk_preference",
    "product_holding",
    "enterprise_financial_metric",
    "financial_fact",
    "service_record",
    "customer_interaction_note",
    "source_document",
    "import_batch",
    "data_quality_issue",
}
IGNORE_TABLES = {"stg_import_row"}

# Only fields that still require semantic extraction belong here. Structured
# description/raw_text columns are consumed directly below and must not create
# REQUIRES_LLM_EXTRACTION issues.
LLM_FIELDS: Dict[str, Set[str]] = {}

DIRECT_TEXT_FIELDS: Dict[str, Set[str]] = {
    "enterprise_event": {"event_description"},
    "financial_event": {"event_description"},
    "social_activity": {"activity_description"},
    "public_reputation": {"description"},
    "reputation_risk": {"risk_description"},
    "enterprise_business": {"business_description"},
    "financial_fact": {"description"},
    "family_member": {"member_description"},
    "person_family_relation": {"relation_description"},
    "succession_arrangement": {"arrangement_description"},
    "person_career": {"career_description"},
    "enterprise_market_relation": {"relation_description"},
    "person_social_relation": {"raw_text"},
    "person_enterprise_relation": {"raw_text"},
}

PENDING_FIELDS: Dict[str, Set[str]] = {
    "person_enterprise_relation": {"ownership_percentage", "voting_right_percentage"},
    "succession_arrangement": {"candidate_description"},
}

MYSQL_ONLY_FIELDS: Dict[str, Set[str]] = {
    "person_profile": {"residence", "health_summary"},
    "person": {"created_at", "updated_at"},
    "enterprise": {"created_at", "updated_at"},
    "customer_interaction_note": {"note_text"},
    "service_record": {"service_description"},
}

RULE_FIELDS: Dict[str, Set[str]] = {
    "person_profile": {
        "birth_date",
        "birth_year",
        "native_place",
        "birth_place",
        "school_name",
        "marital_status",
    },
    "person_career": {"organization_name", "position_title", "start_date", "end_date"},
    "enterprise": {
        "enterprise_name",
        "normalized_name",
        "stock_code",
        "registration_date",
        "listing_date",
        "registration_place",
        "headquarters",
        "employee_count",
    },
    "enterprise_business": {"business_line"},
    "person_enterprise_relation": {"relation_type"},
    "enterprise_market_relation": {"counterpart_name", "relation_type"},
    "family_member": {"member_name", "protected_alias", "public_disclosure_level"},
    "person_family_relation": {"relation_type"},
    "succession_arrangement": {"arrangement_status", "governance_model"},
    "social_organization": {"organization_name", "normalized_name"},
    "person_social_relation": {"relation_type"},
    "social_activity": {
        "activity_type",
        "activity_name",
        "partner_name",
        "activity_date",
        "amount",
        "currency_code",
    },
    "public_reputation": {
        "reputation_type",
        "event_date",
        "publish_date",
        "publication_date",
    },
    "reputation_risk": {"event_date", "publish_date"},
    "financial_event": {"event_type", "event_date", "amount", "currency_code"},
    "enterprise_event": {"event_type", "event_date"},
}

PERSON_ENTERPRISE_RELATION_MAP: Dict[str, str] = {
    "CHAIRPERSON": "CHAIRMAN_OF",
    "CHAIRMAN": "CHAIRMAN_OF",
    "CEO": "CEO_OF",
    "CHIEF_EXECUTIVE": "CEO_OF",
    "FOUNDER": "FOUNDED",
    "WORKS_AT": "WORKS_AT",
    "EMPLOYEE": "WORKS_AT",
    "SHAREHOLDER": "HOLDS_SHARE",
    "ACTUAL_CONTROLLER": "CONTROLS",
    "CONTROLLER": "CONTROLS",
    "CORE_ASSOCIATED": "RELATED_TO",
}

PERSON_SOCIAL_RELATION_MAP: Dict[str, str] = {
    "MEMBER": "MEMBER_OF",
    "MEMBER_OF": "MEMBER_OF",
    "ORGANIZATION_MEMBERSHIP": "MEMBER_OF",
    "EMPLOYMENT": "WORKS_FOR",
    "WORKS_FOR": "WORKS_FOR",
    "PUBLIC_ROLE": "RELATED_TO",
}

MARKET_RELATION_MAP: Dict[str, str] = {
    "UPSTREAM": "HAS_UPSTREAM",
    "HAS_UPSTREAM": "HAS_UPSTREAM",
    "DOWNSTREAM": "HAS_DOWNSTREAM",
    "HAS_DOWNSTREAM": "HAS_DOWNSTREAM",
    "COMPETITOR": "COMPETES_WITH",
    "COMPETES_WITH": "COMPETES_WITH",
}

GENERIC_MARKET_SEGMENT_NAMES: Set[str] = {
    "服务器供应商",
    "内容创作者",
    "游戏开发商",
    "c端用户",
    "广告主",
    "企业客户",
}

FINANCIAL_EVENT_TYPE_MAP: Dict[str, str] = {
    "INVESTMENT": "INVESTMENT",
    "FINANCING": "FINANCING",
    "DONATION": "DONATION",
    "DIVESTMENT": "DIVESTMENT",
    "DIVIDEND": "DIVIDEND",
    "DEBT_REPAYMENT": "DEBT_REPAYMENT",
    "GIFT": "GIFT",
    "SHARE_INCREASE": "SHARE_INCREASE",
    "SHARE_DECREASE": "SHARE_DECREASE",
}

ENTERPRISE_EVENT_TYPE_MAP: Dict[str, str] = {
    "CAPITAL_OPERATION": "CAPITAL_OPERATION",
    "CORPORATE_GOVERNANCE": "CORPORATE_GOVERNANCE",
    "INDUSTRY_TREND": "INDUSTRY_TREND",
    "FINANCING": "FINANCING",
    "BUYBACK": "CAPITAL_OPERATION",
    "REGULATORY_OR_LEGAL": "REGULATORY",
    "REGULATORY": "REGULATORY",
    "LEGAL": "LEGAL",
    "OPERATING_EVENT": "OPERATING_EVENT",
    "INDUSTRY_POLICY": "INDUSTRY_POLICY",
}

SOCIAL_EVENT_TYPE_MAP: Dict[str, str] = {
    "CHARITY_ESG": "CHARITY_ACTIVITY",
    "CHARITY": "CHARITY_ACTIVITY",
    "ESG": "ESG_ACTIVITY",
    "ACADEMIC": "ACADEMIC_ACTIVITY",
    "ACADEMIC_COOPERATION": "ACADEMIC_ACTIVITY",
    "RESEARCH_COLLABORATION": "RESEARCH_COLLABORATION",
    "PUBLIC_SERVICE": "SOCIAL_ACTIVITY",
    "SOCIAL_ACTIVITY": "SOCIAL_ACTIVITY",
}

PUBLIC_REPUTATION_TYPE_MAP: Dict[str, str] = {
    "HONOR_OR_PUBLIC_EVALUATION": "HONOR",
    "MEDIA_ATTENTION": "MEDIA_ATTENTION",
    "HONOR": "HONOR",
    "PUBLIC_EVALUATION": "HONOR",
}

SAFE_ISSUE_PREVIEW_FIELDS = {
    "employee_count",
    "ownership_percentage",
    "voting_right_percentage",
    "amount",
    "currency_code",
    "relation_type",
    "event_type",
    "activity_type",
    "candidate_description",
}


@dataclass
class ReadFailure:
    table_name: str
    message: str


@dataclass
class ExtractionInput:
    person_id: int
    records: Dict[str, List[Dict[str, Any]]] = field(default_factory=lambda: defaultdict(list))
    sources: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    import_batches: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    read_failures: List[ReadFailure] = field(default_factory=list)


class StructuredMapper:
    def __init__(self, data: ExtractionInput):
        self.data = data
        self.person_id = str(data.person_id)
        self.nodes: Dict[str, GraphNode] = {}
        self.relations: Dict[str, GraphRelation] = {}
        self.events: Dict[str, GraphEvent] = {}
        self.issues: Dict[str, GraphIssue] = {}
        self.field_counts: Dict[str, int] = {
            "DIRECT": 0,
            "RULE": 0,
            "LLM": 0,
            "PENDING": 0,
            "MYSQL_ONLY": 0,
            "IGNORE": 0,
        }

    def map_candidates(self) -> CandidateExtraction:
        self._scan_field_treatments()
        for failure in self.data.read_failures:
            self._add_issue(
                reason="READ_FAILED",
                severity="ERROR",
                message=failure.message,
                source_table=failure.table_name,
                requires_manual_confirmation=True,
            )

        person = self._map_person()
        if person is not None:
            self._map_enterprises(person)
            self._map_family(person)
            self._map_organizations(person)
            self._map_direct_relations(person)
            self._map_events(person)

        self._validate_candidates()
        return CandidateExtraction(
            person_id=self.person_id,
            nodes=sorted(self.nodes.values(), key=lambda item: item.node_id),
            relations=sorted(self.relations.values(), key=lambda item: item.relation_id),
            events=sorted(self.events.values(), key=lambda item: item.event_id),
            issues=sorted(self.issues.values(), key=lambda item: item.issue_id),
            table_record_counts={
                table: len(rows) for table, rows in sorted(self.data.records.items())
            },
            field_treatment_counts=dict(self.field_counts),
            skipped_tables=sorted(IGNORE_TABLES),
        )

    def _treatment_for(self, table: str, field_name: str, row: Mapping[str, Any]) -> str:
        if table in IGNORE_TABLES:
            return "IGNORE"
        if field_name in {"created_at", "updated_at"}:
            return "MYSQL_ONLY"
        if field_name in MYSQL_ONLY_FIELDS.get(table, set()):
            return "MYSQL_ONLY"
        if field_name in DIRECT_TEXT_FIELDS.get(table, set()):
            return "DIRECT"
        if table in MYSQL_ONLY_TABLES:
            return "MYSQL_ONLY"
        if (
            table in {"financial_event", "social_activity"}
            and field_name == "currency_code"
            and is_empty_or_invalid(row.get("amount"))
        ):
            return "MYSQL_ONLY"
        if field_name in LLM_FIELDS.get(table, set()):
            return "LLM"
        if field_name in PENDING_FIELDS.get(table, set()):
            return "PENDING"
        if field_name in RULE_FIELDS.get(table, set()):
            return "RULE"
        return "DIRECT"

    def _scan_field_treatments(self) -> None:
        for table, rows in self.data.records.items():
            for row in rows:
                source_pk = self._source_pk(table, row)
                for field_name, value in row.items():
                    marital_unknown_enum = (
                        table == "person_profile"
                        and field_name == "marital_status"
                        and value is not None
                        and str(value).strip().upper() == "UNKNOWN"
                    )
                    if is_empty_or_invalid(value) and not marital_unknown_enum:
                        continue
                    treatment = self._treatment_for(table, field_name, row)
                    self.field_counts[treatment] += 1
                    if treatment == "LLM":
                        self._add_issue(
                            reason="REQUIRES_LLM_EXTRACTION",
                            severity="INFO",
                            message="字段按映射方案需要大模型抽取，本轮未处理。",
                            source_table=table,
                            source_pk=source_pk,
                            field_name=field_name,
                            source_id=self._as_id(row.get("source_id")),
                            requires_manual_confirmation=True,
                        )
                    elif treatment == "PENDING":
                        preview = safe_preview(value) if field_name in SAFE_ISSUE_PREVIEW_FIELDS else None
                        self._add_issue(
                            reason="MAPPING_PENDING",
                            severity="WARNING",
                            message="字段映射或单位尚未确认，本轮不生成图谱属性或关系。",
                            source_table=table,
                            source_pk=source_pk,
                            field_name=field_name,
                            value_preview=preview,
                            source_id=self._as_id(row.get("source_id")),
                            requires_manual_confirmation=True,
                        )

    def _map_person(self) -> Optional[GraphNode]:
        rows = self.data.records.get("person", [])
        if not rows:
            self._add_issue(
                reason="PERSON_NOT_FOUND",
                severity="ERROR",
                message=f"未找到 person_id={self.person_id} 的人物记录。",
                source_table="person",
                source_pk=self.person_id,
                requires_manual_confirmation=True,
            )
            return None
        if len(rows) > 1:
            self._add_issue(
                reason="DUPLICATE_PERSON_RECORD",
                severity="ERROR",
                message="同一 person_id 返回多条人物主记录。",
                source_table="person",
                source_pk=self.person_id,
                requires_manual_confirmation=True,
            )

        row = rows[0]
        properties: Dict[str, Any] = {
            "normalized_name": clean_text(row.get("normalized_name")),
            "person_type": clean_text(row.get("person_type")),
            "mysql_source_table": "person",
            "mysql_source_pk": self.person_id,
        }
        profile = self._first("person_profile")
        source_id = self._as_id(profile.get("source_id")) if profile else None
        if profile:
            for source_field in ("gender", "education_level"):
                value = clean_text(profile.get(source_field))
                if value is not None:
                    properties[source_field] = value
            self._put_year(properties, "birth_year", profile.get("birth_year"), "person_profile", profile)
            self._put_date(properties, "birth_date", profile.get("birth_date"), "person_profile", profile)
            for source_field in ("native_place", "birth_place"):
                value = clean_text(profile.get(source_field))
                if value is not None:
                    properties[source_field] = value
            school = clean_text(profile.get("school_name"))
            if school is not None:
                properties["education_summary"] = school
            raw_marital_status = profile.get("marital_status")
            try:
                marital_status = normalize_marital_status(raw_marital_status)
            except MaritalStatusConflictError:
                self._add_issue(
                    reason="MARITAL_STATUS_CONFLICT",
                    severity="WARNING",
                    message="婚姻状态文本同时包含多个不同状态，未自动选择。",
                    source_table="person_profile",
                    source_pk=self._source_pk("person_profile", profile),
                    field_name="marital_status",
                    value_preview=clean_text(raw_marital_status),
                    source_id=source_id,
                    requires_manual_confirmation=True,
                )
            except RuleParseError:
                self._unknown_enum(
                    "person_profile",
                    profile,
                    "marital_status",
                    clean_text(raw_marital_status) or str(raw_marital_status).strip(),
                )
            else:
                if marital_status is not None:
                    properties["marital_status"] = marital_status.value
                    properties["raw_marital_status"] = marital_status.raw_value
            properties["source_ids"] = [source_id] if source_id else []
        financial_fact_descriptions = self._raw_text_values(
            self.data.records.get("financial_fact", []), "description"
        )
        if financial_fact_descriptions:
            properties["description"] = self._single_or_list(
                financial_fact_descriptions
            )

        node = GraphNode(
            node_id=person_node_id(row.get("person_id")),
            node_type="Person",
            name=clean_text(row.get("full_name")),
            properties=self._without_none(properties),
            person_id=self.person_id,
            source_id=source_id,
            verification_status=self._verification(row.get("verification_status"), source_id),
            confidence=1.0,
            import_batch_id=self._import_batch_id(source_id),
            evidence_text=None,
        )
        self._add_node(node, "person", self.person_id)
        if source_id is None:
            self._missing_source("person", self.person_id, None)
        return node

    def _map_enterprises(self, person: GraphNode) -> None:
        business_by_enterprise: Dict[str, List[Mapping[str, Any]]] = defaultdict(list)
        for row in self.data.records.get("enterprise_business", []):
            business_by_enterprise[self._as_id(row.get("enterprise_id")) or ""].append(row)
        relation_rows = self.data.records.get("person_enterprise_relation", [])

        for row in self.data.records.get("enterprise", []):
            enterprise_id_value = row.get("enterprise_id")
            enterprise_id_text = self._as_id(enterprise_id_value)
            if enterprise_id_text is None:
                self._parse_issue("enterprise", row, "enterprise_id", "企业主键为空。")
                continue
            source_row = next(
                (item for item in relation_rows if self._as_id(item.get("enterprise_id")) == enterprise_id_text),
                None,
            )
            source_id = self._as_id(source_row.get("source_id")) if source_row else None
            try:
                stock_code = parse_stock_code(row.get("stock_code"))
            except RuleParseError as exc:
                stock_code = None
                self._parse_issue("enterprise", row, "stock_code", str(exc))
            try:
                name = clean_enterprise_name(row.get("enterprise_name"), stock_code)
            except RuleParseError as exc:
                name = clean_text(row.get("enterprise_name"))
                self._parse_issue("enterprise", row, "enterprise_name", str(exc))

            properties: Dict[str, Any] = {
                "normalized_name": normalize_organization_name(name),
                "stock_code": stock_code,
                "industry": clean_text(row.get("industry_name")),
                "registration_place": clean_text(row.get("registration_place")),
                "mysql_source_table": "enterprise",
                "mysql_source_pk": enterprise_id_text,
            }
            try:
                employee_count = parse_employee_count(row.get("employee_count"))
            except RuleParseError as exc:
                self._parse_issue("enterprise", row, "employee_count", str(exc))
            else:
                if employee_count is not None:
                    properties["employee_count"] = employee_count
            self._put_date(properties, "registration_date", row.get("registration_date"), "enterprise", row)
            self._put_date(properties, "listing_date", row.get("listing_date"), "enterprise", row)
            headquarters = clean_text(row.get("headquarters"))
            if headquarters:
                if len(headquarters) <= 20 and not any(
                    marker in headquarters for marker in ("路", "街", "号", "大厦", "园区", "栋")
                ):
                    properties["headquarters_region"] = headquarters
                else:
                    self._add_issue(
                        reason="MAPPING_PENDING",
                        severity="INFO",
                        message="企业总部为详细地址，本轮不写入图谱，仅保留 MySQL。",
                        source_table="enterprise",
                        source_pk=enterprise_id_text,
                        field_name="headquarters",
                        source_id=source_id,
                        requires_manual_confirmation=True,
                    )

            business_lines = sorted(
                {
                    value
                    for item in business_by_enterprise.get(enterprise_id_text, [])
                    if (value := clean_text(item.get("business_line"))) is not None
                }
            )
            if business_lines:
                properties["business_lines"] = business_lines
            business_descriptions = self._raw_text_values(
                business_by_enterprise.get(enterprise_id_text, []),
                "business_description",
            )
            if business_descriptions:
                properties["description"] = self._single_or_list(business_descriptions)
            source_ids = sorted(
                {
                    sid
                    for item in business_by_enterprise.get(enterprise_id_text, [])
                    if (sid := self._as_id(item.get("source_id"))) is not None
                }
                | ({source_id} if source_id else set())
            )
            properties["source_ids"] = source_ids

            node = GraphNode(
                node_id=enterprise_node_id(enterprise_id_value),
                node_type="Enterprise",
                name=name,
                properties=self._without_none(properties),
                person_id=self.person_id,
                source_id=source_id,
                verification_status=self._verification(row.get("verification_status"), source_id),
                confidence=1.0,
                import_batch_id=self._import_batch_id(source_id),
                evidence_text=None,
            )
            self._add_node(node, "enterprise", enterprise_id_text)
            if source_id is None:
                self._missing_source("enterprise", enterprise_id_text, None)

    def _map_family(self, person: GraphNode) -> None:
        family_rows = self.data.records.get("family_member", [])
        family_relations = self.data.records.get("person_family_relation", [])
        succession_rows = self.data.records.get("succession_arrangement", [])
        if family_rows or family_relations or succession_rows:
            all_sources = self._source_ids([*family_rows, *family_relations, *succession_rows])
            source_id = all_sources[0] if all_sources else None
            arrangement_types = sorted(
                {
                    value
                    for row in succession_rows
                    if (value := clean_text(row.get("arrangement_status"))) is not None
                }
            )
            governance_models = sorted(
                {
                    value
                    for row in succession_rows
                    if (value := clean_text(row.get("governance_model"))) is not None
                }
            )
            profile = GraphNode(
                node_id=family_profile_node_id(self.person_id),
                node_type="FamilyProfile",
                name=None,
                properties=self._without_none(
                    {
                        "person_id": self.person_id,
                        "arrangement_types": arrangement_types,
                        "governance_models": governance_models,
                        "description": self._single_or_list(
                            self._raw_text_values(
                                succession_rows, "arrangement_description"
                            )
                        ),
                        "source_ids": all_sources,
                        "mysql_source_table": "derived_family_profile",
                        "mysql_source_pk": self.person_id,
                    }
                ),
                person_id=self.person_id,
                source_id=source_id,
                verification_status=self._aggregate_verification(
                    [*family_rows, *family_relations, *succession_rows], source_id
                ),
                confidence=1.0,
                import_batch_id=self._import_batch_id(source_id),
                evidence_text=None,
            )
            profile.verification_status = "PENDING"
            self._add_node(profile, "derived_family_profile", self.person_id)

        for row in family_rows:
            member_id = self._as_id(row.get("family_member_id"))
            if member_id is None:
                self._parse_issue("family_member", row, "family_member_id", "家庭成员主键为空。")
                continue
            disclosure = (clean_text(row.get("public_disclosure_level")) or "").upper()
            alias = clean_text(row.get("protected_alias"))
            member_name = clean_text(row.get("member_name"))
            if disclosure == "RESTRICTED":
                display_name = alias or f"FamilyMember:{member_id}"
            else:
                display_name = member_name or alias or f"FamilyMember:{member_id}"
            source_id = self._as_id(row.get("source_id"))
            node = GraphNode(
                node_id=family_member_node_id(member_id),
                node_type="FamilyMember",
                name=display_name,
                properties=self._without_none(
                    {
                        "family_member_id": member_id,
                        "disclosure_level": disclosure or None,
                        "protected_alias": alias,
                        "description": self._raw_text(row.get("member_description")),
                        "mysql_source_table": "family_member",
                        "mysql_source_pk": member_id,
                    }
                ),
                person_id=self.person_id,
                source_id=source_id,
                verification_status="PENDING",
                confidence=1.0,
                import_batch_id=self._import_batch_id(source_id),
                evidence_text=None,
            )
            self._add_node(node, "family_member", member_id)

    def _map_organizations(self, person: GraphNode) -> None:
        social_relations = self.data.records.get("person_social_relation", [])
        for row in self.data.records.get("social_organization", []):
            organization_id_value = row.get("social_organization_id")
            organization_id_text = self._as_id(organization_id_value)
            if organization_id_text is None:
                self._parse_issue(
                    "social_organization", row, "social_organization_id", "社会组织主键为空。"
                )
                continue
            source_row = next(
                (
                    item
                    for item in social_relations
                    if self._as_id(item.get("social_organization_id")) == organization_id_text
                ),
                None,
            )
            source_id = self._as_id(source_row.get("source_id")) if source_row else None
            name = clean_text(row.get("organization_name"))
            node = GraphNode(
                node_id=organization_node_id(organization_id_value),
                node_type="Organization",
                name=name,
                properties=self._without_none(
                    {
                        "normalized_name": normalize_organization_name(name),
                        "organization_type": clean_text(row.get("organization_type")),
                        "mysql_source_table": "social_organization",
                        "mysql_source_pk": organization_id_text,
                    }
                ),
                person_id=self.person_id,
                source_id=source_id,
                verification_status=self._verification(
                    source_row.get("verification_status") if source_row else None,
                    source_id,
                ),
                confidence=1.0,
                import_batch_id=self._import_batch_id(source_id),
                evidence_text=None,
            )
            self._add_node(node, "social_organization", organization_id_text)
            if source_id is None:
                self._missing_source("social_organization", organization_id_text, None)

    def _map_direct_relations(self, person: GraphNode) -> None:
        self._map_person_enterprise_relations(person)
        self._map_career_relations(person)
        self._map_family_relations(person)
        self._map_family_profile_relation(person)
        self._map_social_relations(person)
        self._map_market_relations()

    def _map_person_enterprise_relations(self, person: GraphNode) -> None:
        for row in self.data.records.get("person_enterprise_relation", []):
            source_pk = self._source_pk("person_enterprise_relation", row)
            enterprise_id_text = self._as_id(row.get("enterprise_id"))
            raw_type = (clean_text(row.get("relation_type")) or "").upper()
            relation_type = PERSON_ENTERPRISE_RELATION_MAP.get(raw_type)
            if relation_type is None:
                self._unknown_enum("person_enterprise_relation", row, "relation_type", raw_type)
                continue
            if enterprise_id_text is None:
                self._unmatched("person_enterprise_relation", row, "enterprise_id", "企业 ID 为空。")
                continue
            end_id = enterprise_node_id(enterprise_id_text)
            source_id = self._as_id(row.get("source_id"))
            properties = self._without_none(
                {
                    "title": clean_text(row.get("title")),
                    "is_core_relation": bool(row.get("is_core_relation")),
                    "valid_from": self._date_value(
                        row.get("valid_from"), "person_enterprise_relation", row, "valid_from"
                    ),
                    "valid_to": self._date_value(
                        row.get("valid_to"), "person_enterprise_relation", row, "valid_to"
                    ),
                    "raw_relation_type": raw_type,
                    "source_level": clean_text(row.get("source_level")),
                    "mysql_source_table": "person_enterprise_relation",
                    "mysql_source_pk": source_pk,
                }
            )
            relation = GraphRelation(
                relation_id=relation_id(
                    start_node_id=person.node_id,
                    relation_type=relation_type,
                    end_node_id=end_id,
                    source_id=source_id,
                    source_table="person_enterprise_relation",
                    source_pk=source_pk,
                ),
                start_node_id=person.node_id,
                end_node_id=end_id,
                relation_type=relation_type,
                properties=properties,
                dimension="ENTERPRISE",
                source_id=source_id,
                verification_status=self._verification(row.get("verification_status"), source_id),
                confidence=1.0,
                evidence_text=self._raw_text(row.get("raw_text")),
                import_batch_id=self._import_batch_id(source_id),
            )
            self._add_relation(relation, "person_enterprise_relation", source_pk)
            if relation_type in {"CONTROLS", "HOLDS_SHARE"} and relation.verification_status != "CONFIRMED":
                self._add_issue(
                    reason="STRICT_RELATION_VERIFICATION",
                    severity="WARNING",
                    message="控制或持股关系必须人工确认，当前仅保留 PENDING 候选。",
                    source_table="person_enterprise_relation",
                    source_pk=source_pk,
                    field_name="relation_type",
                    source_id=source_id,
                    requires_manual_confirmation=True,
                )

    def _map_career_relations(self, person: GraphNode) -> None:
        entity_by_name: Dict[str, GraphNode] = {}
        for node in self.nodes.values():
            if node.node_type not in {"Enterprise", "Organization"}:
                continue
            normalized = node.properties.get("normalized_name")
            if normalized:
                entity_by_name[str(normalized)] = node

        existing_semantics = {
            (relation.start_node_id, relation.relation_type, relation.end_node_id)
            for relation in self.relations.values()
        }
        for row in self.data.records.get("person_career", []):
            source_pk = self._source_pk("person_career", row)
            organization_text = clean_text(row.get("organization_name"))
            try:
                embedded_code = parse_stock_code(organization_text)
            except RuleParseError:
                embedded_code = None
            try:
                cleaned_organization = clean_enterprise_name(organization_text, embedded_code)
            except RuleParseError:
                cleaned_organization = organization_text
            normalized = normalize_organization_name(cleaned_organization)
            target = entity_by_name.get(str(normalized))
            if target is None:
                self._unmatched(
                    "person_career",
                    row,
                    "organization_name",
                    "职业记录中的机构无法唯一匹配到已加载 Enterprise/Organization。",
                )
                continue

            title = clean_text(row.get("position_title")) or ""
            title_upper = title.upper()
            relation_type_values: List[str] = []
            if "董事长" in title or "主席" in title:
                relation_type_values.append("CHAIRMAN_OF")
            if "首席执行官" in title or "CEO" in title_upper:
                relation_type_values.append("CEO_OF")
            if "创始人" in title or "创办人" in title:
                relation_type_values.append("FOUNDED")
            if not relation_type_values and title:
                relation_type_values.append(
                    "WORKS_AT" if target.node_type == "Enterprise" else "WORKS_FOR"
                )
            if not relation_type_values:
                self._add_issue(
                    reason="MAPPING_PENDING",
                    severity="WARNING",
                    message="职业职位为空，无法通过固定规则确定关系类型。",
                    source_table="person_career",
                    source_pk=source_pk,
                    field_name="position_title",
                    source_id=self._as_id(row.get("source_id")),
                    requires_manual_confirmation=True,
                )
                continue
            source_id = self._as_id(row.get("source_id"))
            for relation_type_value in relation_type_values:
                semantic_key = (person.node_id, relation_type_value, target.node_id)
                if semantic_key in existing_semantics:
                    self._add_issue(
                        reason="DUPLICATE_RELATION_CANDIDATE",
                        severity="INFO",
                        message=(
                            "职业记录与显式关系表产生相同关系候选；"
                            "保留显式关系表结果。"
                        ),
                        source_table="person_career",
                        source_pk=source_pk,
                        field_name="position_title",
                        source_id=source_id,
                    )
                    continue
                relation = GraphRelation(
                    relation_id=relation_id(
                        start_node_id=person.node_id,
                        relation_type=relation_type_value,
                        end_node_id=target.node_id,
                        source_id=source_id,
                        source_table="person_career",
                        source_pk=source_pk,
                    ),
                    start_node_id=person.node_id,
                    end_node_id=target.node_id,
                    relation_type=relation_type_value,
                    properties=self._without_none(
                        {
                            "title": title or None,
                            "start_date": self._date_value(
                                row.get("start_date"),
                                "person_career",
                                row,
                                "start_date",
                            ),
                            "end_date": self._date_value(
                                row.get("end_date"),
                                "person_career",
                                row,
                                "end_date",
                            ),
                            "description": self._raw_text(
                                row.get("career_description")
                            ),
                            "mysql_source_table": "person_career",
                            "mysql_source_pk": source_pk,
                        }
                    ),
                    dimension="PERSON",
                    source_id=source_id,
                    verification_status=self._verification(
                        row.get("verification_status"), source_id
                    ),
                    confidence=1.0,
                    evidence_text=None,
                    import_batch_id=self._import_batch_id(source_id),
                )
                self._add_relation(relation, "person_career", source_pk)
                existing_semantics.add(semantic_key)

    def _map_family_relations(self, person: GraphNode) -> None:
        for row in self.data.records.get("person_family_relation", []):
            source_pk = self._source_pk("person_family_relation", row)
            member_id = self._as_id(row.get("family_member_id"))
            if member_id is None:
                self._unmatched("person_family_relation", row, "family_member_id", "家庭成员 ID 为空。")
                continue
            source_id = self._as_id(row.get("source_id"))
            relation = GraphRelation(
                relation_id=relation_id(
                    start_node_id=person.node_id,
                    relation_type="FAMILY_OF",
                    end_node_id=family_member_node_id(member_id),
                    source_id=source_id,
                    source_table="person_family_relation",
                    source_pk=source_pk,
                ),
                start_node_id=person.node_id,
                end_node_id=family_member_node_id(member_id),
                relation_type="FAMILY_OF",
                properties=self._without_none(
                    {
                        "relation_type": clean_text(row.get("relation_type")),
                        "description": self._raw_text(
                            row.get("relation_description")
                        ),
                        "mysql_source_table": "person_family_relation",
                        "mysql_source_pk": source_pk,
                    }
                ),
                dimension="FAMILY",
                source_id=source_id,
                verification_status="PENDING",
                confidence=1.0,
                evidence_text=None,
                import_batch_id=self._import_batch_id(source_id),
            )
            self._add_relation(relation, "person_family_relation", source_pk)
            if relation.verification_status != "CONFIRMED":
                self._add_issue(
                    reason="STRICT_FAMILY_VERIFICATION",
                    severity="WARNING",
                    message="家庭关系默认严格核验；当前关系只能作为 PENDING 候选。",
                    source_table="person_family_relation",
                    source_pk=source_pk,
                    field_name="relation_type",
                    source_id=source_id,
                    requires_manual_confirmation=True,
                )

    def _map_family_profile_relation(self, person: GraphNode) -> None:
        profile_id = family_profile_node_id(self.person_id)
        profile = self.nodes.get(profile_id)
        if profile is None:
            return
        source_pk = self.person_id
        relation = GraphRelation(
            relation_id=relation_id(
                start_node_id=person.node_id,
                relation_type="HAS_FAMILY_PROFILE",
                end_node_id=profile_id,
                source_id=profile.source_id,
                source_table="derived_family_profile",
                source_pk=source_pk,
            ),
            start_node_id=person.node_id,
            end_node_id=profile_id,
            relation_type="HAS_FAMILY_PROFILE",
            properties={"profile_scope": "FAMILY"},
            dimension="FAMILY",
            source_id=profile.source_id,
            verification_status=profile.verification_status,
            confidence=1.0,
            evidence_text=None,
            import_batch_id=profile.import_batch_id,
        )
        self._add_relation(relation, "derived_family_profile", source_pk)

    def _map_social_relations(self, person: GraphNode) -> None:
        for row in self.data.records.get("person_social_relation", []):
            source_pk = self._source_pk("person_social_relation", row)
            organization_id_text = self._as_id(row.get("social_organization_id"))
            raw_type = (clean_text(row.get("relation_type")) or "").upper()
            relation_type = PERSON_SOCIAL_RELATION_MAP.get(raw_type)
            if relation_type is None:
                self._unknown_enum("person_social_relation", row, "relation_type", raw_type)
                continue
            if organization_id_text is None:
                self._unmatched(
                    "person_social_relation", row, "social_organization_id", "社会组织 ID 为空。"
                )
                continue
            end_node_id = organization_node_id(organization_id_text)
            end_node = self.nodes.get(end_node_id)
            if end_node is None or end_node.node_type != "Organization":
                self._unmatched(
                    "person_social_relation",
                    row,
                    "social_organization_id",
                    "social_organization_id 无法唯一匹配到已加载 Organization；未生成关系。",
                )
                continue
            source_id = self._as_id(row.get("source_id"))
            relation = GraphRelation(
                relation_id=relation_id(
                    start_node_id=person.node_id,
                    relation_type=relation_type,
                    end_node_id=end_node_id,
                    source_id=source_id,
                    source_table="person_social_relation",
                    source_pk=source_pk,
                ),
                start_node_id=person.node_id,
                end_node_id=end_node_id,
                relation_type=relation_type,
                properties=self._without_none(
                    {
                        "role_title": clean_text(row.get("role_title")),
                        "start_date": self._date_value(
                            row.get("valid_from"), "person_social_relation", row, "valid_from"
                        ),
                        "end_date": self._date_value(
                            row.get("valid_to"), "person_social_relation", row, "valid_to"
                        ),
                        "raw_relation_type": raw_type,
                        "mysql_source_table": "person_social_relation",
                        "mysql_source_pk": source_pk,
                    }
                ),
                dimension="SOCIAL",
                source_id=source_id,
                verification_status=self._verification(row.get("verification_status"), source_id),
                confidence=1.0,
                evidence_text=self._raw_text(row.get("raw_text")),
                import_batch_id=self._import_batch_id(source_id),
            )
            self._add_relation(relation, "person_social_relation", source_pk)

    def _map_market_relations(self) -> None:
        enterprise_by_name: Dict[str, GraphNode] = {}
        market_segment_by_name: Dict[str, GraphNode] = {}
        for node in self.nodes.values():
            normalized = node.properties.get("normalized_name")
            if not normalized:
                continue
            if node.node_type == "Enterprise":
                enterprise_by_name.setdefault(str(normalized), node)
            elif node.node_type == "MarketSegment":
                market_segment_by_name.setdefault(str(normalized), node)

        for row in self.data.records.get("enterprise_market_relation", []):
            source_pk = self._source_pk("enterprise_market_relation", row)
            source_enterprise_id = self._as_id(row.get("enterprise_id"))
            source_id = self._as_id(row.get("source_id"))
            raw_counterpart = row.get("counterpart_name")
            counterpart = clean_text(raw_counterpart)
            if counterpart is None:
                self._add_issue(
                    reason="COUNTERPART_NAME_MISSING",
                    severity="WARNING",
                    message="市场关系 counterpart_name 为空，无法创建引用端点。",
                    source_table="enterprise_market_relation",
                    source_pk=source_pk,
                    field_name="counterpart_name",
                    value_preview=(
                        raw_counterpart
                        if isinstance(raw_counterpart, str)
                        else str(raw_counterpart) if raw_counterpart is not None else None
                    ),
                    source_id=source_id,
                    requires_manual_confirmation=True,
                )
                continue

            normalized_counterpart = normalize_organization_name(counterpart)
            if normalized_counterpart is None:
                self._add_issue(
                    reason="COUNTERPART_NAME_MISSING",
                    severity="WARNING",
                    message="市场关系 counterpart_name 无法标准化，无法创建引用端点。",
                    source_table="enterprise_market_relation",
                    source_pk=source_pk,
                    field_name="counterpart_name",
                    value_preview=(
                        raw_counterpart
                        if isinstance(raw_counterpart, str)
                        else str(raw_counterpart)
                    ),
                    source_id=source_id,
                    requires_manual_confirmation=True,
                )
                continue

            if source_enterprise_id is None:
                self._unmatched(
                    "enterprise_market_relation",
                    row,
                    "enterprise_id",
                    "市场关系起点 enterprise_id 为空。",
                )
                continue
            start_id = enterprise_node_id(source_enterprise_id)
            if start_id not in self.nodes:
                self._unmatched(
                    "enterprise_market_relation",
                    row,
                    "enterprise_id",
                    "市场关系起点 Enterprise 未加载。",
                )
                continue

            raw_type = (clean_text(row.get("relation_type")) or "").upper()
            relation_type = MARKET_RELATION_MAP.get(raw_type)
            if relation_type is None:
                self._unknown_enum("enterprise_market_relation", row, "relation_type", raw_type)
                continue

            created_from_reference = False
            if relation_type == "COMPETES_WITH":
                target = enterprise_by_name.get(normalized_counterpart)
                if target is None:
                    if normalized_counterpart in GENERIC_MARKET_SEGMENT_NAMES:
                        self._add_issue(
                            reason="COUNTERPART_NOT_ENTERPRISE",
                            severity="WARNING",
                            message="泛化市场角色不能作为竞争企业候选，未创建端点或关系。",
                            source_table="enterprise_market_relation",
                            source_pk=source_pk,
                            field_name="counterpart_name",
                            value_preview=(
                                raw_counterpart
                                if isinstance(raw_counterpart, str)
                                else str(raw_counterpart)
                            ),
                            source_id=source_id,
                            requires_manual_confirmation=True,
                        )
                        continue
                    target = GraphNode(
                        node_id=reference_enterprise_node_id(normalized_counterpart),
                        node_type="Enterprise",
                        name=counterpart,
                        properties={
                            "normalized_name": normalized_counterpart,
                            "profile_completeness": "MINIMAL",
                            "created_from_reference": True,
                            "mysql_source_table": "enterprise_market_relation",
                            "mysql_source_pk": source_pk,
                        },
                        person_id=self.person_id,
                        source_id=source_id,
                        verification_status="PENDING",
                        confidence=1.0,
                        import_batch_id=self._import_batch_id(source_id),
                    )
                    self._add_node(target, "enterprise_market_relation", source_pk)
                    enterprise_by_name[normalized_counterpart] = target
                    created_from_reference = True
                else:
                    created_from_reference = bool(
                        target.properties.get("created_from_reference")
                    )
            else:
                segment_type = (
                    "UPSTREAM" if relation_type == "HAS_UPSTREAM" else "DOWNSTREAM"
                )
                target = market_segment_by_name.get(normalized_counterpart)
                if target is None:
                    target = GraphNode(
                        node_id=market_segment_node_id(normalized_counterpart),
                        node_type="MarketSegment",
                        name=counterpart,
                        properties={
                            "normalized_name": normalized_counterpart,
                            "segment_type": segment_type,
                            "created_from_reference": True,
                            "mysql_source_table": "enterprise_market_relation",
                            "mysql_source_pk": source_pk,
                        },
                        person_id=self.person_id,
                        source_id=source_id,
                        verification_status="PENDING",
                        confidence=1.0,
                        import_batch_id=self._import_batch_id(source_id),
                    )
                    self._add_node(target, "enterprise_market_relation", source_pk)
                    market_segment_by_name[normalized_counterpart] = target
                created_from_reference = True

            relation = GraphRelation(
                relation_id=relation_id(
                    start_node_id=start_id,
                    relation_type=relation_type,
                    end_node_id=target.node_id,
                    source_id=source_id,
                    source_table="enterprise_market_relation",
                    source_pk=source_pk,
                ),
                start_node_id=start_id,
                end_node_id=target.node_id,
                relation_type=relation_type,
                properties=self._without_none(
                    {
                        "raw_relation_type": raw_type,
                        "description": self._raw_text(
                            row.get("relation_description")
                        ),
                        "mysql_source_table": "enterprise_market_relation",
                        "mysql_source_pk": source_pk,
                    }
                ),
                dimension="ENTERPRISE",
                source_id=source_id,
                verification_status=(
                    "PENDING"
                    if created_from_reference
                    else self._verification(row.get("verification_status"), source_id)
                ),
                confidence=1.0,
                evidence_text=None,
                import_batch_id=self._import_batch_id(source_id),
            )
            self._add_relation(relation, "enterprise_market_relation", source_pk)

    def _map_events(self, person: GraphNode) -> None:
        self._events_from_rows(
            table="financial_event",
            subject_field="person_id",
            subject_node=person,
            pk_field="financial_event_id",
            type_field="event_type",
            type_map=FINANCIAL_EVENT_TYPE_MAP,
            date_field="event_date",
            description_field="event_description",
            dimension="PERSON",
            relation_type="HAS_EVENT",
        )

        enterprises = {
            node.properties.get("mysql_source_pk"): node
            for node in self.nodes.values()
            if node.node_type == "Enterprise"
        }
        for row in self.data.records.get("enterprise_event", []):
            enterprise = enterprises.get(self._as_id(row.get("enterprise_id")))
            if enterprise is None:
                self._unmatched("enterprise_event", row, "enterprise_id", "事件主体企业未匹配。")
                continue
            self._event_from_row(
                table="enterprise_event",
                row=row,
                subject=enterprise,
                pk_field="enterprise_event_id",
                type_field="event_type",
                type_map=ENTERPRISE_EVENT_TYPE_MAP,
                date_field="event_date",
                description=self._raw_text(row.get("event_description")),
                dimension="ENTERPRISE",
                relation_type="HAS_EVENT",
                extra_properties={"risk_level": clean_text(row.get("risk_level"))},
            )

        for row in self.data.records.get("social_activity", []):
            raw_type = (clean_text(row.get("activity_type")) or "").upper()
            if raw_type == "RESEARCH_COLLABORATION":
                if self._map_research_collaboration(person, row):
                    continue
                if is_empty_or_invalid(row.get("activity_name")):
                    self._add_issue(
                        reason="MAPPING_PENDING",
                        severity="WARNING",
                        message=(
                            "研究合作既无可匹配组织，也无结构化 activity_name；"
                            "本轮不从 LLM 描述猜测事件。"
                        ),
                        source_table="social_activity",
                        source_pk=self._source_pk("social_activity", row),
                        field_name="activity_name",
                        source_id=self._as_id(row.get("source_id")),
                        requires_manual_confirmation=True,
                    )
                    continue
                self._event_from_row(
                    table="social_activity",
                    row=row,
                    subject=person,
                    pk_field="social_activity_id",
                    type_field="activity_type",
                    type_map=SOCIAL_EVENT_TYPE_MAP,
                    date_field="activity_date",
                    description=self._raw_text(row.get("activity_description"))
                    or self._raw_text(row.get("activity_name")),
                    dimension="SOCIAL",
                    relation_type="PARTICIPATED_IN",
                    extra_properties={"raw_activity_type": raw_type},
                    force_pending=True,
                )
                continue
            event = self._event_from_row(
                table="social_activity",
                row=row,
                subject=person,
                pk_field="social_activity_id",
                type_field="activity_type",
                type_map=SOCIAL_EVENT_TYPE_MAP,
                date_field="activity_date",
                description=self._raw_text(row.get("activity_description"))
                or self._raw_text(row.get("activity_name")),
                dimension="SOCIAL",
                relation_type="PARTICIPATED_IN",
                extra_properties={"raw_activity_type": raw_type},
            )
            if event is not None:
                self._map_activity_partner_relation(person, row, raw_type)

        for row in self.data.records.get("public_reputation", []):
            raw_type = (clean_text(row.get("reputation_type")) or "").upper()
            self._event_from_row(
                table="public_reputation",
                row=row,
                subject=person,
                pk_field="public_reputation_id",
                type_field="reputation_type",
                type_map=PUBLIC_REPUTATION_TYPE_MAP,
                date_field=("event_date", "publish_date", "publication_date"),
                description=self._raw_text(row.get("description"))
                or self._raw_text(row.get("title")),
                dimension="SOCIAL",
                relation_type="HAS_EVENT",
                extra_properties={"publisher_name": clean_text(row.get("publisher_name"))},
                force_pending=True,
            )

        for row in self.data.records.get("reputation_risk", []):
            self._event_from_row(
                table="reputation_risk",
                row=row,
                subject=person,
                pk_field="reputation_risk_id",
                type_field=None,
                type_map={"REPUTATION_RISK": "REPUTATION_RISK_SIGNAL"},
                date_field=("event_date", "publish_date"),
                description=self._raw_text(row.get("risk_description"))
                or self._raw_text(row.get("risk_topic")),
                dimension="SOCIAL",
                relation_type="HAS_EVENT",
                extra_properties={
                    "risk_level": clean_text(row.get("risk_level")),
                    "is_allegation": True,
                },
                forced_raw_type="REPUTATION_RISK",
                force_pending=True,
            )

    def _events_from_rows(
        self,
        *,
        table: str,
        subject_field: str,
        subject_node: GraphNode,
        pk_field: str,
        type_field: str,
        type_map: Mapping[str, str],
        date_field: Any,
        description_field: Optional[str],
        dimension: str,
        relation_type: str,
    ) -> None:
        for row in self.data.records.get(table, []):
            if self._as_id(row.get(subject_field)) != self.person_id:
                self._unmatched(table, row, subject_field, "事件主体 person_id 与目标人物不一致。")
                continue
            self._event_from_row(
                table=table,
                row=row,
                subject=subject_node,
                pk_field=pk_field,
                type_field=type_field,
                type_map=type_map,
                date_field=date_field,
                description=self._raw_text(row.get(description_field)) if description_field else None,
                dimension=dimension,
                relation_type=relation_type,
            )

    def _event_from_row(
        self,
        *,
        table: str,
        row: Mapping[str, Any],
        subject: GraphNode,
        pk_field: str,
        type_field: Optional[str],
        type_map: Mapping[str, str],
        date_field: Any,
        description: Optional[str],
        dimension: str,
        relation_type: str,
        extra_properties: Optional[Mapping[str, Any]] = None,
        forced_raw_type: Optional[str] = None,
        force_pending: bool = False,
    ) -> Optional[GraphEvent]:
        source_pk = self._as_id(row.get(pk_field))
        if source_pk is None:
            self._parse_issue(table, row, pk_field, "事件来源主键为空。")
            return None
        raw_type = forced_raw_type or (clean_text(row.get(type_field)) if type_field else None)
        raw_type = (raw_type or "").upper()
        canonical_type = type_map.get(raw_type)
        if canonical_type is None:
            self._unknown_enum(table, row, type_field or "event_type", raw_type)
            return None
        date_field_name, raw_date = self._select_date_field(row, date_field)
        parsed_date = self._parsed_date(raw_date, table, row, date_field_name)
        source_id = self._as_id(row.get("source_id"))
        properties = {
            "raw_event_type": raw_type,
            "mysql_source_table": table,
            "mysql_source_pk": source_pk,
        }
        if table == "financial_event":
            properties["purpose"] = self._raw_text(row.get("purpose"))
        elif table == "enterprise_event":
            properties["risk_level"] = clean_text(row.get("risk_level"))
        elif table == "social_activity":
            properties["activity_name"] = self._raw_text(row.get("activity_name"))
        elif table == "public_reputation":
            properties["title"] = self._raw_text(row.get("title"))
        elif table == "reputation_risk":
            properties["risk_topic"] = self._raw_text(row.get("risk_topic"))
        date_requires_pending = False
        if not is_empty_or_invalid(raw_date):
            properties["raw_date"] = self._scalar_text(raw_date)
        if parsed_date is not None:
            if parsed_date.raw_date_range:
                properties["raw_date_range"] = parsed_date.raw_date_range
                date_requires_pending = True
            if parsed_date.is_estimated:
                properties["date_is_estimated"] = True
                date_requires_pending = True
        properties.update(dict(extra_properties or {}))
        money_requires_pending = self._put_event_money(table, row, properties)
        verification_status = self._verification(
            row.get("verification_status"),
            source_id,
            description,
        )
        if force_pending or date_requires_pending or money_requires_pending:
            verification_status = "PENDING"
        event = GraphEvent(
            event_id=event_id(
                subject_node_id=subject.node_id,
                event_type=canonical_type,
                event_date=parsed_date.value if parsed_date else None,
                source_id=source_id,
                source_table=table,
                source_pk=source_pk,
            ),
            event_type=canonical_type,
            subject_node_id=subject.node_id,
            event_date=parsed_date.value if parsed_date and parsed_date.value else None,
            date_precision=(
                parsed_date.precision if parsed_date and parsed_date.precision else None
            ),
            description=description,
            properties=self._without_none(properties),
            source_id=source_id,
            verification_status=verification_status,
            confidence=1.0,
            evidence_text=description,
            import_batch_id=self._import_batch_id(source_id),
        )
        self._add_event(event, table, source_pk)
        self._link_event(subject, event, table, source_pk, dimension, relation_type)
        return event

    def _link_event(
        self,
        subject: GraphNode,
        event: GraphEvent,
        source_table: str,
        source_pk: str,
        dimension: str,
        relation_type_value: str,
    ) -> None:
        relation = GraphRelation(
            relation_id=relation_id(
                start_node_id=subject.node_id,
                relation_type=relation_type_value,
                end_node_id=event.event_id,
                source_id=event.source_id,
                source_table=source_table,
                source_pk=source_pk,
            ),
            start_node_id=subject.node_id,
            end_node_id=event.event_id,
            relation_type=relation_type_value,
            properties={"event_type": event.event_type},
            dimension=dimension,
            source_id=event.source_id,
            verification_status=event.verification_status,
            confidence=1.0,
            evidence_text=None,
            import_batch_id=event.import_batch_id,
        )
        self._add_relation(relation, source_table, source_pk)

    def _map_research_collaboration(
        self,
        person: GraphNode,
        row: Mapping[str, Any],
    ) -> bool:
        """Map a research collaboration only through an exact loaded endpoint."""

        partner_name = clean_text(row.get("partner_name"))
        normalized_partner = normalize_organization_name(partner_name)
        matches = [
            node
            for node in self.nodes.values()
            if node.node_type == "Organization"
            and normalized_partner is not None
            and node.properties.get("normalized_name") == normalized_partner
        ]
        if len(matches) != 1:
            self._unmatched(
                "social_activity",
                row,
                "partner_name",
                (
                    "RESEARCH_COLLABORATION 的合作机构缺失或不能通过已加载"
                    " Organization 的标准名唯一匹配；未创建组织节点或关系。"
                ),
            )
            return False

        source_pk = self._source_pk("social_activity", row)
        source_id = self._as_id(row.get("source_id"))
        properties: Dict[str, Any] = {
            "activity_type": "RESEARCH_COLLABORATION",
            "role_title": clean_text(row.get("role_title")),
            "project_name": clean_text(row.get("project_name"))
            or clean_text(row.get("activity_name")),
            "cooperation_topic": clean_text(row.get("cooperation_topic")),
            "description": self._raw_text(row.get("activity_description")),
            "start_date": self._date_value(
                row.get("start_date"), "social_activity", row, "start_date"
            ),
            "end_date": self._date_value(
                row.get("end_date"), "social_activity", row, "end_date"
            ),
            "mysql_source_table": "social_activity",
            "mysql_source_pk": source_pk,
        }
        activity_date = self._parsed_date(
            row.get("activity_date"),
            "social_activity",
            row,
            "activity_date",
        )
        if activity_date is not None:
            if activity_date.value:
                properties["activity_date"] = activity_date.value
            if activity_date.precision:
                properties["activity_date_precision"] = activity_date.precision
            properties["raw_date"] = activity_date.raw_value
            if activity_date.is_estimated:
                properties["date_is_estimated"] = True
            if activity_date.raw_date_range:
                properties["raw_date_range"] = activity_date.raw_date_range
        target = matches[0]
        relation_status = self._verification(row.get("verification_status"), source_id)
        if activity_date is not None and (
            activity_date.is_estimated or activity_date.raw_date_range
        ):
            relation_status = "PENDING"
        relation = GraphRelation(
            relation_id=relation_id(
                start_node_id=person.node_id,
                relation_type="ACADEMIC_COOPERATION",
                end_node_id=target.node_id,
                source_id=source_id,
                source_table="social_activity",
                source_pk=source_pk,
            ),
            start_node_id=person.node_id,
            end_node_id=target.node_id,
            relation_type="ACADEMIC_COOPERATION",
            properties=self._without_none(properties),
            dimension="SOCIAL",
            source_id=source_id,
            document_id=None,
            verification_status=relation_status,
            confidence=1.0,
            evidence_text=self._raw_text(row.get("activity_description")),
            import_batch_id=self._import_batch_id(source_id),
        )
        self._add_relation(relation, "social_activity", source_pk)
        return True

    def _map_activity_partner_relation(
        self,
        person: GraphNode,
        row: Mapping[str, Any],
        raw_activity_type: str,
    ) -> None:
        partner_name = clean_text(row.get("partner_name"))
        if partner_name is None:
            return
        normalized = normalize_organization_name(partner_name)
        organization = next(
            (
                node
                for node in self.nodes.values()
                if node.node_type == "Organization"
                and node.properties.get("normalized_name") == normalized
            ),
            None,
        )
        if organization is None:
            self._unmatched(
                "social_activity",
                row,
                "partner_name",
                "活动合作方无法唯一匹配到已加载 Organization。",
            )
            return
        if raw_activity_type in {"CHARITY_ESG", "CHARITY"}:
            relation_type_value = "CHARITY_COOPERATION"
        elif raw_activity_type in {"ACADEMIC", "ACADEMIC_COOPERATION"}:
            relation_type_value = "ACADEMIC_COOPERATION"
        else:
            self._add_issue(
                reason="MAPPING_PENDING",
                severity="INFO",
                message="活动类型尚不能映射为合作关系。",
                source_table="social_activity",
                source_pk=self._source_pk("social_activity", row),
                field_name="activity_type",
                source_id=self._as_id(row.get("source_id")),
                requires_manual_confirmation=True,
            )
            return
        source_pk = self._source_pk("social_activity", row)
        source_id = self._as_id(row.get("source_id"))
        relation = GraphRelation(
            relation_id=relation_id(
                start_node_id=person.node_id,
                relation_type=relation_type_value,
                end_node_id=organization.node_id,
                source_id=source_id,
                source_table="social_activity",
                source_pk=source_pk,
            ),
            start_node_id=person.node_id,
            end_node_id=organization.node_id,
            relation_type=relation_type_value,
            properties=self._without_none(
                {
                    "activity_name": self._raw_text(row.get("activity_name")),
                    "description": self._raw_text(row.get("activity_description")),
                }
            ),
            dimension="SOCIAL",
            source_id=source_id,
            verification_status=self._verification(row.get("verification_status"), source_id),
            confidence=1.0,
            evidence_text=self._raw_text(row.get("activity_description")),
            import_batch_id=self._import_batch_id(source_id),
        )
        self._add_relation(relation, "social_activity", source_pk)

    def _validate_candidates(self) -> None:
        node_ids = set(self.nodes)
        event_ids = set(self.events)
        available_endpoints = node_ids | event_ids

        for node in list(self.nodes.values()):
            if node.node_type not in NODE_TYPES:
                self._add_issue(
                    reason="INVALID_NODE_TYPE",
                    severity="ERROR",
                    message=f"节点类型不在白名单：{node.node_type}",
                    source_pk=node.node_id,
                )
                del self.nodes[node.node_id]
                continue
            self._validate_source_reference(
                node,
                node.properties.get("mysql_source_table"),
                node.node_id,
            )

        for relation in list(self.relations.values()):
            if relation.relation_type not in RELATION_TYPES:
                self._add_issue(
                    reason="INVALID_RELATION_TYPE",
                    severity="ERROR",
                    message=f"关系类型不在白名单：{relation.relation_type}",
                    source_pk=relation.relation_id,
                )
                del self.relations[relation.relation_id]
                continue
            if (
                relation.start_node_id not in available_endpoints
                or relation.end_node_id not in available_endpoints
            ):
                self._add_issue(
                    reason="MISSING_ENDPOINT",
                    severity="ERROR",
                    message="关系起点或终点不存在，关系未进入预览输出。",
                    source_pk=relation.relation_id,
                    source_id=relation.source_id,
                    requires_manual_confirmation=True,
                )
                del self.relations[relation.relation_id]
                continue
            self._validate_source_reference(relation, None, relation.relation_id)
            if contains_speculation(relation.evidence_text):
                relation.verification_status = "PENDING"

        for event in list(self.events.values()):
            if event.subject_node_id not in node_ids:
                self._add_issue(
                    reason="MISSING_ENDPOINT",
                    severity="ERROR",
                    message="事件主体节点不存在，事件未进入预览输出。",
                    source_pk=event.event_id,
                    source_id=event.source_id,
                    requires_manual_confirmation=True,
                )
                del self.events[event.event_id]
                continue
            self._validate_source_reference(event, None, event.event_id)
            if contains_speculation(event.description) or contains_speculation(event.evidence_text):
                event.verification_status = "PENDING"

    def _add_node(self, node: GraphNode, table: str, source_pk: Any) -> None:
        if node.node_id in self.nodes:
            self._add_issue(
                reason="DUPLICATE_ID",
                severity="ERROR",
                message="检测到重复 node_id；重复候选未加入输出。",
                source_table=table,
                source_pk=self._as_id(source_pk),
                value_preview=node.node_id,
            )
            return
        self.nodes[node.node_id] = node

    def _add_relation(self, relation: GraphRelation, table: str, source_pk: Any) -> None:
        if relation.relation_id in self.relations:
            self._add_issue(
                reason="DUPLICATE_ID",
                severity="ERROR",
                message="检测到重复 relation_id；重复候选未加入输出。",
                source_table=table,
                source_pk=self._as_id(source_pk),
                value_preview=relation.relation_id,
            )
            return
        self.relations[relation.relation_id] = relation

    def _add_event(self, event: GraphEvent, table: str, source_pk: Any) -> None:
        if event.event_id in self.events:
            self._add_issue(
                reason="DUPLICATE_ID",
                severity="ERROR",
                message="检测到重复 event_id；重复候选未加入输出。",
                source_table=table,
                source_pk=self._as_id(source_pk),
                value_preview=event.event_id,
            )
            return
        self.events[event.event_id] = event

    def _add_issue(
        self,
        *,
        reason: str,
        message: str,
        severity: str = "WARNING",
        source_table: Optional[str] = None,
        source_pk: Any = None,
        field_name: Optional[str] = None,
        value_preview: Optional[str] = None,
        source_id: Optional[str] = None,
        requires_manual_confirmation: bool = False,
    ) -> None:
        identifier = issue_id(
            reason=reason,
            source_table=source_table,
            source_pk=source_pk,
            field_name=field_name,
            discriminator=message,
        )
        if identifier in self.issues:
            return
        self.issues[identifier] = GraphIssue(
            issue_id=identifier,
            reason=reason,
            severity=severity,
            message=message,
            source_table=source_table,
            source_pk=self._as_id(source_pk),
            field_name=field_name,
            value_preview=value_preview,
            source_id=source_id,
            person_id=self.person_id,
            requires_manual_confirmation=requires_manual_confirmation,
        )

    def _parse_issue(
        self,
        table: str,
        row: Mapping[str, Any],
        field_name: str,
        message: str,
    ) -> None:
        self._add_issue(
            reason="PARSE_FAILED",
            severity="WARNING",
            message=message,
            source_table=table,
            source_pk=self._source_pk(table, row),
            field_name=field_name,
            value_preview=(
                safe_preview(row.get(field_name))
                if field_name in SAFE_ISSUE_PREVIEW_FIELDS
                else None
            ),
            source_id=self._as_id(row.get("source_id")),
            requires_manual_confirmation=True,
        )

    def _unknown_enum(
        self,
        table: str,
        row: Mapping[str, Any],
        field_name: str,
        value: str,
    ) -> None:
        self._add_issue(
            reason="UNKNOWN_ENUM",
            severity="WARNING",
            message="枚举值未出现在第一轮固定映射字典中，未生成候选。",
            source_table=table,
            source_pk=self._source_pk(table, row),
            field_name=field_name,
            value_preview=safe_preview(value),
            source_id=self._as_id(row.get("source_id")),
            requires_manual_confirmation=True,
        )

    def _unmatched(
        self,
        table: str,
        row: Mapping[str, Any],
        field_name: str,
        message: str,
    ) -> None:
        self._add_issue(
            reason="UNMATCHED_RELATION",
            severity="WARNING",
            message=message,
            source_table=table,
            source_pk=self._source_pk(table, row),
            field_name=field_name,
            value_preview=(
                safe_preview(row.get(field_name)) if field_name in SAFE_ISSUE_PREVIEW_FIELDS else None
            ),
            source_id=self._as_id(row.get("source_id")),
            requires_manual_confirmation=True,
        )

    def _missing_source(self, table: Any, source_pk: Any, source_id: Optional[str]) -> None:
        self._add_issue(
            reason="MISSING_SOURCE",
            severity="WARNING",
            message="候选缺少明确行级来源，状态已保持或降级为 PENDING。",
            source_table=clean_text(table),
            source_pk=source_pk,
            source_id=source_id,
            requires_manual_confirmation=True,
        )

    def _validate_source_reference(
        self,
        candidate: Any,
        source_table: Any,
        candidate_id: str,
    ) -> None:
        source_id = self._as_id(candidate.source_id)
        if source_id is None:
            candidate.verification_status = "PENDING"
            self._missing_source(source_table, candidate_id, None)
            return
        if source_id not in self.data.sources:
            candidate.verification_status = "PENDING"
            self._add_issue(
                reason="SOURCE_NOT_FOUND",
                severity="WARNING",
                message="source_id 无法在已读取的 source_document 中找到，候选保持 PENDING。",
                source_table=clean_text(source_table),
                source_pk=candidate_id,
                source_id=source_id,
                requires_manual_confirmation=True,
            )

    def _source_pk(self, table: str, row: Mapping[str, Any]) -> Optional[str]:
        field_name = PRIMARY_KEYS.get(table)
        return self._as_id(row.get(field_name)) if field_name else None

    @staticmethod
    def _as_id(value: Any) -> Optional[str]:
        if value is None or not str(value).strip():
            return None
        return str(value).strip()

    def _first(self, table: str) -> Optional[Mapping[str, Any]]:
        rows = self.data.records.get(table, [])
        return rows[0] if rows else None

    def _source_ids(self, rows: Iterable[Mapping[str, Any]]) -> List[str]:
        return sorted(
            {
                source_id
                for row in rows
                if (source_id := self._as_id(row.get("source_id"))) is not None
            }
        )

    def _import_batch_id(self, source_id: Optional[str]) -> Optional[str]:
        if source_id is None:
            return None
        source = self.data.sources.get(source_id)
        if source is None:
            return None
        return self._as_id(source.get("import_batch_id"))

    def _verification(
        self,
        raw_status: Any,
        source_id: Optional[str],
        evidence: Any = None,
    ) -> str:
        normalized = (clean_text(raw_status) or "").upper()
        confirmed_values = {"CONFIRMED", "VERIFIED", "CONFIRMED_BY_HUMAN"}
        source_exists = bool(source_id and source_id in self.data.sources)
        if normalized in confirmed_values and source_exists and not contains_speculation(evidence):
            return "CONFIRMED"
        return "PENDING"

    def _aggregate_verification(
        self,
        rows: Sequence[Mapping[str, Any]],
        source_id: Optional[str],
    ) -> str:
        if not rows:
            return "PENDING"
        statuses = {
            self._verification(row.get("verification_status"), self._as_id(row.get("source_id")))
            for row in rows
        }
        return "CONFIRMED" if statuses == {"CONFIRMED"} and source_id else "PENDING"

    def _put_year(
        self,
        properties: Dict[str, Any],
        target: str,
        value: Any,
        table: str,
        row: Mapping[str, Any],
    ) -> None:
        try:
            parsed = parse_year(value)
        except RuleParseError as exc:
            self._parse_issue(table, row, target, str(exc))
            return
        if parsed is not None:
            properties[target] = parsed

    def _select_date_field(
        self,
        row: Mapping[str, Any],
        field_spec: Any,
    ) -> Tuple[str, Any]:
        if isinstance(field_spec, str):
            return field_spec, row.get(field_spec)
        field_names = tuple(str(item) for item in field_spec)
        for field_name in field_names:
            if field_name in row and not is_empty_or_invalid(row.get(field_name)):
                return field_name, row.get(field_name)
        for field_name in field_names:
            if field_name in row:
                return field_name, row.get(field_name)
        return field_names[0], None

    def _put_event_money(
        self,
        table: str,
        row: Mapping[str, Any],
        properties: Dict[str, Any],
    ) -> bool:
        if table not in {"financial_event", "social_activity"}:
            return False
        raw_amount = row.get("amount")
        if is_empty_or_invalid(raw_amount):
            return False

        properties["raw_amount"] = self._scalar_text(raw_amount)
        raw_currency = row.get("currency_code")
        if not is_empty_or_invalid(raw_currency):
            properties["raw_currency"] = self._scalar_text(raw_currency)
        unit = next(
            (
                row.get(field_name)
                for field_name in ("amount_unit", "unit_name", "unit")
                if field_name in row and not is_empty_or_invalid(row.get(field_name))
            ),
            None,
        )
        try:
            parsed = parse_money(raw_amount, raw_currency, unit)
        except UnknownCurrencyError as exc:
            self._add_issue(
                reason="UNKNOWN_CURRENCY",
                severity="WARNING",
                message=str(exc),
                source_table=table,
                source_pk=self._source_pk(table, row),
                field_name="currency_code",
                value_preview=safe_preview(
                    raw_currency if not is_empty_or_invalid(raw_currency) else raw_amount
                ),
                source_id=self._as_id(row.get("source_id")),
                requires_manual_confirmation=True,
            )
            # Currency normalization and amount scaling are independent. Keep
            # a safely parsed base-unit amount even when the explicit currency
            # is unknown, but never copy or infer a currency code.
            try:
                amount_only = parse_money(raw_amount, None, unit)
            except UnknownCurrencyError:
                return True
            except MissingAmountUnitError as unit_exc:
                self._add_issue(
                    reason="MAPPING_PENDING",
                    severity="WARNING",
                    message=str(unit_exc),
                    source_table=table,
                    source_pk=self._source_pk(table, row),
                    field_name="amount",
                    value_preview=safe_preview(raw_amount),
                    source_id=self._as_id(row.get("source_id")),
                    requires_manual_confirmation=True,
                )
                return True
            except RuleParseError as amount_exc:
                self._parse_issue(table, row, "amount", str(amount_exc))
                return True
            if amount_only is not None:
                properties.update(
                    {
                        "amount": self._decimal_string(amount_only.amount),
                        "amount_unit": amount_only.amount_unit,
                        "is_estimated": amount_only.is_estimated,
                        "raw_amount": amount_only.raw_value,
                    }
                )
            return True
        except MissingAmountUnitError as exc:
            try:
                currency = normalize_currency(raw_currency)
            except UnknownCurrencyError as currency_exc:
                currency = None
                self._add_issue(
                    reason="UNKNOWN_CURRENCY",
                    severity="WARNING",
                    message=str(currency_exc),
                    source_table=table,
                    source_pk=self._source_pk(table, row),
                    field_name="currency_code",
                    value_preview=safe_preview(raw_currency),
                    source_id=self._as_id(row.get("source_id")),
                    requires_manual_confirmation=True,
                )
            if currency:
                properties["currency"] = currency
            else:
                self._add_issue(
                    reason="MISSING_CURRENCY",
                    severity="WARNING",
                    message="金额存在但币种缺失；未默认补为 CNY，候选保持 PENDING。",
                    source_table=table,
                    source_pk=self._source_pk(table, row),
                    field_name="currency_code",
                    source_id=self._as_id(row.get("source_id")),
                    requires_manual_confirmation=True,
                )
            self._add_issue(
                reason="MAPPING_PENDING",
                severity="WARNING",
                message=str(exc),
                source_table=table,
                source_pk=self._source_pk(table, row),
                field_name="amount",
                value_preview=safe_preview(raw_amount),
                source_id=self._as_id(row.get("source_id")),
                requires_manual_confirmation=True,
            )
            return True
        except RuleParseError as exc:
            self._parse_issue(table, row, "amount", str(exc))
            return True

        if parsed is None:
            return False
        properties.update(
            {
                "amount": self._decimal_string(parsed.amount),
                "amount_unit": parsed.amount_unit,
                "is_estimated": parsed.is_estimated,
                "raw_amount": parsed.raw_value,
            }
        )
        if parsed.currency_code:
            properties["currency"] = parsed.currency_code
        else:
            self._add_issue(
                reason="MISSING_CURRENCY",
                severity="WARNING",
                message="金额存在但币种缺失；未默认补为 CNY，候选保持 PENDING。",
                source_table=table,
                source_pk=self._source_pk(table, row),
                field_name="currency_code",
                source_id=self._as_id(row.get("source_id")),
                requires_manual_confirmation=True,
            )
            return True
        return parsed.is_estimated

    def _parsed_date(
        self,
        value: Any,
        table: str,
        row: Mapping[str, Any],
        field_name: str,
    ) -> Any:
        try:
            parsed = normalize_date(value)
        except RuleParseError as exc:
            self._parse_issue(table, row, field_name, str(exc))
            return None
        if parsed is not None and parsed.raw_date_range:
            self._add_issue(
                reason="MAPPING_PENDING",
                severity="WARNING",
                message="日期区间不能安全折叠为单一事件日期；已保留 raw_date_range。",
                source_table=table,
                source_pk=self._source_pk(table, row),
                field_name=field_name,
                source_id=self._as_id(row.get("source_id")),
                requires_manual_confirmation=True,
            )
        elif parsed is not None and parsed.is_estimated:
            self._add_issue(
                reason="MAPPING_PENDING",
                severity="INFO",
                message="日期为模糊年份，已保留 YEAR 精度并将候选保持 PENDING。",
                source_table=table,
                source_pk=self._source_pk(table, row),
                field_name=field_name,
                source_id=self._as_id(row.get("source_id")),
                requires_manual_confirmation=True,
            )
        return parsed

    def _put_date(
        self,
        properties: Dict[str, Any],
        target: str,
        value: Any,
        table: str,
        row: Mapping[str, Any],
    ) -> None:
        parsed = self._parsed_date(value, table, row, target)
        if parsed is not None:
            properties[target] = parsed.value
            if parsed.precision != "DAY":
                properties[f"{target}_precision"] = parsed.precision

    def _date_value(
        self,
        value: Any,
        table: str,
        row: Mapping[str, Any],
        field_name: str,
    ) -> Optional[str]:
        parsed = self._parsed_date(value, table, row, field_name)
        return parsed.value if parsed is not None and parsed.value else None

    @staticmethod
    def _raw_text(value: Any) -> Optional[str]:
        """Return an accepted source text byte-for-byte as supplied by the row."""

        if is_empty_or_invalid(value):
            return None
        return value if isinstance(value, str) else str(value)

    def _raw_text_values(
        self,
        rows: Sequence[Mapping[str, Any]],
        field_name: str,
    ) -> List[str]:
        return self._deduplicate_description_values(
            row.get(field_name) for row in rows
        )

    @classmethod
    def _deduplicate_description_values(cls, values: Iterable[Any]) -> List[str]:
        """Drop empty and duplicate descriptions while preserving first order."""

        unique: List[str] = []
        seen: Set[str] = set()
        for value in values:
            text = cls._raw_text(value)
            if text is None or text in seen:
                continue
            seen.add(text)
            unique.append(text)
        return unique

    @classmethod
    def _single_or_list(cls, values: Sequence[str]) -> Any:
        unique = cls._deduplicate_description_values(values)
        if not unique:
            return None
        return unique[0] if len(unique) == 1 else unique

    @staticmethod
    def _scalar_text(value: Any) -> str:
        if isinstance(value, (date, datetime)):
            return value.isoformat()
        if isinstance(value, Decimal):
            return format(value, "f")
        return str(value)

    @staticmethod
    def _decimal_string(value: Decimal) -> str:
        normalized = format(value.normalize(), "f")
        return "0" if normalized in {"-0", "+0"} else normalized

    @staticmethod
    def _without_none(values: Mapping[str, Any]) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, value in values.items():
            if value is None:
                continue
            if isinstance(value, Decimal):
                result[key] = str(value)
            elif isinstance(value, (date, datetime)):
                result[key] = value.isoformat()
            else:
                result[key] = value
        return result
