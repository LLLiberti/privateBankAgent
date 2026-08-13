from __future__ import annotations

import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any, Callable

from .service import BATCH_SIZE, EmbeddingRecord, EmbeddingService, validate_vector


def sha256_file(path: Path | str) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class EmbeddingPipeline:
    def __init__(
        self,
        service: EmbeddingService,
        output_root: Path | str,
        batch_size: int = BATCH_SIZE,
        log: Callable[[str], None] | None = None,
    ) -> None:
        if batch_size <= 0 or batch_size > BATCH_SIZE:
            raise ValueError(f"batch_size must be between 1 and {BATCH_SIZE}")
        self.service = service
        self.output_root = Path(output_root)
        self.batch_size = batch_size
        self.log = log or (lambda _: None)

    def run(self, chunks_path: Path | str) -> dict[str, Any]:
        source_path = Path(chunks_path)
        source_hash = sha256_file(source_path)
        payload = json.loads(source_path.read_text(encoding="utf-8"))
        document_id = str(payload.get("document_id", "")).strip()
        chunks = list(payload.get("chunks", []))
        if not document_id:
            raise ValueError("chunks.json is missing document_id")

        output_dir = self.output_root / document_id
        output_dir.mkdir(parents=True, exist_ok=True)
        jsonl_path = output_dir / "embeddings.jsonl"
        manifest_path = output_dir / "embedding_manifest.json"

        chunk_ids = [str(chunk.get("chunk_id", "")).strip() for chunk in chunks]
        duplicates = [chunk_id for chunk_id, count in Counter(chunk_ids).items() if chunk_id and count > 1]
        invalid_inputs = [
            chunk_id or f"index:{index}"
            for index, (chunk_id, chunk) in enumerate(zip(chunk_ids, chunks))
            if not chunk_id or not str(chunk.get("embedding_text", "")).strip()
        ]
        if duplicates or invalid_inputs:
            manifest = self._manifest(
                document_id=document_id,
                source_hash=source_hash,
                chunk_count=len(chunks),
                records={},
                total_tokens=0,
                batch_count=0,
                status="FAILED",
                duplicate_count=len(duplicates),
                error="invalid or duplicate chunk input",
            )
            self._write_jsonl(jsonl_path, [], [])
            self._write_json(manifest_path, manifest)
            return manifest

        existing, prior_tokens, prior_batches = self._load_reusable_cache(
            jsonl_path, manifest_path, source_hash, set(chunk_ids)
        )
        pending = [chunk for chunk in chunks if chunk["chunk_id"] not in existing]
        self.log(f"Embedding document: {document_id}")
        self.log(f"Chunks: {len(chunks)}")
        self.log(f"Existing valid embeddings: {len(existing)}")
        self.log(f"Pending: {len(pending)}")

        records = dict(existing)
        total_tokens = prior_tokens
        batch_count = prior_batches
        self._write_checkpoint(
            jsonl_path, manifest_path, document_id, source_hash, chunks,
            records, total_tokens, batch_count, "IN_PROGRESS"
        )

        total_pending_batches = (len(pending) + self.batch_size - 1) // self.batch_size
        try:
            for offset in range(0, len(pending), self.batch_size):
                batch = pending[offset : offset + self.batch_size]
                batch_number = offset // self.batch_size + 1
                self.log(f"Batch {batch_number}/{total_pending_batches} ...")
                result = self.service.embed_batch(batch)
                for record in result.records:
                    records[record.chunk_id] = record
                total_tokens += result.total_tokens
                batch_count += 1
                self._write_checkpoint(
                    jsonl_path, manifest_path, document_id, source_hash, chunks,
                    records, total_tokens, batch_count, "IN_PROGRESS"
                )
        except Exception as exc:
            manifest = self._write_checkpoint(
                jsonl_path, manifest_path, document_id, source_hash, chunks,
                records, total_tokens, batch_count, "FAILED",
                error=f"{type(exc).__name__}: {exc}",
            )
            return manifest

        expected = set(chunk_ids)
        actual = set(records)
        status = "SUCCESS" if expected == actual and len(records) == len(chunks) else "FAILED"
        manifest = self._write_checkpoint(
            jsonl_path, manifest_path, document_id, source_hash, chunks,
            records, total_tokens, batch_count, status,
        )
        return manifest

    def _load_reusable_cache(
        self,
        jsonl_path: Path,
        manifest_path: Path,
        source_hash: str,
        expected_ids: set[str],
    ) -> tuple[dict[str, EmbeddingRecord], int, int]:
        if not jsonl_path.is_file() or not manifest_path.is_file():
            return {}, 0, 0
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}, 0, 0
        if (
            manifest.get("model") != self.service.model
            or manifest.get("dimensions") != self.service.dimensions
            or manifest.get("source_chunk_hash") != source_hash
        ):
            return {}, 0, 0

        candidates: dict[str, EmbeddingRecord] = {}
        duplicate_ids: set[str] = set()
        try:
            lines = jsonl_path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return {}, 0, 0
        for line in lines:
            try:
                value = json.loads(line)
                chunk_id = str(value.get("chunk_id", "")).strip()
                if not chunk_id or chunk_id not in expected_ids:
                    continue
                if chunk_id in candidates:
                    duplicate_ids.add(chunk_id)
                    continue
                candidates[chunk_id] = EmbeddingRecord(
                    chunk_id,
                    validate_vector(value.get("vector"), self.service.dimensions),
                )
            except (json.JSONDecodeError, AttributeError, TypeError, ValueError):
                continue
        for chunk_id in duplicate_ids:
            candidates.pop(chunk_id, None)
        total_tokens = manifest.get("total_tokens", 0)
        batch_count = manifest.get("batch_count", 0)
        return (
            candidates,
            total_tokens if isinstance(total_tokens, int) and total_tokens >= 0 else 0,
            batch_count if isinstance(batch_count, int) and batch_count >= 0 else 0,
        )

    def _write_checkpoint(
        self,
        jsonl_path: Path,
        manifest_path: Path,
        document_id: str,
        source_hash: str,
        chunks: list[dict[str, Any]],
        records: dict[str, EmbeddingRecord],
        total_tokens: int,
        batch_count: int,
        status: str,
        error: str | None = None,
    ) -> dict[str, Any]:
        chunk_ids = [str(chunk["chunk_id"]) for chunk in chunks]
        ordered_records = [records[chunk_id] for chunk_id in chunk_ids if chunk_id in records]
        self._write_jsonl(jsonl_path, ordered_records, chunk_ids)
        manifest = self._manifest(
            document_id, source_hash, len(chunks), records,
            total_tokens, batch_count, status, error=error,
            expected_ids=set(chunk_ids),
        )
        self._write_json(manifest_path, manifest)
        return manifest

    def _manifest(
        self,
        document_id: str,
        source_hash: str,
        chunk_count: int,
        records: dict[str, EmbeddingRecord],
        total_tokens: int,
        batch_count: int,
        status: str,
        duplicate_count: int = 0,
        error: str | None = None,
        expected_ids: set[str] | None = None,
    ) -> dict[str, Any]:
        expected_ids = expected_ids or set()
        actual_ids = set(records)
        missing = expected_ids - actual_ids
        extra = actual_ids - expected_ids
        manifest: dict[str, Any] = {
            "document_id": document_id,
            "model": self.service.model,
            "dimensions": self.service.dimensions,
            "chunk_count": chunk_count,
            "embedding_count": len(records),
            "total_tokens": total_tokens,
            "batch_count": batch_count,
            "failed_count": len(missing) if expected_ids else max(1, chunk_count - len(records)),
            "source_chunk_hash": source_hash,
            "duplicate_chunk_id_count": duplicate_count,
            "missing_chunk_count": len(missing),
            "extra_chunk_count": len(extra),
            "invalid_dimension_count": 0,
            "invalid_value_count": 0,
            "status": status,
        }
        if status == "SUCCESS":
            manifest["failed_count"] = 0
        if error:
            manifest["error"] = error
        return manifest

    @staticmethod
    def _write_jsonl(path: Path, records: list[EmbeddingRecord], _: list[str]) -> None:
        temporary = path.with_suffix(path.suffix + ".tmp")
        with temporary.open("w", encoding="utf-8", newline="\n") as target:
            for record in records:
                target.write(json.dumps(record.to_dict(), ensure_ascii=False, allow_nan=False) + "\n")
        temporary.replace(path)

    @staticmethod
    def _write_json(path: Path, value: dict[str, Any]) -> None:
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False),
            encoding="utf-8",
        )
        temporary.replace(path)
