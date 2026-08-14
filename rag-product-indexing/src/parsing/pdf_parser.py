from __future__ import annotations

import shutil
import tempfile
from pathlib import Path
from typing import Any

from models import DocumentBlock, DocumentMetadata, ParseIssue, ParsedDocument


def _load_pymupdf() -> Any:
    try:
        import pymupdf

        return pymupdf
    except ImportError:
        import fitz

        return fitz


class ProductPdfParser:
    MIN_TEXT_LAYER_CHARS = 20

    def parse(
        self, pdf_path: str | Path, metadata: DocumentMetadata
    ) -> tuple[ParsedDocument, list[ParseIssue], str, bool]:
        path = Path(pdf_path)
        issues: list[ParseIssue] = []
        try:
            page_texts = self._read_pages(path)
        except Exception as exc:
            issues.append(
                ParseIssue(
                    stage="PDF_INSPECTION",
                    reason="PDF_READ_ERROR",
                    severity="ERROR",
                    message=f"PyMuPDF 无法读取 PDF: {exc}",
                )
            )
            return ParsedDocument(metadata, []), issues, "docling", False

        metadata.page_count = len(page_texts)
        for page_number, text in enumerate(page_texts, start=1):
            if len(text.strip()) < self.MIN_TEXT_LAYER_CHARS:
                issues.append(
                    ParseIssue(
                        stage="PDF_INSPECTION",
                        reason="OCR_REQUIRED",
                        severity="WARNING",
                        message="页面几乎没有可用文本层；本阶段未启用独立 OCR。",
                        page=page_number,
                    )
                )

        try:
            blocks = self._parse_with_docling(path, metadata.document_id)
            parser_name = "docling"
            fallback_used = False
        except Exception as exc:
            blocks = self._pymupdf_fallback(page_texts, metadata.document_id)
            parser_name = "pymupdf"
            fallback_used = True
            issues.append(
                ParseIssue(
                    stage="PDF_PARSING",
                    reason="FALLBACK_PARSER_USED",
                    severity="INFO",
                    message=f"Docling 转换失败，使用 PyMuPDF 页面文本保底: {type(exc).__name__}: {exc}",
                )
            )

        pages_with_content = {
            page
            for block in blocks
            if block.text.strip()
            for page in range(block.page_start, block.page_end + 1)
        }
        for page_number, text in enumerate(page_texts, start=1):
            if page_number in pages_with_content or len(text.strip()) < self.MIN_TEXT_LAYER_CHARS:
                continue
            blocks.append(
                DocumentBlock(
                    document_id=metadata.document_id,
                    type="paragraph",
                    text=text,
                    page_start=page_number,
                    page_end=page_number,
                    provenance=[{"parser": "pymupdf", "page": page_number}],
                )
            )
            fallback_used = True
            issues.append(
                ParseIssue(
                    stage="PDF_PARSING",
                    reason="FALLBACK_PARSER_USED",
                    severity="INFO",
                    message="Docling 未产生该页有效内容，使用 PyMuPDF 页面文本保底。",
                    page=page_number,
                )
            )

        return ParsedDocument(metadata, blocks), issues, parser_name, fallback_used

    def _read_pages(self, path: Path) -> list[str]:
        fitz = _load_pymupdf()
        with fitz.open(path) as pdf:
            return [page.get_text("text") or "" for page in pdf]

    def _pymupdf_fallback(self, page_texts: list[str], document_id: str) -> list[DocumentBlock]:
        return [
            DocumentBlock(
                document_id=document_id,
                type="paragraph",
                text=text,
                page_start=page_number,
                page_end=page_number,
                provenance=[{"parser": "pymupdf", "page": page_number}],
            )
            for page_number, text in enumerate(page_texts, start=1)
            if text.strip()
        ]

    def _parse_with_docling(self, path: Path, document_id: str) -> list[DocumentBlock]:
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import PdfPipelineOptions
        from docling.document_converter import DocumentConverter, PdfFormatOption

        options = PdfPipelineOptions()
        options.do_ocr = False
        options.do_table_structure = True
        # Docling 2.119 enables torch.compile for its Transformers layout
        # detector by default. On a CPU-only Windows workstation this invokes
        # the MSVC `cl` compiler, which is not a runtime requirement for PDF
        # parsing. Eager inference keeps the same Docling model and avoids that
        # environment-only failure.
        if hasattr(options.layout_options.engine_options, "compile_model"):
            options.layout_options.engine_options.compile_model = False
        converter = DocumentConverter(
            format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=options)}
        )
        # docling-parse on Windows can mis-decode non-ASCII filesystem paths.
        # A short-lived ASCII-named copy avoids that library boundary while all
        # public metadata and hashes continue to refer to the untouched source.
        with tempfile.TemporaryDirectory(prefix="rag_pdf_") as temporary:
            parser_path = Path(temporary) / "document.pdf"
            shutil.copyfile(path, parser_path)
            result = converter.convert(parser_path)
        doc = result.document
        blocks: list[DocumentBlock] = []
        for item, _ in doc.iterate_items():
            label = self._label(item)
            if label in {"picture", "page_header", "page_footer"}:
                continue
            provenance, page_start, page_end = self._provenance(item)
            text = str(getattr(item, "text", "") or "").strip()
            rows = None
            cells = None
            if label == "table":
                rows, cells = self._table_data(item, doc)
                if not text and rows:
                    text = "\n".join(" | ".join(row) for row in rows)
            if not text and not rows:
                continue
            block_type = self._block_type(label)
            blocks.append(
                DocumentBlock(
                    document_id=document_id,
                    type=block_type,
                    text=text,
                    page_start=page_start,
                    page_end=page_end,
                    rows=rows,
                    cells=cells,
                    provenance=provenance or None,
                    parser_heading=block_type == "heading",
                )
            )
        return blocks

    @staticmethod
    def _label(item: Any) -> str:
        label = getattr(item, "label", "")
        return str(getattr(label, "value", label)).lower()

    @staticmethod
    def _block_type(label: str) -> str:
        if label in {"title", "section_header", "heading"}:
            return "heading"
        if label == "table":
            return "table"
        if label in {"list_item", "list"}:
            return "list"
        return "paragraph"

    @staticmethod
    def _provenance(item: Any) -> tuple[list[dict[str, Any]], int, int]:
        values: list[dict[str, Any]] = []
        pages: list[int] = []
        for prov in getattr(item, "prov", []) or []:
            page = int(getattr(prov, "page_no", 1) or 1)
            if page < 1:
                page += 1
            pages.append(page)
            value: dict[str, Any] = {"parser": "docling", "page": page}
            bbox = getattr(prov, "bbox", None)
            if bbox is not None:
                value["bbox"] = {
                    name: float(getattr(bbox, name))
                    for name in ("l", "t", "r", "b")
                    if getattr(bbox, name, None) is not None
                }
            values.append(value)
        return values, min(pages, default=1), max(pages, default=1)

    @staticmethod
    def _table_data(item: Any, doc: Any) -> tuple[list[list[str]] | None, list[dict[str, Any]] | None]:
        rows: list[list[str]] | None = None
        try:
            frame = item.export_to_dataframe(doc=doc)
            rows = [[str(value or "") for value in row] for row in frame.fillna("").values.tolist()]
            columns = [str(value or "") for value in frame.columns.tolist()]
            if columns and not all(value.isdigit() for value in columns):
                rows.insert(0, columns)
        except Exception:
            pass

        cells: list[dict[str, Any]] = []
        table_data = getattr(item, "data", None)
        for cell in getattr(table_data, "table_cells", []) or []:
            cells.append(
                {
                    "row_start": getattr(cell, "start_row_offset_idx", None),
                    "row_end": getattr(cell, "end_row_offset_idx", None),
                    "col_start": getattr(cell, "start_col_offset_idx", None),
                    "col_end": getattr(cell, "end_col_offset_idx", None),
                    "text": str(getattr(cell, "text", "") or ""),
                }
            )
        return rows, cells or None
