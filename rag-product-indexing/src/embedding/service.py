from __future__ import annotations

import math
import time
from dataclasses import dataclass
from typing import Any, Callable, Sequence


BATCH_SIZE = 10
MAX_ATTEMPTS = 3


class EmbeddingValidationError(ValueError):
    pass


@dataclass(frozen=True)
class EmbeddingRecord:
    chunk_id: str
    vector: list[float]

    def to_dict(self) -> dict[str, Any]:
        return {"chunk_id": self.chunk_id, "vector": self.vector}


@dataclass(frozen=True)
class BatchEmbeddingResult:
    records: list[EmbeddingRecord]
    total_tokens: int


def validate_vector(vector: Any, dimensions: int) -> list[float]:
    if not isinstance(vector, (list, tuple)) or len(vector) != dimensions:
        raise EmbeddingValidationError(
            f"embedding vector dimension must be {dimensions}, got "
            f"{len(vector) if isinstance(vector, (list, tuple)) else 'non-list'}"
        )
    values: list[float] = []
    for value in vector:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise EmbeddingValidationError("embedding vector contains a non-numeric value")
        number = float(value)
        if not math.isfinite(number):
            raise EmbeddingValidationError("embedding vector contains NaN or Infinity")
        values.append(number)
    return values


class EmbeddingService:
    def __init__(
        self,
        client: Any,
        model: str,
        dimensions: int,
        max_attempts: int = MAX_ATTEMPTS,
        sleep_fn: Callable[[float], None] = time.sleep,
        retry_base_seconds: float = 1.0,
    ) -> None:
        if dimensions <= 0:
            raise ValueError("dimensions must be positive")
        if max_attempts <= 0:
            raise ValueError("max_attempts must be positive")
        self.client = client
        self.model = model
        self.dimensions = dimensions
        self.max_attempts = max_attempts
        self.sleep_fn = sleep_fn
        self.retry_base_seconds = retry_base_seconds

    def embed_batch(self, chunks: Sequence[dict[str, Any]]) -> BatchEmbeddingResult:
        if not chunks or len(chunks) > BATCH_SIZE:
            raise ValueError(f"batch size must be between 1 and {BATCH_SIZE}")
        chunk_ids = [str(chunk["chunk_id"]) for chunk in chunks]
        if len(set(chunk_ids)) != len(chunk_ids):
            raise EmbeddingValidationError("duplicate chunk_id in embedding batch")
        inputs = [str(chunk.get("embedding_text", "")) for chunk in chunks]
        if any(not value.strip() for value in inputs):
            raise EmbeddingValidationError("embedding_text must not be empty")

        response = self._request_with_retry(inputs)
        data = list(self._field(response, "data", []))
        if len(data) != len(chunks):
            raise EmbeddingValidationError(
                f"embedding API returned {len(data)} items for {len(chunks)} inputs"
            )

        mapped: dict[int, list[float]] = {}
        for item in data:
            index = self._field(item, "index")
            if isinstance(index, bool) or not isinstance(index, int):
                raise EmbeddingValidationError("embedding response index must be an integer")
            if index < 0 or index >= len(chunks) or index in mapped:
                raise EmbeddingValidationError(f"invalid or duplicate embedding response index: {index}")
            mapped[index] = validate_vector(self._field(item, "embedding"), self.dimensions)
        if set(mapped) != set(range(len(chunks))):
            raise EmbeddingValidationError("embedding response indices do not cover the complete batch")

        usage = self._field(response, "usage")
        total_tokens = self._field(usage, "total_tokens") if usage is not None else None
        if isinstance(total_tokens, bool) or not isinstance(total_tokens, int) or total_tokens < 0:
            raise EmbeddingValidationError("embedding API response has invalid usage.total_tokens")
        return BatchEmbeddingResult(
            records=[EmbeddingRecord(chunk_ids[index], mapped[index]) for index in range(len(chunks))],
            total_tokens=total_tokens,
        )

    def _request_with_retry(self, inputs: list[str]) -> Any:
        for attempt in range(1, self.max_attempts + 1):
            try:
                return self.client.embeddings.create(
                    model=self.model,
                    input=inputs,
                    dimensions=self.dimensions,
                    encoding_format="float",
                )
            except Exception as exc:
                if attempt >= self.max_attempts or not self._is_retryable(exc):
                    raise
                self.sleep_fn(self.retry_base_seconds * (2 ** (attempt - 1)))
        raise RuntimeError("unreachable retry state")

    @staticmethod
    def _is_retryable(exc: Exception) -> bool:
        status_code = getattr(exc, "status_code", None)
        if status_code == 429 or (isinstance(status_code, int) and status_code >= 500):
            return True
        if status_code in {400, 401, 403, 404, 422}:
            return False
        exception_name = type(exc).__name__.lower()
        return (
            isinstance(exc, (TimeoutError, ConnectionError, OSError))
            or "timeout" in exception_name
            or "connection" in exception_name
            or "network" in exception_name
        )

    @staticmethod
    def _field(value: Any, name: str, default: Any = None) -> Any:
        if isinstance(value, dict):
            return value.get(name, default)
        return getattr(value, name, default)
