from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Literal


DEFAULT_SOURCE_LEVEL = "P0"

BlockType = Literal["heading", "paragraph", "table", "list"]
Severity = Literal["INFO", "WARNING", "ERROR"]


@dataclass
class DocumentMetadata:
    document_id: str
    filename: str
    file_hash: str
    file_type: str
    source_level: str
    product_code: str
    sales_code: str
    product_name_from_filename: str
    page_count: int = 0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class DocumentBlock:
    document_id: str
    type: BlockType
    text: str
    page_start: int
    page_end: int
    section_path: list[str] = field(default_factory=list)
    block_id: str | None = None
    level: int | None = None
    rows: list[list[str]] | None = None
    cells: list[dict[str, Any]] | None = None
    kv_pairs: list[dict[str, str]] | None = None
    provenance: list[dict[str, Any]] | None = None
    parser_heading: bool = False

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value.pop("parser_heading", None)
        return {key: item for key, item in value.items() if item is not None}


@dataclass
class ParseIssue:
    stage: str
    reason: str
    severity: Severity
    message: str
    page: int | None = None
    block_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ParsedDocument:
    metadata: DocumentMetadata
    blocks: list[DocumentBlock]

    def to_dict(self) -> dict[str, Any]:
        return {
            "metadata": self.metadata.to_dict(),
            "blocks": [block.to_dict() for block in self.blocks],
        }
