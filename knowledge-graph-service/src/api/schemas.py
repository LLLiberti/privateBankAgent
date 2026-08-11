"""HTTP request and response DTOs for the KG API."""

from __future__ import annotations

from enum import Enum
from datetime import datetime
from typing import Annotated, Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator


class BuildMode(str, Enum):
    DRY_RUN = "DRY_RUN"
    EXECUTE = "EXECUTE"


class BatchJobStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    PARTIAL_FAILED = "PARTIAL_FAILED"
    FAILED = "FAILED"


PositivePersonId = Annotated[int, Field(strict=True, gt=0)]


class KGBuildRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mode: BuildMode = Field(
        ...,
        description="DRY_RUN 只抽取和预检；EXECUTE 在预检通过后写入 Neo4j。",
    )


class KGBuildResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    person_id: int
    status: str
    node_count: int
    relation_count: int
    event_count: int
    issue_count: int
    issues: List[Dict[str, Any]] = Field(default_factory=list)
    import_result: Optional[Dict[str, Any]] = None


class BatchBuildRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    person_ids: List[PositivePersonId] = Field(min_length=1)
    mode: BuildMode

    @field_validator("person_ids")
    @classmethod
    def deduplicate_and_sort(cls, values: List[int]) -> List[int]:
        return sorted(set(values))


class BatchSubmitResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    job_id: str
    status: BatchJobStatus


class BatchStatusResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    job_id: str
    batch_id: str
    status: BatchJobStatus
    mode: BuildMode
    person_ids: List[int]
    total: int
    success: int
    failed: int
    pending: int
    processed: int
    created_at: datetime
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None


class BatchIssueResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    person_id: int
    stage: str
    reason: str
    severity: str
    message: str
    source_table: Optional[str] = None
    source_pk: Optional[str] = None
    record_type: Optional[str] = None
    record_id: Optional[str] = None


class BatchIssuesResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    job_id: str
    total: int
    issues: List[BatchIssueResponse] = Field(default_factory=list)


class BatchRetryResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    job_id: str
    status: BatchJobStatus
    retry_person_ids: List[int]


class HealthResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: str


class DependencyStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal["UP", "DOWN"]


class DependencyStatuses(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mysql: DependencyStatus
    neo4j: DependencyStatus


class DependencyHealthResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal["UP", "DEGRADED"]
    dependencies: DependencyStatuses
