"""FastAPI application entry point."""

from fastapi import FastAPI

from src.api.routes.health import router as health_router
from src.api.routes.kg import router as kg_router


def create_app() -> FastAPI:
    application = FastAPI(
        title="私银专业智能体助手 - 知识图谱构建 API",
        version="1.0.0",
        description=(
            "复用现有 KGBuildService 和 BatchKGPipeline 的内部知识图谱构建 API。"
        ),
        docs_url="/docs",
        openapi_url="/openapi.json",
    )
    application.include_router(health_router)
    application.include_router(kg_router)
    return application


app = create_app()
