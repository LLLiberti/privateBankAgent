from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from embedding import sha256_file
from indexing import (
    PreparedDocumentIndex,
    PreparedPoint,
    QdrantIndexPipeline,
    QdrantIndexService,
    QdrantIndexValidationError,
    deterministic_point_id,
    prepare_document_index,
)


DIMENSIONS = 1024


class FakeQdrantClient:
    def __init__(
        self,
        exists: bool = False,
        size: int = DIMENSIONS,
        distance: str = "Cosine",
        forced_count: int | None = None,
    ) -> None:
        self.exists = exists
        self.size = size
        self.distance = distance
        self.forced_count = forced_count
        self.payload_schema: dict[str, Any] = {}
        self.points: dict[str, Any] = {}
        self.operations: list[str] = []

    def collection_exists(self, _: str) -> bool:
        return self.exists

    def create_collection(self, collection_name: str, vectors_config: Any) -> None:
        self.operations.append("create_collection")
        self.exists = True
        self.size = vectors_config.size
        self.distance = vectors_config.distance.value

    def get_collection(self, _: str) -> Any:
        vectors = SimpleNamespace(size=self.size, distance=self.distance)
        return SimpleNamespace(
            config=SimpleNamespace(params=SimpleNamespace(vectors=vectors)),
            payload_schema=self.payload_schema,
        )

    def create_payload_index(self, **_: Any) -> None:
        self.operations.append("create_payload_index")
        self.payload_schema["document_id"] = SimpleNamespace(data_type="Keyword")

    def delete(self, points_selector: Any, **_: Any) -> None:
        self.operations.append("delete")
        document_id = points_selector.filter.must[0].match.value
        self.points = {
            point_id: point
            for point_id, point in self.points.items()
            if point.payload.get("document_id") != document_id
        }

    def upsert(self, points: list[Any], **_: Any) -> None:
        self.operations.append("upsert")
        for point in points:
            self.points[str(point.id)] = point

    def count(self, count_filter: Any, **_: Any) -> Any:
        if self.forced_count is not None:
            return SimpleNamespace(count=self.forced_count)
        document_id = count_filter.must[0].match.value
        count = sum(
            point.payload.get("document_id") == document_id for point in self.points.values()
        )
        return SimpleNamespace(count=count)

    def scroll(self, scroll_filter: Any, **_: Any) -> tuple[list[Any], None]:
        document_id = scroll_filter.must[0].match.value
        records = [
            SimpleNamespace(id=point_id, payload=point.payload)
            for point_id, point in self.points.items()
            if point.payload.get("document_id") == document_id
        ]
        return records, None

    def query_points(self, query: list[float], **_: Any) -> Any:
        for point_id, point in self.points.items():
            if list(point.vector) == query:
                return SimpleNamespace(
                    points=[SimpleNamespace(id=point_id, payload=point.payload, score=1.0)]
                )
        return SimpleNamespace(points=[])


def _chunk(chunk_id: str, document_id: str = "DTEST") -> dict[str, Any]:
    return {
        "chunk_id": chunk_id,
        "document_id": document_id,
        "chunk_type": "text",
        "content": f"content {chunk_id}",
        "embedding_text": f"embedding {chunk_id}",
        "section_path": ["section"],
        "page_start": 1,
        "page_end": 2,
        "source_block_ids": [f"{document_id}_B0001"],
        "token_count": 10,
    }


