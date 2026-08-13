from __future__ import annotations

import json
import uuid
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from qdrant_client import models

from embedding import sha256_file, validate_vector


QDRANT_DIMENSIONS = 1024
QDRANT_DISTANCE = "COSINE"
UPSERT_BATCH_SIZE = 64
_POINT_NAMESPACE = uuid.UUID("82912bcd-f200-54db-9a39-58fe08228bd8")


class QdrantIndexValidationError(ValueError):
    pass


@dataclass(frozen=True)
class PreparedPoint:
    point_id: str
    vector: list[float]
    payload: dict[str, Any]

    @property
    def chunk_id(self) -> str:
        return str(self.payload["chunk_id"])


@dataclass(frozen=True)
class PreparedDocumentIndex:
    document_id: str
    source_chunk_hash: str
    points: list[PreparedPoint]


def deterministic_point_id(chunk_id: str) -> str:
    value = str(chunk_id).strip()
    if not value:
        raise QdrantIndexValidationError("chunk_id must not be empty")
    return str(uuid.uuid5(_POINT_NAMESPACE, value))


def _load_json_object(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise QdrantIndexValidationError(f"cannot read valid {label}: {path}") from exc
    if not isinstance(value, dict):
        raise QdrantIndexValidationError(f"{label} must be a JSON object")
    return value


def _load_embeddings(path: Path, dimensions: int) -> dict[str, list[float]]:
    embeddings: dict[str, list[float]] = {}
    duplicate_ids: set[str] = set()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise QdrantIndexValidationError(f"cannot read embeddings: {path}") from exc
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise QdrantIndexValidationError(
                f"malformed embeddings JSONL at line {line_number}"
            ) from exc
        if not isinstance(value, dict):
            raise QdrantIndexValidationError(
                f"embedding record at line {line_number} must be an object"
            )
        chunk_id = str(value.get("chunk_id", "")).strip()
        if not chunk_id:
            raise QdrantIndexValidationError(
                f"embedding record at line {line_number} is missing chunk_id"
            )
        if chunk_id in embeddings:
            duplicate_ids.add(chunk_id)
            continue
        try:
            embeddings[chunk_id] = validate_vector(value.get("vector"), dimensions)
        except (TypeError, ValueError) as exc:
            raise QdrantIndexValidationError(
                f"invalid vector for embedding {chunk_id}: {exc}"
            ) from exc
    if duplicate_ids:
        raise QdrantIndexValidationError(
            f"duplicate embedding chunk_id: {sorted(duplicate_ids)}"
        )
    return embeddings


def _chunk_payload(chunk: dict[str, Any], document_id: str) -> dict[str, Any]:
    chunk_document_id = str(chunk.get("document_id", "")).strip()
    if chunk_document_id != document_id:
        raise QdrantIndexValidationError(
            f"chunk {chunk.get('chunk_id')} document_id does not match {document_id}"
        )
    required = (
        "chunk_id",
        "chunk_type",
        "content",
        "section_path",
        "page_start",
        "page_end",
        "source_block_ids",
        "token_count",
    )
    missing = [field for field in required if field not in chunk]
    if missing:
        raise QdrantIndexValidationError(
            f"chunk {chunk.get('chunk_id')} is missing payload fields: {missing}"
        )
    if not isinstance(chunk["section_path"], list) or not isinstance(
        chunk["source_block_ids"], list
    ):
        raise QdrantIndexValidationError(
            f"chunk {chunk.get('chunk_id')} has invalid provenance fields"
        )
    return {
        "chunk_id": str(chunk["chunk_id"]),
        "document_id": document_id,
        "chunk_type": chunk["chunk_type"],
        "content": chunk["content"],
        "section_path": list(chunk["section_path"]),
        "page_start": chunk["page_start"],
        "page_end": chunk["page_end"],
        "source_block_ids": list(chunk["source_block_ids"]),
        "token_count": chunk["token_count"],
    }


def prepare_document_index(
    chunks_path: Path | str,
    embeddings_path: Path | str,
    embedding_manifest_path: Path | str,
    dimensions: int = QDRANT_DIMENSIONS,
) -> PreparedDocumentIndex:
    chunks_file = Path(chunks_path)
    embeddings_file = Path(embeddings_path)
    manifest_file = Path(embedding_manifest_path)
    chunks_payload = _load_json_object(chunks_file, "chunks.json")
    manifest = _load_json_object(manifest_file, "embedding manifest")

    document_id = str(chunks_payload.get("document_id", "")).strip()
    if not document_id:
        raise QdrantIndexValidationError("chunks.json is missing document_id")
    if manifest.get("status") != "SUCCESS":
        raise QdrantIndexValidationError("embedding manifest status must be SUCCESS")
    if manifest.get("document_id") != document_id:
        raise QdrantIndexValidationError("embedding manifest document_id does not match chunks.json")
    if manifest.get("dimensions") != dimensions:
        raise QdrantIndexValidationError(
            f"embedding manifest dimensions must be {dimensions}"
        )

    source_hash = sha256_file(chunks_file)
    if manifest.get("source_chunk_hash") != source_hash:
        raise QdrantIndexValidationError("source_chunk_hash does not match current chunks.json")

    chunks = chunks_payload.get("chunks")
    if not isinstance(chunks, list):
        raise QdrantIndexValidationError("chunks.json chunks must be a list")
    chunk_ids = [str(chunk.get("chunk_id", "")).strip() for chunk in chunks]
    empty_ids = [index for index, chunk_id in enumerate(chunk_ids) if not chunk_id]
    duplicates = [
        chunk_id for chunk_id, count in Counter(chunk_ids).items() if chunk_id and count > 1
    ]
    if empty_ids:
        raise QdrantIndexValidationError(f"chunks have empty chunk_id at indexes: {empty_ids}")
    if duplicates:
        raise QdrantIndexValidationError(f"duplicate chunk_id: {duplicates}")

    embeddings = _load_embeddings(embeddings_file, dimensions)
    expected_ids = set(chunk_ids)
    embedding_ids = set(embeddings)
    if expected_ids != embedding_ids:
        missing = sorted(expected_ids - embedding_ids)
        extra = sorted(embedding_ids - expected_ids)
        raise QdrantIndexValidationError(
            f"chunk/embedding ID sets differ; missing={missing}, extra={extra}"
        )
    if manifest.get("chunk_count") != len(chunks) or manifest.get("embedding_count") != len(
        embeddings
    ):
        raise QdrantIndexValidationError("embedding manifest counts do not match local files")

    points: list[PreparedPoint] = []
    for chunk, chunk_id in zip(chunks, chunk_ids):
        payload = _chunk_payload(chunk, document_id)
        points.append(
            PreparedPoint(
                point_id=deterministic_point_id(chunk_id),
                vector=embeddings[chunk_id],
                payload=payload,
            )
        )
    point_ids = [point.point_id for point in points]
    if len(point_ids) != len(set(point_ids)):
        raise QdrantIndexValidationError("deterministic point IDs are not unique")
    return PreparedDocumentIndex(document_id, source_hash, points)


def _field(value: Any, name: str, default: Any = None) -> Any:
    if isinstance(value, dict):
        return value.get(name, default)
    return getattr(value, name, default)


def _enum_name(value: Any) -> str:
    raw = getattr(value, "value", value)
    return str(raw).upper()


def _document_filter(document_id: str) -> models.Filter:
    return models.Filter(
        must=[
            models.FieldCondition(
                key="document_id",
                match=models.MatchValue(value=document_id),
            )
        ]
    )


class QdrantIndexService:
    def __init__(
        self,
        client: Any,
        collection: str,
        dimensions: int = QDRANT_DIMENSIONS,
        distance: str = QDRANT_DISTANCE,
    ) -> None:
        if not collection.strip():
            raise ValueError("Qdrant collection must not be empty")
        if dimensions != QDRANT_DIMENSIONS or distance.upper() != QDRANT_DISTANCE:
            raise ValueError("Phase 3B requires a 1024-dimensional COSINE collection")
        self.client = client
        self.collection = collection
        self.dimensions = dimensions
        self.distance = distance.upper()

    def ensure_collection(self) -> dict[str, Any]:
        if not self.client.collection_exists(self.collection):
            self.client.create_collection(
                collection_name=self.collection,
                vectors_config=models.VectorParams(
                    size=self.dimensions,
                    distance=models.Distance.COSINE,
                ),
            )
        info = self.client.get_collection(self.collection)
        params = _field(_field(_field(info, "config"), "params"), "vectors")
        if isinstance(params, dict):
            raise QdrantIndexValidationError(
                "Qdrant collection uses named vectors; a single 1024/COSINE vector is required"
            )
        size = _field(params, "size")
        distance = _enum_name(_field(params, "distance"))
        if size != self.dimensions or distance != self.distance:
            raise QdrantIndexValidationError(
                "Qdrant collection configuration mismatch: "
                f"expected size={self.dimensions}, distance={self.distance}; "
                f"actual size={size}, distance={distance}"
            )
        self._ensure_document_id_index(info)
        return {"size": size, "distance": distance}

    def _ensure_document_id_index(self, info: Any) -> None:
        schema = _field(info, "payload_schema", {}) or {}
        current = schema.get("document_id") if isinstance(schema, dict) else None
        if current is not None:
            data_type = _enum_name(_field(current, "data_type", current))
            if data_type != "KEYWORD":
                raise QdrantIndexValidationError(
                    f"document_id payload index must be KEYWORD, got {data_type}"
                )
            return
        self.client.create_payload_index(
            collection_name=self.collection,
            field_name="document_id",
            field_schema=models.PayloadSchemaType.KEYWORD,
            wait=True,
        )

    def delete_document(self, document_id: str) -> None:
        self.client.delete(
            collection_name=self.collection,
            points_selector=models.FilterSelector(filter=_document_filter(document_id)),
            wait=True,
        )

    def upsert_points(self, points: list[PreparedPoint]) -> None:
        structs = [
            models.PointStruct(id=point.point_id, vector=point.vector, payload=point.payload)
            for point in points
        ]
        self.client.upsert(
            collection_name=self.collection,
            points=structs,
            wait=True,
        )

    def count_document(self, document_id: str) -> int:
        result = self.client.count(
            collection_name=self.collection,
            count_filter=_document_filter(document_id),
            exact=True,
        )
        return int(_field(result, "count", 0))

    def scroll_document(self, document_id: str) -> list[Any]:
        records: list[Any] = []
        offset: Any = None
        while True:
            batch, next_offset = self.client.scroll(
                collection_name=self.collection,
                scroll_filter=_document_filter(document_id),
                limit=256,
                offset=offset,
                with_payload=True,
                with_vectors=False,
            )
            records.extend(batch)
            if next_offset is None:
                break
            offset = next_offset
        return records

    def self_search(self, vector: list[float]) -> Any | None:
        response = self.client.query_points(
            collection_name=self.collection,
            query=validate_vector(vector, self.dimensions),
            limit=1,
            with_payload=True,
            with_vectors=False,
        )
        points = list(_field(response, "points", []))
        return points[0] if points else None


class QdrantIndexPipeline:
    def __init__(
        self,
        service: QdrantIndexService,
        output_root: Path | str,
        batch_size: int = UPSERT_BATCH_SIZE,
        log: Callable[[str], None] | None = None,
    ) -> None:
        if batch_size <= 0:
            raise ValueError("batch_size must be positive")
        self.service = service
        self.output_root = Path(output_root)
        self.batch_size = batch_size
        self.log = log or (lambda _: None)

    def run(self, prepared: PreparedDocumentIndex) -> dict[str, Any]:
        output_dir = self.output_root / prepared.document_id
        output_dir.mkdir(parents=True, exist_ok=True)
        manifest_path = output_dir / "qdrant_manifest.json"
        expected_ids = {point.point_id for point in prepared.points}
        expected_payloads = {point.point_id: point.payload for point in prepared.points}
        manifest = self._manifest(prepared, status="FAILED")
        try:
            config = self.service.ensure_collection()
            manifest["collection_vector_size"] = config["size"]
            manifest["collection_distance"] = config["distance"]
            self.log(f"document_id={prepared.document_id}")
            self.log(f"collection={self.service.collection}")
            self.log(f"expected points={len(prepared.points)}")
            self.log("delete old points")
            self.service.delete_document(prepared.document_id)

            total_batches = (len(prepared.points) + self.batch_size - 1) // self.batch_size
            for offset in range(0, len(prepared.points), self.batch_size):
                batch_number = offset // self.batch_size + 1
                self.log(f"upsert batch {batch_number}/{total_batches}")
                self.service.upsert_points(prepared.points[offset : offset + self.batch_size])

            indexed_count = self.service.count_document(prepared.document_id)
            records = self.service.scroll_document(prepared.document_id)
            actual_ids = [str(_field(record, "id")) for record in records]
            actual_id_set = set(actual_ids)
            missing = expected_ids - actual_id_set
            extra = actual_id_set - expected_ids
            duplicate_count = len(actual_ids) - len(actual_id_set)
            payload_mismatch = 0
            for record in records:
                point_id = str(_field(record, "id"))
                if point_id in expected_payloads and _field(record, "payload", {}) != expected_payloads[
                    point_id
                ]:
                    payload_mismatch += 1
            status = (
                "SUCCESS"
                if indexed_count == len(prepared.points)
                and not missing
                and not extra
                and duplicate_count == 0
                and payload_mismatch == 0
                else "FAILED"
            )
            manifest.update(
                {
                    "indexed_point_count": indexed_count,
                    "missing_count": len(missing),
                    "extra_count": len(extra),
                    "duplicate_point_id_count": duplicate_count,
                    "payload_mismatch_count": payload_mismatch,
                    "status": status,
                }
            )
            self.log(f"final Qdrant count={indexed_count}")
            self.log(f"status={status}")
            self._write_manifest(manifest_path, manifest)
            return manifest
        except Exception as exc:
            manifest["error"] = f"{type(exc).__name__}: {exc}"
            self._write_manifest(manifest_path, manifest)
            raise

    def _manifest(self, prepared: PreparedDocumentIndex, status: str) -> dict[str, Any]:
        return {
            "document_id": prepared.document_id,
            "collection": self.service.collection,
            "dimensions": self.service.dimensions,
            "distance": self.service.distance,
            "source_chunk_hash": prepared.source_chunk_hash,
            "expected_point_count": len(prepared.points),
            "indexed_point_count": 0,
            "missing_count": len(prepared.points),
            "extra_count": 0,
            "duplicate_point_id_count": 0,
            "payload_mismatch_count": 0,
            "status": status,
        }

    @staticmethod
    def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, allow_nan=False),
            encoding="utf-8",
        )
        temporary.replace(path)
