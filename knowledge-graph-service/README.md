# 私银专业智能体助手：知识图谱数据服务

当前仓库实现私银客户结构化数据的知识图谱构建链路：从 MySQL 只读加载客户“人、企、家、社”等数据，经规则映射和预检生成图谱候选，并可幂等导入 Neo4j。项目同时提供单客户、批量构建、批次状态、Issues、失败重试和依赖健康检查的 FastAPI 接口。

当前范围不包含 RAG、向量数据库、文档解析、MCP、Agent、Celery、Redis 或多进程 Job 调度。

## 快速开始

```bash
python -m venv .venv
```

激活虚拟环境后安装服务依赖：

```bash
python -m pip install -r requirements-api.txt
```

复制 `.env.example` 为 `.env`，填入本地或目标环境的 MySQL、Neo4j 配置，然后启动单进程服务：

```bash
python -m uvicorn src.api.main:app --host 127.0.0.1 --port 8000
```

- Swagger：<http://127.0.0.1:8000/docs>
- 进程健康：<http://127.0.0.1:8000/health>
- 依赖健康：<http://127.0.0.1:8000/health/dependencies>

## 交付文档

- [KG HTTP API](kg_api.md)

## 测试

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q -p no:cacheprovider tests
```

测试使用 mock 覆盖 HTTP 和依赖健康场景，不需要真实连接 MySQL 或 Neo4j。

## 重要限制

- 批量 Job registry 位于 Python 进程内存，服务重启后不会自动恢复旧 Job。
- 请使用单个 Uvicorn worker；多 worker 之间不会共享 Job 状态。
- `output/`、`.env`、日志、manifest 和客户图谱产物属于本地运行数据，不应提交到 Git。
