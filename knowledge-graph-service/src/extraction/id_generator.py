"""Centralized, deterministic identifiers for graph candidates."""

from __future__ import annotations

import hashlib
from typing import Any, Iterable, Optional


def _required_text(value: Any, label: str) -> str:
    if value is None or not str(value).strip():
        raise ValueError(f"{label} is required for ID generation")
    return str(value).strip()


def _fingerprint(parts: Iterable[Any], length: int = 24) -> str:
    normalized = "\x1f".join("" if part is None else str(part).strip() for part in parts)
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:length]


def person_node_id(person_id: Any) -> str:
    return f"person:{_required_text(person_id, 'person_id')}"


def enterprise_node_id(enterprise_id: Any) -> str:
    return f"enterprise:{_required_text(enterprise_id, 'enterprise_id')}"


def reference_enterprise_node_id(normalized_name: Any) -> str:
    """Identify a PENDING enterprise reference without inventing enterprise_id."""

    normalized = _required_text(normalized_name, "normalized_name")
    return f"enterprise-reference:{_fingerprint((normalized,))}"


def market_segment_node_id(normalized_name: Any) -> str:
    """Identify a reusable market segment from its normalized source name."""

    normalized = _required_text(normalized_name, "normalized_name")
    return f"market-segment:{_fingerprint((normalized,))}"


def family_profile_node_id(person_id: Any) -> str:
    return f"family-profile:{_required_text(person_id, 'person_id')}"


def family_member_node_id(family_member_id: Any) -> str:
    return f"family-member:{_required_text(family_member_id, 'family_member_id')}"


def organization_node_id(social_organization_id: Any) -> str:
    return f"organization:{_required_text(social_organization_id, 'social_organization_id')}"


def event_id(
    *,
    subject_node_id: str,
    event_type: str,
    event_date: Optional[str],
    source_id: Any,
    source_table: str,
    source_pk: Any,
) -> str:
    """Build a stable event ID from its structured origin and subject.

    ``source_table`` and ``source_pk`` prevent two distinct structured source
    rows from collapsing. ``event_date`` and ``source_id`` stay in the
    signature for caller compatibility but are intentionally excluded from the
    fingerprint: improving parsing precision or correcting provenance metadata
    must not change the ID of the same MySQL record.
    """

    digest = _fingerprint(
        (
            _required_text(subject_node_id, "subject_node_id"),
            _required_text(event_type, "event_type"),
            _required_text(source_table, "source_table"),
            _required_text(source_pk, "source_pk"),
        )
    )
    return f"event:{digest}"


def relation_id(
    *,
    start_node_id: str,
    relation_type: str,
    end_node_id: str,
    source_id: Any,
    source_table: str,
    source_pk: Any,
) -> str:
    digest = _fingerprint(
        (
            _required_text(start_node_id, "start_node_id"),
            _required_text(relation_type, "relation_type"),
            _required_text(end_node_id, "end_node_id"),
            source_id,
            _required_text(source_table, "source_table"),
            _required_text(source_pk, "source_pk"),
        )
    )
    return f"relation:{digest}"


def issue_id(
    *,
    reason: str,
    source_table: Optional[str],
    source_pk: Any,
    field_name: Optional[str],
    discriminator: Any = None,
) -> str:
    digest = _fingerprint((reason, source_table, source_pk, field_name, discriminator))
    return f"issue:{digest}"


def llm_task_id(
    *,
    person_id: Any,
    source_table: str,
    source_pk: Any,
    field_name: str,
    source_id: Any,
    source_text: str,
    prompt_version: str,
    model_name: str,
) -> str:
    """Build a stable cache key for one authorized LLM extraction task."""

    text_digest = hashlib.sha256(source_text.encode("utf-8")).hexdigest()
    digest = _fingerprint(
        (
            person_id,
            source_table,
            source_pk,
            field_name,
            source_id,
            text_digest,
            prompt_version,
            model_name,
        )
    )
    return f"llm-task:{digest}"


def llm_candidate_id(
    *,
    kind: str,
    task_id: str,
    candidate_type: str,
    evidence_text: str,
    discriminator: Any = None,
) -> str:
    """Build a stable local candidate ID; this is never supplied by the model."""

    digest = _fingerprint(
        (kind, task_id, candidate_type, evidence_text, discriminator)
    )
    return f"llm-{kind.lower()}:{digest}"
