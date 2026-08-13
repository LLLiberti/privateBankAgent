from models import DocumentBlock, DocumentMetadata, ParsedDocument
from normalization import DocumentNormalizer, HeadingNormalizer, ProductTableNormalizer, TextNormalizer


def test_heading_levels_and_conservative_clause() -> None:
    normalizer = HeadingNormalizer()
    assert normalizer.classify("一、产品概述").level == 1
    assert normalizer.classify("（二）产品的申购").level == 2
    assert not normalizer.classify("1.申购原则").is_heading
    assert normalizer.classify("1.申购原则", parser_heading=True).level == 3


def _document(blocks: list[DocumentBlock]) -> ParsedDocument:
    metadata = DocumentMetadata("D000001", "x.pdf", "a" * 64, "pdf", "P0", "23GS2000", "23G2000B", "产品", 3)
    return ParsedDocument(metadata, blocks)


def test_all_exact_root_titles_reset_section_stack() -> None:
    root_titles = [
        "风险揭示书",
        "理财产品说明书",
        "工银理财有限责任公司个人理财产品协议书",
        "客户权益须知",
        "快速赎回服务协议",
        "工银理财现金管理类理财产品 快速赎回服务协议",
        "理财产品投资协议书",
    ]
    for root_title in root_titles:
        document = _document([
            DocumentBlock("D000001", "heading", "理财产品说明书", 1, 1, parser_heading=True),
            DocumentBlock("D000001", "heading", "十三、咨询（投诉）电话", 1, 1, parser_heading=True),
            DocumentBlock("D000001", "heading", root_title, 2, 2, parser_heading=True),
            DocumentBlock("D000001", "heading", "一、重要提示", 2, 2, parser_heading=True),
        ])
        DocumentNormalizer().normalize(document)
        assert document.blocks[2].section_path == [root_title]
        assert document.blocks[3].section_path == [root_title, "一、重要提示"]


def test_long_numbered_heading_is_split_without_losing_text() -> None:
    source = "（一）政策风险。本产品在实际运作过程中，由于国家宏观政策以及相关法律法规发生变化，可能导致投资者本金及收益遭受损失。"
    provenance = [{"page": 2, "bbox": {"l": 10, "t": 20, "r": 30, "b": 5}}]
    document = _document([
        DocumentBlock("D000001", "heading", "九、风险揭示", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "heading", source, 2, 2, provenance=provenance, parser_heading=True),
    ])
    DocumentNormalizer().normalize(document)

    assert [block.type for block in document.blocks] == ["heading", "heading", "paragraph"]
    assert document.blocks[1].text == "（一）政策风险。"
    assert document.blocks[2].text == "本产品在实际运作过程中，由于国家宏观政策以及相关法律法规发生变化，可能导致投资者本金及收益遭受损失。"
    assert document.blocks[1].text + document.blocks[2].text == source
    assert document.blocks[2].provenance == provenance
    assert source not in document.blocks[1].section_path
    assert document.blocks[2].section_path == ["九、风险揭示", "（一）政策风险。"]


def test_unreliable_long_numbered_heading_degrades_to_paragraph() -> None:
    source = "一、本协议仅适用于甲方向乙方购买的单笔个人理财产品并与其他销售文件共同构成完整协议且双方均应严格遵照执行"
    document = _document([
        DocumentBlock("D000001", "heading", source, 1, 1, parser_heading=True),
    ])
    DocumentNormalizer().normalize(document)
    assert document.blocks[0].type == "paragraph"
    assert document.blocks[0].text == source
    assert source not in document.blocks[0].section_path


def test_normal_primary_and_secondary_headings_do_not_degrade() -> None:
    normalizer = HeadingNormalizer()
    assert normalizer.classify("三、投资运作").level == 1
    assert normalizer.classify("（一）投资范围").level == 2
    assert normalizer.classify("五、相关费用").level == 1
    assert normalizer.classify("九、风险揭示").level == 1


def test_adjacent_numeric_siblings_do_not_drift_on_parser_heading_alone() -> None:
    document = _document([
        DocumentBlock("D000001", "heading", "（二）产品的申购", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "list", "（1）金额申购原则", 1, 1),
        DocumentBlock("D000001", "heading", "（2）未知价原则", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "list", "（3）份额确认原则", 1, 1),
        DocumentBlock("D000001", "list", "（4）资金扣款原则", 1, 1),
    ])
    issues = DocumentNormalizer().normalize(document)
    siblings = document.blocks[1:]
    assert [block.type for block in siblings] == ["list", "list", "list", "list"]
    assert all(block.section_path == ["（二）产品的申购"] for block in siblings)
    assert not any(issue.block_id == siblings[1].block_id for issue in issues)


def test_full_fast_redemption_title_resets_root_and_keeps_numeric_heading() -> None:
    root_title = "工银理财现金管理类理财产品 快速赎回服务协议"
    document = _document([
        DocumentBlock("D000001", "heading", "理财产品投资协议书", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "heading", "四、快速赎回服务协议", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "heading", root_title, 2, 2, parser_heading=True),
        DocumentBlock("D000001", "heading", "1、定义", 2, 2, parser_heading=True),
        DocumentBlock("D000001", "paragraph", "本协议所称投资者，是指申请快速赎回服务的份额持有人。", 2, 2),
    ])
    DocumentNormalizer().normalize(document)

    assert document.blocks[2].section_path == [root_title]
    assert document.blocks[3].type == "heading"
    assert document.blocks[3].level == 3
    assert document.blocks[3].section_path == [root_title, "1、定义"]
    assert document.blocks[4].section_path == [root_title, "1、定义"]


