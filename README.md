# 私银专业智能体助手

面向私人银行客户经理的智能业务后端。项目以 Spring Boot 为主服务，围绕客户信息整合、KYC 补充问答、CFS 工作流、多智能体协作、产品知识检索、合规校验和报告交付，配套提供数据加工、知识图谱构建与产品文档索引工具。

## 项目组成

| 目录 | 职责 | 主要技术 |
| --- | --- | --- |
| `src/` | 核心后端、认证鉴权、客户与产品查询、CFS 工作流、智能体编排、报告生成 | Java 21、Spring Boot 3.5、Spring Security、MyBatis-Plus、AgentScope |
| `data-processing/` | 将个人、企业、家庭、社会四维 Excel 数据加工为可追溯的 MySQL 结构化数据 | Python 3.10+、PyMySQL、openpyxl |
| `knowledge-graph-service/` | 从 MySQL 只读构建图谱候选，预检后幂等导入 Neo4j，并提供批处理 API | Python、FastAPI、Neo4j |
| `rag-product-indexing/` | 解析产品 PDF，完成分块、向量生成以及 Qdrant、Elasticsearch 索引 | Python、Qdrant、Elasticsearch |
| `src/main/resources/db/migration/` | Flyway 数据库迁移脚本 | Flyway、MySQL |

子模块的详细操作说明见：

- [数据处理模块](data-processing/README.md)
- [知识图谱服务](knowledge-graph-service/README.md)
- [产品知识库索引](rag-product-indexing/README.md)

## 核心业务流程

```text
登录或注册
  -> 查询客户及客户全景
  -> 创建 CFS 工作流（Idempotency-Key 防止重复创建）
  -> 通过 SSE 订阅工作流事件
  -> KYC 分析与客户经理补充输入/审核
  -> 市场洞察、产品专家、KYP 推荐、CFS 方案、合规检查等智能体协作
  -> 查询工作流结果与产物
  -> 预览或下载 CFS 报告
```

工作流运行状态和智能体结果由后端持久化；请求对象、运行时上下文和 SSE 连接本身不作为新增持久化模型。

## 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.0
- Python 3.10+（仅运行 Python 子模块时需要）
- 按功能选装 Neo4j、Qdrant、Elasticsearch

## 配置

生产凭据、数据库密码和第三方 API Key 必须通过环境变量或外部密钥管理注入，不应写入 `application.yml`、`flyway.toml`、README、测试代码或提交历史。

后端启动前至少检查以下配置：

| 配置 | 用途 |
| --- | --- |
| `PRIVATE_BANK_DB_URL` | MySQL JDBC 地址 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | MySQL 账号与密码 |
| `PRIVATE_BANK_SECURITY_JWT_SECRET` | JWT 签名密钥，UTF-8 长度至少 32 字节 |
| `PRIVATE_BANK_DEEPSEEK_API_KEY` | 智能体对话模型凭据 |
| `PRIVATE_BANK_EMBEDDING_API_KEY` | Embedding 服务凭据 |
| `PRIVATE_BANK_NEO4J_URI` / `PRIVATE_BANK_NEO4J_USERNAME` / `PRIVATE_BANK_NEO4J_PASSWORD` | Neo4j 连接信息 |
| `PRIVATE_BANK_QDRANT_HOST` / `PRIVATE_BANK_QDRANT_PORT` / `PRIVATE_BANK_QDRANT_API_KEY` | Qdrant 连接信息 |
| `PRIVATE_BANK_ES_URIS` / `PRIVATE_BANK_ES_USERNAME` / `PRIVATE_BANK_ES_PASSWORD` | Elasticsearch 连接信息 |
| `PRIVATE_BANK_STORAGE_ROOT` | 报告与产物目录，默认 `./storage` |
| `SERVER_PORT` | HTTP 端口，默认 `8080` |

Python 子模块还各自提供 `.env.example` 或环境变量说明，请以对应模块 README 为准。

## 本地启动

在仓库根目录执行：

```bash
mvn --no-transfer-progress spring-boot:run
```

服务默认监听 `8080` 端口。无需鉴权的存活检查：

```text
GET http://localhost:8080/actuator/health
```

仓库中的 `Dockerfile` 是用于挂载源码运行的开发/集成镜像，不是已经打包应用的生产镜像。

## 主要接口

| 接口前缀 | 功能 |
| --- | --- |
| `/api/auth` | 登录、注册、退出和当前用户 |
| `/api/customers` | 客户列表、详情、全景和关系图谱 |
| `/api/products` | 产品查询 |
| `/api/cfs/workflows` | CFS 工作流创建、查询、事件订阅、输入、审核、重试、取消和文件下载 |
| `/api/cfs/reports` | CFS 报告列表与预览 |
| `/api/admin` | 配置、知识库、工作流、客户范围和演示数据管理 |

除登录、注册和健康检查外，接口默认需要 Bearer JWT。创建工作流、提交补充输入、审核、取消和重试等写操作需要携带 `Idempotency-Key`。

## 测试

Java 后端：

```bash
mvn test
```

Python 子模块：

```bash
cd knowledge-graph-service
python -m pytest -q -p no:cacheprovider tests

cd ../rag-product-indexing
python -m pytest -q
```

`*LiveTest` 可能依赖真实数据库或外部服务，提交前应区分离线单元测试与需要凭据的联调测试。

## 代码与数据管理约定

- 业务代码、数据库迁移、脱敏示例和必要文档应纳入 Git；构建产物、运行输出、IDE 配置、本地缓存、真实客户数据和凭据不得提交。
- Flyway 迁移采用追加式管理，已在共享环境执行的迁移不要改写或删除。
- 一次提交只表达一个完整目的，提交信息建议使用 `feat:`、`fix:`、`docs:`、`test:`、`refactor:`、`chore:` 等统一前缀。
- 提交前至少检查 `git status` 和相关测试结果，避免把本地配置、临时文件或无关修改混入业务提交。
- 涉及真实客户数据、产品材料或报告产物时，提交前必须完成人工脱敏与授权确认。

## 当前边界

- Java 后端是在线业务主入口；三个 Python 目录是独立工具或服务，不由 Maven 生命周期统一管理。
- 产品索引模块当前负责离线解析与建索引，不包含完整的在线 RAG 问答服务。
- 知识图谱批次 Job registry 位于 Python 进程内存，服务重启后不会自动恢复旧 Job 注册状态。
- 智能体输出用于辅助客户经理决策，KYC、产品推荐、合规结论和最终报告仍需人工审核。
