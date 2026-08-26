# RAG 产品知识库索引

本模块负责将产品说明书 PDF 转换为可追溯的标准化文档和结构化 chunks。目前已经完成：

*   产品 PDF 解析与标准化
*   结构化分块
*   Embedding 生成
*   Qdrant 向量索引
*   Elasticsearch 文本索引

当前不包含 RRF、rerank、在线 RAG 或产品推荐。

## 当前处理链路

```
产品说明书 PDF
→ 文件名元数据解析与 SHA-256 登记
→ Docling 结构化解析
→ PyMuPDF 辅助检查
→ ParsedDocument
→ Heading / Table / Text Normalization
→ Validation
→ parsed.json
→ Structure-aware Chunking
→ chunks.json
→ text-embedding-v4（1024 维）
→ embeddings.jsonl / embedding_manifest.json
→ Qdrant（1024 维 / COSINE）
→ qdrant_manifest.json
→ Elasticsearch（cjk analyzer）
→ elasticsearch_manifest.json
```

产品 PDF 解析与标准化和结构化分块共用 `document_id`、页码、`section_path` 和来源 block ID，便于后续从 Chunk 回溯到原始 PDF 页面。

## 目录结构

```
rag-product-indexing/
├── data/raw/                         # 原始产品 PDF，不进入 Git
├── output/
│   ├── document_parse/               # 产品 PDF 解析与标准化输出
│   └── document_chunks/              # 结构化分块输出
│   └── document_embeddings/          # Embedding 生成输出
│   └── document_indexes/             # Qdrant/Elasticsearch 索引摘要
├── scripts/
│   ├── parse_product_document.py     # 产品 PDF 解析与标准化 CLI
│   └── chunk_product_document.py     # 结构化分块 CLI
│   └── embed_product_document.py     # Embedding 生成 CLI
│   └── index_product_qdrant.py        # Qdrant 向量索引 CLI
│   └── index_product_elasticsearch.py # Elasticsearch 文本索引 CLI
├── src/
│   ├── models/                       # ParsedDocument、DocumentBlock、DocumentChunk
│   ├── parsing/                      # 文件名解析、PDF Parser、解析 pipeline
│   ├── normalization/                # 标题、文本、表格标准化
│   ├── validation/                   # 产品 PDF 解析质量校验
│   └── chunking/                     # 结构化分块与 token 统计
│   └── embedding/                    # API 调用、重试、resume 与一致性校验
│   └── indexing/                     # Qdrant/Elasticsearch 预检、写入与一致性校验
└── tests/
```

## 环境与依赖

安装项目实际使用的依赖：

```
python -m pip install -r requirements.txt
```

主要依赖：

*   `docling`：产品 PDF 解析的主 Parser
*   `pymupdf`：页数、文本层和简单 fallback 辅助
*   `transformers`：加载本地 Qwen tokenizer，仅用于 token counting
*   `langchain-text-splitters`：超长原子内容的保守二次切分
*   `openai`：调用百炼 OpenAI-compatible Embedding API
*   `python-dotenv`：从本地 `.env` 加载 API 配置
*   `qdrant-client`：通过 REST 创建/校验 collection 并写入向量 points
*   `elasticsearch`：使用 API Key 写入 BM25 文本索引，并跳过 TLS 证书与主机名校验
*   `pytest`：离线测试

Windows 版 `docling-parse` 的原生后端可能无法从含中文字符的 `site-packages` 路径加载资源。仓库路径包含中文时，建议使用纯 ASCII 路径的虚拟环境，例如：

```
C:\codex-temp\rag-product-indexing-venv
```

## 产品 PDF 解析与标准化

### 输入

原始产品 PDF 默认位于：

```
data/raw/*.pdf
```

文件名支持产品代码与销售代码相同或不同的当前两种格式，并保留原始文件名、产品名称、产品代码、销售代码与文件 SHA-256。

### 使用方式

解析单份 PDF：

```
python scripts/parse_product_document.py data/raw/D000001_23GS2000_23G2000B_天天鑫添益私银低波红利固收增强_产品说明书.pdf
```

解析目录：

```
python scripts/parse_product_document.py data
```

指定输出根目录：

```
python scripts/parse_product_document.py data/raw/example.pdf --output-root output/document_parse
```

### 输出

```
output/document_parse/<document_id>/
├── parsed.json
├── parsed.md
├── parse_manifest.json
└── parse_issues.json
```

*   `parsed.json`：结构化分块的机器输入，包含 metadata 与按阅读顺序排列的 blocks。
*   `parsed.md`：便于人工检查标题、正文和表格。
*   `parse_manifest.json`：页数、block 数量、parser、fallback 和状态摘要。
*   `parse_issues.json`：实际发现的 ERROR、WARNING 和 INFO。

所有对外页码均为 1-based PDF 物理页码。正文 block 保留：

```
document_id
block_id
type
text
page_start / page_end
section_path
rows / cells / kv_pairs（表格存在时）
provenance
```

### 解析策略

Docling 是主 Parser，用于尽可能保留阅读顺序、heading、paragraph、list、table、页码和版面来源信息。