def test_continuous_decimal_body_clauses_do_not_pollute_section_path() -> None:
    parent = "（二）乙方的权利义务"
    document = _document([
        DocumentBlock("D000001", "heading", parent, 1, 1, parser_heading=True),
        DocumentBlock("D000001", "paragraph", "1.甲方依照销售文件约定办理相关业务。", 1, 1),
        DocumentBlock(
            "D000001",
            "heading",
            "2.乙方不对任何理财产品的收益情况做出承诺或保证，亦不会承诺或保证最低收益或本金安全。",
            1,
            1,
            parser_heading=True,
        ),
        DocumentBlock("D000001", "paragraph", "3.乙方依照理财产品销售文件进行投资，并履行约定义务。", 1, 1),
        DocumentBlock("D000001", "paragraph", "4.为实现理财产品投资目标，乙方有权进行必要调整。", 1, 1),
    ])
    DocumentNormalizer().normalize(document)

    clauses = document.blocks[1:]
    assert [block.type for block in clauses] == ["paragraph"] * 4
    assert clauses[1].level is None
    assert all(block.section_path == [parent] for block in clauses)


def test_later_arabic_numeric_siblings_end_previous_clause_scope() -> None:
    root = "快速赎回服务协议"
    document = _document([
        DocumentBlock("D000001", "heading", root, 1, 1, parser_heading=True),
        DocumentBlock("D000001", "heading", "7、产品份额及收益归属", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "paragraph", "这里是第7条正文", 1, 1),
        DocumentBlock("D000001", "list", "8、如任何因投资者之原因导致交易失败，责任由投资者承担。", 1, 1),
        DocumentBlock("D000001", "list", "9、其他约定以销售文件为准。", 1, 1),
        DocumentBlock("D000001", "paragraph", "10、本协议未作特别解释的名词术语适用产品说明书。", 1, 1),
    ])
    DocumentNormalizer().normalize(document)

    assert document.blocks[1].type == "heading"
    assert document.blocks[1].level == 3
    assert document.blocks[2].section_path == [root, "7、产品份额及收益归属"]
    assert all(block.section_path == [root] for block in document.blocks[3:])


def test_parenthesized_definition_items_keep_numeric_heading_scope() -> None:
    root = "快速赎回服务协议"
    document = _document([
        DocumentBlock("D000001", "heading", root, 1, 1, parser_heading=True),
        DocumentBlock("D000001", "heading", "1、定义", 1, 1, parser_heading=True),
        DocumentBlock("D000001", "list", "（1）投资者：指申请快速赎回服务的份额持有人。", 1, 1),
        DocumentBlock("D000001", "list", "（2）服务提供方：指提供快速赎回服务的相关方。", 1, 1),
    ])
    DocumentNormalizer().normalize(document)

    expected_path = [root, "1、定义"]
    assert document.blocks[1].type == "heading"
    assert document.blocks[1].level == 3
    assert document.blocks[2].section_path == expected_path
    assert document.blocks[3].section_path == expected_path


def test_text_wrap_merge_preserves_business_values() -> None:
    text = (
        "本产品的风险评级仅是工银理财有限责任\n公司内部测评结果。\n"
        "B份额：首次购买起点金额20万元，\n追加购买起点金额1000元且以1000元为单位递增。\n"
        "生效日为2025年7月24日，费率0.15％，代码23G2000B。"
    )
    result = TextNormalizer().normalize(text)
    assert "有限责任公司内部测评结果" in result
    assert "20万元" in result
    assert "1000元" in result
    assert "2025年7月24日" in result
    assert "0.15％" in result
    assert "23G2000B" in result


def test_table_relationships_are_retained() -> None:
    block = DocumentBlock(
        document_id="D000001",
        type="table",
        text="",
        page_start=4,
        page_end=5,
        rows=[
            ["销售对象", "B份额：私人银行客户"],
            ["购买起点金额", "B份额：首次购买起点金额20万元，\n追加购买起点金额1000元"],
        ],
    )
    issues = ProductTableNormalizer().normalize(block)
    assert not issues
    pairs = {pair["key"]: pair["value"] for pair in block.kv_pairs or []}
    assert pairs["销售对象"] == "B份额：私人银行客户"
    assert "首次购买起点金额20万元" in pairs["购买起点金额"]
    assert "追加购买起点金额1000元" in pairs["购买起点金额"]


def test_document_uses_same_page_geometry_and_binds_issue_to_target() -> None:
    metadata = DocumentMetadata("D000001", "x.pdf", "a" * 64, "pdf", "P0", "23GS2000", "23G2000B", "产品", 1)
    lower = DocumentBlock(
        "D000001", "heading", "重要须知", 1, 1,
        provenance=[{"page": 1, "bbox": {"l": 40, "t": 700, "r": 100, "b": 690}}],
        parser_heading=True,
    )
    upper = DocumentBlock(
        "D000001", "heading", "理财产品说明书", 1, 1,
        provenance=[{"page": 1, "bbox": {"l": 250, "t": 735, "r": 340, "b": 725}}],
        parser_heading=True,
    )
    document = ParsedDocument(metadata, [lower, upper])
    issues = DocumentNormalizer().normalize(document)
    assert [block.text for block in document.blocks] == ["理财产品说明书", "重要须知"]
    assert issues[0].block_id == lower.block_id
