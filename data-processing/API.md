# 数据处理服务 REST API 契约

## 1. 服务定位

数据处理服务负责：

- 接收个人、企业、家庭、社会四维 Excel 文件；
- 生成 staging 数据和证据记录；
- 将 staging 数据导入 MySQL；
- 执行主体、扩展字段及增强解析；
- 记录数据质量问题；
- 审计并完成导入批次。

Java 后端负责：

- 用户认证与权限控制；
- 文件上传入口；
- 调用 Python 数据处理服务；
- 查询任务进度；
- 展示数据质量问题；
- 提交人工确认和批次完成请求。

Java 后端不应直接执行 Python 命令，也不应向 Python 服务传递数据库密码或任意本地文件路径。

---

## 2. 基础信息

### Base URL

```text
http://data-processing:8090/internal/api/v1/data-processing
```

本地开发环境示例：

```text
http://127.0.0.1:8090/internal/api/v1/data-processing
```

### 通用请求头

| 请求头 | 必填 | 说明 |
| --- | --- | --- |
| `Authorization` | 是 | 服务间访问令牌，格式为 `Bearer <token>` |
| `Idempotency-Key` | 写操作必填 | 防止重复创建任务或重复完成批次 |
| `X-Trace-Id` | 建议 | Java 后端生成的链路追踪 ID |
| `Content-Type` | 是 | 根据接口使用 JSON 或 multipart |

### 通用错误结构

与 Java 后端现有 `ApiError` 保持一致：

```json
{
  "code": "DP_VALIDATION_FAILED",
  "message": "输入文件校验失败",
  "traceId": "01JABCDEF123456",
  "details": {
    "file": "30位企业家个人全维度数据采集表.xlsx",
    "reason": "缺少字段：核心关联企业"
  }
}
```

---

## 3. 状态枚举

### 3.1 任务状态 `JobStatus`

```text
QUEUED
RUNNING
WAITING_REVIEW
SUCCEEDED
FAILED
CANCELLED
```

### 3.2 处理步骤 `ProcessingStep`

```text
VALIDATE_INPUT
EXPORT_STAGING
LOAD_STAGING
PARSE_PILOT
PARSE_REMAINING
PARSE_PERSONAL
PARSE_ENTERPRISE
PARSE_FAMILY
PARSE_SOCIAL
ENHANCE_FINANCIAL_METRICS
ENHANCE_ENTERPRISE_RELATIONS
ENHANCE_FINANCIAL_EVENTS
AUDIT
FINALIZE
```

### 3.3 批次状态 `BatchStatus`

```text
CREATED
LOADING
STAGED
PARSING
WAITING_REVIEW
COMPLETED
FAILED
```

---

## 4. 健康检查

### `GET /health`

检查 Python 服务是否可用。

#### 响应：`200 OK`

```json
{
  "status": "UP",
  "service": "private-bank-data-processing",
  "version": "1.0.0",
  "database": {
    "status": "UP"
  },
  "timestamp": "2026-08-10T16:30:00+08:00"
}
```

如果数据库不可用，服务可返回 `503 Service Unavailable`。

---

## 5. 创建数据处理任务

### `POST /jobs`

上传四维 Excel 文件并创建异步处理任务。

### Content-Type

```text
multipart/form-data
```

### 表单参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `batchName` | string | 是 | 唯一批次名称，最大 128 字符 |
| `mode` | string | 否 | 默认 `FULL_PIPELINE` |
| `replacementBatchId` | long | 否 | 需要替换的旧批次 ID |
| `autoFinalize` | boolean | 否 | 默认 `false`，生产环境不建议自动完成 |
| `personFile` | file | 是 | 个人维度 Excel |
| `enterpriseFile` | file | 是 | 企业维度 Excel |
| `familyFile` | file | 是 | 家庭维度 Excel |
| `socialFile` | file | 是 | 社会维度 Excel |

### `mode` 可选值

