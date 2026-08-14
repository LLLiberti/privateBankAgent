from __future__ import annotations

import json
from dataclasses import fields
from pathlib import Path
from typing import Protocol

from langchain_text_splitters import RecursiveCharacterTextSplitter

from models import (
    ChunkDiagnostic,
    ChunkingResult,
    DocumentBlock,
    DocumentChunk,
    DocumentMetadata,
    ParsedDocument,
)
from .embedding_text import build_embedding_text


TARGET_TOKENS = 400
MAX_TOKENS = 500
TEXT_BLOCK_TYPES = {"heading", "paragraph", "list"}
SPLIT_SEPARATORS = ["\n\n", "\n", "。", "；", "，", ""]


class TokenCounter(Protocol):
    def count(self, text: str) -> int: ...


class StructureAwareChunker:
    def __init__(
        self,
        token_counter: TokenCounter,
        target_tokens: int = TARGET_TOKENS,
        max_tokens: int = MAX_TOKENS,
    ) -> None:
        if target_tokens <= 0 or max_tokens <= 0 or target_tokens > max_tokens:
            raise ValueError("token limits must satisfy 0 < target_tokens <= max_tokens")
        self.token_counter = token_counter
        self.target_tokens = target_tokens
        self.max_tokens = max_tokens
        self._diagnostics: list[ChunkDiagnostic] = []

    def chunk(self, document: ParsedDocument) -> ChunkingResult:
        self._diagnostics = []
        chunks: list[DocumentChunk] = []
        text_group: list[DocumentBlock] = []

        def flush_text_group() -> None:
            if text_group:
                chunks.extend(self._chunk_text_group(document.metadata, list(text_group)))
                text_group.clear()

        for block in document.blocks:
            if block.type == "table":
                flush_text_group()
                chunks.extend(self._chunk_table(document.metadata, block))
                continue
            if block.type not in TEXT_BLOCK_TYPES or not block.text.strip():
                continue
            if text_group and block.section_path != text_group[-1].section_path:
                flush_text_group()
            text_group.append(block)
        flush_text_group()

        for index, chunk in enumerate(chunks, start=1):
            chunk.chunk_id = f"{document.metadata.document_id}_C{index:04d}"
            if chunk.token_count > self.max_tokens:
                raise ValueError(f"chunk exceeds MAX_TOKENS: {chunk.chunk_id}")
        return ChunkingResult(document.metadata.document_id, chunks, list(self._diagnostics))

    def _chunk_text_group(
        self,
        metadata: DocumentMetadata,
        blocks: list[DocumentBlock],
    ) -> list[DocumentChunk]:
        chunks: list[DocumentChunk] = []
        pending: list[DocumentBlock] = []
        for block in blocks:
            candidate = [*pending, block]
            if pending and (
                self._token_count(metadata, block.section_path, self._join_block_text(candidate))
                > self.max_tokens
                or self._token_count(metadata, block.section_path, self._join_block_text(pending))
                >= self.target_tokens
            ):
                chunks.extend(self._emit_text_blocks(metadata, pending))
                pending = []

            if self._token_count(metadata, block.section_path, block.text) > self.max_tokens:
                if pending:
                    chunks.extend(self._emit_text_blocks(metadata, pending))
                    pending = []
                self._diagnostics.append(
                    ChunkDiagnostic(
                        reason="OVERSIZED_TEXT_BLOCK_SPLIT",
                        message="单个文本 block 超过 MAX_TOKENS，已在其 section 内保守切分。",
                        source_block_ids=self._source_ids([block]),
                    )
                )
                chunks.extend(self._split_atomic_content(metadata, block, block.text, "text"))
            else:
                pending.append(block)
        if pending:
            chunks.extend(self._emit_text_blocks(metadata, pending))
        return chunks

    def _emit_text_blocks(
        self,
        metadata: DocumentMetadata,
        blocks: list[DocumentBlock],
    ) -> list[DocumentChunk]:
        content = self._join_block_text(blocks)
        if self._token_count(metadata, blocks[0].section_path, content) <= self.max_tokens:
            return [self._make_chunk(metadata, "text", content, blocks)]
        return self._split_atomic_content(metadata, blocks[0], content, "text", blocks)

    def _chunk_table(
        self,
        metadata: DocumentMetadata,
        block: DocumentBlock,
    ) -> list[DocumentChunk]:
        units = self._table_units(block)
        if not units and block.text.strip():
            units = [block.text.strip()]
        chunks: list[DocumentChunk] = []
        pending: list[str] = []
        for unit in units:
            candidate = "\n".join([*pending, unit])
            if pending and (
                self._token_count(metadata, block.section_path, candidate) > self.max_tokens
                or self._token_count(metadata, block.section_path, "\n".join(pending))
                >= self.target_tokens
            ):
                chunks.append(self._make_chunk(metadata, "table", "\n".join(pending), [block]))
                pending = []

            if self._token_count(metadata, block.section_path, unit) > self.max_tokens:
                if pending:
                    chunks.append(self._make_chunk(metadata, "table", "\n".join(pending), [block]))
                    pending = []
                self._diagnostics.append(
                    ChunkDiagnostic(
                        reason="OVERSIZED_TABLE_UNIT_SPLIT",
                        message="单个表格 key-value/row 超过 MAX_TOKENS，已保留来源并保守切分。",
                        source_block_ids=self._source_ids([block]),
                    )
                )
                chunks.extend(self._split_atomic_content(metadata, block, unit, "table"))
            else:
                pending.append(unit)
        if pending:
            chunks.append(self._make_chunk(metadata, "table", "\n".join(pending), [block]))
        return chunks

    @staticmethod
    def _table_units(block: DocumentBlock) -> list[str]:
        pairs = [
            (
                f"{str(pair.get('key', '')).strip()}：{str(pair.get('value', '')).strip()}",
                StructureAwareChunker._normalize_table_text(pair.get("key", "")),
                StructureAwareChunker._normalize_table_text(pair.get("value", "")),
            )
            for pair in (block.kv_pairs or [])
            if str(pair.get("key", "")).strip() or str(pair.get("value", "")).strip()
        ]
        if pairs:
            rows = block.rows or []
            pairs_by_row: dict[int, list[int]] = {}
            unmatched_pairs: list[int] = []
            for pair_index, (_, key, value) in enumerate(pairs):
                row_index = next(
                    (
                        index
                        for index, row in enumerate(rows)
                        if (not key or key in {StructureAwareChunker._normalize_table_text(cell) for cell in row})
                        and (not value or value in {StructureAwareChunker._normalize_table_text(cell) for cell in row})
                    ),
                    None,
                )
                if row_index is None:
                    unmatched_pairs.append(pair_index)
                else:
                    pairs_by_row.setdefault(row_index, []).append(pair_index)

            covered = [StructureAwareChunker._normalize_table_text(text) for text, _, _ in pairs]
            units: list[str] = []
            for row_index, row in enumerate(rows):
                units.extend(pairs[index][0] for index in pairs_by_row.get(row_index, []))
                nonempty_cells = [str(cell).strip() for cell in row if str(cell).strip()]
                if nonempty_cells and all(cell.isdigit() for cell in nonempty_cells):
                    continue
                for cell in nonempty_cells:
                    normalized = StructureAwareChunker._normalize_table_text(cell)
                    if normalized and not any(normalized in pair_text for pair_text in covered):
                        units.append(cell)
            units.extend(pairs[index][0] for index in unmatched_pairs)
            return units
        return [
            " | ".join(str(cell).strip() for cell in row)
            for row in (block.rows or [])
            if any(str(cell).strip() for cell in row)
        ]

    @staticmethod
    def _normalize_table_text(text: object) -> str:
        return "".join(str(text).split())

    def _split_atomic_content(
        self,
        metadata: DocumentMetadata,
        block: DocumentBlock,
        content: str,
        chunk_type: str,
        source_blocks: list[DocumentBlock] | None = None,
    ) -> list[DocumentChunk]:
        source_blocks = source_blocks or [block]
        empty_tokens = self._token_count(metadata, block.section_path, "")
        if empty_tokens >= self.max_tokens:
            raise ValueError("embedding_text metadata alone reaches MAX_TOKENS")
        splitter = RecursiveCharacterTextSplitter(
            chunk_size=self.max_tokens,
            chunk_overlap=0,
            length_function=lambda value: self._token_count(metadata, block.section_path, value),
            separators=SPLIT_SEPARATORS,
            keep_separator="end",
            strip_whitespace=False,
        )
        pieces = [piece for piece in splitter.split_text(content) if piece]
        return [
            self._make_chunk(metadata, chunk_type, piece, source_blocks)
            for piece in pieces
        ]

    def _make_chunk(
        self,
        metadata: DocumentMetadata,
        chunk_type: str,
        content: str,
        blocks: list[DocumentBlock],
    ) -> DocumentChunk:
        embedding_text = build_embedding_text(metadata, blocks[0].section_path, content)
        return DocumentChunk(
            chunk_id="",
            document_id=metadata.document_id,
            chunk_type=chunk_type,  # type: ignore[arg-type]
            content=content,
            embedding_text=embedding_text,
            section_path=list(blocks[0].section_path),
            page_start=min(block.page_start for block in blocks),
            page_end=max(block.page_end for block in blocks),
            source_block_ids=self._source_ids(blocks),
            token_count=self.token_counter.count(embedding_text),
        )

    def _token_count(
        self,
        metadata: DocumentMetadata,
        section_path: list[str],
        content: str,
    ) -> int:
        return self.token_counter.count(build_embedding_text(metadata, section_path, content))

    @staticmethod
    def _join_block_text(blocks: list[DocumentBlock]) -> str:
        return "\n".join(block.text.strip() for block in blocks if block.text.strip())

    @staticmethod
    def _source_ids(blocks: list[DocumentBlock]) -> list[str]:
        return list(dict.fromkeys(block.block_id for block in blocks if block.block_id))


def load_parsed_document(path: Path | str) -> ParsedDocument:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    metadata_fields = {item.name for item in fields(DocumentMetadata)}
    block_fields = {item.name for item in fields(DocumentBlock)}
    metadata = DocumentMetadata(
        **{key: value for key, value in data["metadata"].items() if key in metadata_fields}
    )
    blocks = [
        DocumentBlock(**{key: value for key, value in item.items() if key in block_fields})
        for item in data["blocks"]
    ]
    return ParsedDocument(metadata, blocks)
