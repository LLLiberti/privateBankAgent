# 产品知识库索引数据契约

## 1\. Purpose / Scope

本文档描述 `rag-product-indexing` 当前实际产出的数据，以及 Elasticsearch 与 Qdrant 的索引契约。

本模块当前将产品 PDF 标准化为统一 chunk，并分别写入：

*   Elasticsearch：中文全文字段与来源 metadata，用于后续 BM25 / keyword retrieval；
*   Qdrant：1024 维向量与来源 payload，用于后续 dense retrieval。

当前数据层不负责 RRF、rerank、在线问答或产品推荐。

## 2\. Data Relationship

```
PDF
→ ParsedDocument.blocks
→ output/document_chunks/<document_id>/chunks.json
   ├→ Elasticsearch document（content + metadata）
   └→ embedding_text → embeddings.jsonl → Qdrant point（vector + payload）
```

同一个逻辑 chunk 在本地、Elasticsearch 和 Qdrant 中统一使用 `chunk_id` 关联：

*   Elasticsearch `_id` 等于 `chunk_id`；
*   Qdrant point ID 是由 `chunk_id` 确定性生成的 UUID，原始 `chunk_id` 保留在 payload 中；
*   跨系统合并结果时，以 Elasticsearch `_source.chunk_id` 和 Qdrant `payload.chunk_id` 做 join。

## 3\. Identifier Rules

| 标识 | 当前规则 | 示例 | 用途 |
| --- | --- | --- | --- |
| `document_id` | 文件名元数据建立的文档标识 | `D000001` | 文档级过滤、replace 和统计 |
| `block_id` | 标准化阅读顺序确定后生成：`<document_id>_B<4位序号>` | `D000001_B0154` | 从 chunk 回溯解析后的 source block |
| `chunk_id` | 最终 chunk 阅读顺序确定后生成：`<document_id>_C<4位序号>` | `D000001_C0062` | 逻辑 chunk 主键及跨系统 join key |
| Elasticsearch `_id` | 直接使用 `chunk_id` | `D000001_C0062` | ES document 主键 |
| Qdrant point ID | UUIDv5：`uuid.uuid5(namespace, chunk_id)` | `e6cba359-06ae-5519-ab0d-a6108487c44a` | Qdrant 技术主键 |

Qdrant UUIDv5 使用代码内固定 namespace：

```
82912bcd-f200-54db-9a39-58fe08228bd8
```

Qdrant point ID 不是业务侧 join key。后端和检索模块应使用 payload 中的 `chunk_id`。

## 4\. Chunk Schema

文件位置：`output/document_chunks/<document_id>/chunks.json`。

顶层结构包含 `document_id`、`chunks` 和 `diagnostics`。`diagnostics` 是 chunking 过程的诊断数组，不属于单条 chunk，也不会写入 Elasticsearch 或 Qdrant。D000001 当前含一条 `OVERSIZED_TABLE_UNIT_SPLIT` 诊断。

### 4.1 单条 chunk

| 字段 | 实际类型 | 必填 / 空值 | 示例 | 含义 |
| --- | --- | --- | --- | --- |
| `chunk_id` | string | 必填，非空，文档内唯一 | `D000001_C0062` | 逻辑 chunk 主键 |
| `document_id` | string | 必填，非空 | `D000001` | 来源文档标识 |
| `chunk_type` | string enum | 必填；`text` 或 `table` | `text` | chunk 内容类型 |
| `content` | string | 必填；由结构化分块生成 | `6、以摊余成本法计量……` | 证据正文；供全文检索、rerank、上下文展示等后续消费 |
| `embedding_text` | string | 必填，非空 | `产品：…\n章节：…\n内容：…` | Embedding 生成的唯一输入；不写入 ES/Qdrant payload |
| `section_path` | array\[string\] | 必填；可为空数组 | `['理财产品说明书', '四、产品估值', '（三）估值方法']` | 产品 PDF 解析与标准化提供的章节路径 |
| `page_start` | integer | 必填，`>= 1` | `11` | 来源 PDF 起始物理页 |
| `page_end` | integer | 必填，`>= page_start` | `11` | 来源 PDF 结束物理页 |
| `source_block_ids` | array\[string\] | 必填；当前由有 ID 的来源 blocks 去重生成 | `['D000001_B0154', …]` | 解析后的 source block 标识 |
| `token_count` | integer | 必填 | `302` | 本地 Qwen tokenizer 对完整 `embedding_text` 的 token 计数 |

