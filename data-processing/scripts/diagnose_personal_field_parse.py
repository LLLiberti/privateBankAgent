"""Read-only diagnostic for personal extension parsing coverage."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import parse_personal_extensions as parser


class CountingCursor:
    """Minimal cursor replacement used to count parser insert attempts."""

    def __init__(self) -> None:
        self.count = 0

    def execute(self, _sql: str, _params: object = None) -> None:
        self.count += 1


def count(parse_function, raw: str) -> int:
    cursor = CountingCursor()
    return parse_function(cursor, 1, raw, 1)


def main() -> None:
    csv_path = Path("outputs/staging_import_v1/stg_import_row.csv")
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = csv.DictReader(handle)
        for row in rows:
            if row["data_dimension"] != "PERSON":
                continue
            cells = json.loads(row["raw_cells"])
            checks = [
                ("资产负债概况", parser.parse_assets, parser.PERSON_COLUMNS[1]),
                ("产品持仓和到期情况", parser.parse_holdings, parser.PERSON_COLUMNS[2]),
                ("交易和大额资金变动", parser.parse_events, parser.PERSON_COLUMNS[3]),
                ("历史服务记录", parser.parse_services, parser.PERSON_COLUMNS[4]),
            ]
            for label, function, column_name in checks:
                raw = cells[column_name]
                if count(function, raw) == 0:
                    print(json.dumps({
                        "person_name": row["person_name"],
                        "field": label,
                        "raw_text": raw,
                    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
