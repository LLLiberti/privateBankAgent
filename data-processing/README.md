# 私银智能体数据处理模块

## 1. 模块简介

本目录是 `privateBankAgent` 项目的独立数据处理模块，负责将企业主型私人银行客户的四维 Excel 数据转换为可追溯、可校验的 MySQL 结构化数据。

四维数据包括：

- 个人维度（PERSON）
- 企业维度（ENTERPRISE）
- 家庭维度（FAMILY）
- 社会维度（SOCIAL）

本模块覆盖以下流程：

```text
四维 Excel 原始数据
  → 文件与表头校验
  → CSV/JSON 暂存文件
  → MySQL staging 层
  → 人物及企业基础实体
  → 个人/企业/家庭/社会扩展信息
  → 财务指标与关系增强
  → 数据质量问题记录
  → 批次完整性审计
  → 人工确认后完成批次
```

该模块作为独立的 Python ETL 工具运行，不修改项目现有的 Java/Spring Boot 业务代码。

---

## 2. 设计目标

### 2.1 可追溯

原始 Excel 中每个非空单元格都会生成一条 `source_document` 记录，保留：

- 文件名
- Sheet 名称
- 原始行号
- 原始列名
- 单元格坐标
- 原始文本
- 来源定位信息
- 来源等级

结构化事实通过 `source_id` 与原始证据关联。

### 2.2 可校验

系统会记录：

- 输入文件缺失
- 表头缺失或重复
- Excel 行列数量异常
- 暂存数据数量异常
- 字段无法结构化解析
- 人企关系待确认
- 财务指标提取失败
- 缺失或失效的证据引用

相关问题统一写入 `data_quality_issue` 表。

### 2.3 安全边界

- 原始 Excel 只读，不对源文件做修改。
- 数据库写入使用事务；发生异常时自动回滚。
- 数据库密码不写入代码或配置文件。
- 默认只提交脱敏示例，不提交真实客户数据。
- 解析结果默认标记为 `UNVERIFIED` 或 `PENDING_CONFIRMATION`，不能替代人工审核。
- 公开声誉和风险信息只能作为待核验线索，不能直接作为业务或合规结论。

---

## 3. 目录结构

```text
data-processing/
├── README.md
├── requirements.txt
├── .gitignore
│
├── sql/
│   └── 001_create_data_model.sql
│
├── scripts/
│   ├── import_excel_to_staging.py
│   ├── load_staging_to_mysql.py
│   ├── parse_pilot_to_mysql.py
│   ├── parse_remaining_to_mysql.py
│   ├── parse_personal_extensions.py
│   ├── parse_enterprise_extensions.py
│   ├── parse_family_extensions.py
│   ├── parse_social_extensions.py
│   ├── enhance_financial_metrics.py
│   ├── enhance_person_enterprise_relations_v2.py
│   ├── enhance_personal_financial_events.py
│   ├── audit_and_finalize_batch.py
│   ├── diagnose_enterprise_staging.py
│   ├── diagnose_personal_field_parse.py
│   └── verify_family_parser_coverage.py
│
└── examples/
    └── desensitized-enterprise-owner.json
```

运行脚本时建议将当前目录切换到 `data-processing`，因为诊断脚本默认从相对路径 `outputs/staging_import_v1/` 读取文件。

---

## 4. 环境要求

### 4.1 软件要求

- Python 3.10 或更高版本
- MySQL 8.0
- `utf8mb4` 字符集
- 可选：MySQL 命令行客户端

### 4.2 Python 依赖

```text
openpyxl>=3.1,<4
PyMySQL>=1.1,<2
```

### 4.3 创建虚拟环境

在仓库根目录执行：

```powershell
cd .\data-processing

python -m venv .venv
.\.venv\Scripts\Activate.ps1

python -m pip install --upgrade pip
pip install -r requirements.txt
```

检查脚本语法：

```powershell
python -m compileall .\scripts
```

---

## 5. 数据库配置

