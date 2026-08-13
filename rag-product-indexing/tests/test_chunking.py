from __future__ import annotations

from unittest.mock import patch

from chunking import QwenTokenCounter, StructureAwareChunker
from models import DocumentBlock, DocumentMetadata, ParsedDocument


class CharacterTokenCounter:
    def count(self, text: str) -> int:
        return len(text)


def _document(blocks: list[DocumentBlock]) -> ParsedDocument:
    return ParsedDocument(
        DocumentMetadata(
            document_id="D000001",
            filename="x.pdf",
            file_hash="a" * 64,
            file_type="pdf",
            source_level="P0",
            product_code="23GS2000",
            sales_code="23G2000B",
            product_name_from_filename="测试产品",
            page_count=5,
        ),
        blocks,
    )


def _block(
    block_id: str,
    block_type: str,
    text: str,
    section_path: list[str],
    page: int = 1,
    **kwargs: object,
) -> DocumentBlock:
    return DocumentBlock(
        document_id="D000001",
        block_id=block_id,
        type=block_type,  # type: ignore[arg-type]
        text=text,
        page_start=page,
        page_end=page,
        section_path=section_path,
        **kwargs,
    )


def test_same_section_aggregates_heading_paragraph_and_list() -> None:
    path = ["理财产品说明书", "九、风险揭示", "（一）政策风险。"]
    document = _document([
        _block("B1", "heading", "（一）政策风险。", path, 2),
        _block("B2", "paragraph", "本产品可能受到宏观政策变化影响。", path, 2),
        _block("B3", "list", "（1）相关法律法规可能发生变化。", path, 3),
    ])
    result = StructureAwareChunker(CharacterTokenCounter(), 300, 400).chunk(document)

    assert len(result.chunks) == 1
    chunk = result.chunks[0]
    assert chunk.content.startswith("（一）政策风险。\n本产品")
    assert chunk.source_block_ids == ["B1", "B2", "B3"]
    assert (chunk.page_start, chunk.page_end) == (2, 3)
    assert chunk.content != chunk.embedding_text
    assert "产品：测试产品" in chunk.embedding_text
    assert "章节：理财产品说明书 > 九、风险揭示 > （一）政策风险。" in chunk.embedding_text


def test_different_and_short_sections_never_merge() -> None:
    document = _document([
        _block("B1", "heading", "一、产品概述", ["理财产品说明书", "一、产品概述"]),
        _block("B2", "paragraph", "短小但完整的产品概述。", ["理财产品说明书", "一、产品概述"]),
        _block("B3", "heading", "二、投资运作", ["理财产品说明书", "二、投资运作"]),
        _block("B4", "paragraph", "另一个短小章节。", ["理财产品说明书", "二、投资运作"]),
    ])
    result = StructureAwareChunker(CharacterTokenCounter(), 400, 500).chunk(document)

    assert len(result.chunks) == 2
    assert result.chunks[0].source_block_ids == ["B1", "B2"]
    assert result.chunks[1].source_block_ids == ["B3", "B4"]


def test_oversized_section_splits_and_keeps_provenance_under_max() -> None:
    text = "第一句风险说明。" * 80
    block = _block("B1", "paragraph", text, ["理财产品说明书", "九、风险揭示"], 4)
    result = StructureAwareChunker(CharacterTokenCounter(), 100, 130).chunk(_document([block]))

    assert len(result.chunks) > 1
    assert all(chunk.token_count <= 130 for chunk in result.chunks)
    assert all(chunk.source_block_ids == ["B1"] for chunk in result.chunks)
    assert "".join(chunk.content for chunk in result.chunks) == text
    assert result.diagnostics[0].reason == "OVERSIZED_TEXT_BLOCK_SPLIT"


def test_table_prefers_kv_pairs_without_splitting_pairs() -> None:
    block = _block(
        "T1",
        "table",
        "fallback text",
        ["理财产品说明书", "一、产品概述"],
        2,
        kv_pairs=[
            {"key": "销售对象", "value": "私人银行客户"},
            {"key": "购买起点金额", "value": "首次购买20万元，追加购买1000元"},
        ],
        rows=[
            ["销售对象", "私人银行客户"],
            ["购买起点金额", "首次购买20万元，追加购买1000元"],
        ],
    )
    result = StructureAwareChunker(CharacterTokenCounter(), 90, 110).chunk(_document([block]))

    combined = "\n".join(chunk.content for chunk in result.chunks)
    assert "销售对象：私人银行客户" in combined
    assert "购买起点金额：首次购买20万元，追加购买1000元" in combined
    assert all(chunk.chunk_type == "table" for chunk in result.chunks)
    assert all(chunk.source_block_ids == ["T1"] for chunk in result.chunks)


