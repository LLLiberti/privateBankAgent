"""Validated candidate graph structures for the first MySQL dry-run.

The models deliberately contain only the six node types and the relation
types approved by ``docs/mysql_to_neo4j_mapping.md``.  They are preview
records, not Neo4j driver objects.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, Field, field_validator


NodeType = Literal[
    "Person",
    "Enterprise",
    "FamilyProfile",
    "FamilyMember",
    "Organization",
    "MarketSegment",
    "Event",
]

RelationType = Literal[
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
]

# Canonical Event values already used by deterministic mappings or explicitly
# reserved by the mapping document. Database raw enums are normalized before a
# GraphEvent is constructed; arbitrary strings must not become graph events.
EventType = Literal[
    "INVESTMENT",
    "FINANCING",
    "DONATION",
    "DIVESTMENT",
    "DIVIDEND",
    "DEBT_REPAYMENT",
    "GIFT",
    "SHARE_INCREASE",
    "SHARE_DECREASE",
    "CAPITAL_OPERATION",
    "CORPORATE_GOVERNANCE",
    "INDUSTRY_POLICY",
    "INDUSTRY_TREND",
    "OPERATING_EVENT",
    "REGULATORY",
    "LEGAL",
    "CHARITY_ACTIVITY",
    "ESG_ACTIVITY",
    "ACADEMIC_ACTIVITY",
    "RESEARCH_COLLABORATION",
    "SOCIAL_ACTIVITY",
    "HONOR",
    "MEDIA_ATTENTION",
    "REPUTATION_RISK_SIGNAL",
    "CAREER_APPOINTMENT",
    "CAREER_DEPARTURE",
    "FOUNDING",
    "GUARANTEE",
    "PLEDGE",
    "LISTING",
    "SUCCESSION_PLAN",
    "FAMILY_PROTECTION_PLAN",
    "SERVICE_MILESTONE",
]

Dimension = Literal["PERSON", "ENTERPRISE", "FAMILY", "SOCIAL"]
VerificationStatus = Literal["CONFIRMED", "PENDING"]
IssueSeverity = Literal["INFO", "WARNING", "ERROR"]


class StrictModel(BaseModel):
    """Base model compatible with Pydantic 1.x and 2.x compatibility APIs."""

    class Config:
        extra = "forbid"
        validate_assignment = True

    def as_dict(self) -> Dict[str, Any]:
        model_dump = getattr(self, "model_dump", None)
        if callable(model_dump):
            return _json_safe(model_dump(mode="python"))
        return _json_safe(self.dict())


def _json_safe(value: Any) -> Any:
    """Preserve Decimal precision and make nested model data JSON-safe."""

    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


class GraphNode(StrictModel):
    node_id: str
    node_type: NodeType
    name: Optional[str] = None
    properties: Dict[str, Any] = Field(default_factory=dict)
    person_id: Optional[str] = None
    source_id: Optional[str] = None
    document_id: Optional[str] = None
    verification_status: VerificationStatus = "PENDING"
    confidence: float = 1.0
    import_batch_id: Optional[str] = None
    evidence_text: Optional[str] = None

    @field_validator("node_id")
    def node_id_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("node_id must not be blank")
        return value

    @field_validator("confidence")
    def node_confidence_in_range(cls, value: float) -> float:
        if not 0.0 <= value <= 1.0:
            raise ValueError("confidence must be between 0 and 1")
        return value


class GraphRelation(StrictModel):
    relation_id: str
    start_node_id: str
    end_node_id: str
    relation_type: RelationType
    properties: Dict[str, Any] = Field(default_factory=dict)
    dimension: Dimension
    source_id: Optional[str] = None
    document_id: Optional[str] = None
    verification_status: VerificationStatus = "PENDING"
    confidence: float = 1.0
    evidence_text: Optional[str] = None
    import_batch_id: Optional[str] = None

    @field_validator("relation_id", "start_node_id", "end_node_id")
    def relation_ids_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("relation identifiers must not be blank")
        return value

    @field_validator("confidence")
    def relation_confidence_in_range(cls, value: float) -> float:
        if not 0.0 <= value <= 1.0:
            raise ValueError("confidence must be between 0 and 1")
        return value


class GraphEvent(StrictModel):
    event_id: str
    event_type: EventType
    subject_node_id: str
    event_date: Optional[str] = None
    date_precision: Optional[Literal["DAY", "MONTH", "QUARTER", "YEAR"]] = None
    description: Optional[str] = None
    properties: Dict[str, Any] = Field(default_factory=dict)
    source_id: Optional[str] = None
    document_id: Optional[str] = None
    verification_status: VerificationStatus = "PENDING"
    confidence: float = 1.0
    evidence_text: Optional[str] = None
    import_batch_id: Optional[str] = None

    @field_validator("event_id", "event_type", "subject_node_id")
    def event_identifiers_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("event identifiers must not be blank")
        return value

    @field_validator("confidence")
    def event_confidence_in_range(cls, value: float) -> float:
        if not 0.0 <= value <= 1.0:
            raise ValueError("confidence must be between 0 and 1")
        return value


class GraphIssue(StrictModel):
    issue_id: str
    reason: str
    severity: IssueSeverity = "WARNING"
    message: str
    source_table: Optional[str] = None
    source_pk: Optional[str] = None
    field_name: Optional[str] = None
    value_preview: Optional[str] = None
    source_id: Optional[str] = None
    person_id: Optional[str] = None
    requires_manual_confirmation: bool = False

    @field_validator("issue_id", "reason", "message")
    def issue_text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("issue identifiers and text must not be blank")
        return value


class CandidateExtraction(StrictModel):
    person_id: str
    nodes: List[GraphNode] = Field(default_factory=list)
    relations: List[GraphRelation] = Field(default_factory=list)
    events: List[GraphEvent] = Field(default_factory=list)
    issues: List[GraphIssue] = Field(default_factory=list)
    table_record_counts: Dict[str, int] = Field(default_factory=dict)
    field_treatment_counts: Dict[str, int] = Field(default_factory=dict)
    skipped_tables: List[str] = Field(default_factory=list)
