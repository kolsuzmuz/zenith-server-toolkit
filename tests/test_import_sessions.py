import os
import sqlite3
import sys
import tempfile
from pathlib import Path

TOOLKIT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLKIT_ROOT))
sys.path.insert(0, str(TOOLKIT_ROOT / "scripts"))

from scripts.import_sessions import init_db, handle_line, run_once, get_offset

def test_join_leave_produces_closed_session():
    with tempfile.NamedTemporaryFile(suffix=".db") as db_file:
        con = sqlite3.connect(db_file.name)
        init_db(con)
        handle_line(con, "2026-01-01T12:00:00+00:00|uuid1|Player1|JOIN\n")
        handle_line(con, "2026-01-01T12:05:00+00:00|uuid1|Player1|LEAVE\n")
        con.commit()

        sessions = con.execute(
            "SELECT uuid, name, join_ts, leave_ts, seconds, start_kind, end_kind FROM sessions"
        ).fetchall()
        assert len(sessions) == 1
        uuid, name, join_ts, leave_ts, seconds, start_kind, end_kind = sessions[0]
        assert uuid == "uuid1"
        assert name == "Player1"
        assert join_ts == "2026-01-01T12:00:00+00:00"
        assert leave_ts == "2026-01-01T12:05:00+00:00"
        assert seconds == 300
        assert start_kind == "JOIN"
        assert end_kind == "LEAVE"
        con.close()

def test_duplicate_join_does_not_create_second_open_session():
    with tempfile.NamedTemporaryFile(suffix=".db") as db_file:
        con = sqlite3.connect(db_file.name)
        init_db(con)
        handle_line(con, "2026-01-01T12:00:00+00:00|uuid1|Player1|JOIN\n")
        handle_line(con, "2026-01-01T12:01:00+00:00|uuid1|Player1|JOIN\n")
        con.commit()

        open_sessions = con.execute("SELECT id FROM sessions WHERE leave_ts IS NULL").fetchall()
        assert len(open_sessions) == 1
        all_sessions = con.execute("SELECT id FROM sessions").fetchall()
        assert len(all_sessions) == 1
        con.close()

def test_bot_disconnect_closes_all_open_sessions():
    with tempfile.NamedTemporaryFile(suffix=".db") as db_file:
        con = sqlite3.connect(db_file.name)
        init_db(con)
        handle_line(con, "2026-01-01T12:00:00+00:00|uuid1|Player1|JOIN\n")
        handle_line(con, "2026-01-01T12:02:00+00:00|uuid2|Player2|JOIN\n")
        handle_line(con, "2026-01-01T12:10:00+00:00|||BOT_DISCONNECT\n")
        con.commit()

        open_sessions = con.execute("SELECT id FROM sessions WHERE leave_ts IS NULL").fetchall()
        assert len(open_sessions) == 0

        closed_sessions = con.execute("SELECT uuid, leave_ts, seconds, end_kind FROM sessions").fetchall()
        assert len(closed_sessions) == 2
        for s in closed_sessions:
            assert s[1] == "2026-01-01T12:10:00+00:00"
            assert s[2] > 0
            assert s[3] == "BOT_DISCONNECT"
        con.close()

def test_snapshot_start_closes_open_sessions_with_crash():
    with tempfile.NamedTemporaryFile(suffix=".db") as db_file:
        con = sqlite3.connect(db_file.name)
        init_db(con)
        handle_line(con, "2026-01-01T12:00:00+00:00|uuid1|Player1|JOIN\n")
        handle_line(con, "2026-01-01T12:05:00+00:00||10|HEARTBEAT\n")
        # Bot crashed here without clean disconnect. Restart occurs:
        handle_line(con, "2026-01-01T13:00:00+00:00|||SNAPSHOT_START\n")
        con.commit()

        sessions = con.execute("SELECT uuid, join_ts, leave_ts, seconds, end_kind FROM sessions").fetchall()
        assert len(sessions) == 1
        uuid, join_ts, leave_ts, seconds, end_kind = sessions[0]
        assert uuid == "uuid1"
        assert join_ts == "2026-01-01T12:00:00+00:00"
        # leave_ts must be the timestamp of previous event, not 13:00:00
        assert leave_ts == "2026-01-01T12:05:00+00:00"
        assert seconds == 300
        assert end_kind == "CRASH"
        con.close()

def test_trailing_line_without_newline_not_consumed():
    with tempfile.NamedTemporaryFile(suffix=".db") as db_file, tempfile.NamedTemporaryFile(mode="wb", delete=False) as log_file:
        log_path = log_file.name
        try:
            con = sqlite3.connect(db_file.name)
            init_db(con)

            # Write one complete line and one incomplete line (no newline)
            log_file.write(b"2026-01-01T12:00:00+00:00|uuid1|Player1|JOIN\n")
            log_file.write(b"2026-01-01T12:05:00+00:00|uuid1|Player1|LEAVE")
            log_file.flush()

            n = run_once(con, log_path)
            assert n == 1
            events = con.execute("SELECT id FROM events").fetchall()
            assert len(events) == 1

            # Complete the trailing line
            with open(log_path, "ab") as f:
                f.write(b"\n")

            n2 = run_once(con, log_path)
            assert n2 == 1
            events2 = con.execute("SELECT id FROM events").fetchall()
            assert len(events2) == 2

            sessions = con.execute("SELECT end_kind FROM sessions").fetchall()
            assert len(sessions) == 1
            assert sessions[0][0] == "LEAVE"
            con.close()
        finally:
            if os.path.exists(log_path):
                os.remove(log_path)

def test_stored_offset_prevents_duplicate_import():
    with tempfile.NamedTemporaryFile(suffix=".db") as db_file, tempfile.NamedTemporaryFile(mode="wb", delete=False) as log_file:
        log_path = log_file.name
        try:
            con = sqlite3.connect(db_file.name)
            init_db(con)

            log_file.write(b"2026-01-01T12:00:00+00:00|uuid1|Player1|JOIN\n")
            log_file.flush()

            n1 = run_once(con, log_path)
            assert n1 == 1

            # Second run with no new lines
            n2 = run_once(con, log_path)
            assert n2 == 0

            events = con.execute("SELECT id FROM events").fetchall()
            assert len(events) == 1
            con.close()
        finally:
            if os.path.exists(log_path):
                os.remove(log_path)

if __name__ == "__main__":
    test_join_leave_produces_closed_session()
    test_duplicate_join_does_not_create_second_open_session()
    test_bot_disconnect_closes_all_open_sessions()
    test_snapshot_start_closes_open_sessions_with_crash()
    test_trailing_line_without_newline_not_consumed()
    test_stored_offset_prevents_duplicate_import()
    print("All import_sessions tests passed!")