```text
VALIDATE_ONLY
STAGE_ONLY
FULL_PIPELINE
```

### 文件名要求

```text
30位企业家个人全维度数据采集表.xlsx
30位企业家企业全维度数据采集表.xlsx
30位企业家家庭维度数据采集表.xlsx
30位企业家社会维度数据采集表.xlsx
```

### 请求示例

```bash
curl -X POST \
  "http://127.0.0.1:8090/internal/api/v1/data-processing/jobs" \
  -H "Authorization: Bearer SERVICE_TOKEN" \
  -H "Idempotency-Key: import-20260810-001" \
  -H "X-Trace-Id: trace-20260810-001" \
  -F "batchName=entrepreneur-four-dimension-20260810" \
  -F "mode=FULL_PIPELINE" \
  -F "autoFinalize=false" \
  -F "personFile=@30位企业家个人全维度数据采集表.xlsx" \
  -F "enterpriseFile=@30位企业家企业全维度数据采集表.xlsx" \
  -F "familyFile=@30位企业家家庭维度数据采集表.xlsx" \
  -F "socialFile=@30位企业家社会维度数据采集表.xlsx"
```

### 响应：`202 Accepted`

```json
{
  "jobId": "dpj_01JABCDEF123456",
  "batchId": null,
  "batchName": "entrepreneur-four-dimension-20260810",
  "status": "QUEUED",
  "currentStep": "VALIDATE_INPUT",
  "progress": 0,
  "createdAt": "2026-08-10T16:30:00+08:00",
  "links": {
    "self": "/internal/api/v1/data-processing/jobs/dpj_01JABCDEF123456",
    "events": "/internal/api/v1/data-processing/jobs/dpj_01JABCDEF123456/events"
  }
}
```

### 业务规则

1. 相同 `Idempotency-Key` 重复请求必须返回同一个任务。
2. `batchName` 必须唯一。
3. `replacementBatchId` 只能指向明确允许被替换的旧批次。
4. 没有传入 `replacementBatchId` 时，应创建全新批次，不得删除历史数据。
5. 服务端必须自行生成临时目录，不接受客户端传入任意服务器路径。
6. 上传文件必须限制扩展名、文件大小和压缩包风险。
7. `autoFinalize` 默认必须为 `false`。

> 当前 `load_staging_to_mysql.py` 强制要求 `--cleanup-batch-id`。实现 HTTP 服务时应重构该逻辑：新建批次不执行删除；只有传入 `replacementBatchId` 时才进入受控替换流程。

---

## 6. 查询任务状态

### `GET /jobs/{jobId}`

#### 响应：`200 OK`

```json
{
  "jobId": "dpj_01JABCDEF123456",
  "batchId": 5,
  "batchName": "entrepreneur-four-dimension-20260810",
  "status": "RUNNING",
  "currentStep": "PARSE_ENTERPRISE",
  "progress": 62,
  "message": "正在解析企业扩展字段",
  "startedAt": "2026-08-10T16:30:02+08:00",
  "updatedAt": "2026-08-10T16:31:18+08:00",
  "finishedAt": null,
  "statistics": {
    "stagingRows": 120,
    "evidenceRows": 1200,
    "persons": 30,
    "enterprises": 30,
    "openQualityIssues": 4
  },
  "steps": [
    {
      "step": "VALIDATE_INPUT",
      "status": "SUCCEEDED",
      "startedAt": "2026-08-10T16:30:02+08:00",
      "finishedAt": "2026-08-10T16:30:03+08:00",
      "message": "四个工作簿校验通过"
    },
    {
      "step": "PARSE_ENTERPRISE",
      "status": "RUNNING",
      "startedAt": "2026-08-10T16:31:10+08:00",
      "finishedAt": null,
      "message": "正在处理企业维度数据"
    }
  ],
  "error": null
}
```

任务失败时：

