"""Read-only coverage check for family member extraction."""

from __future__ import annotations

import csv
import json

import parse_family_extensions as parser


class CountingCursor:
    def __init__(self) -> None:
        self.count = 0
        self.lastrowid = 0

    def execute(self, _sql: str, _params: object = None) -> None:
        self.count += 1
        self.lastrowid = self.count


def main() -> None:
    with open("outputs/staging_import_v1/stg_import_row.csv", encoding="utf-8-sig", newline="") as handle:
        rows = [row for row in csv.DictReader(handle) if row["data_dimension"] == "FAMILY"]
    total = 0
    unparsed: list[str] = []
    for row in rows:
        cursor = CountingCursor()
        cells = json.loads(row["raw_cells"])
        count, _ = parser.parse_members(cursor, 1, cells[parser.MEMBER_FIELD], 1)
        total += count
        if not count:
            unparsed.append(row["person_name"])
    print(json.dumps({"family_rows": len(rows), "parsed_members": total, "unparsed_people": unparsed}, ensure_ascii=False))


if __name__ == "__main__":
    main()