def _write_inputs(
    root: Path,
    chunk_ids: list[str] | None = None,
    embedding_ids: list[str] | None = None,
    manifest_hash: str | None = None,
) -> tuple[Path, Path, Path]:
    chunk_ids = chunk_ids or ["DTEST_C0001", "DTEST_C0002"]
    embedding_ids = embedding_ids or list(chunk_ids)
    chunks_path = root / "chunks.json"
    chunks_path.write_text(
        json.dumps(
            {"document_id": "DTEST", "chunks": [_chunk(item) for item in chunk_ids]},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    embeddings_path = root / "embeddings.jsonl"
    embeddings_path.write_text(
        "\n".join(
            json.dumps({"chunk_id": chunk_id, "vector": [float(index + 1)] * DIMENSIONS})
            for index, chunk_id in enumerate(embedding_ids)
        )
        + "\n",
        encoding="utf-8",
    )
    manifest_path = root / "embedding_manifest.json"
    manifest_path.write_text(
        json.dumps(
            {
                "document_id": "DTEST",
                "status": "SUCCESS",
                "dimensions": DIMENSIONS,
                "chunk_count": len(chunk_ids),
                "embedding_count": len(embedding_ids),
                "source_chunk_hash": manifest_hash or sha256_file(chunks_path),
            }
        ),
        encoding="utf-8",
    )
    return chunks_path, embeddings_path, manifest_path


def _prepared() -> PreparedDocumentIndex:
    points = [
        PreparedPoint(
            deterministic_point_id("DTEST_C0001"),
            [1.0] * DIMENSIONS,
            {
                "chunk_id": "DTEST_C0001",
                "document_id": "DTEST",
                "chunk_type": "text",
                "content": "content",
                "section_path": ["section"],
                "page_start": 1,
                "page_end": 1,
                "source_block_ids": ["DTEST_B0001"],
                "token_count": 10,
            },
        )
    ]
    return PreparedDocumentIndex("DTEST", "abc", points)


def test_collection_is_created_with_expected_configuration() -> None:
    client = FakeQdrantClient(exists=False)
    service = QdrantIndexService(client, "products")

    assert service.ensure_collection() == {"size": DIMENSIONS, "distance": "COSINE"}
    assert client.operations == ["create_collection", "create_payload_index"]


def test_matching_existing_collection_continues_and_existing_index_is_skipped() -> None:
    client = FakeQdrantClient(exists=True)
    client.payload_schema["document_id"] = SimpleNamespace(data_type="Keyword")

    QdrantIndexService(client, "products").ensure_collection()

    assert client.operations == []


def test_collection_configuration_mismatch_fails_without_delete() -> None:
    client = FakeQdrantClient(exists=True, size=768)
    service = QdrantIndexService(client, "products")

    with pytest.raises(QdrantIndexValidationError, match="configuration mismatch"):
        service.ensure_collection()
    assert "delete" not in client.operations
    assert "create_collection" not in client.operations


def test_chunk_embedding_set_mismatch_is_rejected_before_remote_write(tmp_path: Path) -> None:
    paths = _write_inputs(tmp_path, embedding_ids=["DTEST_C0001"])
    client = FakeQdrantClient()

    with pytest.raises(QdrantIndexValidationError, match="ID sets differ"):
        prepare_document_index(*paths)
    assert client.operations == []


def test_source_chunk_hash_mismatch_is_rejected(tmp_path: Path) -> None:
    paths = _write_inputs(tmp_path, manifest_hash="wrong")

    with pytest.raises(QdrantIndexValidationError, match="source_chunk_hash"):
        prepare_document_index(*paths)


def test_duplicate_embedding_id_is_rejected(tmp_path: Path) -> None:
    paths = _write_inputs(
        tmp_path,
        embedding_ids=["DTEST_C0001", "DTEST_C0001", "DTEST_C0002"],
    )
    manifest = json.loads(paths[2].read_text(encoding="utf-8"))
    manifest["embedding_count"] = 2
    paths[2].write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(QdrantIndexValidationError, match="duplicate embedding"):
        prepare_document_index(*paths)


def test_non_finite_vector_is_rejected(tmp_path: Path) -> None:
    paths = _write_inputs(tmp_path)
    records = paths[1].read_text(encoding="utf-8").splitlines()
    bad = json.loads(records[0])
    bad["vector"][0] = float("nan")
    records[0] = json.dumps(bad)
    paths[1].write_text("\n".join(records) + "\n", encoding="utf-8")

    with pytest.raises(QdrantIndexValidationError, match="NaN or Infinity"):
        prepare_document_index(*paths)


def test_deterministic_point_id_is_stable_and_distinct() -> None:
    first = deterministic_point_id("D000001_C0001")

    assert first == deterministic_point_id("D000001_C0001")
    assert first != deterministic_point_id("D000001_C0002")


def test_prepared_payload_matches_chunk_and_excludes_embedding_text(tmp_path: Path) -> None:
    prepared = prepare_document_index(*_write_inputs(tmp_path))
    payload = prepared.points[0].payload

    assert payload["chunk_id"] == "DTEST_C0001"
    assert payload["section_path"] == ["section"]
    assert payload["page_start"] == 1
    assert payload["source_block_ids"] == ["DTEST_B0001"]
    assert "embedding_text" not in payload


def test_document_replace_deletes_before_upsert(tmp_path: Path) -> None:
    client = FakeQdrantClient(exists=True)
    service = QdrantIndexService(client, "products")
    manifest = QdrantIndexPipeline(service, tmp_path).run(_prepared())

    assert client.operations.index("delete") < client.operations.index("upsert")
    assert manifest["status"] == "SUCCESS"


def test_final_point_count_mismatch_returns_failed_manifest(tmp_path: Path) -> None:
    client = FakeQdrantClient(exists=True, forced_count=0)
    service = QdrantIndexService(client, "products")

    manifest = QdrantIndexPipeline(service, tmp_path).run(_prepared())

    assert manifest["status"] == "FAILED"
    assert manifest["indexed_point_count"] == 0


def test_normal_pipeline_success_and_self_search(tmp_path: Path) -> None:
    client = FakeQdrantClient(exists=True)
    service = QdrantIndexService(client, "products")
    prepared = _prepared()

    manifest = QdrantIndexPipeline(service, tmp_path).run(prepared)
    top = service.self_search(prepared.points[0].vector)

    assert manifest["expected_point_count"] == 1
    assert manifest["indexed_point_count"] == 1
    assert manifest["missing_count"] == 0
    assert manifest["extra_count"] == 0
    assert manifest["payload_mismatch_count"] == 0
    assert manifest["status"] == "SUCCESS"
    assert top.payload["chunk_id"] == "DTEST_C0001"
