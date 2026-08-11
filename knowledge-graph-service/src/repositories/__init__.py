"""Repository abstractions for external data sources."""

from .mysql_kg_repository import (
    ReadOnlyMySQLReader,
    fetch_all_person_ids,
)

__all__ = ["ReadOnlyMySQLReader", "fetch_all_person_ids"]
