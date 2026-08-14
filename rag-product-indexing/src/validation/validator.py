from __future__ import annotations

import re

from models import ParseIssue, ParsedDocument


class ProductDocumentValidator:
    IMPORTANT_CONTENT = ("产品概述", "投资运作", "相关费用", "风险揭示", "风险评级", "销售对象")

    def validate(self, document: ParsedDocument) -> list[ParseIssue]:
        issues: list[ParseIssue] = []
        metadata = document.metadata
        for field_name in ("document_id", "product_code"):
            if not getattr(metadata, field_name, "").strip():
                issues.append(
                    ParseIssue(
                        stage="VALIDATION",
                        reason=f"MISSING_{field_name.upper()}",
                        severity="ERROR",
                        message=f"缺少必需身份字段 {field_name}。",
                    )
                )
        if metadata.page_count <= 0:
            issues.append(ParseIssue("VALIDATION", "INVALID_PAGE_COUNT", "ERROR", "page_count 必须大于 0。"))
        if not document.blocks:
            issues.append(ParseIssue("VALIDATION", "EMPTY_BLOCKS", "ERROR", "解析结果 blocks 为空。"))

        corpus = "\n".join(block.text for block in document.blocks)
        for keyword in self.IMPORTANT_CONTENT:
            if keyword not in corpus:
                issues.append(
                    ParseIssue(
                        stage="VALIDATION",
                        reason="IMPORTANT_CONTENT_NOT_FOUND",
                        severity="WARNING",
                        message=f"未在标准化结果中找到重要内容: {keyword}",
                    )
                )
        issues.extend(self._identity_issues(document))
        return issues

    def _identity_issues(self, document: ParsedDocument) -> list[ParseIssue]:
        expected = {
            "产品代码": document.metadata.product_code,
            "销售代码": document.metadata.sales_code,
        }
        found: dict[str, set[str]] = {key: set() for key in expected}
        pattern = re.compile(r"(?=.{6,16}\b)(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+")
        for block in document.blocks:
            for pair in block.kv_pairs or []:
                key, value = pair.get("key", ""), pair.get("value", "")
                for field in expected:
                    if field in key:
                        found[field].update(pattern.findall(value))
            for field in expected:
                for match in re.finditer(rf"{field}\s*[：:]?\s*([A-Za-z0-9]{{6,16}})", block.text):
                    found[field].add(match.group(1))

        issues: list[ParseIssue] = []
        for field, expected_value in expected.items():
            values = found[field]
            if values and expected_value not in values:
                issues.append(
                    ParseIssue(
                        stage="VALIDATION",
                        reason="FILENAME_METADATA_MISMATCH",
                        severity="WARNING",
                        message=f"文件名 {field}={expected_value}，正文高可信位置识别为 {sorted(values)}；未覆盖文件名值。",
                    )
                )
        return issues

    @staticmethod
    def status(issues: list[ParseIssue]) -> str:
        severities = {issue.severity for issue in issues}
        if "ERROR" in severities:
            return "FAILED"
        if "WARNING" in severities:
            return "SUCCESS_WITH_WARNINGS"
        return "SUCCESS"
