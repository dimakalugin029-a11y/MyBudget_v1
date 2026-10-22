"""Validate committed Room schema snapshots without opening user databases."""
import hashlib
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "app/src/main/kotlin/ru/mybudget/app/data/BudgetDatabase.kt").read_text(encoding="utf-8")
version = int(re.search(r"version\s*=\s*(\d+)", source).group(1))
folder = root / "app/schemas/ru.mybudget.app.data.BudgetDatabase"
assert (folder / f"{version}.json").is_file(), f"Missing schema for current database version {version}"
assert "exportSchema = true" in source, "Schema export must remain enabled"
provenance = json.loads((root / "app/schemas/provenance.json").read_text(encoding="utf-8"))
for record in provenance["historicalSchemas"]:
    path = folder / f"{record['version']}.json"
    canonical = json.dumps(json.loads(path.read_text(encoding="utf-8")), sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    assert hashlib.sha256(canonical).hexdigest() == record["sha256"], f"Historical schema changed: {path.name}"
for path in sorted(folder.glob("*.json")):
    schema = json.loads(path.read_text(encoding="utf-8"))["database"]
    assert schema["version"] == int(path.stem), f"Schema version mismatch: {path.name}"
    assert schema["identityHash"], f"Missing identity hash: {path.name}"
    with sqlite3.connect(":memory:") as connection:
        for entity in schema["entities"]:
            sql = entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])
            connection.execute(sql)
            for index in entity.get("indices", []):
                connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for view in schema.get("views", []):
            connection.execute(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))
        for sql in schema.get("setupQueries", []):
            connection.execute(sql)
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    print(f"Room schema {path.stem}: SQL and metadata OK")
print(f"Current database version: {version}; device migration tests are a separate check")
