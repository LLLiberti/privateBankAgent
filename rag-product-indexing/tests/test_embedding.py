from __future__ import annotations

import json
from math import inf, nan
from pathlib import Path
from types import SimpleNamespace

import pytest

from embedding import (
    EmbeddingPipeline,
    EmbeddingService,
    EmbeddingValidationError,
    sha256_file,
    validate_vector,
)


class FakeEmbeddings:
    def __init__(self, dimensions: int = 1024) -> None:
        self.dimensions = dimensions
        self.calls: list[dict[str, object]] = []
        self.failures: list[Exception] = []
        self.short_response = False
        self.bad_dimension = False

    def create(self, **kwargs: object) -> SimpleNamespace:
        self.calls.append(kwargs)
        if self.failures:
            raise self.failures.pop(0)
        inputs = list(kwargs["input"])  # type: ignore[arg-type]
        data = []
        for index in reversed(range(len(inputs))):
            marker = float(str(inputs[index]).rsplit("-", 1)[-1])
            length = self.dimensions - 1 if self.bad_dimension else self.dimensions
            data.append(SimpleNamespace(index=index, embedding=[marker] + [0.0] * (length - 1)))
        if self.short_response:
            data.pop()
        return SimpleNamespace(
            data=data,
            usage=SimpleNamespace(total_tokens=len(inputs) * 7),
        )


class FakeClient:
    def __init__(self, embeddings: FakeEmbeddings) -> None:
        self.embeddings = embeddings


class RetryableError(RuntimeError):
    def __init__(self, status_code: int) -> None:
        super().__init__(f"HTTP {status_code}")
        self.status_code = status_code


def _chunks(count: int) -> list[dict[str, str]]:
    return [
        {"chunk_id": f"D000001_C{index + 1:04d}", "embedding_text": f"input-{index + 1}"}
        for index in range(count)
    ]


