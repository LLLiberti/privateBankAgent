from __future__ import annotations

from pathlib import Path

from transformers import AutoTokenizer


DEFAULT_TOKENIZER_PATH = Path(r"D:\codex-temp\models\qwen3-embedding-0.6b-tokenizer")


class QwenTokenCounter:
    def __init__(self, tokenizer_path: Path | str = DEFAULT_TOKENIZER_PATH) -> None:
        self.tokenizer_path = Path(tokenizer_path)
        self.tokenizer = AutoTokenizer.from_pretrained(
            str(self.tokenizer_path),
            local_files_only=True,
        )

    def count(self, text: str) -> int:
        return len(self.tokenizer.encode(text, add_special_tokens=False))