def test_table_supplements_uncovered_row_cells_without_duplicating_kv() -> None:
    continuation = "5％、衍生品类资产0-5％，杠杆率100％-130％。"
    block = _block(
        "T1",
        "table",
        "",
        ["产品概述"],
        kv_pairs=[
            {"key": "业绩比较基准", "value": "权益类资产0-"},
            {"key": "固定管理费", "value": "0.10％（年）"},
        ],
        rows=[
            ["业绩比较基准", "权益类资产0-"],
            ["", continuation],
            ["固定管理费", "0.10％（年）"],
        ],
    )
    result = StructureAwareChunker(CharacterTokenCounter(), 300, 400).chunk(_document([block]))
    combined = "\n".join(chunk.content for chunk in result.chunks)

    assert combined == (
        "业绩比较基准：权益类资产0-\n"
        f"{continuation}\n"
        "固定管理费：0.10％（年）"
    )
    assert combined.count("固定管理费") == 1


def test_table_chunk_content_covers_every_meaningful_source_cell() -> None:
    block = _block(
        "T1",
        "table",
        "",
        ["产品概述"],
        kv_pairs=[{"key": "销售对象", "value": "私人银行客户"}],
        rows=[
            ["", "1"],
            ["销售 对象", "私人银行 客户"],
            ["", "跨页续行中的有效业务文本。"],
        ],
    )
    result = StructureAwareChunker(CharacterTokenCounter(), 80, 100).chunk(_document([block]))
    normalized_chunks = StructureAwareChunker._normalize_table_text(
        "".join(chunk.content for chunk in result.chunks)
    )

    for row in block.rows or []:
        if all(not str(cell).strip() or str(cell).strip().isdigit() for cell in row):
            continue
        for cell in row:
            normalized_cell = StructureAwareChunker._normalize_table_text(cell)
            if normalized_cell:
                assert normalized_cell in normalized_chunks


def test_table_rows_fallback_and_stable_unique_chunk_ids() -> None:
    blocks = [
        _block(
            "T1",
            "table",
            "",
            ["产品概述"],
            rows=[["风险等级", "PR2"], ["销售对象", "私人银行客户"]],
        ),
        _block("B2", "paragraph", "正文证据。", ["其他章节"], 2),
    ]
    chunker = StructureAwareChunker(CharacterTokenCounter(), 200, 250)
    first = chunker.chunk(_document(blocks))
    second = chunker.chunk(_document(blocks))

    assert first.chunks[0].content == "风险等级 | PR2\n销售对象 | 私人银行客户"
    assert [chunk.chunk_id for chunk in first.chunks] == ["D000001_C0001", "D000001_C0002"]
    assert [chunk.chunk_id for chunk in first.chunks] == [chunk.chunk_id for chunk in second.chunks]
    assert len({chunk.chunk_id for chunk in first.chunks}) == len(first.chunks)


def test_oversized_single_table_pair_uses_diagnostic_fallback() -> None:
    value = "这是不可静默截断的超长表格字段。" * 30
    block = _block(
        "T1",
        "table",
        "",
        ["产品概述"],
        kv_pairs=[{"key": "重要提示", "value": value}],
    )
    result = StructureAwareChunker(CharacterTokenCounter(), 100, 130).chunk(_document([block]))

    assert len(result.chunks) > 1
    assert all(chunk.token_count <= 130 for chunk in result.chunks)
    assert all(chunk.source_block_ids == ["T1"] for chunk in result.chunks)
    assert "".join(chunk.content for chunk in result.chunks) == f"重要提示：{value}"
    assert result.diagnostics[0].reason == "OVERSIZED_TABLE_UNIT_SPLIT"


def test_all_effective_blocks_are_covered_without_text_table_mixing() -> None:
    blocks = [
        _block("B1", "heading", "一、概述", ["一、概述"]),
        _block("B2", "paragraph", "正文。", ["一、概述"]),
        _block("T1", "table", "", ["一、概述"], rows=[["键", "值"]]),
        _block("B3", "list", "（1）列表项。", ["一、概述"], 2),
    ]
    result = StructureAwareChunker(CharacterTokenCounter(), 200, 250).chunk(_document(blocks))

    covered = {block_id for chunk in result.chunks for block_id in chunk.source_block_ids}
    assert covered == {"B1", "B2", "T1", "B3"}
    assert [chunk.chunk_type for chunk in result.chunks] == ["text", "table", "text"]


def test_tokenizer_load_is_strictly_local() -> None:
    tokenizer = object()
    with patch("chunking.token_counter.AutoTokenizer.from_pretrained", return_value=tokenizer) as loader:
        counter = QwenTokenCounter(r"D:\local\qwen-tokenizer")

    assert counter.tokenizer is tokenizer
    loader.assert_called_once_with(r"D:\local\qwen-tokenizer", local_files_only=True)
