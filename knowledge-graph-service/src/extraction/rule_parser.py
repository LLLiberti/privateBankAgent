"""Deterministic parsers used by the DIRECT/RULE-only dry-run.

Every parser either returns an explicit result or raises ``RuleParseError``.
No parser fills missing facts from general knowledge.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any, Optional


INVALID_TEXT_VALUES = {
    "",
    "-",
    "--",
    "/",
    "n/a",
    "na",
    "none",
    "null",
    "unknown",
    "未公开",
    "暂无",
    "不详",
    "未知",
    "无数据",
    "未披露",
}

SPECULATION_MARKERS = (
    "推测",
    "可能",
    "据传",
    "传闻",
    "疑似",
    "或许",
    "未证实",
    "网传",
)

STOCK_CODE_PATTERN = re.compile(r"(?<![A-Z0-9])([0-9A-Z]{4,10}\.(?:HK|SH|SZ|US))(?![A-Z0-9])", re.I)


class RuleParseError(ValueError):
    """Raised when a deterministic rule cannot safely parse a value."""


class UnknownCurrencyError(RuleParseError):
    """Raised when an explicit currency cannot be normalized safely."""


class MissingAmountUnitError(RuleParseError):
    """Raised when a numeric amount has no reliable scale/unit."""


class MaritalStatusConflictError(RuleParseError):
    """Raised when one profile value contains multiple marital states."""


@dataclass(frozen=True)
class ParsedDate:
    value: Optional[str]
    precision: Optional[str]
    is_estimated: bool
    raw_value: str
    raw_date_range: Optional[str] = None


@dataclass(frozen=True)
class ParsedMoney:
    amount: Decimal
    amount_unit: str
    is_estimated: bool
    raw_value: str
    currency_code: Optional[str]
    raw_currency: Optional[str] = None


@dataclass(frozen=True)
class ParsedMaritalStatus:
    value: str
    raw_value: str


MARITAL_STATUS_MAP = {
    "已婚": "MARRIED",
    "未婚": "UNMARRIED",
    "离异": "DIVORCED",
    "丧偶": "WIDOWED",
    "MARRIED": "MARRIED",
    "UNMARRIED": "UNMARRIED",
    "DIVORCED": "DIVORCED",
    "WIDOWED": "WIDOWED",
    "UNKNOWN": "UNKNOWN",
}


CURRENCY_ALIASES = {
    "人民币": "CNY",
    "人民币元": "CNY",
    "RMB": "CNY",
    "CNY": "CNY",
    "港元": "HKD",
    "港币": "HKD",
    "HKD": "HKD",
    "美元": "USD",
    "USD": "USD",
}

AMOUNT_UNIT_FACTORS = {
    "元": Decimal("1"),
    "千元": Decimal("1000"),
    "万元": Decimal("10000"),
    "百万元": Decimal("1000000"),
    "千万元": Decimal("10000000"),
    "亿元": Decimal("100000000"),
    # Currency-specific colloquial forms such as “8000万港元”.
    "千": Decimal("1000"),
    "万": Decimal("10000"),
    "百万": Decimal("1000000"),
    "千万": Decimal("10000000"),
    "亿": Decimal("100000000"),
}

ESTIMATE_MARKERS = ("不低于", "大约", "超过", "至少", "约", "近")


def clean_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = re.sub(r"\s+", " ", str(value)).strip()
    if text.lower() in INVALID_TEXT_VALUES:
        return None
    return text


def is_empty_or_invalid(value: Any) -> bool:
    return clean_text(value) is None


def contains_speculation(value: Any) -> bool:
    text = clean_text(value)
    return bool(text and any(marker in text for marker in SPECULATION_MARKERS))


def normalize_marital_status(value: Any) -> Optional[ParsedMaritalStatus]:
    """Normalize only marital state; never extract people or child counts."""

    if value is None:
        return None
    raw_fallback = re.sub(r"\s+", " ", str(value)).strip()
    text = clean_text(value)
    # UNKNOWN is a valid field-specific enum even though the generic text
    # cleaner treats it as a missing-value marker in other contexts.
    if text is None and raw_fallback.upper() == "UNKNOWN":
        text = raw_fallback
    if text is None:
        return None

    exact = MARITAL_STATUS_MAP.get(text.upper())
    if exact is not None:
        return ParsedMaritalStatus(exact, text)

    matches = {
        canonical
        for keyword, canonical in MARITAL_STATUS_MAP.items()
        if keyword in {"已婚", "未婚", "离异", "丧偶"} and keyword in text
    }
    if len(matches) == 1:
        return ParsedMaritalStatus(next(iter(matches)), text)
    if len(matches) > 1:
        raise MaritalStatusConflictError(
            f"multiple marital statuses found in {text!r}"
        )
    raise RuleParseError(f"unknown marital status: {text!r}")


def parse_employee_count(value: Any) -> Optional[int]:
    """Parse an exact non-negative employee count without unit inference."""

    if is_empty_or_invalid(value):
        return None
    if isinstance(value, bool):
        raise RuleParseError("employee_count must not be boolean")
    if isinstance(value, int):
        parsed = value
    elif isinstance(value, Decimal):
        if not value.is_finite() or value != value.to_integral_value():
            raise RuleParseError(f"employee_count is not an integer: {value!r}")
        parsed = int(value)
    elif isinstance(value, str):
        text = value.strip()
        if not re.fullmatch(r"(?:\d+|\d{1,3}(?:,\d{3})+)", text):
            raise RuleParseError(f"cannot parse employee_count from {value!r}")
        parsed = int(text.replace(",", ""))
    else:
        raise RuleParseError(
            f"unsupported employee_count type: {type(value).__name__}"
        )
    if parsed < 0:
        raise RuleParseError(f"employee_count must be non-negative: {parsed}")
    return parsed


def parse_year(value: Any) -> Optional[int]:
    if is_empty_or_invalid(value):
        return None
    if isinstance(value, (date, datetime)):
        return value.year
    if isinstance(value, int):
        year = value
    else:
        match = re.search(r"(?<!\d)(18\d{2}|19\d{2}|20\d{2}|21\d{2})(?!\d)", str(value))
        if not match:
            raise RuleParseError(f"cannot parse year from {value!r}")
        year = int(match.group(1))
    if not 1800 <= year <= 2199:
        raise RuleParseError(f"year out of supported range: {year}")
    return year


def normalize_date(value: Any) -> Optional[ParsedDate]:
    if is_empty_or_invalid(value):
        return None
    if isinstance(value, datetime):
        raw_value = value.isoformat()
        return ParsedDate(value.date().isoformat(), "DAY", False, raw_value)
    if isinstance(value, date):
        raw_value = value.isoformat()
        return ParsedDate(value.isoformat(), "DAY", False, raw_value)

    text = str(value).strip()
    date_token = (
        r"\d{4}(?:年(?:\d{1,2}月(?:\d{1,2}日?)?)?"
        r"|[-/.]\d{1,2}(?:[-/.]\d{1,2})?)"
    )
    range_patterns = (
        rf"{date_token}\s*(?:-|至|到|—|–|~|～)\s*{date_token}",
    )
    if any(re.search(pattern, text) for pattern in range_patterns):
        return ParsedDate(None, None, False, text, raw_date_range=text)

    fuzzy_markers = ("约", "大约", "前后", "左右")
    is_estimated = any(marker in text for marker in fuzzy_markers)
    if is_estimated:
        year_match = re.search(r"(?<!\d)(18\d{2}|19\d{2}|20\d{2}|21\d{2})(?!\d)", text)
        if year_match:
            return ParsedDate(year_match.group(1), "YEAR", True, text)

    for pattern, precision, output in (
        (r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$", "DAY", "{0:04d}-{1:02d}-{2:02d}"),
        (r"^(\d{4})年(\d{1,2})月(\d{1,2})日?$", "DAY", "{0:04d}-{1:02d}-{2:02d}"),
        (r"^(\d{4})[-/.](\d{1,2})$", "MONTH", "{0:04d}-{1:02d}"),
        (r"^(\d{4})年(\d{1,2})月$", "MONTH", "{0:04d}-{1:02d}"),
        (r"^(\d{4})年?[Qq]([1-4])$", "QUARTER", "{0:04d}-Q{1}"),
        (r"^(\d{4})年?$", "YEAR", "{0:04d}"),
    ):
        match = re.fullmatch(pattern, text)
        if not match:
            continue
        numbers = tuple(int(item) for item in match.groups())
        try:
            if precision == "DAY":
                date(numbers[0], numbers[1], numbers[2])
            elif precision == "MONTH" and not 1 <= numbers[1] <= 12:
                raise ValueError("month out of range")
        except ValueError as exc:
            raise RuleParseError(f"invalid date {value!r}: {exc}") from exc
        return ParsedDate(output.format(*numbers), precision, False, text)
    raise RuleParseError(f"cannot normalize date from {value!r}")


def parse_percentage(value: Any) -> Optional[Decimal]:
    if is_empty_or_invalid(value):
        return None
    text = str(value).strip().replace(",", "")
    text = text[:-1].strip() if text.endswith("%") else text
    try:
        percentage = Decimal(text)
    except InvalidOperation as exc:
        raise RuleParseError(f"cannot parse percentage from {value!r}") from exc
    if percentage < 0 or percentage > 100:
        raise RuleParseError(f"percentage out of range: {percentage}")
    return percentage


def normalize_currency(value: Any) -> Optional[str]:
    text = clean_text(value)
    if text is None:
        return None
    normalized = text.upper() if re.fullmatch(r"[A-Za-z]{3}", text) else text
    currency = CURRENCY_ALIASES.get(normalized)
    if currency is None:
        raise UnknownCurrencyError(f"unknown currency: {text}")
    return currency


def parse_money(
    amount: Any,
    currency_code: Any = None,
    unit: Any = None,
) -> Optional[ParsedMoney]:
    if is_empty_or_invalid(amount):
        return None
    raw_value = str(amount).strip()
    text = raw_value.replace(",", "").replace("，", "").replace(" ", "")
    if re.search(r"\d\s*(?:-|—|–|~|～|至|到)\s*\d", text):
        raise RuleParseError(f"amount range is not a single exact value: {raw_value!r}")

    is_estimated = any(marker in text for marker in ESTIMATE_MARKERS)
    for marker in ESTIMATE_MARKERS:
        text = text.replace(marker, "")

    detected_currency: Optional[str] = None
    raw_currency = clean_text(currency_code)
    if raw_currency is not None:
        detected_currency = normalize_currency(raw_currency)

    currency_symbols = {"HK$": "HKD", "¥": "CNY", "￥": "CNY", "$": "USD"}
    for symbol, code in sorted(currency_symbols.items(), key=lambda item: -len(item[0])):
        if symbol in text:
            detected_currency = detected_currency or code
            raw_currency = raw_currency or symbol
            text = text.replace(symbol, "", 1)
            break

    for alias in sorted(CURRENCY_ALIASES, key=len, reverse=True):
        if alias.upper() in text.upper():
            alias_currency = CURRENCY_ALIASES[alias]
            if detected_currency and detected_currency != alias_currency:
                raise UnknownCurrencyError(
                    f"conflicting currencies: {raw_currency!r} and {alias!r}"
                )
            detected_currency = alias_currency
            raw_currency = raw_currency or alias
            text = re.sub(re.escape(alias), "", text, count=1, flags=re.I)
            break

    # An explicit unsupported Chinese currency must not be mistaken for an
    # amount unit. Known amount units are excluded from this suffix check.
    if detected_currency is None:
        currency_match = re.search(r"([\u4e00-\u9fff]{1,8}(?:元|币))\s*$", text)
        if currency_match:
            currency_name = currency_match.group(1)
            if currency_name not in set(AMOUNT_UNIT_FACTORS):
                raise UnknownCurrencyError(f"unknown currency: {currency_name}")

    unknown_iso = re.search(r"(?<![A-Za-z])([A-Za-z]{3})(?![A-Za-z])", text)
    if unknown_iso:
        raise UnknownCurrencyError(f"unknown currency: {unknown_iso.group(1)}")

    explicit_unit = clean_text(unit)
    factor: Optional[Decimal] = None
    if explicit_unit is not None:
        factor = AMOUNT_UNIT_FACTORS.get(explicit_unit)
        if factor is None:
            raise MissingAmountUnitError(f"unknown amount unit: {explicit_unit}")
    else:
        for unit_name in sorted(AMOUNT_UNIT_FACTORS, key=len, reverse=True):
            if unit_name in text:
                factor = AMOUNT_UNIT_FACTORS[unit_name]
                text = text.replace(unit_name, "", 1)
                break
    if factor is None:
        raise MissingAmountUnitError(
            f"amount unit is missing; cannot determine base-unit scale: {raw_value!r}"
        )

    if not re.fullmatch(r"[-+]?\d+(?:\.\d+)?", text):
        raise RuleParseError(f"cannot parse exact amount from {raw_value!r}")
    try:
        number = Decimal(text)
    except InvalidOperation as exc:
        raise RuleParseError(f"cannot parse amount from {amount!r}") from exc
    return ParsedMoney(
        amount=number * factor,
        amount_unit="元",
        is_estimated=is_estimated,
        raw_value=raw_value,
        currency_code=detected_currency,
        raw_currency=raw_currency,
    )


def parse_stock_code(value: Any) -> Optional[str]:
    if is_empty_or_invalid(value):
        return None
    match = STOCK_CODE_PATTERN.search(str(value).upper())
    if not match:
        raise RuleParseError(f"cannot parse stock code from {value!r}")
    return match.group(1).upper()


def clean_enterprise_name(value: Any, stock_code: Any = None) -> Optional[str]:
    name = clean_text(value)
    if name is None:
        return None
    parsed_code: Optional[str] = None
    if not is_empty_or_invalid(stock_code):
        parsed_code = parse_stock_code(stock_code)
    if parsed_code:
        escaped = re.escape(parsed_code)
        name = re.sub(rf"\s*[（(]\s*{escaped}\s*[)）]\s*$", "", name, flags=re.I).strip()
    return name or None


def normalize_organization_name(value: Any) -> Optional[str]:
    text = clean_text(value)
    if text is None:
        return None
    return re.sub(r"[\s·•,，。;；:：()（）\[\]【】]+", "", text).lower()


def safe_preview(value: Any, limit: int = 160) -> Optional[str]:
    text = clean_text(value)
    if text is None:
        return None
    return text if len(text) <= limit else text[:limit] + "…"
