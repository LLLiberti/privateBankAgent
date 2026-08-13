from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from elasticsearch.helpers import bulk as elasticsearch_bulk
from elasticsearch.helpers import scan as elasticsearch_scan

from embedding import sha256_file


ELASTICSEARCH_ANALYZER = "cjk"
BULK_BATCH_SIZE = 100
INDEX_PREFIX = "private-bank-"

INDEX_MAPPINGS: dict[str, Any] = {
    "properties": {
        "chunk_id": {"type": "keyword"},
        "document_id": {"type": "keyword"},
        "chunk_type": {"type": "keyword"},
        "content": {"type": "text", "analyzer": ELASTICSEARCH_ANALYZER},
        "section_path": {"type": "keyword"},
        "section_text": {"type": "text", "analyzer": ELASTICSEARCH_ANALYZER},
        "page_start": {"type": "integer"},
        "page_end": {"type": "integer"},
        "source_block_ids": {"type": "keyword"},
        "token_count": {"type": "integer"},
    }
}


class ElasticsearchIndexValidationError(ValueError):
    pass


@dataclass(frozen=True)
class PreparedElasticsearchDocument:
    chunk_id: str
    source: dict[str, Any]


@dataclass(frozen=True)
class PreparedElasticsearchIndex:
    document_id: str
    source_chunk_hash: str
    documents: list[PreparedElasticsearchDocument]


