from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


MODULE_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = MODULE_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from chunking import (  # noqa: E402
    DEFAULT_TOKENIZER_PATH,
    QwenTokenCounter,
    StructureAwareChunker,
    load_parsed_document,
)


def discover_inputs(input_path: Path) -> list[Path]:
    if input_path.is_file():
        return [input_path]
    direct = input_path / "parsed.json"
    if direct.is_file():
        return [direct]
    return sorted(input_path.glob("*/parsed.json"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Create Phase 2 structure-aware chunks from parsed.json.")
    parser.add_argument("input", type=Path, help="parsed.json, a document_parse/<id> directory, or document_parse root")
    parser.add_argument(
        "--output-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_chunks",
    )
    parser.add_argument("--tokenizer-path", type=Path, default=DEFAULT_TOKENIZER_PATH)
    args = parser.parse_args()

    input_path = args.input if args.input.is_absolute() else (Path.cwd() / args.input).resolve()
    inputs = discover_inputs(input_path)
    if not inputs:
        parser.error(f"未找到 parsed.json: {input_path}")

    counter = QwenTokenCounter(args.tokenizer_path)
    chunker = StructureAwareChunker(counter)
    for parsed_path in inputs:
        document = load_parsed_document(parsed_path)
        result = chunker.chunk(document)
        output_dir = args.output_root / document.metadata.document_id
        output_dir.mkdir(parents=True, exist_ok=True)
        output_path = output_dir / "chunks.json"
        output_path.write_text(
            json.dumps(result.to_dict(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        text_count = sum(chunk.chunk_type == "text" for chunk in result.chunks)
        table_count = sum(chunk.chunk_type == "table" for chunk in result.chunks)
        print(
            f"Document: {document.metadata.document_id}\n"
            f"Chunks: {len(result.chunks)}\n"
            f"Text: {text_count}\n"
            f"Tables: {table_count}\n"
            f"Diagnostics: {len(result.diagnostics)}\n"
            f"Output: {output_path}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
