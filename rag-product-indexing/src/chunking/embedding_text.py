from __future__ import annotations

from models import DocumentMetadata


def product_label(metadata: DocumentMetadata) -> str | None:
    return (
        metadata.product_name_from_filename.strip()
        or metadata.product_code.strip()
        or metadata.sales_code.strip()
        or None
    )


def build_embedding_text(
    metadata: DocumentMetadata,
    section_path: list[str],
    content: str,
) -> str:
    lines: list[str] = []
    product = product_label(metadata)
    if product:
        lines.append(f"产品：{product}")
    if section_path:
        lines.append(f"章节：{' > '.join(section_path)}")
    lines.append(f"内容：{content}")
    return "\n".join(lines)
