#!/usr/bin/env python3
"""Cross-checks the SQL in data/ and engine/ against the schema in data/Db.kt.

There is no compiler for the Android sources in some environments (no Android SDK, no network to
Maven Central), so this catches the class of bug that only shows up at runtime: a column that was
renamed in the schema but not in a query, a table typo, an INSERT with a field that does not
exist.

Usage:  python3 tools/static-checks/check_sql_schema.py        # from the repository root
"""

import glob
import re
import sys


def schema_columns(db_source: str) -> dict[str, set[str]]:
    """{table: {column, ...}} parsed out of CREATE TABLE statements."""
    schema: dict[str, set[str]] = {}
    for match in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\((.*?)\)\s*\"\"\"", db_source, re.S):
        table, body = match.group(1), match.group(2)
        columns = set()
        for line in body.split("\n"):
            line = line.strip()
            if not line or line.startswith("--"):
                continue
            if line.upper().startswith(("PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "CHECK")):
                continue
            name = re.match(r"(\w+)\s+", line)
            if name:
                columns.add(name.group(1))
        schema[table] = columns
    return schema


SQL_FUNCTIONS = {
    "COUNT", "MAX", "MIN", "SUM", "AVG", "TOTAL", "ABS", "LENGTH", "COALESCE", "IFNULL",
    "NULLIF", "CAST", "SUBSTR", "REPLACE", "INSTR", "GROUP_CONCAT", "DISTINCT", "CASE", "WHEN",
    "THEN", "ELSE", "END", "AS", "OR", "REPLACE", "NOT", "NULL", "IS", "IN", "LIKE", "AND",
}


def check_file(path: str, schema: dict[str, set[str]], all_columns: set[str]) -> list[str]:
    issues: list[str] = []
    name = path.rsplit("/", 1)[-1]
    source = open(path).read()
    for block in re.finditer(r'"""(.*?)"""', source, re.S):
        sql = block.group(1)
        for table, columns in schema.items():
            for insert in re.finditer(
                r"INSERT\s+(?:OR REPLACE\s+)?INTO\s+" + table + r"\s*\(([^)]*)\)", sql, re.I | re.S
            ):
                for column in re.findall(r"[A-Za-z_]\w*", insert.group(1)):
                    if column not in columns:
                        issues.append(f"{name}: INSERT INTO {table} uses unknown column '{column}'")
            for update in re.finditer(r"UPDATE\s+" + table + r"\s+SET\s+([^;]*?)(?:WHERE|$)", sql, re.I | re.S):
                for column in re.findall(r"([A-Za-z_]\w*)\s*=", update.group(1)):
                    if column not in columns:
                        issues.append(f"{name}: UPDATE {table} sets unknown column '{column}'")
            for select in re.finditer(r"SELECT\s+(.*?)\s+FROM\s+" + table + r"\b", sql, re.I | re.S):
                projection = select.group(1).replace("\n", " ")
                for column in re.findall(r"([A-Za-z_]\w*)\s*(?:,|$)", projection):
                    if column.upper() in SQL_FUNCTIONS or column in columns:
                        continue
                    issues.append(f"{name}: SELECT from {table} reads unknown column '{column}'")
        for where in re.finditer(r"WHERE\s+(.*?)(?:ORDER BY|LIMIT|$|\"\"\")", sql, re.I | re.S):
            for column in re.findall(r"([A-Za-z_]\w*)\s*(?:=|\s+IS\b|\s+LIKE\b|\s+IN\b|<=|>=|<|>)", where.group(1)):
                if column not in all_columns:
                    issues.append(f"{name}: WHERE uses '{column}', which exists in no table")
    return issues


def main() -> int:
    db_path = "app/src/main/java/com/ping/app/data/Db.kt"
    schema = schema_columns(open(db_path).read())
    if not schema:
        print(f"no schema found in {db_path} — did the file move?")
        return 1
    all_columns = {column for columns in schema.values() for column in columns}
    print(f"schema: {len(schema)} tables, {len(all_columns)} columns")

    issues: list[str] = []
    for path in sorted(
        glob.glob("app/src/main/java/com/ping/app/data/*.kt")
        + glob.glob("app/src/main/java/com/ping/app/engine/*.kt")
    ):
        issues.extend(check_file(path, schema, all_columns))

    for issue in issues:
        print("  ✗", issue)
    print(f"{len(issues)} problem(s)")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
