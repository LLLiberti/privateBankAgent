from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


MODULE_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = MODULE_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from parsing import ProductDocumentPipeline  # noqa: E402


def discover_pdfs(input_path: Path) -> list[Path]:
    if input_path.is_file():
        return [input_path] if input_path.suffix.lower() == ".pdf" else []
    direct = sorted(input_path.glob("*.pdf"))
    if direct:
        return direct
    raw = input_path / "raw"
    return sorted(raw.glob("*.pdf")) if raw.is_dir() else []


def main() -> int:
    if os.name == "nt" and sys.flags.utf8_mode == 0:
        # Transformers model configs are UTF-8. Windows installations whose
        # active code page is GBK otherwise fail before layout inference.
        environment = os.environ.copy()
        environment["PYTHONUTF8"] = "1"
        os.execve(sys.executable, [sys.executable, *sys.argv], environment)

    parser = argparse.ArgumentParser(description="Parse product PDF files into Phase 1 ParsedDocument output.")
    parser.add_argument("input", type=Path, help="PDF file or directory (data and data/raw are supported)")
    parser.add_argument(
        "--output-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_parse",
        help="Output root; defaults to output/document_parse",
    )
    args = parser.parse_args()
    input_path = args.input if args.input.is_absolute() else (Path.cwd() / args.input).resolve()
    pdfs = discover_pdfs(input_path)
    if not pdfs:
        parser.error(f"未找到 PDF: {input_path}")

    pipeline = ProductDocumentPipeline(args.output_root)
    failed = False
    for pdf in pdfs:
        try:
            result = pipeline.run(pdf)
        except Exception as exc:
            failed = True
            print(f"Document: {pdf.name}\nStatus: FAILED\nError: {type(exc).__name__}: {exc}", file=sys.stderr)
            continue
        print(
            f"Document: {result['document_id']}\n"
            f"Pages: {result['page_count']}\n"
            f"Headings: {result['heading_count']}\n"
            f"Tables: {result['table_count']}\n"
            f"Paragraphs: {result['paragraph_count']}\n"
            f"Issues: {result['issue_count']}\n"
            f"Status: {result['status']}\n"
            f"Output: {result['output_dir']}"
        )
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
