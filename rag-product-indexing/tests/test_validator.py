from models import DocumentBlock, DocumentMetadata, ParsedDocument
from validation import ProductDocumentValidator


def metadata(**overrides: object) -> DocumentMetadata:
    values = dict(
        document_id="D000001",
        filename="x.pdf",
        file_hash="a" * 64,
        file_type="pdf",
        source_level="P0",
        product_code="23GS2000",
        sales_code="23G2000B",
        product_name_from_filename="产品",
        page_count=1,
    )
    values.update(overrides)
    return DocumentMetadata(**values)


def block(text: str, kv_pairs: list[dict[str, str]] | None = None) -> DocumentBlock:
    return DocumentBlock("D000001", "paragraph", text, 1, 1, kv_pairs=kv_pairs)


def reasons(document: ParsedDocument) -> set[str]:
    return {issue.reason for issue in ProductDocumentValidator().validate(document)}


def test_normal_document_has_no_issues() -> None:
    text = "产品概述 投资运作 相关费用 风险揭示 风险评级 销售对象 产品代码23GS2000 销售代码23G2000B"
    assert reasons(ParsedDocument(metadata(), [block(text)])) == set()


def test_missing_identity_fields_are_errors() -> None:
    result = ProductDocumentValidator().validate(ParsedDocument(metadata(document_id="", product_code=""), [block("正文")]))
    assert {issue.reason for issue in result} >= {"MISSING_DOCUMENT_ID", "MISSING_PRODUCT_CODE"}
    assert all(issue.severity == "ERROR" for issue in result if issue.reason.startswith("MISSING_"))


def test_empty_blocks_is_error() -> None:
    assert "EMPTY_BLOCKS" in reasons(ParsedDocument(metadata(), []))


def test_missing_important_content_is_warning() -> None:
    result = ProductDocumentValidator().validate(ParsedDocument(metadata(), [block("普通正文")]))
    assert any(issue.reason == "IMPORTANT_CONTENT_NOT_FOUND" and issue.severity == "WARNING" for issue in result)


def test_filename_metadata_mismatch() -> None:
    document = ParsedDocument(
        metadata(),
        [
            block(
                "产品概述 投资运作 相关费用 风险揭示 风险评级 销售对象",
                [{"key": "产品代码", "value": "99GS9999"}],
            )
        ],
    )
    assert "FILENAME_METADATA_MISMATCH" in reasons(document)


def test_status_rules() -> None:
    validator = ProductDocumentValidator()
    warning = validator.validate(ParsedDocument(metadata(), [block("普通正文")]))
    error = validator.validate(ParsedDocument(metadata(page_count=0), []))
    assert validator.status([]) == "SUCCESS"
    assert validator.status(warning) == "SUCCESS_WITH_WARNINGS"
    assert validator.status(error) == "FAILED"
