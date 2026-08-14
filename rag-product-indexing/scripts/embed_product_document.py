from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI


MODULE_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = MODULE_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from embedding import EmbeddingPipeline, EmbeddingService  # noqa: E402


def required_environment(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"缺少环境变量: {name}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Phase 3A embeddings for one product document.")
    parser.add_argument("--document-id", required=True)
    parser.add_argument(
        "--chunks-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_chunks",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_embeddings",
    )
    args = parser.parse_args()

    load_dotenv(MODULE_ROOT / ".env")
    api_key = required_environment("DASHSCOPE_API_KEY")
    base_url = required_environment("DASHSCOPE_BASE_URL")
    model = required_environment("EMBEDDING_MODEL")
    dimensions_text = required_environment("EMBEDDING_DIMENSIONS")
    try:
        dimensions = int(dimensions_text)
    except ValueError as exc:
        raise ValueError("EMBEDDING_DIMENSIONS 必须是整数") from exc

    chunks_path = args.chunks_root / args.document_id / "chunks.json"
    if not chunks_path.is_file():
        parser.error(f"未找到 chunks.json: {chunks_path}")

    client = OpenAI(api_key=api_key, base_url=base_url, timeout=60.0, max_retries=0)
    service = EmbeddingService(client, model, dimensions)
    pipeline = EmbeddingPipeline(service, args.output_root, log=print)
    manifest = pipeline.run(chunks_path)
    print(
        f"Embedding complete\n"
        f"{manifest['embedding_count']}/{manifest['chunk_count']}\n"
        f"status={manifest['status']}"
    )
    return 0 if manifest["status"] == "SUCCESS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
