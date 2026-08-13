from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

from models import DEFAULT_SOURCE_LEVEL, DocumentMetadata


@dataclass(frozen=True)
class FilenameMetadata:
    document_id: str
    product_code: str
    sales_code: str
    product_name_from_filename: str


class FilenameMetadataParser:
    """Parse the two currently observed product-document filename shapes."""

    DOCUMENT_ID_PATTERN = re.compile(r"^D\d{6}$", re.IGNORECASE)
    # Current codes are compact ASCII identifiers containing both letters and digits.
    CODE_PATTERN = re.compile(r"^(?=.{6,16}$)(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9]+$")
    SUFFIX = "_产品说明书"

    @classmethod
    def looks_like_code(cls, value: str) -> bool:
        return bool(cls.CODE_PATTERN.fullmatch(value))

    def parse(self, filename: str | Path) -> FilenameMetadata:
        name = Path(filename).name
        if Path(name).suffix.lower() != ".pdf":
            raise ValueError(f"不是 PDF 文件: {name}")

        stem = Path(name).stem
        if stem.endswith(self.SUFFIX):
            stem = stem[: -len(self.SUFFIX)]
        parts = stem.split("_")
        if len(parts) < 3:
            raise ValueError(f"文件名字段不足: {name}")

        document_id, product_code = parts[0].strip(), parts[1].strip()
        if not self.DOCUMENT_ID_PATTERN.fullmatch(document_id):
            raise ValueError(f"无法从文件名建立 document_id: {name}")
        if not self.looks_like_code(product_code):
            raise ValueError(f"无法从文件名建立 product_code: {name}")

        if len(parts) >= 4 and self.looks_like_code(parts[2].strip()):
            sales_code = parts[2].strip()
            product_name = "_".join(parts[3:]).strip()
        else:
            sales_code = product_code
            product_name = "_".join(parts[2:]).strip()
        if not product_name:
            raise ValueError(f"无法从文件名建立产品名称: {name}")
        return FilenameMetadata(document_id, product_code, sales_code, product_name)

    def register(self, pdf_path: str | Path) -> DocumentMetadata:
        path = Path(pdf_path)
        parsed = self.parse(path.name)
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return DocumentMetadata(
            document_id=parsed.document_id,
            filename=path.name,
            file_hash=digest.hexdigest(),
            file_type="pdf",
            source_level=DEFAULT_SOURCE_LEVEL,
            product_code=parsed.product_code,
            sales_code=parsed.sales_code,
            product_name_from_filename=parsed.product_name_from_filename,
        )
