"""Read-only summary of enterprise staging fields and representative values."""

from __future__ import annotations

import csv
import json
from pathlib import Path


def main() -> None:
    path = Path("outputs/staging_import_v1/stg_import_row.csv")
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = [row for row in csv.DictReader(handle) if row["data_dimension"] == "ENTERPRISE"]
    if not rows:
        raise RuntimeError("No ENTERPRISE rows found")
    first = json.loads(rows[0]["raw_cells"])
    print("FIELDS")
    for index, field in enumerate(first, start=1):
        print(f"{index}. {field}")
    print("SAMPLES")
    for row in rows[:2]:
        print(json.dumps({"person_name": row["person_name"], "cells": json.loads(row["raw_cells"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