```json
{
  "jobId": "dpj_01JABCDEF123456",
  "batchId": 5,
  "status": "FAILED",
  "currentStep": "LOAD_STAGING",
  "progress": 20,
  "error": {
    "code": "DP_STAGING_COUNT_MISMATCH",
    "message": "暂存数据数量异常",
    "details": {
      "expectedStagingRows": 120,
      "actualStagingRows": 119,
      "expectedEvidenceRows": 1200,
      "actualEvidenceRows": 1198
    }
  }
}
```

---

## 7. 订阅任务事件

### `GET /jobs/{jobId}/events`

可选接口，使用 SSE 向 Java 后端推送进度，与现有 Workflow SSE 风格保持一致。

### Content-Type

```text
text/event-stream
```

### 事件示例

```text
event: step-started
data: {"jobId":"dpj_01JABCDEF123456","step":"PARSE_FAMILY","progress":70}

event: step-completed
data: {"jobId":"dpj_01JABCDEF123456","step":"PARSE_FAMILY","progress":76}

event: quality-issue
data: {"batchId":5,"issueId":32,"severity":"MEDIUM","issueType":"FAMILY_MEMBER_PARSE_EMPTY"}

event: job-waiting-review
data: {"jobId":"dpj_01JABCDEF123456","batchId":5,"openQualityIssues":4}
```

如果暂不实现 SSE，Java 后端可以每 2～5 秒轮询一次任务状态。

---

## 8. 取消任务

### `POST /jobs/{jobId}/cancel`

### 请求

```json
{
  "reason": "上传了错误版本的数据文件"
}
```

### 响应：`200 OK`

```json
{
  "jobId": "dpj_01JABCDEF123456",
  "status": "CANCELLED",
  "message": "任务已取消"
}
```

### 业务规则

- 只允许取消 `QUEUED` 或 `RUNNING` 状态任务；
- 已进入数据库事务的步骤应先回滚；
- 已完成任务不能取消；
- 取消操作需要 `Idempotency-Key`。

---

## 9. 重试失败步骤

### `POST /jobs/{jobId}/steps/{step}/retry`

示例：

```text
POST /jobs/dpj_01JABCDEF123456/steps/PARSE_ENTERPRISE/retry
```

### 请求

```json
{
  "reason": "字段映射已修正",
  "operator": "backend-service"
}
```

### 响应：`202 Accepted`

```json
{
  "jobId": "dpj_01JABCDEF123456",
  "status": "QUEUED",
  "currentStep": "PARSE_ENTERPRISE",
  "message": "步骤已进入重试队列"
}
```

### 业务规则

- 只能重试 `FAILED` 的步骤；
- 必须确保步骤具备幂等性；
- 当前部分解析脚本不具备完全幂等性，实现服务前需要增加步骤执行记录或唯一约束；
- 不允许通过重试绕过数据质量审计。

---

## 10. 查询批次详情

### `GET /batches/{batchId}`

#### 响应：`200 OK`

```json
{
  "batchId": 5,
  "batchName": "entrepreneur-four-dimension-20260810",
  "status": "WAITING_REVIEW",
  "recordCount": 120,
  "sourceDocumentCount": 1200,
  "dimensionRows": {
    "PERSON": 30,
    "ENTERPRISE": 30,
    "FAMILY": 30,
    "SOCIAL": 30
  },
  "entityCounts": {
    "person": 30,
    "enterprise": 30,
    "personProfile": 30,
    "riskPreference": 30,
    "personCareer": 30
  },
  "openQualityIssues": 4,
  "createdAt": "2026-08-10T16:30:00+08:00",
  "completedAt": null
}
```

---

## 11. 查询质量问题

### `GET /batches/{batchId}/quality-issues`

### Query 参数

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 否 | `OPEN`、`RESOLVED` |
| `severity` | 否 | `HIGH`、`MEDIUM`、`LOW` |
| `pageNo` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20，最大 100 |

### 请求示例

```text
GET /batches/5/quality-issues?status=OPEN&pageNo=1&pageSize=20
```