def _write_chunks(path: Path, count: int) -> Path:
    path.write_text(
        json.dumps({"document_id": "D000001", "chunks": _chunks(count)}, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def _read_jsonl(path: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def test_23_chunks_use_three_batches_and_map_response_indices(tmp_path: Path) -> None:
    api = FakeEmbeddings()
    service = EmbeddingService(FakeClient(api), "text-embedding-v4", 1024)
    chunks_path = _write_chunks(tmp_path / "chunks.json", 23)
    manifest = EmbeddingPipeline(service, tmp_path / "out").run(chunks_path)
    records = _read_jsonl(tmp_path / "out" / "D000001" / "embeddings.jsonl")

    assert [len(call["input"]) for call in api.calls] == [10, 10, 3]  # type: ignore[arg-type]
    assert manifest["status"] == "SUCCESS"
    assert manifest["embedding_count"] == 23
    assert manifest["batch_count"] == 3
    assert manifest["total_tokens"] == 161
    assert [record["chunk_id"] for record in records] == [item["chunk_id"] for item in _chunks(23)]
    assert [record["vector"][0] for record in records] == [float(index) for index in range(1, 24)]  # type: ignore[index]


def test_invalid_vector_dimension_and_short_response_fail_batch() -> None:
    bad_dimension = FakeEmbeddings()
    bad_dimension.bad_dimension = True
    service = EmbeddingService(FakeClient(bad_dimension), "text-embedding-v4", 1024)
    with pytest.raises(EmbeddingValidationError, match="dimension"):
        service.embed_batch(_chunks(2))

    short = FakeEmbeddings()
    short.short_response = True
    service = EmbeddingService(FakeClient(short), "text-embedding-v4", 1024)
    with pytest.raises(EmbeddingValidationError, match="returned 1 items"):
        service.embed_batch(_chunks(2))


@pytest.mark.parametrize("bad_value", [nan, inf, -inf])
def test_nan_and_infinity_vectors_are_rejected(bad_value: float) -> None:
    vector = [0.0] * 1024
    vector[7] = bad_value
    with pytest.raises(EmbeddingValidationError, match="NaN or Infinity"):
        validate_vector(vector, 1024)


def test_duplicate_chunk_ids_are_rejected() -> None:
    service = EmbeddingService(FakeClient(FakeEmbeddings()), "text-embedding-v4", 1024)
    chunks = _chunks(2)
    chunks[1]["chunk_id"] = chunks[0]["chunk_id"]
    with pytest.raises(EmbeddingValidationError, match="duplicate chunk_id"):
        service.embed_batch(chunks)


def test_retryable_error_succeeds_and_exhaustion_fails() -> None:
    sleeps: list[float] = []
    api = FakeEmbeddings()
    api.failures = [RetryableError(429), RetryableError(503)]
    service = EmbeddingService(
        FakeClient(api), "text-embedding-v4", 1024,
        sleep_fn=sleeps.append, retry_base_seconds=0.5,
    )
    result = service.embed_batch(_chunks(1))
    assert len(result.records) == 1
    assert sleeps == [0.5, 1.0]
    assert len(api.calls) == 3

    exhausted = FakeEmbeddings()
    exhausted.failures = [TimeoutError(), TimeoutError(), TimeoutError()]
    service = EmbeddingService(FakeClient(exhausted), "text-embedding-v4", 1024, sleep_fn=lambda _: None)
    with pytest.raises(TimeoutError):
        service.embed_batch(_chunks(1))
    assert len(exhausted.calls) == 3


def test_non_retryable_auth_error_is_not_repeated() -> None:
    api = FakeEmbeddings()
    api.failures = [RetryableError(401)]
    service = EmbeddingService(FakeClient(api), "text-embedding-v4", 1024, sleep_fn=lambda _: None)
    with pytest.raises(RetryableError):
        service.embed_batch(_chunks(1))
    assert len(api.calls) == 1


def test_resume_reuses_valid_records_and_requests_only_missing(tmp_path: Path) -> None:
    chunks_path = _write_chunks(tmp_path / "chunks.json", 13)
    output_root = tmp_path / "out"
    first_api = FakeEmbeddings()
    first = EmbeddingPipeline(
        EmbeddingService(FakeClient(first_api), "text-embedding-v4", 1024), output_root
    ).run(chunks_path)
    assert first["status"] == "SUCCESS"

    jsonl_path = output_root / "D000001" / "embeddings.jsonl"
    jsonl_path.write_text(
        "\n".join(json.dumps(item) for item in _read_jsonl(jsonl_path)[:10]) + "\n",
        encoding="utf-8",
    )
    second_api = FakeEmbeddings()
    second = EmbeddingPipeline(
        EmbeddingService(FakeClient(second_api), "text-embedding-v4", 1024), output_root
    ).run(chunks_path)

    assert len(second_api.calls) == 1
    assert len(second_api.calls[0]["input"]) == 3  # type: ignore[arg-type]
    assert second["embedding_count"] == 13
    assert second["status"] == "SUCCESS"


def test_changed_source_hash_does_not_reuse_old_embeddings(tmp_path: Path) -> None:
    chunks_path = _write_chunks(tmp_path / "chunks.json", 4)
    output_root = tmp_path / "out"
    first_api = FakeEmbeddings()
    EmbeddingPipeline(
        EmbeddingService(FakeClient(first_api), "text-embedding-v4", 1024), output_root
    ).run(chunks_path)
    old_hash = sha256_file(chunks_path)

    payload = json.loads(chunks_path.read_text(encoding="utf-8"))
    payload["chunks"][0]["embedding_text"] = "changed-input-1"
    chunks_path.write_text(json.dumps(payload), encoding="utf-8")
    assert sha256_file(chunks_path) != old_hash

    second_api = FakeEmbeddings()
    manifest = EmbeddingPipeline(
        EmbeddingService(FakeClient(second_api), "text-embedding-v4", 1024), output_root
    ).run(chunks_path)
    assert len(second_api.calls) == 1
    assert len(second_api.calls[0]["input"]) == 4  # type: ignore[arg-type]
    assert manifest["embedding_count"] == 4


def test_malformed_duplicate_and_invalid_cached_records_are_not_reused(tmp_path: Path) -> None:
    chunks_path = _write_chunks(tmp_path / "chunks.json", 4)
    output_root = tmp_path / "out"
    api = FakeEmbeddings()
    EmbeddingPipeline(
        EmbeddingService(FakeClient(api), "text-embedding-v4", 1024), output_root
    ).run(chunks_path)

    output_dir = output_root / "D000001"
    records = _read_jsonl(output_dir / "embeddings.jsonl")
    damaged = [
        records[0],
        records[0],
        {"chunk_id": records[1]["chunk_id"], "vector": [0.0]},
        {"chunk_id": records[2]["chunk_id"], "vector": [nan] + [0.0] * 1023},
    ]
    (output_dir / "embeddings.jsonl").write_text(
        "\n".join([*(json.dumps(item) for item in damaged), "{malformed"]) + "\n",
        encoding="utf-8",
    )

    resume_api = FakeEmbeddings()
    manifest = EmbeddingPipeline(
        EmbeddingService(FakeClient(resume_api), "text-embedding-v4", 1024), output_root
    ).run(chunks_path)
    assert len(resume_api.calls) == 1
    assert len(resume_api.calls[0]["input"]) == 4  # type: ignore[arg-type]
    assert manifest["status"] == "SUCCESS"
    assert manifest["embedding_count"] == 4


def test_retry_exhaustion_produces_failed_manifest(tmp_path: Path) -> None:
    chunks_path = _write_chunks(tmp_path / "chunks.json", 2)
    api = FakeEmbeddings()
    api.failures = [TimeoutError(), TimeoutError(), TimeoutError()]
    manifest = EmbeddingPipeline(
        EmbeddingService(
            FakeClient(api), "text-embedding-v4", 1024,
            sleep_fn=lambda _: None,
        ),
        tmp_path / "out",
    ).run(chunks_path)

    assert len(api.calls) == 3
    assert manifest["status"] == "FAILED"
    assert manifest["failed_count"] == 2
    assert manifest["missing_chunk_count"] == 2


def test_pipeline_failure_manifest_reports_missing_coverage(tmp_path: Path) -> None:
    chunks_path = _write_chunks(tmp_path / "chunks.json", 12)
    api = FakeEmbeddings()
    api.failures = [RetryableError(401)]
    manifest = EmbeddingPipeline(
        EmbeddingService(FakeClient(api), "text-embedding-v4", 1024), tmp_path / "out"
    ).run(chunks_path)

    assert manifest["status"] == "FAILED"
    assert manifest["embedding_count"] == 0
    assert manifest["failed_count"] == 12
    assert manifest["missing_chunk_count"] == 12