页码统一为 1-based PDF 物理页码。`embedding_text` 由产品标识、可选章节路径和 `content` 组成；产品名称优先使用文件名元数据中的产品名称，其次才是产品代码或销售代码。

### 4.2 顶层 diagnostics

每条诊断的实际模型字段为：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `reason` | string | 诊断原因代码 |
| `message` | string | 可读说明 |
| `source_block_ids` | array\[string\] | 涉及的解析后 source blocks |

## 5\. Elasticsearch Schema

*   索引名读取环境变量 `ELASTICSEARCH_INDEX`；
*   `.env.example` 当前示例为 `private-bank-product-chunks-v1`；
*   索引名必须以 `private-bank-` 开头；
*   Elasticsearch `_id = chunk_id`。

### 5.1 Mapping

| 字段 | ES 类型 | Analyzer | 用途 |
| --- | --- | --- | --- |
| `chunk_id` | `keyword` | — | 逻辑 chunk 标识 |
| `document_id` | `keyword` | — | 文档过滤、replace、count |
| `chunk_type` | `keyword` | — | 区分 `text` / `table` |
| `content` | `text` | `cjk` | BM25 主文本字段 |
| `section_path` | `keyword` | — | 原始章节路径数组、精确过滤/展示 |
| `section_text` | `text` | `cjk` | 章节全文检索字段 |
| `page_start` | `integer` | — | 起始物理页 |
| `page_end` | `integer` | — | 结束物理页 |
| `source_block_ids` | `keyword` | — | 来源 blocks |
| `token_count` | `integer` | — | embedding 输入 token 数 |

`section_text` 不是 `chunks.json` 字段，而是在写入 ES 前由 `section_path` 通过 `" > ".join(section_path)` 生成；空路径对应空字符串。

Elasticsearch 不保存 embedding vector，也不保存 `embedding_text`。

## 6\. Qdrant Schema

*   collection 名读取环境变量 `QDRANT_COLLECTION`；
*   `.env.example` 和 D000001 manifest 当前值为 `private-bank-product-chunks-v1`；
*   单向量配置固定为 `size=1024`、`distance=COSINE`；
*   `document_id` 建立 keyword payload index；
*   point ID 为固定 namespace 下对 `chunk_id` 生成的 UUIDv5。

### 6.1 Payload

| 字段 | 实际类型 | 用途 |
| --- | --- | --- |
| `chunk_id` | string | 逻辑 chunk 标识、与 ES join |
| `document_id` | string | 文档过滤、replace、count |
| `chunk_type` | string | `text` / `table` |
| `content` | string | 原始证据文本 |
| `section_path` | array\[string\] | 章节路径 |
| `page_start` | integer | 起始物理页 |
| `page_end` | integer | 结束物理页 |
| `source_block_ids` | array\[string\] | 来源解析后 source blocks |
| `token_count` | integer | embedding 输入 token 数 |

Qdrant payload 不含 `section_text`，也不含 `embedding_text`。向量保存在 point 的 `vector` 字段，不在 payload 中重复保存。

逻辑结构：

```
{
  "id": "e6cba359-06ae-5519-ab0d-a6108487c44a",
  "vector": "<1024-dimensional vector>",
  "payload": {
    "chunk_id": "D000001_C0062",
    "document_id": "D000001",
    "chunk_type": "text",
    "content": "6、以摊余成本法计量……",
    "section_path": ["理财产品说明书", "四、产品估值", "（三）估值方法"],
    "page_start": 11,
    "page_end": 11,
    "source_block_ids": ["D000001_B0154", "D000001_B0155", "D000001_B0156", "D000001_B0157", "D000001_B0158"],
    "token_count": 302
  }
}
```

