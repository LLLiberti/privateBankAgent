from __future__ import annotations

import re


class TextNormalizer:
    PAGE_NUMBER_PATTERN = re.compile(r"^\s*(?:第\s*)?\d{1,4}(?:\s*页)?\s*$")
    SENTENCE_END = set("。！？；：.!?;:")

    def normalize(self, text: str) -> str:
        value = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")
        lines = [re.sub(r"[ \t]+", " ", line).strip() for line in value.split("\n")]
        lines = [line for line in lines if line]
        if not lines:
            return ""

        merged = lines[0]
        for line in lines[1:]:
            if self._should_join(merged, line):
                merged += line
            else:
                merged += "\n" + line
        return re.sub(r"\n{3,}", "\n\n", merged).strip()

    def is_page_number(self, text: str) -> bool:
        return bool(self.PAGE_NUMBER_PATTERN.fullmatch(text))

    def _should_join(self, previous: str, current: str) -> bool:
        if not previous or not current:
            return False
        if previous[-1] in self.SENTENCE_END:
            return False
        if re.match(r"^(?:[（(]?\d+[）).、]|[（(][一二三四五六七八九十]+[）)])", current):
            return False
        # Join PDF line wraps conservatively. No characters are rewritten, so amounts,
        # dates, percentages, codes and formulas remain byte-for-byte in content.
        return True
