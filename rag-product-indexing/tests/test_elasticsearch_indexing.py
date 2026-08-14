from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from indexing import (
    INDEX_MAPPINGS,
    ElasticsearchIndexPipeline,
    ElasticsearchIndexService,
    ElasticsearchIndexValidationError,
    PreparedElasticsearchDocument,
    PreparedElasticsearchIndex,
    prepare_elasticsearch_index,
)


class FakeIndices:
    def __init__(self, client: "FakeElasticsearchClient") -> None:
        self.client = client

    def exists(self, index: str) -> bool:
        return self.client.exists

    def create(self, index: str, mappings: dict[str, Any]) -> None:
        self.client.operations.append("create_index")
        self.client.exists = True
        self.client.mapping = mappings

    def get_mapping(self, index: str) -> dict[str, Any]:
        return {index: {"mappings": self.client.mapping}}

    def refresh(self, index: str) -> None:
        self.client.operations.append("refresh")


class FakeElasticsearchClient:
    def __init__(
        self,
        exists: bool = False,
        mapping: dict[str, Any] | None = None,
        forced_count: int | None = None,
    ) -> None:
        self.exists = exists
        self.mapping = mapping or INDEX_MAPPINGS
        self.forced_count = forced_count
        self.documents: dict[str, dict[str, Any]] = {}
        self.operations: list[str] = []
        self.indices = FakeIndices(self)

    def delete_by_query(self, query: dict[str, Any], **_: Any) -> dict[str, int]:
        self.operations.append("delete_by_query")
        document_id = query["term"]["document_id"]
        old_ids = [
            doc_id
            for doc_id, source in self.documents.items()
            if source["document_id"] == document_id
        ]
        for doc_id in old_ids:
            del self.documents[doc_id]
        return {"deleted": len(old_ids)}

    def count(self, query: dict[str, Any], **_: Any) -> dict[str, int]:
        if self.forced_count is not None:
            return {"count": self.forced_count}
        document_id = query["term"]["document_id"]
        return {
            "count": sum(
                source["document_id"] == document_id for source in self.documents.values()
            )
        }


def fake_bulk(client: FakeElasticsearchClient, actions: list[dict[str, Any]], **_: Any):
    client.operations.append("bulk")
    for action in actions:
        client.documents[action["_id"]] = action["_source"]
    return len(actions), []


def fake_scan(
    client: FakeElasticsearchClient, query: dict[str, Any], **_: Any
):
    document_id = query["query"]["term"]["document_id"]
    for doc_id, source in client.documents.items():
        if source["document_id"] == document_id:
            yield {"_id": doc_id, "_source": source}


def _chunk(chunk_id: str, document_id: str = "DTEST") -> dict[str, Any]:
    return {
        "chunk_id": chunk_id,
        "document_id": document_id,
        "chunk_type": "text",
        "content": f"content {chunk_id}",
        "embedding_text": "not indexed",
        "section_path": ["理财产品说明书", "产品风险评级"],
        "page_start": 2,
        "page_end": 3,
        "source_block_ids": [f"{document_id}_B0001"],
        "token_count": 20,
    }


