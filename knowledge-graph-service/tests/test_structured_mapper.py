from collections import defaultdict
from decimal import Decimal

import pytest

from src.extraction.structured_mapper import ExtractionInput, StructuredMapper


def _source(source_id: int) -> dict:
    return {
        "source_id": source_id,
        "import_batch_id": 1,
        "file_name": "local-test.xlsx",
        "sheet_name": "test",
        "source_row_number": 1,
        "column_name": "test",
        "cell_reference": "A1",
        "original_text": "local fixture",
        "source_level": "S0",
    }


def _base_input() -> ExtractionInput:
    data = ExtractionInput(person_id=1)
    data.records = defaultdict(list)
    data.records["person"] = [
        {
            "person_id": 1,
            "full_name": "测试人物",
            "normalized_name": "测试人物",
            "person_type": "ENTREPRENEUR",
            "verification_status": "CONFIRMED",
        }
    ]
    data.records["person_profile"] = [
        {
            "person_id": 1,
            "birth_year": 1980,
            "source_id": 100,
            "verification_status": "CONFIRMED",
        }
    ]
    data.sources = {"100": _source(100), "200": _source(200)}
    return data


def _organization(organization_id: int = 10, name: str = "测试研究院") -> dict:
    return {
        "social_organization_id": organization_id,
        "organization_name": name,
        "normalized_name": name,
        "organization_type": "RESEARCH_INSTITUTE",
    }


