"""FastAPI dependency providers."""

from __future__ import annotations

from functools import lru_cache

from src.services.dependency_health_service import DependencyHealthService
from src.services.kg_build_service import KGBuildService
from src.services.kg_batch_job_service import KGBatchJobService


@lru_cache(maxsize=1)
def get_kg_build_service() -> KGBuildService:
    """Return the stateless application service used by HTTP requests."""

    return KGBuildService()


@lru_cache(maxsize=1)
def get_kg_batch_job_service() -> KGBatchJobService:
    """Return the in-process registry and executor for batch HTTP jobs."""

    return KGBatchJobService()


@lru_cache(maxsize=1)
def get_dependency_health_service() -> DependencyHealthService:
    """Return the lightweight database dependency checker."""

    return DependencyHealthService()
