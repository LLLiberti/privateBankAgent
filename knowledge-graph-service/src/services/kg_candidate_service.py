"""Application service for MySQL-to-candidate extraction."""

from __future__ import annotations

from typing import Any, Callable, Optional

from src.extraction.structured_mapper import ExtractionInput, ReadFailure, StructuredMapper
from src.models.graph_models import CandidateExtraction
from src.repositories.mysql_kg_repository import (
    ReadOnlyMySQLReader,
    redact_mysql_error,
)


def _default_connection_factory() -> Any:
    # Kept lazy so callers using injected extractors do not need mysql.connector.
    from src.database.mysql_client import get_mysql_connection

    return get_mysql_connection()


class KGCandidateService:
    """Load one person's MySQL scope and map it to graph candidates."""

    def __init__(
        self,
        *,
        connection_factory: Optional[Callable[[], Any]] = None,
        reader_factory: Callable[[Any, int], ReadOnlyMySQLReader] = ReadOnlyMySQLReader,
    ) -> None:
        self.connection_factory = connection_factory or _default_connection_factory
        self.reader_factory = reader_factory

    def extract_candidates_for_person(self, person_id: int) -> CandidateExtraction:
        if isinstance(person_id, bool) or not isinstance(person_id, int) or person_id <= 0:
            raise ValueError("person_id 必须是正整数")

        data = ExtractionInput(person_id=person_id)
        try:
            with self.connection_factory() as connection:
                cursor = connection.cursor(dictionary=True)
                try:
                    data = self.reader_factory(cursor, person_id).read()
                finally:
                    cursor.close()
        except Exception as exc:
            message = redact_mysql_error(f"{type(exc).__name__}: {exc}")
            data.read_failures.append(
                ReadFailure(table_name="database_connection", message=message)
            )
        return StructuredMapper(data).map_candidates()


def extract_candidates_for_person(
    person_id: int,
    connection_factory: Optional[Callable[[], Any]] = None,
) -> CandidateExtraction:
    """Backward-compatible functional entry backed by ``KGCandidateService``."""

    return KGCandidateService(
        connection_factory=connection_factory,
    ).extract_candidates_for_person(person_id)
