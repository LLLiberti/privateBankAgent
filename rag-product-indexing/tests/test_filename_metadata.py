from pathlib import Path

from parsing.filename_metadata import FilenameMetadataParser


def test_filename_with_distinct_sales_code() -> None:
    metadata = FilenameMetadataParser().parse(
        "D000001_23GS2000_23G2000B_天天鑫添益私银低波红利固收增强_产品说明书.pdf"
    )
    assert metadata.document_id == "D000001"
    assert metadata.product_code == "23GS2000"
    assert metadata.sales_code == "23G2000B"
    assert metadata.product_name_from_filename == "天天鑫添益私银低波红利固收增强"


def test_filename_with_same_sales_code() -> None:
    metadata = FilenameMetadataParser().parse(
        "D000011_21HH3879_两权其美私银550天混合_产品说明书.pdf"
    )
    assert metadata.document_id == "D000011"
    assert metadata.product_code == "21HH3879"
    assert metadata.sales_code == "21HH3879"


def test_registration_keeps_filename_and_sha256(tmp_path: Path) -> None:
    path = tmp_path / "D000011_21HH3879_两权其美私银550天混合_产品说明书.pdf"
    path.write_bytes(b"pdf-test")
    metadata = FilenameMetadataParser().register(path)
    assert metadata.filename == path.name
    assert metadata.file_type == "pdf"
    assert metadata.source_level == "P0"
    assert len(metadata.file_hash) == 64