数据库连接脚本支持以下环境变量：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` | `127.0.0.1` | MySQL 主机地址 |
| `DB_PORT` | `3306` | MySQL 端口 |
| `DB_NAME` | `private_bank_agent` | 数据库名称 |
| `DB_USER` | `root` | 数据库用户 |
| `DB_PASSWORD` | 无 | 数据库密码 |

PowerShell 示例：

```powershell
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_NAME = "private_bank_agent"
$env:DB_USER = "root"
```

出于安全考虑，不建议将密码写入脚本、README、`.env` 或命令历史。

如果没有设置 `DB_PASSWORD`，脚本会在运行时安全提示输入密码。

也可以通过 `--password-env` 指定其他密码环境变量名称：

```powershell
python .\scripts\audit_and_finalize_batch.py `
  --batch-id 4 `
  --password-env MY_PRIVATE_BANK_DB_PASSWORD
```

---

## 6. 初始化数据库

先创建数据库，并确保使用 `utf8mb4`：

```sql
CREATE DATABASE IF NOT EXISTS private_bank_agent
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

进入 MySQL 客户端后执行数据模型：

```sql
USE private_bank_agent;
SOURCE sql/001_create_data_model.sql;
```

`001_create_data_model.sql` 只负责创建表，不导入 Excel 数据。

---

## 7. 数据模型

### 7.1 导入与证据层

| 表名 | 用途 |
| --- | --- |
| `import_batch` | 管理导入批次、状态和记录数量 |
| `source_document` | 保存原始单元格级证据 |
| `stg_import_row` | 保存 Excel 原始行及 `raw_cells` JSON |
| `data_quality_issue` | 保存数据缺失、解析失败和待确认问题 |

### 7.2 人物与企业主体

| 表名 | 用途 |
| --- | --- |
| `person` | 企业主基础实体 |
| `enterprise` | 企业基础实体 |
| `person_profile` | 人物基本信息 |
| `person_enterprise_relation` | 人物与企业的关系、职务及持股 |
| `person_career` | 人物职业经历 |

### 7.3 个人金融信息

| 表名 | 用途 |
| --- | --- |
| `financial_fact` | 资产、负债等金融事实 |
| `product_holding` | 产品持仓及到期信息 |
| `risk_preference` | 风险等级、投资期限及流动性要求 |
| `financial_event` | 交易或大额资金变动 |
| `service_record` | 历史服务记录 |
| `customer_interaction_note` | 客户明确表达及互动信息 |

### 7.4 企业扩展信息

| 表名 | 用途 |
| --- | --- |
| `enterprise_business` | 企业主营业务 |
| `enterprise_financial_metric` | 企业营收、利润、资产等指标 |
| `enterprise_event` | 企业融资、诉讼、处罚等事件 |
| `enterprise_market_relation` | 客户、供应商、投资等市场关系 |

### 7.5 家庭信息

| 表名 | 用途 |
| --- | --- |
| `family_member` | 家庭成员或受保护别名 |
| `person_family_relation` | 企业主与家庭成员关系 |
| `succession_arrangement` | 家族传承及接班安排 |

### 7.6 社会信息

| 表名 | 用途 |
| --- | --- |
| `social_organization` | 社会组织 |
| `person_social_relation` | 企业主与社会组织关系 |
| `social_activity` | 公益、行业及其他社会活动 |
| `public_reputation` | 公开荣誉及声誉信息 |
| `reputation_risk` | 待核验的声誉风险线索 |

---

## 8. 输入数据规范

输入目录必须包含以下四个工作簿，文件名需要完全一致：

```text
30位企业家个人全维度数据采集表.xlsx
30位企业家家庭维度数据采集表.xlsx
30位企业家企业全维度数据采集表.xlsx
30位企业家社会维度数据采集表.xlsx
```

每个工作簿当前读取第一个活动 Sheet，并要求包含以下关联列：

- `序号`，兼容别名 `、`
- `企业家`
- `核心关联企业`

其他要求：

- 表头不能为空；
- 表头不能重复；
- 数据行列数必须与表头一致；
- 每条记录必须包含企业家和核心关联企业；
- 空行会被忽略；
- 原始工作簿不会被修改；
- 当前受控样例预期每个维度 30 行，共 120 条 staging 记录。

---

## 9. 完整运行流程

以下命令均假设当前目录为：

```powershell
cd .\data-processing
```

### 步骤一：Excel 转换为暂存文件

准备输入、输出目录：

```powershell
New-Item -ItemType Directory -Force .\inputs
New-Item -ItemType Directory -Force .\outputs\staging_import_v1
```

