import json
from decimal import Decimal

import pytest

from src.extraction.rule_parser import (
    MaritalStatusConflictError,
    RuleParseError,
    UnknownCurrencyError,
    normalize_date,
    normalize_marital_status,
    parse_employee_count,
    parse_money,
)
from src.models.graph_models import GraphEvent


def test_parse_150_million_cny() -> None:
    parsed = parse_money("1.5亿元人民币")

    assert parsed is not None
    assert parsed.amount == Decimal("150000000")
    assert parsed.amount_unit == "元"
    assert parsed.currency_code == "CNY"
    assert parsed.is_estimated is False
    assert parsed.raw_value == "1.5亿元人民币"


def test_parse_estimated_80_million_hkd() -> None:
    parsed = parse_money("约8000万港元")

    assert parsed is not None
    assert parsed.amount == Decimal("80000000")
    assert parsed.currency_code == "HKD"
    assert parsed.is_estimated is True


def test_unknown_currency_is_not_defaulted_to_cny() -> None:
    with pytest.raises(UnknownCurrencyError):
        parse_money("100万元", currency_code="EUR")


@pytest.mark.parametrize(
    ("raw_value", "expected_value", "expected_precision"),
    [
        ("2025年", "2025", "YEAR"),
        ("2025年6月", "2025-06", "MONTH"),
        ("2025-06-18", "2025-06-18", "DAY"),
    ],
)
def test_date_precision(
    raw_value: str,
    expected_value: str,
    expected_precision: str,
) -> None:
    parsed = normalize_date(raw_value)

    assert parsed is not None
    assert parsed.value == expected_value
    assert parsed.precision == expected_precision
    assert parsed.is_estimated is False


def test_estimated_year_is_not_completed_to_a_day() -> None:
    parsed = normalize_date("约2025年前后")

    assert parsed is not None
    assert parsed.value == "2025"
    assert parsed.precision == "YEAR"
    assert parsed.is_estimated is True


def test_date_range_is_preserved_without_selecting_an_endpoint() -> None:
    parsed = normalize_date("2025年6月至2025年7月")

    assert parsed is not None
    assert parsed.value is None
    assert parsed.precision is None
    assert parsed.raw_date_range == "2025年6月至2025年7月"
    assert parsed.is_estimated is False


def test_invalid_placeholders_do_not_become_zero_or_dates() -> None:
    assert parse_money("未公开") is None
    assert normalize_date("未公开") is None


def test_decimal_is_json_safe_without_precision_loss() -> None:
    event = GraphEvent(
        event_id="event:test",
        event_type="INVESTMENT",
        subject_node_id="person:1",
        properties={"amount": Decimal("150000000.01")},
        source_id="100",
        verification_status="PENDING",
    )

    encoded = json.dumps(event.as_dict(), ensure_ascii=False)

    assert '"150000000.01"' in encoded


@pytest.mark.parametrize(
    ("raw_value", "expected"),
    [
        ("已婚", "MARRIED"),
        ("未婚", "UNMARRIED"),
        ("离异", "DIVORCED"),
        ("丧偶", "WIDOWED"),
        ("MARRIED", "MARRIED"),
        ("UNMARRIED", "UNMARRIED"),
        ("DIVORCED", "DIVORCED"),
        ("WIDOWED", "WIDOWED"),
        ("UNKNOWN", "UNKNOWN"),
        ("mArRiEd", "MARRIED"),
        ("已婚，育有一子", "MARRIED"),
        ("已婚，育有一女", "MARRIED"),
        ("已婚，育有一子一女", "MARRIED"),
        ("已婚，育有三子一女", "MARRIED"),
        ("已婚（丈夫孙飘扬）", "MARRIED"),
        ("婚姻状况：已婚", "MARRIED"),
        ("丧偶，育有一子", "WIDOWED"),
        ("目前为离异状态", "DIVORCED"),
        ("公开资料显示其未婚", "UNMARRIED"),
    ],
)
def test_normalize_marital_status(raw_value: str, expected: str) -> None:
    parsed = normalize_marital_status(raw_value)

    assert parsed is not None
    assert parsed.value == expected
    assert parsed.raw_value == raw_value


@pytest.mark.parametrize(
    "raw_value",
    ["曾离异，后再次已婚", "已婚后离异", "婚姻状态由未婚变为已婚"],
)
def test_conflicting_marital_status_is_rejected(raw_value: str) -> None:
    with pytest.raises(MaritalStatusConflictError):
        normalize_marital_status(raw_value)


@pytest.mark.parametrize("raw_value", ["婚姻情况不详", "家庭稳定", "有妻女", "曾有婚史"])
def test_unknown_marital_status_is_rejected(raw_value: str) -> None:
    with pytest.raises(RuleParseError):
        normalize_marital_status(raw_value)


@pytest.mark.parametrize("raw_value", [None, "", "   "])
def test_empty_marital_status_is_ignored(raw_value) -> None:
    assert normalize_marital_status(raw_value) is None


@pytest.mark.parametrize(
    ("raw_value", "expected"),
    [
        (87412, 87412),
        ("87412", 87412),
        ("87,412", 87412),
        ("  87,412  ", 87412),
        (Decimal("87412"), 87412),
        (Decimal("87412.0"), 87412),
        (0, 0),
    ],
)
def test_parse_employee_count(raw_value, expected: int) -> None:
    parsed = parse_employee_count(raw_value)

    assert parsed == expected
    assert isinstance(parsed, int)


@pytest.mark.parametrize(
    "raw_value",
    [-1, Decimal("-1"), 1.5, Decimal("1.5"), True, False, "1.5", "八万人", "8,74"],
)
def test_invalid_employee_count_is_rejected(raw_value) -> None:
    with pytest.raises(RuleParseError):
        parse_employee_count(raw_value)
