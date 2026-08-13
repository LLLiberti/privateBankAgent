from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class HeadingDecision:
    is_heading: bool
    level: int | None = None
    heading_text: str | None = None
    body_text: str | None = None


class HeadingNormalizer:
    ROOT_TITLES = {
        "风险揭示书",
        "理财产品说明书",
        "工银理财有限责任公司个人理财产品协议书",
        "客户权益须知",
        "快速赎回服务协议",
        "工银理财现金管理类理财产品 快速赎回服务协议",
        "理财产品投资协议书",
    }
    PRIMARY_PATTERN = re.compile(r"^[一二三四五六七八九十百]+、\S.*$")
    SECONDARY_PATTERN = re.compile(r"^（[一二三四五六七八九十百]+）\S.*$")
    CLAUSE_PATTERN = re.compile(r"^\d+[\.．、]\s*\S.*$")
    NUMBERED_PREFIX_PATTERN = re.compile(
        r"^(?:[一二三四五六七八九十百]+、|（[一二三四五六七八九十百]+）)"
    )
    BODY_BOUNDARY_PATTERN = re.compile(r"[。！？]|：(?=.{20,})")
    EXPLANATORY_BODY_PATTERN = re.compile(
        r"^(?:本产品|本理财产品|产品|投资者|客户|甲方|乙方|由于|如|若|在|当|为|因|除|受|涉及|净值型产品)"
    )
    MAX_HEADING_PREFIX_LENGTH = 36

    def classify(self, text: str, parser_heading: bool = False) -> HeadingDecision:
        value = text.strip()
        if value in self.ROOT_TITLES:
            return HeadingDecision(True, 1)
        if self.PRIMARY_PATTERN.match(value):
            return self._numbered_heading_decision(value, 1)
        if self.SECONDARY_PATTERN.match(value):
            return self._numbered_heading_decision(value, 2)
        if self.CLAUSE_PATTERN.match(value):
            return HeadingDecision(parser_heading, 3 if parser_heading else None)
        if parser_heading and value:
            return HeadingDecision(True, None)
        return HeadingDecision(False, None)

    def _numbered_heading_decision(self, value: str, level: int) -> HeadingDecision:
        boundary = self.BODY_BOUNDARY_PATTERN.search(value)
        if not boundary:
            heading_without_number = self.NUMBERED_PREFIX_PATTERN.sub("", value).strip()
            return HeadingDecision(
                len(heading_without_number) <= self.MAX_HEADING_PREFIX_LENGTH,
                level if len(heading_without_number) <= self.MAX_HEADING_PREFIX_LENGTH else None,
            )

        prefix_end = boundary.end()
        heading_text = value[:prefix_end].strip()
        body_text = value[prefix_end:].strip()
        if not body_text:
            heading_without_number = self.NUMBERED_PREFIX_PATTERN.sub("", heading_text).strip()
            return HeadingDecision(
                len(heading_without_number) <= self.MAX_HEADING_PREFIX_LENGTH,
                level if len(heading_without_number) <= self.MAX_HEADING_PREFIX_LENGTH else None,
            )

        heading_without_number = self.NUMBERED_PREFIX_PATTERN.sub("", heading_text).strip()
        title_like_prefix = len(heading_without_number) <= self.MAX_HEADING_PREFIX_LENGTH
        explanatory_body = bool(self.EXPLANATORY_BODY_PATTERN.match(body_text)) or len(body_text) >= 24

        if title_like_prefix and explanatory_body:
            return HeadingDecision(True, level, heading_text=heading_text, body_text=body_text)

        return HeadingDecision(False)