将四个源 Excel 文件放入 `inputs`，然后执行：

```powershell
python .\scripts\import_excel_to_staging.py `
  --input-dir .\inputs `
  --output-dir .\outputs\staging_import_v1
```

脚本输出：

```text
outputs/staging_import_v1/
├── stg_import_row.csv
├── source_documents.csv
├── import_manifest.json
└── load_staging.sql
```

各文件用途：

| 文件 | 用途 |
| --- | --- |
| `stg_import_row.csv` | 四维行级暂存数据 |
| `source_documents.csv` | 单元格级证据数据 |
| `import_manifest.json` | 文件哈希、表头和数量清单 |
| `load_staging.sql` | MySQL `LOAD DATA LOCAL INFILE` 导入脚本 |

`import_manifest.json` 应重点核对：

- 四个工作簿是否齐全；
- 文件 SHA-256 是否已记录；
- 每个维度是否为 30 行；
- 总 staging 行数是否为 120；
- 总证据单元格数是否符合预期。

### 步骤二：运行只读诊断

以下脚本不会连接数据库：

```powershell
python .\scripts\diagnose_enterprise_staging.py
python .\scripts\diagnose_personal_field_parse.py
python .\scripts\verify_family_parser_coverage.py
```

说明：

- `diagnose_enterprise_staging.py`：输出企业字段清单及样例。
- `diagnose_personal_field_parse.py`：列出无法解析的个人扩展字段。
- `verify_family_parser_coverage.py`：统计家庭成员解析覆盖率。

这些脚本固定读取：

```text
outputs/staging_import_v1/stg_import_row.csv
```

### 步骤三：导入 staging 数据

#### 方式 A：首次导入

首次导入、数据库尚不存在旧批次时，可使用生成的：

```text
outputs/staging_import_v1/load_staging.sql
```

执行前需要检查并修改其中的：

- `batch_name`
- `operator_name`
- 数据来源描述

该方式依赖 MySQL 启用 `LOCAL INFILE`。

#### 方式 B：替换已有批次

推荐使用参数化 Python 加载器：

```powershell
python .\scripts\load_staging_to_mysql.py `
  --input-dir .\outputs\staging_import_v1 `
  --cleanup-batch-id 旧批次ID `
  --batch-name entrepreneur-four-dimension-v2
```

成功后会输出：

```json
{
  "import_batch_id": 5,
  "staging_rows": 120,
  "evidence_rows": 1200
}
```

记录返回的 `import_batch_id`，后续所有解析脚本都需要显式传入该值。

> **重要警告：** `--cleanup-batch-id` 是破坏性参数。脚本会在同一事务中删除指定旧批次的质量问题、证据、暂存行和批次记录，然后写入新批次。只能传入经过确认允许替换的批次 ID。

如果旧批次的证据已经被结构化事实表引用，外键约束可能阻止删除并触发整体回滚。不要通过关闭外键检查绕过该保护。

加载器当前对受控样例进行严格数量校验：

```text
staging 记录：120
证据记录：1200
```

如果数据集规模发生变化，需要先评估并调整代码中的：

```python
EXPECTED_STAGING_ROWS
EXPECTED_EVIDENCE_ROWS
```

### 步骤四：查询批次 ID

```sql
SELECT
  import_batch_id,
  batch_name,
  import_status,
  record_count,
  imported_at
FROM import_batch
ORDER BY import_batch_id DESC;
```

PowerShell 中记录批次 ID：

```powershell
$batchId = 5
```

不要依赖脚本当前的默认值 `4`，应始终显式传入 `--batch-id`。

### 步骤五：解析三人试点数据

```powershell
python .\scripts\parse_pilot_to_mysql.py `
  --batch-id $batchId
```

当前试点人物固定为：

- 马化腾
- 马云
- 雷军

脚本会写入：

- `person`
- `enterprise`
- `person_profile`
- `risk_preference`
- `person_enterprise_relation`
- `enterprise_financial_metric`

如果以上试点人物已经存在，脚本会停止，防止重复写入。

### 步骤六：解析其余 27 人

```powershell
python .\scripts\parse_remaining_to_mysql.py `
  --batch-id $batchId
