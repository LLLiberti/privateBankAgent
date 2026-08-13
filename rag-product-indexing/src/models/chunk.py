from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Literal


ChunkType = Literal["text", "table"]


@dataclass
class DocumentChunk:
    chunk_id: str
    document_id: str
    chunk_type: ChunkType
    content: str
    embedding_text: str
    section_path: list[str]
    page_start: int
    page_end: int
    source_block_ids: list[str]
    token_count: int

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ChunkDiagnostic:
    reason: str
    message: str
    source_block_ids: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ChunkingResult:
    document_id: str
    chunks: list[DocumentChunk]
    diagnostics: list[ChunkDiagnostic] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "document_id": self.document_id,
            "chunks": [chunk.to_dict() for chunk in self.chunks],
            "diagnostics": [item.to_dict() for item in self.diagnostics],
        }
