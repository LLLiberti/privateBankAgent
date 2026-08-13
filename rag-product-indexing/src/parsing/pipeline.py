from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from models import DocumentBlock, ParseIssue, ParsedDocument
from normalization import DocumentNormalizer
from validation import ProductDocumentValidator
from .filename_metadata import FilenameMetadataParser
from .pdf_parser import ProductPdfParser


class ProductDocumentPipeline:
    def __init__(self, output_root: str | Path) -> None:
        self.output_root = Path(output_root)
        self.filename_parser = FilenameMetadataParser()
        self.pdf_parser = ProductPdfParser()
        self.normalizer = DocumentNormalizer()
        self.validator = ProductDocumentValidator()

    def run(self, pdf_path: str | Path) -> dict[str, Any]:
        path = Path(pdf_path).resolve()
        metadata = self.filename_parser.register(path)
        document, issues, parser_name, fallback_used = self.pdf_parser.parse(path, metadata)
        issues.extend(self.normalizer.normalize(document))
        issues.extend(self.validator.validate(document))
        manifest = self._manifest(document, issues, parser_name, fallback_used)
        output_dir = self.output_root / metadata.document_id
        self._write_outputs(output_dir, document, issues, manifest)
        manifest["output_dir"] = str(output_dir.resolve())
        return manifest

    def _manifest(
        self,
        document: ParsedDocument,
        issues: list[ParseIssue],
        parser_name: str,
        fallback_used: bool,
    ) -> dict[str, Any]:
        counts = {kind: sum(block.type == kind for block in document.blocks) for kind in ("heading", "table", "paragraph", "list")}
        return {
            "document_id": document.metadata.document_id,
            "filename": document.metadata.filename,
            "page_count": document.metadata.page_count,
            "heading_count": counts["heading"],
            "table_count": counts["table"],
            "paragraph_count": counts["paragraph"],
            "list_count": counts["list"],
            "issue_count": len(issues),
            "parser": parser_name,
            "fallback_used": fallback_used,
            "status": self.validator.status(issues),
        }

    def _write_outputs(
        self,
        output_dir: Path,
        document: ParsedDocument,
        issues: list[ParseIssue],
        manifest: dict[str, Any],
    ) -> None:
        output_dir.mkdir(parents=True, exist_ok=True)
        self._write_json(output_dir / "parsed.json", document.to_dict())
        (output_dir / "parsed.md").write_text(self._to_markdown(document), encoding="utf-8")
        self._write_json(output_dir / "parse_manifest.json", manifest)
        self._write_json(output_dir / "parse_issues.json", [issue.to_dict() for issue in issues])

    @staticmethod
    def _write_json(path: Path, value: Any) -> None:
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def _to_markdown(self, document: ParsedDocument) -> str:
        lines = [
            "<!-- 本文件由解析流水线生成；方括号中的页码为 1-based PDF 物理页码。 -->",
            "",
        ]
        for block in document.blocks:
            page_ref = self._page_ref(block)
            if block.type == "heading":
                heading_level = self._markdown_heading_level(block)
                lines.extend([f"{'#' * heading_level} {block.text}", "", page_ref, ""])
            elif block.type == "table" and block.rows:
                lines.extend([page_ref, "", *self._markdown_table(block.rows), ""])
            elif block.type == "list":
                for item in block.text.splitlines():
                    lines.append(f"- {item}")
                lines.extend(["", page_ref, ""])
            else:
                lines.extend([block.text, "", page_ref, ""])
        return "\n".join(lines).rstrip() + "\n"

    @staticmethod
    def _markdown_heading_level(block: DocumentBlock) -> int:
        if block.text in {"风险揭示书", "理财产品说明书"}:
            return 1
        return min((block.level or 2) + 1, 6)

    @staticmethod
    def _page_ref(block: DocumentBlock) -> str:
        pages = str(block.page_start) if block.page_start == block.page_end else f"{block.page_start}-{block.page_end}"
        return f"<!-- source: {block.document_id}, {block.block_id}, page {pages} -->"

    @staticmethod
    def _markdown_table(rows: list[list[str]]) -> list[str]:
        if not rows:
            return []
        width = max(len(row) for row in rows)

        def clean(value: str) -> str:
            return value.replace("|", "\\|").replace("\n", "<br>")

        normalized = [row + [""] * (width - len(row)) for row in rows]
        output = ["| " + " | ".join(clean(value) for value in normalized[0]) + " |"]
        output.append("| " + " | ".join(["---"] * width) + " |")
        output.extend("| " + " | ".join(clean(value) for value in row) + " |" for row in normalized[1:])
        return output