def _load_chunks(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ElasticsearchIndexValidationError(f"cannot read valid chunks.json: {path}") from exc
    if not isinstance(value, dict):
        raise ElasticsearchIndexValidationError("chunks.json must be a JSON object")
    return value


def _require_integer(value: Any, field: str, chunk_id: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ElasticsearchIndexValidationError(
            f"chunk {chunk_id} field {field} must be an integer"
        )
    return value


def _require_string_list(value: Any, field: str, chunk_id: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ElasticsearchIndexValidationError(
            f"chunk {chunk_id} field {field} must be a list of strings"
        )
    return list(value)


def prepare_elasticsearch_index(
    chunks_path: Path | str,
    expected_document_id: str | None = None,
) -> PreparedElasticsearchIndex:
    path = Path(chunks_path)
    payload = _load_chunks(path)
    document_id = str(payload.get("document_id", "")).strip()
    if not document_id:
        raise ElasticsearchIndexValidationError("chunks.json is missing document_id")
    if expected_document_id is not None and document_id != expected_document_id:
        raise ElasticsearchIndexValidationError(
            f"chunks.json document_id {document_id} does not match CLI request {expected_document_id}"
        )
    chunks = payload.get("chunks")
    if not isinstance(chunks, list):
        raise ElasticsearchIndexValidationError("chunks.json chunks must be a list")

    chunk_ids = [
        str(chunk.get("chunk_id", "")).strip() if isinstance(chunk, dict) else ""
        for chunk in chunks
    ]
    duplicates = [
        chunk_id for chunk_id, count in Counter(chunk_ids).items() if chunk_id and count > 1
    ]
    if any(not chunk_id for chunk_id in chunk_ids):
        raise ElasticsearchIndexValidationError("all chunks must have a non-empty chunk_id")
    if duplicates:
        raise ElasticsearchIndexValidationError(f"duplicate chunk_id: {duplicates}")

    documents: list[PreparedElasticsearchDocument] = []
    for chunk, chunk_id in zip(chunks, chunk_ids):
        if not isinstance(chunk, dict):
            raise ElasticsearchIndexValidationError(f"chunk {chunk_id} must be an object")
        chunk_document_id = str(chunk.get("document_id", "")).strip()
        if chunk_document_id != document_id:
            raise ElasticsearchIndexValidationError(
                f"chunk {chunk_id} document_id does not match {document_id}"
            )
        if "content" not in chunk or not isinstance(chunk["content"], str):
            raise ElasticsearchIndexValidationError(
                f"chunk {chunk_id} field content must be a string"
            )
        chunk_type = chunk.get("chunk_type")
        if not isinstance(chunk_type, str) or not chunk_type:
            raise ElasticsearchIndexValidationError(
                f"chunk {chunk_id} field chunk_type must be a non-empty string"
            )
        section_path = _require_string_list(chunk.get("section_path"), "section_path", chunk_id)
        source_block_ids = _require_string_list(
            chunk.get("source_block_ids"), "source_block_ids", chunk_id
        )
        page_start = _require_integer(chunk.get("page_start"), "page_start", chunk_id)
        page_end = _require_integer(chunk.get("page_end"), "page_end", chunk_id)
        token_count = _require_integer(chunk.get("token_count"), "token_count", chunk_id)
        if page_start < 1 or page_end < page_start:
            raise ElasticsearchIndexValidationError(
                f"chunk {chunk_id} has invalid page range {page_start}-{page_end}"
            )
        source = {
            "chunk_id": chunk_id,
            "document_id": document_id,
            "chunk_type": chunk_type,
            "content": chunk["content"],
            "section_path": section_path,
            "section_text": " > ".join(section_path),
            "page_start": page_start,
            "page_end": page_end,
            "source_block_ids": source_block_ids,
            "token_count": token_count,
        }
        documents.append(PreparedElasticsearchDocument(chunk_id, source))
    return PreparedElasticsearchIndex(document_id, sha256_file(path), documents)


def _response_body(value: Any) -> Any:
    return getattr(value, "body", value)


class ElasticsearchIndexService:
    def __init__(
        self,
        client: Any,
        index_name: str,
        bulk_fn: Callable[..., Any] = elasticsearch_bulk,
        scan_fn: Callable[..., Any] = elasticsearch_scan,
    ) -> None:
        if not index_name.startswith(INDEX_PREFIX):
            raise ValueError(f"Elasticsearch index must start with {INDEX_PREFIX}")
        self.client = client
        self.index_name = index_name
        self.bulk_fn = bulk_fn
        self.scan_fn = scan_fn

    def ensure_index(self) -> None:
        if not bool(self.client.indices.exists(index=self.index_name)):
            self.client.indices.create(index=self.index_name, mappings=INDEX_MAPPINGS)
        mapping_response = _response_body(self.client.indices.get_mapping(index=self.index_name))
        try:
            actual = mapping_response[self.index_name]["mappings"]["properties"]
        except (KeyError, TypeError) as exc:
            raise ElasticsearchIndexValidationError(
                f"cannot read mapping for index {self.index_name}"
            ) from exc
        expected = INDEX_MAPPINGS["properties"]
        incompatibilities: list[str] = []
        for field, expected_mapping in expected.items():
            actual_mapping = actual.get(field, {})
            if actual_mapping.get("type") != expected_mapping["type"]:
                incompatibilities.append(
                    f"{field}.type={actual_mapping.get('type')!r}, expected {expected_mapping['type']!r}"
                )
            expected_analyzer = expected_mapping.get("analyzer")
            if expected_analyzer and actual_mapping.get("analyzer") != expected_analyzer:
                incompatibilities.append(
                    f"{field}.analyzer={actual_mapping.get('analyzer')!r}, expected {expected_analyzer!r}"
                )
        if incompatibilities:
            raise ElasticsearchIndexValidationError(
                "incompatible Elasticsearch mapping: " + "; ".join(incompatibilities)
            )

    def delete_document(self, document_id: str) -> int:
        response = self.client.delete_by_query(
            index=self.index_name,
            query={"term": {"document_id": document_id}},
            refresh=True,
            wait_for_completion=True,
            conflicts="proceed",
        )
        body = _response_body(response)
        return int(body.get("deleted", 0))

    def bulk_documents(self, documents: list[PreparedElasticsearchDocument]) -> int:
        actions = [
            {
                "_op_type": "index",
                "_index": self.index_name,
                "_id": document.chunk_id,
                "_source": document.source,
            }
            for document in documents
        ]
        succeeded, errors = self.bulk_fn(
            self.client,
            actions,
            raise_on_error=False,
            raise_on_exception=True,
        )
        if errors:
            raise RuntimeError(f"Elasticsearch bulk returned {len(errors)} failed actions")
        if int(succeeded) != len(documents):
            raise RuntimeError(
                f"Elasticsearch bulk indexed {succeeded} of {len(documents)} documents"
            )
        return int(succeeded)

    def refresh(self) -> None:
        self.client.indices.refresh(index=self.index_name)

    def count_document(self, document_id: str) -> int:
        response = self.client.count(
            index=self.index_name,
            query={"term": {"document_id": document_id}},
        )
        return int(_response_body(response).get("count", 0))

    def scan_document(self, document_id: str) -> list[dict[str, Any]]:
        return list(
            self.scan_fn(
                self.client,
                index=self.index_name,
                query={"query": {"term": {"document_id": document_id}}},
                preserve_order=False,
            )
        )


class ElasticsearchIndexPipeline:
    def __init__(
        self,
        service: ElasticsearchIndexService,
        output_root: Path | str,
        batch_size: int = BULK_BATCH_SIZE,
        log: Callable[[str], None] | None = None,
    ) -> None:
        if batch_size <= 0:
            raise ValueError("batch_size must be positive")
        self.service = service
        self.output_root = Path(output_root)
        self.batch_size = batch_size
        self.log = log or (lambda _: None)

    def run(self, prepared: PreparedElasticsearchIndex) -> dict[str, Any]:
        output_dir = self.output_root / prepared.document_id
        output_dir.mkdir(parents=True, exist_ok=True)
        manifest_path = output_dir / "elasticsearch_manifest.json"
        manifest = self._manifest(prepared)
        expected = {document.chunk_id: document.source for document in prepared.documents}
        try:
            self.service.ensure_index()
            self.log(f"document_id={prepared.document_id}")
            self.log(f"index={self.service.index_name}")
            self.log(f"local chunk count={len(prepared.documents)}")
            deleted = self.service.delete_document(prepared.document_id)
            self.log(f"delete old document count={deleted}")
            total_batches = (len(prepared.documents) + self.batch_size - 1) // self.batch_size
            for offset in range(0, len(prepared.documents), self.batch_size):
                batch_number = offset // self.batch_size + 1
                self.log(f"bulk batch {batch_number}/{total_batches}")
                self.service.bulk_documents(
                    prepared.documents[offset : offset + self.batch_size]
                )
            self.service.refresh()
            indexed_count = self.service.count_document(prepared.document_id)
            hits = self.service.scan_document(prepared.document_id)
            actual_ids = [str(hit.get("_id", "")) for hit in hits]
            actual_id_set = set(actual_ids)
            expected_id_set = set(expected)
            missing = expected_id_set - actual_id_set
            extra = actual_id_set - expected_id_set
            mismatch = 0
            for hit in hits:
                hit_id = str(hit.get("_id", ""))
                if hit_id in expected and hit.get("_source") != expected[hit_id]:
                    mismatch += 1
            duplicate_remote = len(actual_ids) - len(actual_id_set)
            mismatch += duplicate_remote
            status = (
                "SUCCESS"
                if indexed_count == len(prepared.documents)
                and not missing
                and not extra
                and mismatch == 0
                else "FAILED"
            )
            manifest.update(
                {
                    "indexed_document_count": indexed_count,
                    "missing_count": len(missing),
                    "extra_count": len(extra),
                    "mismatch_count": mismatch,
                    "status": status,
                }
            )
            self.log(f"final ES document count={indexed_count}")
            self.log(
                f"missing={len(missing)} extra={len(extra)} mismatch={mismatch}"
            )
            self.log(f"status={status}")
            self._write_manifest(manifest_path, manifest)
            return manifest
        except Exception as exc:
            manifest["error"] = f"{type(exc).__name__}: {exc}"
            self._write_manifest(manifest_path, manifest)
            raise

    def _manifest(self, prepared: PreparedElasticsearchIndex) -> dict[str, Any]:
        return {
            "document_id": prepared.document_id,
            "index_name": self.service.index_name,
            "source_chunk_hash": prepared.source_chunk_hash,
            "expected_document_count": len(prepared.documents),
            "indexed_document_count": 0,
            "missing_count": len(prepared.documents),
            "extra_count": 0,
            "mismatch_count": 0,
            "analyzer": ELASTICSEARCH_ANALYZER,
            "status": "FAILED",
        }

    @staticmethod
    def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, allow_nan=False),
            encoding="utf-8",
        )
        temporary.replace(path)
