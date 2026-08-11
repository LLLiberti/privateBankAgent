# Python KG 服务 HTTP API

本文档面向 Spring Boot 后端开发人员，内容以当前 FastAPI Router、Pydantic DTO 和 Service 实现为准。

## 1. 通用约定

- 本地 Base URL：`http://127.0.0.1:8000`
- JSON 请求使用：`Content-Type: application/json`
- Swagger：`http://127.0.0.1:8000/docs`
- 除依赖健康检查外，KG API 前缀为 `/api/v1/kg`。
- 请求 DTO 均禁止未声明字段；字段类型、必填项或枚举不合法时由 FastAPI/Pydantic 返回 HTTP 422。
- 业务执行失败通常通过响应中的 `status` 表示，并不等同于 HTTP 调用失败。

### 1.1 构建模式

| mode | 语义 |
| --- | --- |
| `DRY_RUN` | 读取 MySQL、生成候选并执行 Preflight，不创建 Neo4j 写入连接。预检通过时单客户业务状态为 `PREFLIGHT_PASSED`。 |
| `EXECUTE` | 完成抽取和 Preflight，并在写入前复检；复检通过后调用 Neo4jImporter。导入无 ERROR issue 时业务状态为 `SUCCESS`。 |

`WARNING` 和 `INFO` issue 不会单独导致构建失败；抽取、预检或导入阶段的 `ERROR` 会产生相应失败状态。

### 1.2 批量 Job 状态

| 状态 | 语义 |
| --- | --- |
| `PENDING` | HTTP 已接受任务，后台线程尚未开始执行。 |
| `RUNNING` | `BatchKGPipeline` 正在逐个处理客户。 |
| `SUCCESS` | 目标客户全部成功。DRY_RUN 下 `PREFLIGHT_PASSED` 计为成功；EXECUTE 下 `SUCCESS` 计为成功。 |
| `PARTIAL_FAILED` | 批次结束，至少一个客户成功且至少一个客户属于失败状态。 |
| `FAILED` | 全部客户失败，或 Pipeline 顶层异常导致批次无法正常完成。 |

失败客户状态由 Pipeline 统一定义为：`EXTRACTION_FAILED`、`PREFLIGHT_FAILED`、`IMPORT_FAILED`。

### 1.3 Job 标识与执行模型

- 首次提交时：`job_id == batch_id == UUID`。
- 批量提交为异步接口，POST 不等待整个批次执行完成。
- 默认进程内线程池最多同时执行 2 个批次；单个批次内部按 `person_id` 串行处理。
- Spring Boot 收到 `job_id` 后，应轮询批次状态接口。
- 当前 Job registry 位于 Python 进程内存。FastAPI 重启后，磁盘 manifest 和输出仍存在，但旧 Job 不会自动注册，状态、Issues、Retry 等旧 Job API 可能返回 `JOB_NOT_FOUND`。
- 当前不适合多个 Uvicorn worker。开发和联调阶段应使用单进程启动命令，不要增加 `--workers`。

### 1.4 错误结构

Service 主动映射的错误采用 FastAPI `detail` 包装：

```json
{
  "detail": {
    "code": "JOB_NOT_FOUND",
    "message": "未找到批量任务：example-job-id"
  }
}
```

Pydantic 参数校验错误使用 FastAPI 标准 HTTP 422 响应，其 `detail` 为校验错误数组。

---

## 2. FastAPI 进程健康检查

### 2.1 接口名称

FastAPI 进程健康检查。

### 2.2 用途

只确认 Python HTTP 进程可以响应，不检查 MySQL 或 Neo4j。

### 2.3 Method + Path

```http
GET /health
```

### 2.4 Path Parameters

无。

### 2.5 Query Parameters

无。

### 2.6 Request Body

无。

### 2.7 Response

HTTP 200：