```

该脚本要求待解析人物数严格等于 27。数量不符时会停止，避免重复或不完整写入。

无法识别财务指标或核心关系时，会生成 `data_quality_issue`。

### 步骤七：解析四维扩展信息

建议按以下顺序执行：

```powershell
python .\scripts\parse_personal_extensions.py `
  --batch-id $batchId

python .\scripts\parse_enterprise_extensions.py `
  --batch-id $batchId

python .\scripts\parse_family_extensions.py `
  --batch-id $batchId

python .\scripts\parse_social_extensions.py `
  --batch-id $batchId
```

对应处理内容：

| 脚本 | 处理范围 |
| --- | --- |
| `parse_personal_extensions.py` | 职业、资产负债、持仓、资金事件、服务记录 |
| `parse_enterprise_extensions.py` | 主营业务、市场关系及企业事件 |
| `parse_family_extensions.py` | 家庭成员、家庭关系和传承安排 |
| `parse_social_extensions.py` | 社会组织、活动、公开声誉及风险线索 |

### 步骤八：增强解析

```powershell
python .\scripts\enhance_financial_metrics.py `
  --batch-id $batchId

python .\scripts\enhance_person_enterprise_relations_v2.py `
  --batch-id $batchId

python .\scripts\enhance_personal_financial_events.py `
  --batch-id $batchId
```

增强脚本主要用于重新处理开放的数据质量问题：

- 补充企业财务指标；
- 补充股权、表决权及核心人企关系；
- 补充个人金融事件；
- 对已成功处理的问题更新状态。

### 步骤九：检查数据质量问题

```sql
SELECT
  data_quality_issue_id,
  issue_type,
  severity,
  issue_status,
  issue_message
FROM data_quality_issue
WHERE import_batch_id = 5
ORDER BY
  FIELD(severity, 'HIGH', 'MEDIUM', 'LOW'),
  data_quality_issue_id;
```

批次完成前，所有问题都必须：

- 被自动增强脚本成功处理；或
- 经人工核验后更新为 `RESOLVED`；或
- 明确记录处理人、时间和处理说明。

### 步骤十：只读审计

先执行不带 `--finalize` 的审计：

```powershell
python .\scripts\audit_and_finalize_batch.py `
  --batch-id $batchId
```

审计通过条件包括：

- 批次记录数为 120；
- 四个维度分别为 30 行；
- 证据记录数为 1200；
- 未解决质量问题为 0；
- 人物、企业、档案、风险偏好、职业经历达到预期数量；
- 所有结构化事实的 `source_id` 均能找到有效证据。

成功结果应包含：

```json
{
  "passed": true,
  "open_quality_issues": 0
}
```

### 步骤十一：完成批次

只有审计结果 `passed` 为 `true` 时才能执行：

```powershell
python .\scripts\audit_and_finalize_batch.py `
  --batch-id $batchId `
  --finalize
```

完成后：

- `stg_import_row.parse_status` 更新为 `PARSED`；
- `import_batch.import_status` 更新为 `COMPLETED`；
- 批次备注中记录最终验收结果。

如果审计不通过，脚本会回滚，不会更新完成状态。

---

## 10. 通用命令行参数

除 Excel 转换和诊断脚本外，多数数据库脚本支持：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--batch-id` | `4` | 导入批次 ID，建议始终显式指定 |
| `--host` | `DB_HOST` 或 `127.0.0.1` | 数据库地址 |
| `--port` | `DB_PORT` 或 `3306` | 数据库端口 |
| `--database` | `DB_NAME` 或 `private_bank_agent` | 数据库名称 |
| `--user` | `DB_USER` 或 `root` | 数据库用户名 |
| `--password-env` | `DB_PASSWORD` | 保存数据库密码的环境变量名称 |

查看具体脚本帮助：

```powershell
python .\scripts\脚本名.py --help
```

---

## 11. 事务及重复执行行为

数据库写入脚本统一使用：

```python
autocommit=False
```

行为如下：

- 全部操作成功后执行 `commit`；
- 任一操作失败时执行 `rollback`；
- 异常会继续向上抛出，便于命令行和 CI 判断失败；
- 试点解析脚本检测到已有试点人物时停止；
- 剩余人物解析脚本要求待解析人物严格为 27；
- 部分扩展表没有全局幂等约束，重复执行前应检查已有数据；
- 不建议在生产数据库中直接反复运行解析脚本。

建议每次导入使用独立测试数据库验证，再按审批流程执行正式导入。