def _write_chunks(path: Path, chunks: list[dict[str, Any]]) -> Path:
    path.write_text(
        json.dumps({"document_id": "DTEST", "chunks": chunks}, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def _prepared() -> PreparedElasticsearchIndex:
    source = {
        "chunk_id": "DTEST_C0001",
        "document_id": "DTEST",
        "chunk_type": "text",
        "content": "产品风险评级为PR2",
        "section_path": ["理财产品说明书", "产品风险评级"],
        "section_text": "理财产品说明书 > 产品风险评级",
        "page_start": 1,
        "page_end": 1,
        "source_block_ids": ["DTEST_B0001"],
        "token_count": 12,
    }
    return PreparedElasticsearchIndex(
        "DTEST", "abc", [PreparedElasticsearchDocument("DTEST_C0001", source)]
    )


def _service(client: FakeElasticsearchClient, scan_fn=fake_scan) -> ElasticsearchIndexService:
    return ElasticsearchIndexService(
        client,
        "private-bank-products",
        bulk_fn=fake_bulk,
        scan_fn=scan_fn,
    )


def test_missing_index_is_created_with_explicit_mapping() -> None:
    client = FakeElasticsearchClient(exists=False)

    _service(client).ensure_index()

    assert client.operations == ["create_index"]
    assert client.mapping["properties"]["content"] == {"type": "text", "analyzer": "cjk"}


def test_compatible_existing_mapping_continues() -> None:
    client = FakeElasticsearchClient(exists=True)

    _service(client).ensure_index()

    assert client.operations == []


def test_incompatible_mapping_fails_without_deleting_index() -> None:
    mapping = json.loads(json.dumps(INDEX_MAPPINGS))
    mapping["properties"]["content"]["analyzer"] = "standard"
    client = FakeElasticsearchClient(exists=True, mapping=mapping)

    with pytest.raises(ElasticsearchIndexValidationError, match="incompatible"):
        _service(client).ensure_index()

    assert "delete_by_query" not in client.operations
    assert "delete_index" not in client.operations


def test_duplicate_chunk_id_is_rejected_before_remote_write(tmp_path: Path) -> None:
    chunks = [_chunk("DTEST_C0001"), _chunk("DTEST_C0001")]
    client = FakeElasticsearchClient()

    with pytest.raises(ElasticsearchIndexValidationError, match="duplicate chunk_id"):
        prepare_elasticsearch_index(_write_chunks(tmp_path / "chunks.json", chunks), "DTEST")

    assert client.operations == []


def test_cli_document_id_mismatch_is_rejected(tmp_path: Path) -> None:
    path = _write_chunks(tmp_path / "chunks.json", [_chunk("DTEST_C0001")])

    with pytest.raises(ElasticsearchIndexValidationError, match="CLI request"):
        prepare_elasticsearch_index(path, "DOTHER")


def test_section_text_payload_and_id_are_prepared_from_chunk(tmp_path: Path) -> None:
    prepared = prepare_elasticsearch_index(
        _write_chunks(tmp_path / "chunks.json", [_chunk("DTEST_C0001")]), "DTEST"
    )
    document = prepared.documents[0]

    assert document.chunk_id == "DTEST_C0001"
    assert document.source["section_text"] == "理财产品说明书 > 产品风险评级"
    assert document.source["page_start"] == 2
    assert document.source["source_block_ids"] == ["DTEST_B0001"]
    assert "embedding_text" not in document.source


def test_document_replace_deletes_before_bulk(tmp_path: Path) -> None:
    client = FakeElasticsearchClient(exists=True)
    client.documents["old"] = {"document_id": "DTEST"}

    manifest = ElasticsearchIndexPipeline(_service(client), tmp_path).run(_prepared())

    assert client.operations.index("delete_by_query") < client.operations.index("bulk")
    assert "old" not in client.documents
    assert manifest["status"] == "SUCCESS"


def test_bulk_uses_chunk_id_as_elasticsearch_id(tmp_path: Path) -> None:
    client = FakeElasticsearchClient(exists=True)

    ElasticsearchIndexPipeline(_service(client), tmp_path).run(_prepared())

    assert set(client.documents) == {"DTEST_C0001"}


def test_final_count_mismatch_returns_failed(tmp_path: Path) -> None:
    client = FakeElasticsearchClient(exists=True, forced_count=0)

    manifest = ElasticsearchIndexPipeline(_service(client), tmp_path).run(_prepared())

    assert manifest["indexed_document_count"] == 0
    assert manifest["status"] == "FAILED"


def test_missing_extra_and_metadata_mismatch_are_detected(tmp_path: Path) -> None:
    client = FakeElasticsearchClient(exists=True)
    prepared = _prepared()
    second_source = {
        **prepared.documents[0].source,
        "chunk_id": "DTEST_C0002",
        "content": "second",
    }
    prepared = PreparedElasticsearchIndex(
        "DTEST",
        "abc",
        prepared.documents
        + [PreparedElasticsearchDocument("DTEST_C0002", second_source)],
    )

    def inconsistent_scan(*_: Any, **__: Any):
        yield {
            "_id": "DTEST_C0001",
            "_source": {**prepared.documents[0].source, "token_count": 999},
        }
        yield {"_id": "DTEST_EXTRA", "_source": {"document_id": "DTEST"}}

    manifest = ElasticsearchIndexPipeline(
        _service(client, inconsistent_scan), tmp_path
    ).run(prepared)

    assert manifest["missing_count"] == 1
    assert manifest["extra_count"] == 1
    assert manifest["mismatch_count"] == 1
    assert manifest["status"] == "FAILED"


def test_normal_flow_is_success(tmp_path: Path) -> None:
    client = FakeElasticsearchClient(exists=True)

    manifest = ElasticsearchIndexPipeline(_service(client), tmp_path).run(_prepared())

    assert manifest == {
        "document_id": "DTEST",
        "index_name": "private-bank-products",
        "source_chunk_hash": "abc",
        "expected_document_count": 1,
        "indexed_document_count": 1,
        "missing_count": 0,
        "extra_count": 0,
        "mismatch_count": 0,
        "analyzer": "cjk",
        "status": "SUCCESS",
    }
