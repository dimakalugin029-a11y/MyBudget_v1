"""Exercise production ledger trigger SQL on disposable in-memory SQLite."""
import sqlite3
from check_migration_sql import create, migrate

with sqlite3.connect(":memory:") as db:
    schema = create(db, 46)
    category = next(e for e in schema["entities"] if e["tableName"] == "categories")
    def insert(id, parent, budget, balance):
        values = {"id":id,"name":f"Category {id}","parentId":parent,"budgetId":budget,"currentBalance":balance,"isActive":1}
        names, row = [], []
        for field in category["fields"]:
            name=field["columnName"]
            names.append('"'+name+'"')
            row.append(values.get(name,"" if field["affinity"]=="TEXT" else 0))
        db.execute('INSERT INTO categories ('+','.join(names)+') VALUES ('+','.join('?' for _ in row)+')',row)
    def check():
        actual=db.execute('SELECT COALESCE(SUM(currentBalance),0) FROM categories WHERE parentId=0 AND isActive=1').fetchone()[0]
        journal=db.execute('SELECT COALESCE(SUM(budgetDelta),0) FROM balance_movements').fetchone()[0]
        assert abs(actual-journal)<0.001,(actual,journal)
        for budget in (1,2,3):
            actual=db.execute('SELECT COALESCE(SUM(currentBalance),0) FROM categories WHERE parentId=0 AND isActive=1 AND budgetId=?',(budget,)).fetchone()[0]
            journal=db.execute('SELECT COALESCE(SUM(budgetDelta),0) FROM balance_movements WHERE budgetId=?',(budget,)).fetchone()[0]
            assert abs(actual-journal)<0.001,(budget,actual,journal)
    insert(1,0,1,1000);insert(2,1,1,1000);insert(3,0,2,500)
    migrate(db,46)
    check()
    assert db.execute('SELECT SUM(budgetDelta) FROM balance_movements').fetchone()[0]==1500
    db.execute('UPDATE categories SET currentBalance=currentBalance-100 WHERE id IN (1,2)')
    check()
    db.execute("UPDATE balance_ledger_state SET kind='transfer',operationId='transfer-1' WHERE id=1")
    db.execute('UPDATE categories SET currentBalance=currentBalance-50 WHERE id IN (1,2)')
    db.execute('UPDATE categories SET currentBalance=currentBalance+50 WHERE id=3')
    db.execute("UPDATE balance_ledger_state SET kind='adjustment',operationId='' WHERE id=1")
    check()
    assert db.execute("SELECT SUM(budgetDelta) FROM balance_movements WHERE operationId='transfer-1'").fetchone()[0]==0
    db.execute('UPDATE categories SET isActive=0 WHERE id=1');check()
    assert db.execute('SELECT kind FROM balance_movements ORDER BY id DESC LIMIT 1').fetchone()[0]=='archive'
    db.execute('UPDATE categories SET isActive=1 WHERE id=1');check()
    db.execute('UPDATE categories SET budgetId=3 WHERE id=3');check()
    db.execute('UPDATE categories SET currentBalance=777 WHERE id=3');check()
    # Upsert is an update, so an existing balance is not counted as a second opening.
    db.execute('UPDATE categories SET currentBalance=800 WHERE id=3');check()
    db.execute('DELETE FROM categories WHERE id=3');check()
    db.commit()
    before=db.execute('SELECT COUNT(*) FROM balance_movements').fetchone()[0]
    db.execute('BEGIN')
    db.execute('UPDATE categories SET currentBalance=999999 WHERE id=1')
    db.rollback()
    assert db.execute('SELECT COUNT(*) FROM balance_movements').fetchone()[0]==before
    check()
    assert db.execute('SELECT COUNT(*) FROM transactions').fetchone()[0]==0
    assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    print('Ledger SQL: baseline, parent deduplication, transfers, archive, move, adjustment, delete and rollback OK')
