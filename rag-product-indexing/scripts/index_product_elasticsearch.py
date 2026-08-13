from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from elasticsearch import Elasticsearch


MODULE_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = MODULE_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from indexing import (  # noqa: E402
    ElasticsearchIndexPipeline,
    ElasticsearchIndexService,
    prepare_elasticsearch_index,
)


def required_environment(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ValueError(f"Missing environment variable: {name}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Index one product document into Elasticsearch.")
    parser.add_argument("--document-id", required=True)
    parser.add_argument(
        "--chunks-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_chunks",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=MODULE_ROOT / "output" / "document_indexes",
    )
    args = parser.parse_args()

    load_dotenv(MODULE_ROOT / ".env")
    url = required_environment("ELASTICSEARCH_URL")
    api_key = required_environment("ELASTICSEARCH_API_KEY")
    fingerprint = required_environment("ELASTICSEARCH_CA_FINGERPRINT")
    index_name = required_environment("ELASTICSEARCH_INDEX")
    chunks_path = args.chunks_root / args.document_id / "chunks.json"
    if not chunks_path.is_file():
        parser.error(f"Missing Phase 3C input: {chunks_path}")

    try:
        prepared = prepare_elasticsearch_index(chunks_path, args.document_id)
        client = Elasticsearch(
            url,
            api_key=api_key,
            ssl_assert_fingerprint=fingerprint,
            request_timeout=60,
        )
        service = ElasticsearchIndexService(client, index_name)
        manifest = ElasticsearchIndexPipeline(service, args.output_root, log=print).run(
            prepared
        )
    except Exception as exc:
        print(f"Elasticsearch indexing failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0 if manifest["status"] == "SUCCESS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
