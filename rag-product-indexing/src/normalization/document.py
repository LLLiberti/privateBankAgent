from __future__ import annotations

import re
from dataclasses import replace

from models import DocumentBlock, ParseIssue, ParsedDocument
from .heading import HeadingNormalizer
from .table import ProductTableNormalizer
from .text import TextNormalizer


class DocumentNormalizer:
    def __init__(self) -> None:
        self.heading = HeadingNormalizer()
        self.text = TextNormalizer()
        self.table = ProductTableNormalizer(self.text)

    def normalize(self, document: ParsedDocument) -> list[ParseIssue]:
        issues: list[ParseIssue] = []
        issue_targets: list[tuple[ParseIssue, DocumentBlock]] = []
        cleaned: list[DocumentBlock] = []

        for block in document.blocks:
            if block.type == "table":
                issues.extend(self.table.normalize(block))
            else:
                block.text = self.text.normalize(block.text)
            if not block.text or (block.type != "table" and self.text.is_page_number(block.text)):
                continue

            normalized_blocks, block_issues = self._normalize_heading_block(block)
            cleaned.extend(normalized_blocks)
            issues.extend(issue for issue, _ in block_issues)
            issue_targets.extend(block_issues)

        # Docling normally supplies reading order, but some PDFs return same-page
        # items out of physical order. Its PDF bounding boxes use a bottom-left
        # origin, hence larger `t` values appear earlier on the page.
        cleaned = [
            item[1]
            for item in sorted(
                enumerate(cleaned),
                key=lambda item: self._reading_order_key(item[1], item[0]),
            )
        ]
        self._assign_sections(cleaned)
        stabilized_targets = self._stabilize_numeric_siblings(cleaned)
        stabilized_targets.update(self._stabilize_decimal_clause_siblings(cleaned))
        if stabilized_targets:
            stale_issues = {
                id(issue)
                for issue, target in issue_targets
                if id(target) in stabilized_targets
            }
            issues = [issue for issue in issues if id(issue) not in stale_issues]
            issue_targets = [
                (issue, target)
                for issue, target in issue_targets
                if id(target) not in stabilized_targets
            ]
        self._assign_sections(cleaned)
        for index, block in enumerate(cleaned, start=1):
            block.block_id = f"{document.metadata.document_id}_B{index:04d}"
        document.blocks = cleaned

        for issue, target in issue_targets:
            issue.block_id = target.block_id
        return issues

    def _normalize_heading_block(
        self, block: DocumentBlock
    ) -> tuple[list[DocumentBlock], list[tuple[ParseIssue, DocumentBlock]]]:
        decision = self.heading.classify(block.text, block.parser_heading)
        if not decision.is_heading:
            if block.type == "heading":
                block.type = "paragraph"
                block.level = None
            return [block], []

        block.type = "heading"
        block.level = decision.level
        if decision.heading_text and decision.body_text:
            block.text = decision.heading_text
            body = replace(
                block,
                type="paragraph",
                text=decision.body_text,
                block_id=None,
                level=None,
                section_path=[],
                rows=None,
                cells=None,
                kv_pairs=None,
                parser_heading=False,
            )
            return [block, body], []

        if decision.level is not None:
            return [block], []

        issue = ParseIssue(
            stage="HEADING_NORMALIZATION",
            reason="HEADING_LEVEL_UNKNOWN",
            severity="WARNING",
            message=f"Parser 标记了标题，但规则无法确认层级: {block.text[:80]}",
            page=block.page_start,
        )
        return [block], [(issue, block)]

    @staticmethod
    def _numeric_sibling(text: str) -> tuple[int, str] | None:
        match = re.match(r"^（(\d+)）\s*(.*)$", text.strip())
        if not match:
            return None
        return int(match.group(1)), match.group(2).strip()

    def _stabilize_numeric_siblings(self, blocks: list[DocumentBlock]) -> set[int]:
        runs: list[list[DocumentBlock]] = []
        stabilized_targets: set[int] = set()
        current: list[DocumentBlock] = []
        previous_number: int | None = None
        current_parent: tuple[str, ...] | None = None
        gap = 0

        for block in blocks:
            sibling = self._numeric_sibling(block.text)
            if sibling is None:
                gap += 1
                if block.type not in {"paragraph", "list"} or gap > 2:
                    current = []
                    previous_number = None
                    current_parent = None
                continue

            number, _ = sibling
            parent = tuple(
                block.section_path[:-1]
                if block.type == "heading" and block.section_path and block.section_path[-1] == block.text
                else block.section_path
            )
            if (
                current
                and previous_number is not None
                and number == previous_number + 1
                and block.page_start - current[-1].page_end <= 1
                and parent == current_parent
            ):
                current.append(block)
            else:
                current = [block]
                runs.append(current)
                current_parent = parent
            previous_number = number
            gap = 0

        for run in runs:
            if len(run) < 3:
                continue
            heading_items = [block for block in run if block.type == "heading" and block.level is None]
            non_heading_items = [block for block in run if block.type in {"list", "paragraph"}]
            if not heading_items or len(non_heading_items) < len(heading_items):
                continue

            lengths = [len(self._numeric_sibling(block.text)[1]) for block in run]  # type: ignore[index]
            has_explanatory_sibling = any(
                re.search(r"[。！？]$", block.text.strip()) for block in non_heading_items
            )
            if max(lengths) - min(lengths) > 36 and not has_explanatory_sibling:
                continue
            target_type = "list" if any(block.type == "list" for block in non_heading_items) else "paragraph"
            for block in heading_items:
                block.type = target_type
                block.level = None
                stabilized_targets.add(id(block))
        return stabilized_targets

    @staticmethod
    def _decimal_clause_sibling(text: str) -> tuple[int, str] | None:
        match = re.match(r"^(\d+)[\.．]\s*(.*)$", text.strip())
        if not match:
            return None
        return int(match.group(1)), match.group(2).strip()

    @classmethod
    def _is_decimal_body_clause(cls, text: str) -> bool:
        sibling = cls._decimal_clause_sibling(text)
        if sibling is None:
            return False
        body = sibling[1]
        if re.search(r"[。！？；]$", body):
            return True
        return len(body) >= 24 and bool(
            re.match(r"^(?:甲方|乙方|本产品|本理财产品|投资者|客户|为|如|若|当|因)", body)
        )

    def _stabilize_decimal_clause_siblings(self, blocks: list[DocumentBlock]) -> set[int]:
        """Prevent one parser-marked sentence from changing a body-clause sequence."""
        runs: list[list[DocumentBlock]] = []
        current: list[DocumentBlock] = []
        previous_number: int | None = None
        current_parent: tuple[str, ...] | None = None
        gap = 0

        for block in blocks:
            sibling = self._decimal_clause_sibling(block.text)
            if sibling is None:
                gap += 1
                if block.type not in {"paragraph", "list"} or gap > 1:
                    current = []
                    previous_number = None
                    current_parent = None
                continue

            number, _ = sibling
            parent_path = list(block.section_path)
            if parent_path and self._decimal_clause_sibling(parent_path[-1]) is not None:
                parent_path.pop()
            parent = tuple(parent_path)
            if (
                current
                and previous_number is not None
                and number == previous_number + 1
                and block.page_start - current[-1].page_end <= 1
                and parent == current_parent
            ):
                current.append(block)
            else:
                current = [block]
                runs.append(current)
                current_parent = parent
            previous_number = number
            gap = 0

        stabilized_targets: set[int] = set()
        for run in runs:
            if len(run) < 3:
                continue
            heading_items = [
                block
                for block in run
                if block.type == "heading" and block.level == 3
            ]
            non_heading_items = [block for block in run if block.type in {"paragraph", "list"}]
            sentence_items = [block for block in run if self._is_decimal_body_clause(block.text)]
            if (
                not heading_items
                or len(non_heading_items) < 2
                or len(sentence_items) < max(2, (len(run) + 1) // 2)
            ):
                continue

            paragraph_count = sum(block.type == "paragraph" for block in non_heading_items)
            target_type = "paragraph" if paragraph_count * 2 >= len(non_heading_items) else "list"
            for block in heading_items:
                if not self._is_decimal_body_clause(block.text):
                    continue
                block.type = target_type
                block.level = None
                stabilized_targets.add(id(block))
        return stabilized_targets

    @staticmethod
    def _reading_order_key(block: DocumentBlock, original_index: int) -> tuple[float, float, float, int]:
        boxes = [
            value.get("bbox", {})
            for value in (block.provenance or [])
            if value.get("page") == block.page_start and value.get("bbox")
        ]
        if not boxes:
            return float(block.page_start), 1.0, 0.0, original_index
        top = max(float(box.get("t", 0.0)) for box in boxes)
        left = min(float(box.get("l", 0.0)) for box in boxes)
        return float(block.page_start), -top, left, original_index

    @staticmethod
    def _arabic_numeric_prefix(text: str) -> tuple[int, str] | None:
        match = re.match(r"^\s*(\d+)\s*([、\.．])", text)
        if not match:
            return None
        return int(match.group(1)), match.group(2)

    def _assign_sections(self, blocks: list[DocumentBlock]) -> None:
        root: str | None = None
        primary: str | None = None
        secondary: str | None = None
        clause: str | None = None

        for block in blocks:
            if clause is not None:
                clause_prefix = self._arabic_numeric_prefix(clause)
                current_prefix = self._arabic_numeric_prefix(block.text)
                if (
                    clause_prefix is not None
                    and current_prefix is not None
                    and current_prefix[1] == clause_prefix[1]
                    and current_prefix[0] > clause_prefix[0]
                ):
                    clause = None
            if block.type == "heading":
                if block.text in self.heading.ROOT_TITLES:
                    root, primary, secondary, clause = block.text, None, None, None
                elif block.level == 1:
                    primary, secondary, clause = block.text, None, None
                elif block.level == 2:
                    secondary, clause = block.text, None
                elif block.level == 3:
                    clause = block.text
            path = [value for value in (root, primary, secondary, clause) if value]
            if block.type == "heading" and block.text not in path:
                path.append(block.text)
            block.section_path = path