## 7\. Elasticsearch ↔ Qdrant Mapping

| 语义 | Elasticsearch | Qdrant |
| --- | --- | --- |
| 逻辑 chunk 标识 | `_id` 与 `_source.chunk_id` | `payload.chunk_id` |
| 技术主键 | `_id = chunk_id` | UUIDv5 point ID |
| 文档标识 | `document_id` | `payload.document_id` |
| 内容 | `content`（`cjk` text） | `payload.content` |
| 章节路径 | `section_path` | `payload.section_path` |
| 章节检索文本 | `section_text`（`cjk` text） | 不保存 |
| 页码 | `page_start`, `page_end` | payload 同名字段 |
| 来源 block | `source_block_ids` | payload 同名字段 |
| token 数 | `token_count` | payload 同名字段 |
| vector | 不保存 | point `vector` |
| `embedding_text` | 不保存 | 不保存 |

合并 ES 与 Qdrant 检索结果时，使用 `chunk_id` join，不使用 Qdrant point UUID。

## 8\. Update Semantics

当前两套索引均采用按 `document_id` replace：

### Qdrant

1.  在访问远端写入前校验 embedding manifest 状态、维度、`chunks.json` SHA-256、chunk/embedding ID 集合、重复 ID、向量维度和有限数值；
2.  collection 不存在则创建；存在则要求单向量 `1024/COSINE`，并校验 `document_id` keyword payload index；
3.  按 `document_id` 删除旧 points，`wait=True`；
4.  每批 64 points upsert，`wait=True`；
5.  精确 count，并 scroll 全量核对 point ID 与 payload。

### Elasticsearch

1.  在远端变更前校验 `chunks.json`、请求的 `document_id`、chunk ID 唯一性、内容、章节路径、页码和来源字段类型；
2.  index 不存在则创建显式 mapping；存在则校验关键字段类型及 analyzer；
3.  `delete_by_query` 删除当前 `document_id` 的旧 documents；
4.  每批 100 条 bulk 写入，随后 refresh；
5.  精确 count，并 scan 全量核对 `_id` 与 `_source`。

当前实现不会自动删除或重建整个 Qdrant collection / Elasticsearch index。已有配置或 mapping 不兼容时直接失败并写入失败 manifest。

## 9\. Manifest / Validation

Manifest 是本地审计与一致性摘要，不是线上检索数据。

### 9.1 Embedding manifest

路径：`output/document_embeddings/<document_id>/embedding_manifest.json`。

D000001 当前实际字段：

| 字段 | 含义 |
| --- | --- |
| `document_id` | 文档标识 |
| `model`, `dimensions` | embedding 模型与向量维度 |
| `chunk_count`, `embedding_count` | 输入 chunk 与有效向量数量 |
| `total_tokens` | API 返回并累计的 token 数 |
| `batch_count`, `failed_count` | API 批次数与最终失败数 |
| `source_chunk_hash` | 原始 `chunks.json` SHA-256 |
| `duplicate_chunk_id_count` | 重复 embedding ID 数 |
| `missing_chunk_count`, `extra_chunk_count` | ID 集合差异 |
| `invalid_dimension_count`, `invalid_value_count` | 非法向量统计 |
| `status` | 当前状态；索引前要求 `SUCCESS` |

### 9.2 Qdrant manifest

路径：`output/document_indexes/<document_id>/qdrant_manifest.json`。

当前实际字段：`document_id`、`collection`、`dimensions`、`distance`、`source_chunk_hash`、`expected_point_count`、`indexed_point_count`、`missing_count`、`extra_count`、`duplicate_point_id_count`、`payload_mismatch_count`、`status`；成功运行还记录 `collection_vector_size` 和 `collection_distance`。异常时可能包含 `error`。