PyMuPDF 用于：

*   获取 PDF 页数；
*   检查页面是否有正常文本层；
*   获取简单页面文本；
*   Docling 某页完全没有有效内容时提供保底文本。

仅使用 PyMuPDF 做页数或文本层检查不计为 fallback；只有实际使用 PyMuPDF 内容替代 Docling 失败结果时，manifest 才设置 `fallback_used=true`，并记录 `FALLBACK_PARSER_USED`。

默认不启用独立 OCR。疑似扫描页或几乎没有有效文本的页面记录 `OCR_REQUIRED`，不会静默输出空页面。

### 状态规则

```
存在 ERROR             → FAILED
无 ERROR、有 WARNING   → SUCCESS_WITH_WARNINGS
无 ERROR/WARNING       → SUCCESS
INFO                   → 不影响成功状态
```

## 结构化分块

结构化分块读取产品 PDF 解析与标准化生成的 `parsed.json`，并生成供后续 Elasticsearch 与 Qdrant 共用的一套稳定 chunks。此过程不生成 embedding vector。

### 使用方式

输入单个 `parsed.json`：

```
python scripts/chunk_product_document.py output/document_parse/D000003/parsed.json
```

输入单个文档目录：

```
python scripts/chunk_product_document.py output/document_parse/D000003
```

输入 `document_parse` 根目录：

```
python scripts/chunk_product_document.py output/document_parse
```

指定 tokenizer 或输出目录：

```
python scripts/chunk_product_document.py output/document_parse/D000003 \
  --tokenizer-path D:\codex-temp\models\qwen3-embedding-0.6b-tokenizer \
  --output-root output/document_chunks
```

### 输出

```
output/document_chunks/<document_id>/chunks.json
```

每个 chunk 至少包含：

```
{
  "chunk_id": "D000003_C0001",
  "document_id": "D000003",
  "chunk_type": "text",
  "content": "原始证据文本",
  "embedding_text": "产品、章节和内容组成的嵌入输入文本",
  "section_path": ["理财产品说明书", "九、风险揭示"],
  "page_start": 16,
  "page_end": 16,
  "source_block_ids": ["D000003_B0252", "D000003_B0253"],
  "token_count": 88
}
```

`chunk_id` 按最终阅读顺序稳定生成：

```
D000003_C0001
D000003_C0002
...
```

### Text Chunking

*   按解析结果的 reading order 处理 `heading`、`paragraph` 和 `list`。
*   只聚合连续且 `section_path` 相同的 blocks。
*   不跨不同 section 合并短内容。
*   heading 与相同路径的正文优先进入同一 chunk。
*   table 出现时先结束当前 text chunk，table 不与普通正文混合。
*   `TARGET_TOKENS=400` 是软目标，完整短 section 可以直接保留。
*   `MAX_TOKENS=500` 是最终 `embedding_text` 的硬上限。

同一 section 超过上限时，优先按已有 block 边界拆分。单个 block 仍然超长时，使用以下顺序递归切分：

```
段落空行 → 换行 → 句号 → 分号 → 逗号 → 字符
```

切分不跨 `section_path`，不静默截断内容。

### Table Chunking

*   table 始终生成独立的 `table` chunk。
*   优先将 `kv_pairs` 作为不可拆分的最小语义单元。
*   没有有效 `kv_pairs` 时使用 `rows`。
*   多个 key-value/row 累积到接近目标 token 数。
*   单个 key-value/row 超过上限时进行保守 fallback 切分，并在 `diagnostics` 中记录原因。
*   结构化分块不猜测或重建解析过程中已丢失的跨页 cell 关系。

### content 与 embedding\_text

`content` 尽可能保留原始证据，用于后续 BM25、rerank、LLM context 和证据展示。

`embedding_text` 为后续 embedding 准备，格式为：

```
产品：<product_name>
章节：<section_path>
内容：<content>
```

产品名称优先使用产品 PDF 解析结果中的 `product_name_from_filename`，缺失时依次使用产品代码、销售代码；不会编造名称。

token 数针对最终 `embedding_text` 计算，而不是只计算 `content`。

## Embedding 生成

Embedding 生成只读取 chunks 中的 `embedding_text`，调用阿里云百炼 OpenAI-compatible API，并使用 `text-embedding-v4` 生成 1024 维向量。API Key 不写入代码或日志。

复制 `.env.example` 为 `.env`，配置：

```
DASHSCOPE_API_KEY=
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_MODEL=text-embedding-v4
EMBEDDING_DIMENSIONS=1024
```

运行单份文档：

```
python scripts/embed_product_document.py --document-id D000001
```

输入与输出：

```
output/document_chunks/<document_id>/chunks.json
→ output/document_embeddings/<document_id>/
   ├── embeddings.jsonl
   └── embedding_manifest.json
```

每个 JSONL 记录只保存：

```
{"chunk_id": "D000001_C0001", "vector": [0.0]}
```