---

## 12. 常见问题

### 12.1 提示缺少源工作簿

检查文件名是否与要求完全一致，包括中文、空格和扩展名。

### 12.2 提示缺少关联列

确保工作簿包含：

```text
序号（或“、”）
企业家
核心关联企业
```

### 12.3 暂存或证据数量异常

当前加载器只接受：

```text
staging_rows = 120
evidence_rows = 1200
```

首先检查：

- 是否存在缺失或多余数据行；
- 是否有空行；
- 是否修改了表头；
- 是否增加或减少了非空证据单元格；
- 是否使用了不同版本的数据集。

### 12.4 提示试点人物已存在

`parse_pilot_to_mysql.py` 会阻止重复写入。不要直接删除生产数据，应确认：

- 是否已经成功运行过；
- 是否使用了错误数据库；
- 是否使用了错误批次；
- 是否应该创建新的测试数据库。

### 12.5 待解析人物不是 27

说明以下情况之一发生：

- 三人试点未完整执行；
- 部分人物已经被提前写入；
- staging 数据不是预期的 30 人；
- 当前数据库包含其他测试数据。

### 12.6 批次审计不通过

重点检查输出中的：

- `dimension_rows`
- `source_documents`
- `open_quality_issues`
- `mandatory_table_counts`
- `broken_source_references`

不要在 `passed=false` 时绕过检查强制更新批次状态。

### 12.7 中文乱码

确认：

- 数据库字符集为 `utf8mb4`；
- Python 文件和 CSV 使用 UTF-8；
- MySQL 连接使用 `charset="utf8mb4"`；
- 命令行环境支持 UTF-8 输出。

---

## 13. 数据安全要求

以下内容禁止提交到 Git：

- 原始客户 Excel；
- 中间 CSV；
- 数据库备份；
- SQL 数据转储；
- 真实姓名、证件号、账户和交易数据；
- 数据库密码；
- API Token；
- SSH 私钥；
- `.env` 文件；
- 生产数据库连接信息。

当前 `.gitignore` 已排除：

```text
__pycache__/
*.py[cod]
.venv/
.env
.env.*
outputs/
backups/
*.xlsx
*.xls
*.csv
```

提交前建议执行：

```powershell
git status --short

rg -n -i `
  "password|passwd|secret|token|api.key|private.key" `
  .
```

脱敏示例数据仍需人工复核，确保不存在可反向识别真实客户的信息。

---

## 14. 当前实现边界

当前版本是针对“30 位企业家、四维数据采集表”的受控数据集实现，存在以下数据集特定约束：

- 输入文件名固定；
- 每个维度固定 30 行；
- staging 总数固定为 120；
- 证据数量固定为 1200；
- 三人试点名单固定；
- 剩余人物数量固定为 27；
- 部分字段依赖中文正则表达式；
- 财务报告期部分固定为 `2025`；
- 诊断脚本使用固定相对输出路径。

如果需要支持其他批次、其他人数、动态报告期或不同表头，应先将这些常量配置化，并补充自动化测试。

---

## 15. 与主项目的集成边界

本目录是独立数据处理模块：

- Java/Spring Boot 应用负责接口、业务流程、权限及服务编排；
- 本模块负责受控数据导入、结构化解析、质量检查和来源追溯；
- 两部分通过 MySQL 数据模型进行衔接；
- 修改表结构前，应同步评估 Java Entity、Mapper 和 Service；
- 正式环境执行数据导入前，应由数据库负责人和业务负责人共同审核。

---

## 16. 推荐验收清单

提交或交付前确认：

- [ ] 四个源文件名称正确
- [ ] 输入数据已脱敏
- [ ] `import_manifest.json` 已检查
- [ ] staging 行数符合预期
- [ ] 证据数量符合预期
- [ ] 三人试点解析成功
- [ ] 剩余 27 人解析成功
- [ ] 四维扩展解析完成
- [ ] 增强脚本执行完成
- [ ] 所有质量问题已处理
- [ ] 所有事实具有有效 `source_id`
- [ ] 批次审计结果为 `passed=true`
- [ ] 批次状态已更新为 `COMPLETED`
- [ ] 未提交原始数据、输出文件或凭据
- [ ] 未修改无关的 Java/Spring Boot 代码