### 响应：`200 OK`

```json
{
  "items": [
    {
      "issueId": 32,
      "batchId": 5,
      "stagingRowId": 88,
      "sourceId": 901,
      "issueType": "CORE_RELATION_NEEDS_CONFIRMATION",
      "severity": "LOW",
      "status": "OPEN",
      "message": "人物与核心企业的具体控制关系需要人工确认",
      "source": {
        "fileName": "30位企业家个人全维度数据采集表.xlsx",
        "sheetName": "个人维度",
        "cellReference": "D12",
        "sourceLocator": "30位企业家个人全维度数据采集表.xlsx!个人维度!D12"
      },
      "createdAt": "2026-08-10T16:31:30+08:00"
    }
  ],
  "pageNo": 1,
  "pageSize": 20,
  "total": 4
}
```

---

## 12. 解决质量问题

### `POST /batches/{batchId}/quality-issues/{issueId}/resolve`

### 请求

```json
{
  "resolution": "已依据工商登记材料确认其为实际控制人",
  "resolvedBy": "user-10086",
  "correctedValue": {
    "relationType": "CONTROLLING_SHAREHOLDER",
    "ownershipPercentage": 51.2
  }
}
```

### 响应：`200 OK`

```json
{
  "issueId": 32,
  "status": "RESOLVED",
  "resolvedBy": "user-10086",
  "resolvedAt": "2026-08-10T17:05:00+08:00",
  "resolution": "已依据工商登记材料确认其为实际控制人"
}
```

### 业务规则

- 后端需要记录当前操作用户；
- 必须保留原始值和修改后值；
- 不允许直接删除质量问题；
- 涉及数据修改时，应与质量问题状态更新处于同一事务；
- 高风险问题应由具备审批权限的人员处理。

---

## 13. 批次审计

### `POST /batches/{batchId}/audit`

执行只读审计，不更新批次状态。

### 请求

```json
{
  "strict": true
}
```

### 响应：`200 OK`

```json
{
  "batchId": 5,
  "passed": true,
  "recordCount": 120,
  "dimensionRows": {
    "PERSON": 30,
    "ENTERPRISE": 30,
    "FAMILY": 30,
    "SOCIAL": 30
  },
  "sourceDocuments": 1200,
  "openQualityIssues": 0,
  "mandatoryTableCounts": {
    "person": 30,
    "enterprise": 30,
    "person_profile": 30,
    "risk_preference": 30,
    "person_career": 30
  },
  "brokenSourceReferences": {},
  "auditedAt": "2026-08-10T17:10:00+08:00"
}
```

审计未通过仍可返回 `200`，但 `passed=false`：

```json
{
  "batchId": 5,
  "passed": false,
  "openQualityIssues": 2,
  "failedChecks": [
    {
      "code": "OPEN_QUALITY_ISSUES",
      "message": "仍有 2 个未解决的数据质量问题"
    }
  ]
}
```

---

## 14. 完成批次

### `POST /batches/{batchId}/finalize`

只有审计通过后才能完成批次。

### 请求头

```text
Idempotency-Key: finalize-batch-5
```

### 请求

```json
{
  "confirmed": true,
  "reviewedBy": "user-10086",
  "reviewComment": "四维数据及证据链已经复核，同意完成批次"
}
```

### 响应：`200 OK`

```json
{
  "batchId": 5,
  "status": "COMPLETED",
  "finalized": true,
  "reviewedBy": "user-10086",
  "completedAt": "2026-08-10T17:15:00+08:00"
}
```

如果审计未通过：

### 响应：`409 Conflict`

```json
{
  "code": "DP_AUDIT_NOT_PASSED",
  "message": "批次未通过审计，不能完成",
  "traceId": "trace-20260810-020",
  "details": {
    "openQualityIssues": 2
  }
}
```

---

## 15. 推荐错误码

