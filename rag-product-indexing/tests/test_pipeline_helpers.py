from pathlib import Path

from models import DocumentBlock
from parsing.pipeline import ProductDocumentPipeline
from scripts.parse_product_document import discover_pdfs


def test_data_directory_discovers_raw_subdirectory(tmp_path: Path) -> None:
    raw = tmp_path / "raw"
    raw.mkdir()
    pdf = raw / "sample.pdf"
    pdf.write_bytes(b"x")
    assert discover_pdfs(tmp_path) == [pdf]


def test_markdown_table_and_page_reference() -> None:
    rows = [["销售对象", "说明"], ["B份额", "私人银行客户"]]
    rendered = ProductDocumentPipeline._markdown_table(rows)
    assert "| B份额 | 私人银行客户 |" in rendered
    block = DocumentBlock("D000001", "paragraph", "正文", 4, 5, block_id="D000001_B0001")
    assert ProductDocumentPipeline._page_ref(block) == "<!-- source: D000001, D000001_B0001, page 4-5 -->"
