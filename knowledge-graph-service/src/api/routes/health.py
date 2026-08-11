"""Process and dependency health endpoints."""

from typing import Annotated

from fastapi import APIRouter, Depends

from src.api.dependencies import get_dependency_health_service
from src.api.schemas import DependencyHealthResponse, HealthResponse
from src.services.dependency_health_service import DependencyHealthService


router = APIRouter(tags=["Health"])


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="UP")


@router.get(
    "/health/dependencies",
    response_model=DependencyHealthResponse,
    summary="检查 MySQL 和 Neo4j 依赖",
)
def dependency_health(
    service: Annotated[
        DependencyHealthService,
        Depends(get_dependency_health_service),
    ],
) -> DependencyHealthResponse:
    return DependencyHealthResponse.model_validate(service.check().as_dict())