实际 vector 固定为 1024 维。每批最多 10 条，并使用 API 返回的 `item.index` 映射原 chunk。每批校验返回数量、索引覆盖、维度及 NaN/Infinity；网络、timeout、429 和 5xx 最多尝试 3 次，401/403 和明显参数错误直接失败。

CLI 会在每个成功 batch 后原子写入检查点。只有 model、dimensions 和 `chunks.json` SHA-256 全部一致时才复用已有合法向量；hash 不一致时从空结果重建，避免新旧向量混合。最终仅在 chunk ID 集合完全一致、无重复、无缺失、无额外和无非法向量时标记 `SUCCESS`。

## Qdrant 向量索引

Qdrant 向量索引按 `chunk_id` 对齐 `chunks.json`、`embeddings.jsonl` 和 `embedding_manifest.json`。本地预检会校验 manifest 状态、1024 维向量、SHA-256、ID 集合、重复 ID 及 NaN/Infinity；任一条件失败时不会写入 Qdrant。

在 `.env` 中配置：

```
QDRANT_URL=
QDRANT_API_KEY=
QDRANT_COLLECTION=private-bank-product-chunks-v1
```

运行单份文档：

```
python scripts/index_product_qdrant.py --document-id D000001
```

collection 不存在时会创建单向量配置 `size=1024`、`distance=COSINE`，并为 payload 的 `document_id` 创建 keyword index。collection 已存在时必须与该配置一致，不一致时直接失败，不会删除或重建 collection。

每个 `chunk_id` 映射为稳定的 deterministic UUID，原始 `chunk_id` 与正文、章节路径、页码和来源 block 保存在 payload 中，不重复保存 `embedding_text`。重新索引采用 document replace：本地校验通过后，先按 `document_id` 删除旧 points，再按每批 64 条 upsert，最后通过精确 count、point ID 集合和 payload 对照确认结果。

输出摘要：

```
output/document_indexes/<document_id>/qdrant_manifest.json
```

## Elasticsearch 文本索引

Elasticsearch 文本索引读取 `chunks.json` 的 `content` 与来源 metadata，每个 chunk 使用原始 `chunk_id` 作为 Elasticsearch `_id`。`section_path` 同时保留 keyword 数组，并以 `>` 连接为 `section_text`，用于后续 BM25 检索；不写入 embedding vector 或 `embedding_text`。

在 `.env` 中配置：

```
ELASTICSEARCH_URL=
ELASTICSEARCH_API_KEY=
ELASTICSEARCH_INDEX=private-bank-product-chunks-v1
```

索引名必须以 `private-bank-` 开头。连接使用 API Key，并跳过 TLS 证书与主机名校验。运行单份文档：

```
python scripts/index_product_elasticsearch.py --document-id D000001
```

索引不存在时创建显式 mapping：`content` 和 `section_text` 为使用内置 `cjk` analyzer 的 text 字段；文档、chunk 类型、章节路径与来源 block ID 为 keyword；页码和 token 数为 integer。索引已存在时会逐项校验关键 mapping，不兼容时直接失败，不删除或重建整个索引。

重新索引采用 document replace：本地数据完整性校验通过后，先通过 `document_id` 删除旧 documents，再每批 100 条 bulk 写入并 refresh。最后精确校验 document count、ID 集合和全部索引字段，输出：

```
output/document_indexes/<document_id>/elasticsearch_manifest.json
```

## 测试

测试完全离线，不连接 Elasticsearch、Qdrant 或其他外部服务：

```
python -m pytest -q
```

结构化分块测试覆盖：

*   相同 section 连续 block 聚合；
*   不同 section 不合并；
*   heading 与正文组合；
*   短 section 保持独立；
*   超长文本二次切分及 500-token 硬上限；
*   本地 tokenizer 与 `local_files_only=True`；
*   table 的 `kv_pairs` 与 rows fallback；
*   超长表格原子单元诊断；
*   provenance、稳定 chunk ID 和 source block 完整覆盖。
*   Embedding batch/index 映射、向量校验、重试、resume、hash 失效和最终 coverage。
*   Qdrant collection 创建/配置校验、稳定 UUID、payload、document replace、远端计数和一致性结果。
*   Elasticsearch 显式 mapping、chunk 预检、document replace、bulk `_id`、远端计数及字段一致性结果。

## 已知限制

*   Docling 的阅读顺序和 PDF 原始结构不一定能在所有版式中完全恢复；结构化分块只消费产品 PDF 解析与标准化提供的结构。
*   跨页表格的二维关系若已在解析过程中丢失，结构化分块不会猜测缺失 cell 或重建 continuation。
*   table 与 text 严格分离，因此“heading 后直接跟 table”的结构可能产生较短的 heading-only text chunk。
*   产品 PDF 解析结果中未知层级 heading 与后文 `section_path` 不一致时，结构化分块不会越权修复标题结构。
*   第一版不实现固定 token overlap，优先保持 block、section 和证据来源完整。

## 后续阶段

后续可以在检索阶段组合 Qdrant dense 与 Elasticsearch sparse 结果。当前模块尚未实现：

```
RRF / rerank
query embedding
在线 RAG 问答
```