### 9.3 Elasticsearch manifest

路径：`output/document_indexes/<document_id>/elasticsearch_manifest.json`。

当前实际字段：`document_id`、`index_name`、`source_chunk_hash`、`expected_document_count`、`indexed_document_count`、`missing_count`、`extra_count`、`mismatch_count`、`analyzer`、`status`。异常时可能包含 `error`。

消费侧判断一次索引是否完整时，至少确认：

*   对应 manifest 的 `status == "SUCCESS"`；
*   expected count 等于 indexed / embedding count；
*   missing、extra、duplicate、mismatch 和 failed 等问题计数为 0；
*   三份 manifest 的 `source_chunk_hash` 指向同一版本 `chunks.json`。

## 10\. D000001\_C0062 Example

以下值来自当前 D000001 真实输出，长文本已省略。

### 10.1 `chunks.json`

```
{
  "chunk_id": "D000001_C0062",
  "document_id": "D000001",
  "chunk_type": "text",
  "content": "6、以摊余成本法计量……10、暂停估值的情形：……",
  "embedding_text": "产品：天天鑫添益私银低波红利固收增强\n章节：理财产品说明书 > 四、产品估值 > （三）估值方法\n内容：6、以摊余成本法计量……",
  "section_path": ["理财产品说明书", "四、产品估值", "（三）估值方法"],
  "page_start": 11,
  "page_end": 11,
  "source_block_ids": ["D000001_B0154", "D000001_B0155", "D000001_B0156", "D000001_B0157", "D000001_B0158"],
  "token_count": 302
}
```

### 10.2 Elasticsearch document

```
{
  "_id": "D000001_C0062",
  "_source": {
    "chunk_id": "D000001_C0062",
    "document_id": "D000001",
    "chunk_type": "text",
    "content": "6、以摊余成本法计量……10、暂停估值的情形：……",
    "section_path": ["理财产品说明书", "四、产品估值", "（三）估值方法"],
    "section_text": "理财产品说明书 > 四、产品估值 > （三）估值方法",
    "page_start": 11,
    "page_end": 11,
    "source_block_ids": ["D000001_B0154", "D000001_B0155", "D000001_B0156", "D000001_B0157", "D000001_B0158"],
    "token_count": 302
  }
}
```

### 10.3 Qdrant point

```
{
  "id": "e6cba359-06ae-5519-ab0d-a6108487c44a",
  "vector": "<1024-dimensional vector>",
  "payload": {
    "chunk_id": "D000001_C0062",
    "document_id": "D000001",
    "chunk_type": "text",
    "content": "6、以摊余成本法计量……10、暂停估值的情形：……",
    "section_path": ["理财产品说明书", "四、产品估值", "（三）估值方法"],
    "page_start": 11,
    "page_end": 11,
    "source_block_ids": ["D000001_B0154", "D000001_B0155", "D000001_B0156", "D000001_B0157", "D000001_B0158"],
    "token_count": 302
  }
}
```

## 11\. Known Limitations

*   `section_path` 来源于产品 PDF 解析与标准化；结构化分块不修复解析结果中未知或错误的标题层级。
*   产品 PDF 解析过程中已丢失的跨页表格二维关系不会由结构化分块猜测或重建；已解析出的 rows/cells 文本会尽量保留。
*   `table` 与 `text` chunk 严格分离，标题后直接出现 table 时可能产生较短的 heading-only `text` chunk。
*   `content` 可能保留 PDF 排版产生的空格或断行；数据层不会用 LLM 改写业务原文。
*   `source_block_ids` 和页码用于来源追溯，不代表一个 chunk 必然只来自单页或单 block。
*   `chunk_id` 依赖当前文档最终 chunk 阅读顺序；文档重切分后编号可能变化，应通过 document replace 同步两套索引，并用新的 `source_chunk_hash` 判断版本一致性。