```json
{
  "status": "UP"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | string | 当前固定为 `UP`。 |

### 2.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 200 | FastAPI 进程正常响应。 |
| 500 | 框架发生未预期错误。 |

### 2.9 错误响应

没有自定义业务错误结构。

### 2.10 备注

不要用该接口判断数据库是否可用；依赖状态请调用 `/health/dependencies`。

---

## 3. MySQL / Neo4j 依赖健康检查

### 3.1 接口名称

外部依赖健康检查。

### 3.2 用途

执行轻量 MySQL `SELECT 1` 和 Neo4j `RETURN 1 AS ok`，不读取客户数据、不写入数据库。

### 3.3 Method + Path

```http
GET /health/dependencies
```

### 3.4 Path Parameters

无。

### 3.5 Query Parameters

无。

### 3.6 Request Body

无。

### 3.7 Response

全部正常：

```json
{
  "status": "UP",
  "dependencies": {
    "mysql": {"status": "UP"},
    "neo4j": {"status": "UP"}
  }
}
```

任意依赖异常：

```json
{
  "status": "DEGRADED",
  "dependencies": {
    "mysql": {"status": "UP"},
    "neo4j": {"status": "DOWN"}
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `UP` / `DEGRADED` | MySQL、Neo4j 都为 UP 时是 UP，否则是 DEGRADED。 |
| `dependencies.mysql.status` | `UP` / `DOWN` | MySQL 连通性。 |
| `dependencies.neo4j.status` | `UP` / `DOWN` | Neo4j 连通性。 |

### 3.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 200 | 已形成健康检查结果，包括 `DEGRADED`。 |
| 500 | 健康检查 Service 自身发生无法形成结构化结果的未预期错误。 |

### 3.9 错误响应

单个数据库不可用不会返回错误响应或 HTTP 500，而是返回 HTTP 200 + `DEGRADED`。

### 3.10 备注

响应不会包含数据库地址、用户名、密码、版本或异常详情。该接口是同步检查，耗时受驱动连接超时和 Neo4j 重试配置影响。

---

## 4. 构建单客户知识图谱

### 4.1 接口名称

单客户 KG 构建。

### 4.2 用途

同步执行单个客户的 MySQL 读取、候选生成、StructuredMapper、Preflight，并按 mode 决定是否导入 Neo4j。

### 4.3 Method + Path

```http
POST /api/v1/kg/persons/{person_id}/build
```

### 4.4 Path Parameters

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `person_id` | integer | 是 | MySQL `person.person_id`，必须大于 0。 |

### 4.5 Query Parameters

无。

### 4.6 Request Body

DRY_RUN：

```json
{
  "mode": "DRY_RUN"
}
```

EXECUTE：

```json
{
  "mode": "EXECUTE"
}
```

只接受 `DRY_RUN` 和 `EXECUTE`，不接受数据库配置、节点 Label、关系类型、SQL、Cypher 或任意图谱 JSON。

### 4.7 Response

DRY_RUN 预检通过示例：

```json
{
  "person_id": 1,
  "status": "PREFLIGHT_PASSED",
  "node_count": 28,
  "relation_count": 67,
  "event_count": 39,
  "issue_count": 1,
  "issues": [
    {
      "stage": "EXTRACTION",
      "issue_id": "issue-example",
      "reason": "MAPPING_PENDING",
      "severity": "WARNING",
      "message": "字段需要后续人工确认"
    }
  ],
  "import_result": null
}
```

EXECUTE 成功示例：

```json
{
  "person_id": 1,
  "status": "SUCCESS",
  "node_count": 28,
  "relation_count": 67,
  "event_count": 39,
  "issue_count": 1,
  "issues": [
    {
      "stage": "EXTRACTION",
      "issue_id": "issue-example",
      "reason": "MAPPING_PENDING",
      "severity": "WARNING",
      "message": "字段需要后续人工确认"
    }
  ],
  "import_result": {
    "summary": {
      "normal_node_input_count": 28,
      "event_input_count": 39,
      "relation_input_count": 67,
      "merged_node_count": 28,
      "merged_event_count": 39,
      "merged_relation_count": 67,
      "skipped_dangling_relation_count": 0,
      "invalid_type_count": 0,
      "pending_candidate_count": 134,
      "graph_status": "PREVIEW",
      "database": "neo4j",
      "mysql_write_performed": false,
      "llm_called": false
    },
    "issues": []
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `person_id` | integer | 请求客户 ID。 |
| `status` | string | `PREFLIGHT_PASSED`、`SUCCESS` 或抽取/预检/导入失败状态。 |
| `node_count` | integer | 候选普通节点数量。 |
| `relation_count` | integer | 候选关系数量。 |
| `event_count` | integer | 候选事件数量。 |
| `issue_count` | integer | 当前 `KGBuildResult.issues` 的数量。 |
| `issues` | array | 抽取、预检和导入阶段的结构化 issue；具体扩展字段取决于 issue 来源。 |
| `import_result` | object / null | DRY_RUN 或未进入导入时通常为 null；导入阶段包含 summary 和 import issues。 |

`graph_status=PREVIEW` 表示当前候选图谱仍保留待确认/预览语义，不表示 Neo4j 一定没有写入。应以请求 mode、顶层 `status` 和 merged 计数判断导入结果。

### 4.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 200 | Service 已返回结构化构建结果，包括业务失败状态。 |
| 422 | `person_id <= 0`、mode 非法、缺字段或出现额外请求字段。 |
| 500 | Router/Service 发生未捕获的未预期错误。 |

### 4.9 错误响应

输入错误由 FastAPI 返回标准 422。抽取、Preflight 或导入失败通常仍为 HTTP 200，并通过 `status`、`issues`、`import_result` 表达。

### 4.10 备注

该接口同步执行，调用方会等待单客户流程结束。WARNING / INFO issue 不等于请求失败。

---

## 5. 提交批量 KG 任务

### 5.1 接口名称

异步提交批量构建任务。

### 5.2 用途

创建进程内后台 Job，并由 `BatchKGPipeline` 逐个调用 `KGBuildService.build_person()`。

### 5.3 Method + Path

```http
POST /api/v1/kg/batches
```

### 5.4 Path Parameters

无。

### 5.5 Query Parameters

无。

### 5.6 Request Body

```json
{
  "person_ids": [1, 2, 3],
  "mode": "EXECUTE"
}
```

DRY_RUN：

```json
{
  "person_ids": [1, 2, 3],
  "mode": "DRY_RUN"
}
```

约束：

- `person_ids` 必填且至少一个元素；
- 每个 ID 必须是严格正整数，字符串和布尔值不接受；
- 重复 ID 自动去重，并按数字升序处理；
- mode 只允许 `DRY_RUN`、`EXECUTE`；
- 当前 HTTP API 不支持 `allPersons`。

### 5.7 Response

HTTP 202：

```json
{
  "job_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `job_id` | string | 当前同时作为 batch_id 使用的 UUID。 |
| `status` | string | 提交响应固定为 `PENDING`。后台可能很快进入 RUNNING。 |

### 5.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 202 | 任务已提交到进程内线程池。 |
| 422 | person_ids 或 mode 校验失败，或包含未声明字段。 |
| 500 | 线程池提交等未预期错误。 |

### 5.9 错误响应

参数错误使用 FastAPI 标准 422。某个客户之后出现 `PREFLIGHT_FAILED` 或 `IMPORT_FAILED` 不会回写为原 POST 的 HTTP 500，应查询 Job 状态。

### 5.10 备注

该 POST 必须快速返回。Spring Boot 不应等待它完成整个批次，应保存 `job_id` 并轮询下一接口。

---

## 6. 查询批量任务状态

### 6.1 接口名称

批量 Job 状态查询。

### 6.2 用途

查询进程内 Job 状态，并在 manifest 可用时读取当前成功、失败和待处理数量。

### 6.3 Method + Path

```http
GET /api/v1/kg/batches/{job_id}
```

### 6.4 Path Parameters

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `job_id` | string | 是 | 批量提交接口返回的 UUID。 |

### 6.5 Query Parameters

无。

### 6.6 Request Body

无。

### 6.7 Response

```json
{
  "job_id": "550e8400-e29b-41d4-a716-446655440000",
  "batch_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "RUNNING",
  "mode": "EXECUTE",
  "person_ids": [1, 2, 3],
  "total": 3,
  "success": 1,
  "failed": 0,
  "pending": 2,
  "processed": 1,
  "created_at": "2026-08-11T01:00:00Z",
  "started_at": "2026-08-11T01:00:00Z",
  "finished_at": null
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `job_id` | string | HTTP Job ID。 |
| `batch_id` | string | Pipeline batch ID；首次 HTTP 批次与 job_id 相同。 |
| `status` | enum | `PENDING`、`RUNNING`、`SUCCESS`、`PARTIAL_FAILED`、`FAILED`。 |
| `mode` | enum | 原任务模式。 |
| `person_ids` | integer[] | 去重、排序后的目标客户。 |
| `total` | integer | 批次客户总数。 |
| `success` | integer | 当前成功客户数。 |
| `failed` | integer | 当前失败客户数。 |
| `pending` | integer | 尚未形成成功/失败终态的客户数。 |
| `processed` | integer | `success + failed`。 |
| `created_at` | datetime | Job 创建时间。 |
| `started_at` | datetime / null | 当前运行开始时间。 |
| `finished_at` | datetime / null | 当前运行结束时间。 |

### 6.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 200 | 查询成功。 |
| 404 | 当前进程 registry 中不存在该 Job。 |
| 500 | 未预期错误。 |

### 6.9 错误响应

```json
{
  "detail": {
    "code": "JOB_NOT_FOUND",
    "message": "未找到批量任务：550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### 6.10 备注

建议轮询间隔由 Spring Boot 控制，避免高频无间隔请求。服务重启后，即使磁盘 manifest 仍在，该接口也不会自动恢复旧 Job。

---

## 7. 查询批量 Issues

### 7.1 接口名称

批次 Issues 查询。

### 7.2 用途

只读取批次已经生成的 issue 文件，不访问 MySQL、Neo4j，也不重新运行 Mapper、Preflight 或 Pipeline。

### 7.3 Method + Path

```http
GET /api/v1/kg/batches/{job_id}/issues
```

### 7.4 Path Parameters

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `job_id` | string | 是 | 批量 Job ID。 |

### 7.5 Query Parameters

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `person_id` | integer | 否 | 必须大于 0，精确匹配客户 ID。 |
| `severity` | string | 否 | 非空字符串，不区分大小写精确匹配，如 `WARNING`、`INFO`、`ERROR`。 |
| `stage` | string | 否 | 非空字符串，不区分大小写精确匹配，如 `EXTRACTION`、`PREFLIGHT`、`IMPORT`。 |
| `reason` | string | 否 | 非空字符串，不区分大小写精确匹配，如 `MAPPING_PENDING`。 |

四个条件可以组合。过滤只影响 HTTP 返回内容，不修改磁盘文件；当前不分页、不全文搜索、不自定义排序。

### 7.6 Request Body

无。

示例：

```http
GET /api/v1/kg/batches/550e8400-e29b-41d4-a716-446655440000/issues?person_id=1&severity=WARNING
```

### 7.7 Response

```json
{
  "job_id": "550e8400-e29b-41d4-a716-446655440000",
  "total": 1,
  "issues": [
    {
      "person_id": 1,
      "stage": "EXTRACTION",
      "reason": "MAPPING_PENDING",
      "severity": "WARNING",
      "message": "字段需要后续人工确认",
      "source_table": "enterprise",
      "source_pk": "1"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `job_id` | string | Job ID。 |
| `total` | integer | 过滤后的 issue 数量。 |
| `issues` | array | 过滤后的 issue。 |
| `issues[].person_id` | integer | issue 所属客户。 |
| `issues[].stage` | string | issue 产生阶段。 |
| `issues[].reason` | string | issue reason/code。 |
| `issues[].severity` | string | issue 严重级别。 |
| `issues[].message` | string | 已脱敏的问题描述。 |
| `source_table` / `source_pk` | string，可选 | 抽取来源定位。不存在时不输出。 |
| `record_type` / `record_id` | string，可选 | Preflight/Import 记录定位。不存在时不输出。 |

内部真实来源为 `output/batch_runs/<job_id>/batch_issues.json`。Spring Boot 不应直接读取该路径，应始终调用本接口。

### 7.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 200 | 查询成功；合法空文件或尚无 issue 时返回 `total=0, issues=[]`。 |
| 404 | 当前进程 registry 中不存在 Job。 |
| 422 | person_id 非正整数，或字符串过滤参数为空。 |
| 500 | 已存在的 issue 文件无法读取、JSON 非法或结构不符合 Pipeline 格式。 |

### 7.9 错误响应

Job 不存在：

```json
{
  "detail": {
    "code": "JOB_NOT_FOUND",
    "message": "未找到批量任务：example-job-id"
  }
}
```

文件异常时，`detail.code` 为以下当前实现之一：

- `BATCH_ISSUES_READ_FAILED`
- `BATCH_ISSUES_INVALID_JSON`
- `BATCH_ISSUES_INVALID_FORMAT`

### 7.10 备注

无过滤参数时返回整个批次的全部 issue。文件尚未生成或合法内容为 `[]` 时返回空数组，不会伪造 manifest 中只有数量而没有内容的 issue。

---

## 8. 重试失败客户

### 8.1 接口名称

异步重试批次失败客户。

### 8.2 用途

复用原 manifest 和 `BatchKGPipeline` 的 resume/retry-failed 能力，只重新执行失败客户。

### 8.3 Method + Path

```http
POST /api/v1/kg/batches/{job_id}/retry-failed
```

### 8.4 Path Parameters

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `job_id` | string | 是 | 要重试的原 Job ID。 |

### 8.5 Query Parameters

无。

### 8.6 Request Body

无。客户端不能重新指定 mode 或 person_ids。

### 8.7 Response

HTTP 202：

```json
{
  "job_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "retry_person_ids": [2, 4]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `job_id` | string | 仍为原 Job ID，不生成新 retry Job。 |
| `status` | string | 提交响应为 `PENDING`。 |
| `retry_person_ids` | integer[] | 从原 manifest 推导出的失败客户。 |

当前是原地 Retry：

- retry job_id 等于原 job_id；
- retry batch_id 等于原 batch_id；
- 自动继承原任务 mode；
- 只选择 `EXTRACTION_FAILED`、`PREFLIGHT_FAILED`、`IMPORT_FAILED`；
- 已成功客户不会重新执行；
- 原 manifest 中失败客户的 `attempts` 会递增；
- 重试后继续通过原 job_id 查询状态。

### 8.8 HTTP 状态码

| 状态码 | 说明 |
| --- | --- |
| 202 | 重试已提交到后台线程池。 |
| 404 | 当前进程不存在该 Job。 |
| 409 | 原 Job 仍在运行，或没有失败客户。 |
| 500 | 后台任务提交等未预期错误。 |

### 8.9 错误响应

原任务仍在 PENDING/RUNNING：

```json
{
  "detail": {
    "code": "JOB_STILL_RUNNING",
    "message": "批量任务尚未结束，当前不能重试失败客户"
  }
}
```

没有失败客户：

```json
{
  "detail": {
    "code": "NO_FAILED_PERSONS",
    "message": "当前批次没有可重试的失败客户"
  }
}
```

Job 不存在时使用 `JOB_NOT_FOUND` 结构。

### 8.10 备注

重试同样是异步操作。Spring Boot 收到 202 后应继续轮询原 Job，不应把 HTTP 202 当作客户已经重试成功。

---

## 9. Spring Boot 推荐调用流程

### 9.1 单客户

```text
POST /api/v1/kg/persons/{person_id}/build
→ 检查 HTTP 状态
→ 检查响应 status
→ 按需展示 issues / import_result
```

### 9.2 批量

```text
POST /api/v1/kg/batches
→ 保存 job_id
→ GET /api/v1/kg/batches/{job_id}
→ 轮询至 SUCCESS / PARTIAL_FAILED / FAILED
→ GET /api/v1/kg/batches/{job_id}/issues
→ 如需重试，POST /retry-failed
→ 继续轮询原 job_id
```

### 9.3 健康检查

```text
GET /health
→ 判断 Python HTTP 进程

GET /health/dependencies
→ 判断 MySQL / Neo4j
→ HTTP 200 仍需检查 status 是否为 DEGRADED
```
