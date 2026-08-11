"""Knowledge-graph build HTTP endpoints."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Path, Query, status

from src.api.dependencies import get_kg_batch_job_service, get_kg_build_service
from src.api.schemas import (
    BatchBuildRequest,
    BatchIssuesResponse,
    BatchRetryResponse,
    BatchStatusResponse,
    BatchSubmitResponse,
    BuildMode,
    KGBuildRequest,
    KGBuildResponse,
)
from src.services.kg_batch_job_service import (
    BatchJobConflictError,
    BatchJobNotFoundError,
    BatchIssuesReadError,
    KGBatchJobService,
)
from src.services.kg_build_service import KGBuildService


router = APIRouter(prefix="/api/v1/kg", tags=["Knowledge Graph"])


@router.post(
    "/persons/{person_id}/build",
    response_model=KGBuildResponse,
    summary="构建单个客户知识图谱",
)
def build_person(
    person_id: Annotated[int, Path(gt=0, description="MySQL person.person_id")],
    request: KGBuildRequest,
    service: Annotated[KGBuildService, Depends(get_kg_build_service)],
) -> KGBuildResponse:
    result = service.build_person(
        person_id,
        execute=request.mode == BuildMode.EXECUTE,
    )
    return KGBuildResponse.model_validate(result.as_dict())


@router.post(
    "/batches",
    response_model=BatchSubmitResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="提交批量知识图谱构建任务",
)
def submit_batch(
    request: BatchBuildRequest,
    jobs: Annotated[KGBatchJobService, Depends(get_kg_batch_job_service)],
) -> BatchSubmitResponse:
    job = jobs.submit(request.person_ids, request.mode.value)
    return BatchSubmitResponse(job_id=job.job_id, status=job.status)


@router.get(
    "/batches/{job_id}",
    response_model=BatchStatusResponse,
    summary="查询批量知识图谱构建任务状态",
)
def get_batch_status(
    job_id: str,
    jobs: Annotated[KGBatchJobService, Depends(get_kg_batch_job_service)],
) -> BatchStatusResponse:
    job = jobs.get_job(job_id)
    if job is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={
                "code": "JOB_NOT_FOUND",
                "message": f"未找到批量任务：{job_id}",
            },
        )
    return BatchStatusResponse(
        job_id=job.job_id,
        batch_id=job.batch_id,
        status=job.status,
        mode=job.mode,
        person_ids=list(job.person_ids),
        total=job.total,
        success=job.success,
        failed=job.failed,
        pending=job.pending,
        processed=job.processed,
        created_at=job.created_at,
        started_at=job.started_at,
        finished_at=job.finished_at,
    )


@router.get(
    "/batches/{job_id}/issues",
    response_model=BatchIssuesResponse,
    response_model_exclude_none=True,
    summary="查询批量知识图谱构建问题",
)
def get_batch_issues(
    job_id: str,
    jobs: Annotated[KGBatchJobService, Depends(get_kg_batch_job_service)],
    person_id: Annotated[int | None, Query(gt=0)] = None,
    severity: Annotated[str | None, Query(min_length=1)] = None,
    stage: Annotated[str | None, Query(min_length=1)] = None,
    reason: Annotated[str | None, Query(min_length=1)] = None,
) -> BatchIssuesResponse:
    try:
        issues = jobs.get_issues(
            job_id,
            person_id=person_id,
            severity=severity,
            stage=stage,
            reason=reason,
        )
    except BatchJobNotFoundError:
        raise _job_not_found(job_id)
    except BatchIssuesReadError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": exc.code, "message": exc.message},
        ) from exc
    return BatchIssuesResponse(job_id=job_id, total=len(issues), issues=issues)


@router.post(
    "/batches/{job_id}/retry-failed",
    response_model=BatchRetryResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="异步重试批量任务中的失败客户",
)
def retry_failed_batch(
    job_id: str,
    jobs: Annotated[KGBatchJobService, Depends(get_kg_batch_job_service)],
) -> BatchRetryResponse:
    try:
        retry = jobs.retry_failed(job_id)
    except BatchJobNotFoundError:
        raise _job_not_found(job_id)
    except BatchJobConflictError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": exc.code, "message": exc.message},
        ) from exc
    return BatchRetryResponse(
        job_id=retry.job_id,
        status=retry.status,
        retry_person_ids=list(retry.retry_person_ids),
    )


def _job_not_found(job_id: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_404_NOT_FOUND,
        detail={
            "code": "JOB_NOT_FOUND",
            "message": f"未找到批量任务：{job_id}",
        },
    )
