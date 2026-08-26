from __future__ import annotations

from typing import Any

from scripts import index_product_elasticsearch


def test_elasticsearch_client_disables_tls_identity_verification(monkeypatch) -> None:
    captured: dict[str, Any] = {}

    class FakeElasticsearch:
        def __init__(self, url: str, **kwargs: Any) -> None:
            captured["url"] = url
            captured.update(kwargs)

    monkeypatch.setattr(
        index_product_elasticsearch,
        "Elasticsearch",
        FakeElasticsearch,
    )

    client = index_product_elasticsearch.create_elasticsearch_client(
        "https://es.example.test:9200",
        "api-key",
    )

    assert isinstance(client, FakeElasticsearch)
    assert captured == {
        "url": "https://es.example.test:9200",
        "api_key": "api-key",
        "verify_certs": False,
        "ssl_show_warn": False,
        "request_timeout": 60,
    }
