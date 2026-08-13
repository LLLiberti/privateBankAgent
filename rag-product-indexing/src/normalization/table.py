from __future__ import annotations

from models import DocumentBlock, ParseIssue
from .text import TextNormalizer


class ProductTableNormalizer:
    def __init__(self, text_normalizer: TextNormalizer | None = None) -> None:
        self.text_normalizer = text_normalizer or TextNormalizer()

    def normalize(self, block: DocumentBlock) -> list[ParseIssue]:
        issues: list[ParseIssue] = []
        if not block.rows:
            issues.append(
                ParseIssue(
                    stage="TABLE_NORMALIZATION",
                    reason="TABLE_PARSE_WARNING",
                    severity="WARNING",
                    message="Parser 识别到表格，但未能恢复二维行列结构；已保留表格文本。",
                    page=block.page_start,
                    block_id=block.block_id,
                )
            )
            return issues

        width = max((len(row) for row in block.rows), default=0)
        normalized_rows: list[list[str]] = []
        for row in block.rows:
            normalized = [self.text_normalizer.normalize(str(cell or "")) for cell in row]
            if len(normalized) < width:
                normalized.extend([""] * (width - len(normalized)))
            normalized_rows.append(normalized)
        block.rows = normalized_rows
        block.text = "\n".join(" | ".join(row) for row in normalized_rows).strip()

        if width == 2:
            pairs = [
                {"key": row[0], "value": row[1]}
                for row in normalized_rows
                if row[0].strip() and row[1].strip()
            ]
            block.kv_pairs = pairs or None
            if normalized_rows and not pairs:
                issues.append(
                    ParseIssue(
                        stage="TABLE_NORMALIZATION",
                        reason="TABLE_PARSE_WARNING",
                        severity="WARNING",
                        message="双列表格没有可可靠恢复的非空 key-value 行；已保留二维结构。",
                        page=block.page_start,
                        block_id=block.block_id,
                    )
                )

        if block.page_end > block.page_start and any(not row[0] for row in normalized_rows if row):
            issues.append(
                ParseIssue(
                    stage="TABLE_NORMALIZATION",
                    reason="TABLE_PARSE_WARNING",
                    severity="WARNING",
                    message="跨页表格包含空首列，未猜测其与上一页单元格的归属。",
                    page=block.page_start,
                    block_id=block.block_id,
                )
            )
        return issues
