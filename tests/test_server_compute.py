import os
import sqlite3
import sys
import tempfile
import time
from pathlib import Path

TOOLKIT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLKIT_ROOT / "site"))

import server
from server import compute, iso

def test_missing_db():
    server.DB_OVERRIDE = "/tmp/nonexistent_sessions_db_12345.db"
    try:
        res = compute(86400)
        assert res["db_found"] is False
        assert res["points"] == []
        assert res["step"] == 420
    finally:
        server.DB_OVERRIDE = None

def test_compute_bucketing_and_bot_exclusion():
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as db_file:
        db_path = db_file.name

    try:
        con = sqlite3.connect(db_path)
        con.execute("""
        CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL, uuid TEXT, name TEXT, kind TEXT NOT NULL
        );
        """)

        now = int(time.time())
        # Insert a heartbeat within a 1-hour span (3600 seconds)
        # span=3600 -> step = max(120, 3600 // 200 // 60 * 60) = 120
        # n = 3600 // 120 = 30 buckets
        # name = "6" -> bot account excluded (6 - 1 = 5)
        ts_heartbeat = iso(now - 60)
        con.execute("INSERT INTO events (ts, uuid, name, kind) VALUES (?, '', '6', 'HEARTBEAT')", (ts_heartbeat,))
        con.commit()
        con.close()

        server.DB_OVERRIDE = db_path
        res = compute(3600)

        assert res["db_found"] is True
        assert res["step"] == 120
        points = res["points"]
        assert len(points) == 30

        # Non-empty bucket has average player count 5
        values = [p[1] for p in points if p[1] is not None]
        assert values == [5]

        # Empty buckets have None
        none_points = [p[1] for p in points if p[1] is None]
        assert len(none_points) == 29
    finally:
        server.DB_OVERRIDE = None
        if os.path.exists(db_path):
            os.remove(db_path)

if __name__ == "__main__":
    test_missing_db()
    test_compute_bucketing_and_bot_exclusion()
    print("All server compute tests passed!")
