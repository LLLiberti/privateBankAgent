from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from qdrant_client import QdrantClient


MODULE_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = MODULE_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from indexing import (  # noqa: E402
    QdrantIndexPipeline,
    QdrantIndexService,
    prepare_document_index,
)


def required_environment(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"Missing environment variable: {name}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Index one product document into Qdrant.")
    parser.add_argument("--document-id", required=True)
    parser.add_argument(
        "--chunks-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_chunks",
    )
    parser.add_argument(
        "--embeddings-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_embeddings",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_indexes",
    )
    args = parser.parse_args()

    load_dotenv(MODULE_ROOT / ".env")
    qdrant_url = required_environment("QDRANT_URL")
    qdrant_api_key = required_environment("QDRANT_API_KEY")
    collection = required_environment("QDRANT_COLLECTION")

    chunks_path = args.chunks_root / args.document_id / "chunks.json"
    embedding_dir = args.embeddings_root / args.document_id
    embeddings_path = embedding_dir / "embeddings.jsonl"
    embedding_manifest_path = embedding_dir / "embedding_manifest.json"
    for path in (chunks_path, embeddings_path, embedding_manifest_path):
        if not path.is_file():
            parser.error(f"Missing Phase 3B input: {path}")

    try:
        prepared = prepare_document_index(
            chunks_path,
            embeddings_path,
            embedding_manifest_path,
        )
        client = QdrantClient(
            url=qdrant_url,
            api_key=qdrant_api_key,
            prefer_grpc=False,
            timeout=60,
        )
        service = QdrantIndexService(client, collection)
        pipeline = QdrantIndexPipeline(service, args.output_root, log=print)
        manifest = pipeline.run(prepared)
    except Exception as exc:
        print(f"Qdrant indexing failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0 if manifest["status"] == "SUCCESS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
