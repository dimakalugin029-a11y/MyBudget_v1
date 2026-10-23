"""Execute the actual simple SQL migrations 41..46 on in-memory SQLite.
This supplements, and does not replace, Room/device migration validation.
"""
import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/ru.mybudget.app.data.BudgetDatabase"
MIGRATIONS = ROOT / "app/src/main/kotlin/ru/mybudget/app/data/migration"
DATABASE_SOURCE = (ROOT / "app/src/main/kotlin/ru/mybudget/app/data/BudgetDatabase.kt").read_text(encoding="utf-8")
TARGET = int(re.search(r"version\s*=\s*(\d+)", DATABASE_SOURCE).group(1))


def create(connection, version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
    for entity in schema["entities"]:
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for sql in schema.get("setupQueries", []):
        connection.execute(sql)
    return schema


def seed(connection, schema):
    samples = {
        "budget_profiles": {"id": 1, "name": "Test", "isActive": 1},
        "categories": {"id": 1, "name": "Car", "parentId": 0, "budgetId": 1, "currentBalance": 900.0, "isActive": 1},
        "transactions": {"id": 1, "categoryId": 1, "amount": 100.0, "type": "expense", "description": "Repair", "plannedIncomeSourceId": 1, "plannedIncomeMonth": "2026-10"},
        "planned_income_sources": {"id": 1, "budgetId": 1, "name": "Salary", "amount": 1000.0, "sourceType": "salary", "periodType": "monthly", "isActive": 1},
        "vehicles": {"id": 1, "name": "Car"},
        "vehicle_repairs": {"id": 1, "vehicleId": 1, "categoryId": 1, "amount": 100.0, "paidTransactionId": 1, "paymentMethod": "budget"},
        "planned_obligations": {"id": 1, "budgetId": 1, "categoryId": 1, "amount": 100.0, "name": "Payment", "periodType": "monthly", "isActive": 1, "attentionStartMonth": "2026-10"},
        "obligation_payments": {"id": 1, "obligationId": 1, "periodYear": 2026, "periodMonth": 10, "amount": 100.0, "transactionId": 1, "dueDate": "2026-10-22"},
    }
    before = {}
    for entity in schema["entities"]:
        table = entity["tableName"]
        if table not in samples:
            continue
        names, values = [], []
        for field in entity["fields"]:
            name = field["columnName"]
            names.append(name)
            default = ("" if field["affinity"] == "TEXT" else 0) if field.get("notNull") else None
            values.append(samples[table].get(name, default))
        columns = ",".join(f'"{name}"' for name in names)
        marks = ",".join("?" for _ in values)
        connection.execute(f'INSERT INTO "{table}" ({columns}) VALUES ({marks})', values)
        before[table] = (columns, connection.execute(f'SELECT {columns} FROM "{table}"').fetchall())
    return before


def migrate(connection, version):
    path = MIGRATIONS / f"Migration{version}To{version + 1}.kt"
    source = path.read_text(encoding="utf-8")
    if "BalanceLedgerSchema.install(db)" in source:
        assert version == 46, "Review the ledger migration installer before extending this check"
        helper = (MIGRATIONS.parent / "BalanceLedgerSchema.kt").read_text(encoding="utf-8")
        block = helper.split("val statements = listOf(", 1)[1].split("fun install", 1)[0]
        statements = re.findall(r'\"\"\"(.*?)\"\"\"', block, re.S)
        assert statements and all("$" not in sql for sql in statements)
        for sql in statements:
            connection.execute(sql)
        if connection.execute("SELECT COUNT(*) FROM balance_ledger_state WHERE id=1").fetchone()[0] == 0:
            initial = re.findall(r'db\.execSQL\(("(?:[^"\\]|\\.)*")\)', helper)
            assert len(initial) == 2, "Review ledger baseline SQL"
            for sql in initial:
                connection.execute(json.loads(sql))
        return
    # Fail closed if these migrations stop being simple literal SQL.
    literals = re.findall(r'(?:database|db)\.execSQL\(("(?:[^"\\]|\\.)*")\)', source)
    assert literals and len(literals) == source.count(".execSQL("), f"Unsupported SQL expression in {path.name}"
    statements = [json.loads(literal) for literal in literals]
    if "$table" in " ".join(statements):
        tables = re.search(r'for \(table in listOf\((.*?)\)\)', source)
        assert tables, f"Unsupported interpolation in {path.name}"
        table_names = re.findall(r'"([a-z_]+)"', tables.group(1))
        statements = [sql.replace("$table", table) for table in table_names for sql in statements]
    for sql in statements:
        assert "$" not in sql, f"Unsupported interpolation in {path.name}"
        connection.execute(sql)


def structure(connection):
    result = {}
    for (table,) in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
        columns = connection.execute(f'PRAGMA table_info("{table}")').fetchall()
        foreign_keys = sorted(connection.execute(f'PRAGMA foreign_key_list("{table}")').fetchall())
        indices = {}
        for _, name, unique, origin, partial in connection.execute(f'PRAGMA index_list("{table}")'):
            if origin == "pk":
                continue
            indices[name] = (unique, partial, connection.execute(f'PRAGMA index_info("{name}")').fetchall())
        result[table] = (columns, foreign_keys, indices)
    return result


def main():
    for version in (41, 45, 46, 47, 48, 49):
        with sqlite3.connect(":memory:") as old, sqlite3.connect(":memory:") as current:
            historical = create(old, version)
            before = seed(old, historical)
            for step in range(version, TARGET):
                migrate(old, step)
            create(current, TARGET)
            actual, expected = structure(old), structure(current)
            assert actual == expected, f"Target structure differs after {version}->{TARGET}: " + ", ".join(
                table for table in actual.keys() | expected.keys() if actual.get(table) != expected.get(table))
            for table, (columns, rows) in before.items():
                assert old.execute(f'SELECT {columns} FROM "{table}"').fetchall() == rows, f"Data changed in {table}"
            assert not old.execute("PRAGMA foreign_key_check").fetchall()
            assert old.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            method = old.execute("SELECT paymentMethod FROM vehicle_repairs WHERE id=1").fetchone()[0]
            assert method == "budget"
            for table in ("vehicles", "vehicle_service_logs", "vehicle_insurances", "vehicle_fuel_logs", "vehicle_repairs"):
                assert old.execute(f'SELECT COUNT(*) FROM "{table}" WHERE isArchived != 0').fetchone()[0] == 0, f"Existing rows unexpectedly archived in {table}"
            print(f"SQL migration {version}->{TARGET}: full structure, sample data and payment link OK")
    print("Android Room validation still requires running DatabaseMigrationChainTest on a device")


if __name__ == "__main__":
    main()