| HTTP 状态 | 错误码 | 说明 |
| --- | --- | --- |
| 400 | `DP_VALIDATION_FAILED` | 请求或文件校验失败 |
| 400 | `DP_FILE_MISSING` | 缺少某个维度文件 |
| 400 | `DP_HEADER_INVALID` | 表头缺失、为空或重复 |
| 401 | `DP_UNAUTHORIZED` | 服务认证失败 |
| 403 | `DP_FORBIDDEN` | 无权执行操作 |
| 404 | `DP_JOB_NOT_FOUND` | 任务不存在 |
| 404 | `DP_BATCH_NOT_FOUND` | 批次不存在 |
| 404 | `DP_ISSUE_NOT_FOUND` | 质量问题不存在 |
| 409 | `DP_IDEMPOTENCY_CONFLICT` | 幂等键对应不同请求 |
| 409 | `DP_JOB_ALREADY_RUNNING` | 相同批次已有任务运行 |
| 409 | `DP_BATCH_ALREADY_COMPLETED` | 批次已经完成 |
| 409 | `DP_AUDIT_NOT_PASSED` | 审计未通过 |
| 422 | `DP_STAGING_COUNT_MISMATCH` | staging 数量不符合预期 |
| 422 | `DP_EVIDENCE_COUNT_MISMATCH` | 证据数量不符合预期 |
| 422 | `DP_PARSE_FAILED` | 字段解析失败 |
| 500 | `DP_DATABASE_ERROR` | 数据库异常 |
| 500 | `DP_INTERNAL_ERROR` | 未分类内部异常 |
| 503 | `DP_SERVICE_UNAVAILABLE` | Python 服务或数据库不可用 |

---

## 16. Java 后端推荐调用流程

```text
用户上传四个 Excel
  → Java 校验权限和文件大小
  → Java 调用 POST /jobs
  → Python 返回 jobId
  → Java 轮询 GET /jobs/{jobId}
     或订阅 /jobs/{jobId}/events
  → 任务进入 WAITING_REVIEW
  → Java 查询质量问题
  → 客户经理/管理员处理问题
  → Java 调用 POST /batches/{batchId}/audit
  → passed=true
  → Java 调用 POST /batches/{batchId}/finalize
  → 批次状态变为 COMPLETED
```

---

## 17. 后端配置建议

Java `application.yml` 增加：

```yaml
private-bank:
  data-processing:
    base-url: ${DATA_PROCESSING_BASE_URL:http://127.0.0.1:8090}
    service-token: ${DATA_PROCESSING_SERVICE_TOKEN}
    connect-timeout: 5s
    read-timeout: 30s
    job-poll-interval: 3s
```

Python 服务配置：

```text
DATA_PROCESSING_SERVICE_TOKEN
DB_HOST
DB_PORT
DB_NAME
DB_USER
DB_PASSWORD
DATA_PROCESSING_TEMP_ROOT
DATA_PROCESSING_MAX_FILE_SIZE
DATA_PROCESSING_WORKERS
```

数据库密码和服务 Token 只能通过环境变量或密钥管理系统注入，不能由 Java 请求传入。

---

## 18. 实现前必须处理的代码问题

当前 CLI 脚本封装成服务前，需要完成以下改造：

1. 将每个脚本的 `main()` 逻辑提取为可调用函数。
2. 新建批次时允许不传 `cleanup-batch-id`。
3. 为每个处理步骤增加幂等控制。
4. 增加任务表和步骤执行记录。
5. 将固定批次 ID `4` 改为必填参数。
6. 将固定人数 `30/27/3` 改为配置或数据驱动校验。
7. 将固定输出目录改为任务隔离的临时目录。
8. 将固定试点名单改为请求参数或配置。
9. 增加文件大小、类型、哈希和恶意文件检查。
10. 增加服务间认证、审计日志和 Trace ID。
11. 增加任务超时、取消、失败重试和临时文件清理机制。
12. 禁止接口接受任意本地路径或数据库密码。