def _financial_fact_rows(descriptions) -> list:
    return [
        {
            "financial_fact_id": index,
            "person_id": 1,
            "description": description,
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
        for index, description in enumerate(descriptions, start=1)
    ]


def _person_description(data: ExtractionInput):
    result = StructuredMapper(data).map_candidates()
    person = next(item for item in result.nodes if item.node_type == "Person")
    return person.properties.get("description")


def _market_input(market_rows, extra_enterprises=None) -> ExtractionInput:
    data = _base_input()
    data.records["person_enterprise_relation"] = [
        {
            "person_enterprise_relation_id": 1,
            "person_id": 1,
            "enterprise_id": 10,
            "relation_type": "WORKS_AT",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]
    data.records["enterprise"] = [
        {
            "enterprise_id": 10,
            "enterprise_name": "起点企业有限公司",
            "verification_status": "CONFIRMED",
        },
        *(extra_enterprises or []),
    ]
    data.records["enterprise_market_relation"] = list(market_rows)
    return data


def _market_row(row_id: int, relation_type: str, counterpart_name) -> dict:
    return {
        "enterprise_market_relation_id": row_id,
        "enterprise_id": 10,
        "relation_type": relation_type,
        "counterpart_name": counterpart_name,
        "relation_description": "本地市场关系测试",
        "source_id": 200,
        "verification_status": "CONFIRMED",
    }


def test_five_identical_person_descriptions_are_kept_once() -> None:
    data = _base_input()
    data.records["financial_fact"] = _financial_fact_rows(["同一段描述"] * 5)

    assert _person_description(data) == "同一段描述"


def test_distinct_person_descriptions_keep_original_order() -> None:
    data = _base_input()
    data.records["financial_fact"] = _financial_fact_rows(
        ["第一段", "第二段", "第三段"]
    )

    assert _person_description(data) == ["第一段", "第二段", "第三段"]


def test_null_and_empty_person_descriptions_are_ignored() -> None:
    data = _base_input()
    data.records["financial_fact"] = _financial_fact_rows(
        [None, "", "   ", "有效描述", None]
    )
    assert _person_description(data) == "有效描述"

    empty_data = _base_input()
    empty_data.records["financial_fact"] = _financial_fact_rows([None, "", "   "])
    assert _person_description(empty_data) is None


def test_marital_status_adds_only_person_properties_without_family_candidates() -> None:
    data = _base_input()
    data.records["person_profile"][0]["marital_status"] = "已婚（丈夫孙飘扬），育有一子一女"

    result = StructuredMapper(data).map_candidates()
    person = next(item for item in result.nodes if item.node_type == "Person")

    assert person.properties["marital_status"] == "MARRIED"
    assert person.properties["raw_marital_status"] == "已婚（丈夫孙飘扬），育有一子一女"
    assert person.properties["normalized_name"] == "测试人物"
    assert "children_count" not in person.properties
    assert "son_count" not in person.properties
    assert "daughter_count" not in person.properties
    assert not any(item.node_type == "FamilyMember" for item in result.nodes)
    assert not any(item.relation_type == "FAMILY_OF" for item in result.relations)
    assert not any(
        issue.reason == "MAPPING_PENDING"
        and issue.source_table == "person_profile"
        and issue.field_name == "marital_status"
        for issue in result.issues
    )


def test_unknown_marital_status_keeps_unknown_enum_with_full_preview() -> None:
    data = _base_input()
    raw_value = "家庭稳定"
    data.records["person_profile"][0]["marital_status"] = raw_value

    result = StructuredMapper(data).map_candidates()
    person = next(item for item in result.nodes if item.node_type == "Person")
    issue = next(
        item
        for item in result.issues
        if item.reason == "UNKNOWN_ENUM"
        and item.source_table == "person_profile"
        and item.field_name == "marital_status"
    )

    assert "marital_status" not in person.properties
    assert "raw_marital_status" not in person.properties
    assert issue.value_preview == raw_value
    assert issue.requires_manual_confirmation is True


def test_explicit_unknown_marital_enum_is_a_valid_person_property() -> None:
    data = _base_input()
    data.records["person_profile"][0]["marital_status"] = "UnKnOwN"

    result = StructuredMapper(data).map_candidates()
    person = next(item for item in result.nodes if item.node_type == "Person")

    assert person.properties["marital_status"] == "UNKNOWN"
    assert person.properties["raw_marital_status"] == "UnKnOwN"
    assert result.field_treatment_counts["RULE"] >= 1
    assert not any(
        issue.source_table == "person_profile"
        and issue.field_name == "marital_status"
        and issue.reason in {"UNKNOWN_ENUM", "MAPPING_PENDING"}
        for issue in result.issues
    )


def test_conflicting_marital_status_creates_conflict_issue() -> None:
    data = _base_input()
    raw_value = "曾离异，后再次已婚"
    data.records["person_profile"][0]["marital_status"] = raw_value

    result = StructuredMapper(data).map_candidates()
    person = next(item for item in result.nodes if item.node_type == "Person")
    issue = next(
        item for item in result.issues if item.reason == "MARITAL_STATUS_CONFLICT"
    )

    assert "marital_status" not in person.properties
    assert "raw_marital_status" not in person.properties
    assert issue.source_table == "person_profile"
    assert issue.field_name == "marital_status"
    assert issue.value_preview == raw_value
    assert issue.requires_manual_confirmation is True


@pytest.mark.parametrize("raw_value", [None, "", "   "])
def test_empty_marital_status_creates_no_enum_or_pending_issue(raw_value) -> None:
    data = _base_input()
    data.records["person_profile"][0]["marital_status"] = raw_value

    result = StructuredMapper(data).map_candidates()
    person = next(item for item in result.nodes if item.node_type == "Person")

    assert "marital_status" not in person.properties
    assert "raw_marital_status" not in person.properties
    assert not any(
        issue.source_table == "person_profile"
        and issue.field_name == "marital_status"
        and issue.reason in {"UNKNOWN_ENUM", "MAPPING_PENDING"}
        for issue in result.issues
    )


@pytest.mark.parametrize(
    "raw_value",
    [87412, "87412", "87,412", Decimal("87412")],
)
def test_employee_count_is_mapped_as_non_negative_integer(raw_value) -> None:
    data = _market_input([])
    data.records["enterprise"][0]["employee_count"] = raw_value
    data.records["enterprise"][0]["industry_name"] = "测试行业"

    result = StructuredMapper(data).map_candidates()
    enterprise = next(
        item for item in result.nodes if item.node_id == "enterprise:10"
    )

    assert enterprise.properties["employee_count"] == 87412
    assert isinstance(enterprise.properties["employee_count"], int)
    assert enterprise.properties["industry"] == "测试行业"
    assert "currency" not in enterprise.properties
    assert "amount_unit" not in enterprise.properties
    assert "amount_scale" not in enterprise.properties
    assert not any(
        issue.reason == "MAPPING_PENDING"
        and issue.source_table == "enterprise"
        and issue.field_name == "employee_count"
        for issue in result.issues
    )


@pytest.mark.parametrize("raw_value", [-1, Decimal("1.5"), True, "非数字"])
def test_invalid_employee_count_creates_parse_issue(raw_value) -> None:
    data = _market_input([])
    data.records["enterprise"][0]["employee_count"] = raw_value

    result = StructuredMapper(data).map_candidates()
    enterprise = next(
        item for item in result.nodes if item.node_id == "enterprise:10"
    )

    assert "employee_count" not in enterprise.properties
    assert any(
        issue.reason == "PARSE_FAILED"
        and issue.source_table == "enterprise"
        and issue.field_name == "employee_count"
        for issue in result.issues
    )


def test_other_pending_fields_still_create_mapping_pending() -> None:
    data = _base_input()
    data.records["succession_arrangement"] = [
        {
            "succession_arrangement_id": 88,
            "person_id": 1,
            "candidate_description": "仍需人工确认",
            "source_id": 200,
            "verification_status": "PENDING",
        }
    ]

    result = StructuredMapper(data).map_candidates()

    assert any(
        issue.reason == "MAPPING_PENDING"
        and issue.source_table == "succession_arrangement"
        and issue.field_name == "candidate_description"
        for issue in result.issues
    )


def test_competitor_prefers_existing_enterprise_by_normalized_name() -> None:
    data = _market_input(
        [_market_row(1, "COMPETITOR", "既有（竞争）企业有限公司")],
        extra_enterprises=[
            {
                "enterprise_id": 11,
                "enterprise_name": "既有竞争企业有限公司",
                "verification_status": "CONFIRMED",
            }
        ],
    )

    result = StructuredMapper(data).map_candidates()
    relation = next(item for item in result.relations if item.relation_type == "COMPETES_WITH")

    assert relation.start_node_id == "enterprise:10"
    assert relation.end_node_id == "enterprise:11"
    assert not any(
        node.node_type == "Enterprise"
        and node.properties.get("created_from_reference") is True
        for node in result.nodes
    )


def test_unmatched_competitor_creates_minimal_pending_enterprise() -> None:
    data = _market_input([_market_row(1, "COMPETITOR", "新竞争企业有限公司")])

    result = StructuredMapper(data).map_candidates()
    target = next(
        node
        for node in result.nodes
        if node.node_type == "Enterprise"
        and node.properties.get("created_from_reference") is True
    )
    relation = next(item for item in result.relations if item.relation_type == "COMPETES_WITH")

    assert target.name == "新竞争企业有限公司"
    assert target.properties["profile_completeness"] == "MINIMAL"
    assert target.properties["normalized_name"] == "新竞争企业有限公司".lower()
    assert target.verification_status == "PENDING"
    assert relation.end_node_id == target.node_id
    assert relation.verification_status == "PENDING"
    assert not any(
        issue.reason == "UNMATCHED_RELATION"
        and issue.field_name == "counterpart_name"
        for issue in result.issues
    )


def test_repeated_competitor_name_reuses_one_reference_enterprise() -> None:
    data = _market_input(
        [
            _market_row(1, "COMPETITOR", "同名竞争企业（集团）"),
            _market_row(2, "COMPETITOR", "同名竞争企业集团"),
        ]
    )

    result = StructuredMapper(data).map_candidates()
    reference_nodes = [
        node
        for node in result.nodes
        if node.node_type == "Enterprise"
        and node.properties.get("created_from_reference") is True
    ]
    competitor_relations = [
        item for item in result.relations if item.relation_type == "COMPETES_WITH"
    ]

    assert len(reference_nodes) == 1
    assert len(competitor_relations) == 2
    assert {item.end_node_id for item in competitor_relations} == {
        reference_nodes[0].node_id
    }


def test_upstream_and_downstream_reuse_one_market_segment() -> None:
    data = _market_input(
        [
            _market_row(1, "UPSTREAM", "服务器供应商"),
            _market_row(2, "DOWNSTREAM", "服务器供应商"),
        ]
    )

    result = StructuredMapper(data).map_candidates()
    segments = [node for node in result.nodes if node.node_type == "MarketSegment"]
    market_relations = [
        item
        for item in result.relations
        if item.relation_type in {"HAS_UPSTREAM", "HAS_DOWNSTREAM"}
    ]

    assert len(segments) == 1
    assert segments[0].name == "服务器供应商"
    assert segments[0].properties["segment_type"] == "UPSTREAM"
    assert segments[0].properties["created_from_reference"] is True
    assert segments[0].properties["mysql_source_table"] == "enterprise_market_relation"
    assert segments[0].verification_status == "PENDING"
    assert {item.relation_type for item in market_relations} == {
        "HAS_UPSTREAM",
        "HAS_DOWNSTREAM",
    }
    assert {item.end_node_id for item in market_relations} == {segments[0].node_id}


def test_missing_counterpart_name_preserves_raw_value_preview() -> None:
    raw_counterpart = "   "
    data = _market_input([_market_row(1, "UPSTREAM", raw_counterpart)])

    result = StructuredMapper(data).map_candidates()
    issue = next(
        item for item in result.issues if item.reason == "COUNTERPART_NAME_MISSING"
    )

    assert issue.value_preview == raw_counterpart
    assert not any(
        item.relation_type in {"HAS_UPSTREAM", "HAS_DOWNSTREAM", "COMPETES_WITH"}
        for item in result.relations
    )


@pytest.mark.parametrize(
    "generic_name",
    ["服务器供应商", "内容创作者", "游戏开发商", "C端用户", "广告主", "企业客户"],
)
def test_generic_market_role_is_not_created_as_competitor_enterprise(
    generic_name: str,
) -> None:
    data = _market_input([_market_row(1, "COMPETITOR", generic_name)])

    result = StructuredMapper(data).map_candidates()

    assert len([node for node in result.nodes if node.node_type == "Enterprise"]) == 1
    assert not any(item.relation_type == "COMPETES_WITH" for item in result.relations)
    assert any(
        issue.reason == "COUNTERPART_NOT_ENTERPRISE"
        and issue.value_preview == generic_name
        for issue in result.issues
    )


@pytest.mark.parametrize(
    ("table", "field_name"),
    [
        ("enterprise_event", "event_description"),
        ("financial_event", "event_description"),
        ("social_activity", "activity_description"),
        ("public_reputation", "description"),
        ("reputation_risk", "risk_description"),
        ("enterprise_business", "business_description"),
        ("financial_fact", "description"),
        ("family_member", "member_description"),
        ("person_family_relation", "relation_description"),
        ("succession_arrangement", "arrangement_description"),
        ("person_career", "career_description"),
        ("enterprise_market_relation", "relation_description"),
        ("person_social_relation", "raw_text"),
        ("person_enterprise_relation", "raw_text"),
    ],
)
def test_structured_text_fields_are_direct(table: str, field_name: str) -> None:
    mapper = StructuredMapper(_base_input())

    assert mapper._treatment_for(table, field_name, {field_name: "原始文本"}) == "DIRECT"


@pytest.mark.parametrize(
    ("table", "field_name"),
    [
        ("customer_interaction_note", "note_text"),
        ("service_record", "service_description"),
    ],
)
def test_service_text_fields_are_mysql_only(table: str, field_name: str) -> None:
    mapper = StructuredMapper(_base_input())

    assert mapper._treatment_for(table, field_name, {field_name: "原始文本"}) == "MYSQL_ONLY"


def test_organization_membership_maps_to_member_of() -> None:
    data = _base_input()
    data.records["social_organization"] = [_organization()]
    data.records["person_social_relation"] = [
        {
            "person_social_relation_id": 1,
            "person_id": 1,
            "social_organization_id": 10,
            "relation_type": "ORGANIZATION_MEMBERSHIP",
            "role_title": "会员",
            "valid_from": "2025-01-01",
            "valid_to": None,
            "raw_text": "测试人物是测试研究院会员",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    membership = [item for item in result.relations if item.relation_type == "MEMBER_OF"]

    assert len(membership) == 1
    assert membership[0].start_node_id == "person:1"
    assert membership[0].end_node_id == "organization:10"
    assert membership[0].properties["raw_relation_type"] == "ORGANIZATION_MEMBERSHIP"
    assert membership[0].properties["start_date"] == "2025-01-01"
    assert membership[0].evidence_text == "测试人物是测试研究院会员"
    assert not any(
        issue.reason == "UNKNOWN_ENUM"
        and issue.value_preview == "ORGANIZATION_MEMBERSHIP"
        for issue in result.issues
    )


def test_membership_without_organization_endpoint_is_unmatched() -> None:
    data = _base_input()
    data.records["person_social_relation"] = [
        {
            "person_social_relation_id": 2,
            "person_id": 1,
            "social_organization_id": 999,
            "relation_type": "ORGANIZATION_MEMBERSHIP",
            "source_id": 200,
            "verification_status": "PENDING_CONFIRMATION",
        }
    ]

    result = StructuredMapper(data).map_candidates()

    assert not any(item.relation_type == "MEMBER_OF" for item in result.relations)
    assert not any(item.relation_type == "RELATED_TO" for item in result.relations)
    assert any(
        issue.reason == "UNMATCHED_RELATION"
        and issue.source_table == "person_social_relation"
        for issue in result.issues
    )


def test_research_collaboration_with_exact_organization() -> None:
    data = _base_input()
    data.records["social_organization"] = [_organization()]
    data.records["social_activity"] = [
        {
            "social_activity_id": 8,
            "person_id": 1,
            "activity_type": "RESEARCH_COLLABORATION",
            "activity_name": "联合实验室项目",
            "partner_name": "测试研究院",
            "activity_date": "2025-06",
            "activity_description": "需要 LLM 处理的详细合作内容",
            "source_id": 200,
            "verification_status": "PENDING_CONFIRMATION",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    relations = [
        item for item in result.relations if item.relation_type == "ACADEMIC_COOPERATION"
    ]

    assert len(relations) == 1
    assert relations[0].end_node_id == "organization:10"
    assert relations[0].properties["project_name"] == "联合实验室项目"
    assert not any(
        issue.reason == "UNKNOWN_ENUM"
        and issue.value_preview == "RESEARCH_COLLABORATION"
        for issue in result.issues
    )
    assert not any(issue.reason == "REQUIRES_LLM_EXTRACTION" for issue in result.issues)


def test_research_collaboration_without_organization_creates_pending_event() -> None:
    data = _base_input()
    data.records["social_activity"] = [
        {
            "social_activity_id": 9,
            "person_id": 1,
            "activity_type": "RESEARCH_COLLABORATION",
            "activity_name": "产学研联合攻关",
            "partner_name": None,
            "activity_date": "约2025年",
            "activity_description": "合作机构位于长文本中，本轮禁止解析",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    events = [item for item in result.events if item.event_type == "RESEARCH_COLLABORATION"]

    assert len(events) == 1
    assert events[0].verification_status == "PENDING"
    assert events[0].event_date == "2025"
    assert events[0].date_precision == "YEAR"
    assert any(
        item.relation_type == "PARTICIPATED_IN" and item.end_node_id == events[0].event_id
        for item in result.relations
    )
    assert not any(item.relation_type == "RELATED_TO" for item in result.relations)
    assert any(issue.reason == "UNMATCHED_RELATION" for issue in result.issues)
    assert not any(issue.reason == "REQUIRES_LLM_EXTRACTION" for issue in result.issues)


def test_structured_event_writes_rule_parsed_amount_currency_and_date() -> None:
    data = _base_input()
    data.records["financial_event"] = [
        {
            "financial_event_id": 1,
            "person_id": 1,
            "event_type": "INVESTMENT",
            "event_date": "2025-06-18",
            "amount": "1.5亿元人民币",
            "currency_code": None,
            "purpose": "结构化测试事项",
            "event_description": "需要 LLM 的说明",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    event = result.events[0]

    assert event.event_date == "2025-06-18"
    assert event.date_precision == "DAY"
    assert event.properties["amount"] == "150000000"
    assert event.properties["currency"] == "CNY"
    assert event.properties["amount_unit"] == "元"
    assert event.properties["raw_amount"] == "1.5亿元人民币"
    assert event.properties["raw_date"] == "2025-06-18"
    assert event.description == "需要 LLM 的说明"
    assert event.evidence_text == "需要 LLM 的说明"
    assert not any(issue.reason == "REQUIRES_LLM_EXTRACTION" for issue in result.issues)


def test_enterprise_event_description_is_direct_event_text() -> None:
    data = _base_input()
    data.records["person_enterprise_relation"] = [
        {
            "person_enterprise_relation_id": 1,
            "person_id": 1,
            "enterprise_id": 10,
            "relation_type": "WORKS_AT",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]
    data.records["enterprise"] = [
        {
            "enterprise_id": 10,
            "enterprise_name": "测试科技有限公司",
            "normalized_name": "测试科技有限公司",
            "verification_status": "CONFIRMED",
        }
    ]
    data.records["enterprise_event"] = [
        {
            "enterprise_event_id": 7,
            "enterprise_id": 10,
            "event_type": "OPERATING_EVENT",
            "event_date": "2025年",
            "event_description": "  企业原样描述文本  ",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    event = next(item for item in result.events if item.event_type == "OPERATING_EVENT")

    assert event.description == "  企业原样描述文本  "
    assert event.evidence_text == "  企业原样描述文本  "
    assert not any(
        issue.reason == "REQUIRES_LLM_EXTRACTION"
        and issue.source_table == "enterprise_event"
        for issue in result.issues
    )


@pytest.mark.parametrize(
    ("raw_type", "canonical_type"),
    [
        ("CAPITAL_OPERATION", "CAPITAL_OPERATION"),
        ("CORPORATE_GOVERNANCE", "CORPORATE_GOVERNANCE"),
        ("INDUSTRY_POLICY", "INDUSTRY_POLICY"),
        ("INDUSTRY_TREND", "INDUSTRY_TREND"),
        ("OPERATING_EVENT", "OPERATING_EVENT"),
        ("REGULATORY_OR_LEGAL", "REGULATORY"),
    ],
)
def test_known_enterprise_event_enums_are_normalized_without_unknown_issue(
    raw_type: str,
    canonical_type: str,
) -> None:
    data = _market_input([])
    data.records["enterprise_event"] = [
        {
            "enterprise_event_id": 91,
            "enterprise_id": 10,
            "event_type": raw_type,
            "event_date": None,
            "event_description": "企业事件原始描述",
            "source_id": 200,
            "verification_status": "PENDING",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    event = next(
        item
        for item in result.events
        if item.properties.get("mysql_source_table") == "enterprise_event"
    )

    assert event.event_type == canonical_type
    assert event.properties["raw_event_type"] == raw_type
    assert event.properties["mysql_source_pk"] == "91"
    assert event.source_id == "200"
    assert event.description == "企业事件原始描述"
    assert event.evidence_text == "企业事件原始描述"
    assert event.verification_status == "PENDING"
    assert not any(
        issue.reason == "UNKNOWN_ENUM"
        and issue.source_table == "enterprise_event"
        and issue.value_preview == raw_type
        for issue in result.issues
    )


@pytest.mark.parametrize(
    ("raw_type", "canonical_type"),
    [
        ("HONOR_OR_PUBLIC_EVALUATION", "HONOR"),
        ("MEDIA_ATTENTION", "MEDIA_ATTENTION"),
    ],
)
def test_known_public_reputation_enums_are_normalized_without_unknown_issue(
    raw_type: str,
    canonical_type: str,
) -> None:
    data = _base_input()
    data.records["public_reputation"] = [
        {
            "public_reputation_id": 92,
            "person_id": 1,
            "reputation_type": raw_type,
            "description": "公开声誉原始描述",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    event = next(
        item
        for item in result.events
        if item.properties.get("mysql_source_table") == "public_reputation"
    )

    assert event.event_type == canonical_type
    assert event.properties["raw_event_type"] == raw_type
    assert event.properties["mysql_source_pk"] == "92"
    assert event.source_id == "200"
    assert event.description == "公开声誉原始描述"
    assert event.evidence_text == "公开声誉原始描述"
    assert event.verification_status == "PENDING"
    assert not any(
        issue.reason == "UNKNOWN_ENUM"
        and issue.source_table == "public_reputation"
        and issue.value_preview == raw_type
        for issue in result.issues
    )


def test_unknown_event_enum_is_rejected_with_original_value_preview() -> None:
    data = _market_input([])
    data.records["enterprise_event"] = [
        {
            "enterprise_event_id": 93,
            "enterprise_id": 10,
            "event_type": "UNKNOWN_TEST_TYPE",
            "event_description": "不得生成错误候选",
            "source_id": 200,
            "verification_status": "PENDING",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    issues = [
        issue
        for issue in result.issues
        if issue.reason == "UNKNOWN_ENUM"
        and issue.source_table == "enterprise_event"
    ]

    assert not any(
        event.properties.get("mysql_source_table") == "enterprise_event"
        for event in result.events
    )
    assert len(issues) == 1
    assert issues[0].value_preview == "UNKNOWN_TEST_TYPE"
    assert issues[0].requires_manual_confirmation is True


@pytest.mark.parametrize("raw_type", [None, "", "   "])
def test_null_or_empty_event_enum_is_not_accepted(raw_type) -> None:
    data = _market_input([])
    data.records["enterprise_event"] = [
        {
            "enterprise_event_id": 94,
            "enterprise_id": 10,
            "event_type": raw_type,
            "event_description": "缺少合法事件类型",
            "source_id": 200,
            "verification_status": "PENDING",
        }
    ]

    result = StructuredMapper(data).map_candidates()

    assert not any(
        event.properties.get("mysql_source_table") == "enterprise_event"
        for event in result.events
    )
    assert any(
        issue.reason == "UNKNOWN_ENUM"
        and issue.source_table == "enterprise_event"
        for issue in result.issues
    )


def test_existing_financial_event_mapping_is_unchanged() -> None:
    data = _base_input()
    data.records["financial_event"] = [
        {
            "financial_event_id": 95,
            "person_id": 1,
            "event_type": "INVESTMENT",
            "event_description": "既有投资事件",
            "source_id": 200,
            "verification_status": "PENDING",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    event = next(item for item in result.events if item.event_type == "INVESTMENT")

    assert event.properties["raw_event_type"] == "INVESTMENT"
    assert not any(
        issue.reason == "UNKNOWN_ENUM"
        and issue.source_table == "financial_event"
        for issue in result.issues
    )


def test_social_activity_description_is_direct_without_llm_issue() -> None:
    data = _base_input()
    data.records["social_activity"] = [
        {
            "social_activity_id": 12,
            "person_id": 1,
            "activity_type": "ESG",
            "activity_name": "绿色项目",
            "activity_description": "人物参与绿色项目",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    event = next(item for item in result.events if item.event_type == "ESG_ACTIVITY")

    assert event.description == "人物参与绿色项目"
    assert event.evidence_text == "人物参与绿色项目"
    assert result.field_treatment_counts["DIRECT"] >= 1
    assert not any(issue.reason == "REQUIRES_LLM_EXTRACTION" for issue in result.issues)


def test_customer_note_text_is_mysql_only() -> None:
    data = _base_input()
    row = {
        "interaction_note_id": 3,
        "person_id": 1,
        "note_type": "EXPLICIT_NEED",
        "note_text": "明确表达的客户纪要",
        "is_explicit_expression": 1,
        "source_id": 200,
        "verification_status": "PENDING_CONFIRMATION",
    }
    data.records["customer_interaction_note"] = [row]
    mapper = StructuredMapper(data)

    assert mapper._treatment_for("customer_interaction_note", "note_text", row) == "MYSQL_ONLY"
    result = mapper.map_candidates()
    assert result.field_treatment_counts["MYSQL_ONLY"] >= 1
    assert not any(issue.reason == "REQUIRES_LLM_EXTRACTION" for issue in result.issues)


def test_family_descriptions_are_direct_but_remain_pending() -> None:
    data = _base_input()
    data.records["family_member"] = [
        {
            "family_member_id": 5,
            "person_id": 1,
            "member_name": "家庭成员",
            "protected_alias": "成员A",
            "public_disclosure_level": "RESTRICTED",
            "member_description": "  原样家庭描述  ",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]
    data.records["person_family_relation"] = [
        {
            "person_family_relation_id": 6,
            "person_id": 1,
            "family_member_id": 5,
            "relation_type": "FAMILY_MEMBER",
            "relation_description": "原样家庭关系说明",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]
    data.records["succession_arrangement"] = [
        {
            "succession_arrangement_id": 9,
            "person_id": 1,
            "arrangement_status": "PLANNING",
            "governance_model": "FAMILY_GOVERNANCE",
            "arrangement_description": "原样传承安排说明",
            "source_id": 200,
            "verification_status": "CONFIRMED",
        }
    ]

    result = StructuredMapper(data).map_candidates()
    member = next(item for item in result.nodes if item.node_type == "FamilyMember")
    profile = next(item for item in result.nodes if item.node_type == "FamilyProfile")
    relation = next(item for item in result.relations if item.relation_type == "FAMILY_OF")

    assert member.properties["description"] == "  原样家庭描述  "
    assert profile.properties["description"] == "原样传承安排说明"
    assert relation.properties["description"] == "原样家庭关系说明"
    assert member.verification_status == "PENDING"
    assert profile.verification_status == "PENDING"
    assert relation.verification_status == "PENDING"
    assert not any(issue.reason == "REQUIRES_LLM_EXTRACTION" for issue in result.issues)
