#!/usr/bin/env python3
"""
Session importer: Parses session events logged by SessionLogger
into an SQLite database for player activity and analytics.
Designed for portable, zero-configuration operation across any VDS.
"""

import argparse
import os
import sqlite3
import sys
import time
from datetime import datetime, timezone, timedelta

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
TOOLKIT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))

def resolve_paths(cli_log=None, cli_db=None):
    """
    Intelligently discovers sessions.log and sessions.db across environment
    variables, command line arguments, and standard candidate locations.
    """
    resolved_log = None
    if cli_log:
        resolved_log = os.path.abspath(cli_log)
    elif "SESSIONS_LOG" in os.environ and os.environ["SESSIONS_LOG"]:
        resolved_log = os.path.abspath(os.environ["SESSIONS_LOG"])
    else:
        log_candidates = []
        for env_key in ("ZENITH_DIR", "ZENITH_HOME", "PROXY_DIR"):
            if env_key in os.environ and os.environ[env_key]:
                log_candidates.append(os.path.join(os.environ[env_key], "sessions.log"))

        log_candidates.extend([
            os.path.join(os.getcwd(), "sessions.log"),
            os.path.join(TOOLKIT_ROOT, "sessions.log"),
            os.path.join(TOOLKIT_ROOT, "..", "sessions.log"),
            os.path.join(TOOLKIT_ROOT, "..", "zenithproxy", "sessions.log"),
            os.path.join(TOOLKIT_ROOT, "..", "proxy", "sessions.log"),
            os.path.join(TOOLKIT_ROOT, "zenithproxy", "sessions.log"),
            os.path.expanduser("~/zenithproxy/sessions.log"),
            os.path.expanduser("~/proxy/sessions.log"),
            os.path.expanduser("~/.zenithproxy/sessions.log"),
            "/opt/zenithproxy/sessions.log",
            "/var/zenithproxy/sessions.log",
        ])

        for candidate in log_candidates:
            if os.path.exists(candidate):
                resolved_log = os.path.abspath(candidate)
                break

    if not resolved_log:
        resolved_log = os.path.abspath(cli_log if cli_log else "sessions.log")

    resolved_db = None
    if cli_db:
        resolved_db = os.path.abspath(cli_db)
    elif os.environ.get("SESSIONS_DB"):
        resolved_db = os.path.abspath(os.environ["SESSIONS_DB"])
    elif resolved_log and os.path.exists(resolved_log):
        # Place database in the same directory as the log file by default
        resolved_db = os.path.join(os.path.dirname(resolved_log), "sessions.db")
    else:
        resolved_db = os.path.abspath("sessions.db")

    return resolved_log, resolved_db

def parse_args():
    parser = argparse.ArgumentParser(description="Import sessions.log into SQLite database")
    parser.add_argument("--log", default=None, help="Path to sessions.log (default: auto-detect)")
    parser.add_argument("--db", default=None, help="Path to sessions.db (default: auto-detect)")
    parser.add_argument("--loop", action="store_true", help="Run continuously every 30 seconds")
    parser.add_argument("--interval", type=int, default=30, help="Interval in seconds for loop mode")
    parser.add_argument("--retention-days", type=int, default=0, help="Retention period in days for events (default: 0, disabled)")
    return parser.parse_args()

def init_db(con):
    con.executescript("""
    CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts TEXT NOT NULL, uuid TEXT, name TEXT, kind TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        uuid TEXT, name TEXT NOT NULL,
        join_ts TEXT NOT NULL, leave_ts TEXT, seconds INTEGER,
        start_kind TEXT, end_kind TEXT);
    CREATE INDEX IF NOT EXISTS idx_sess_name ON sessions(name);
    CREATE INDEX IF NOT EXISTS idx_sess_uuid ON sessions(uuid);
    CREATE INDEX IF NOT EXISTS idx_sess_open ON sessions(leave_ts);
    CREATE INDEX IF NOT EXISTS idx_events_kind_ts ON events(kind, ts);
    CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
    """)

def get_offset(con):
    r = con.execute("SELECT v FROM meta WHERE k='offset'").fetchone()
    return int(r[0]) if r else 0

def set_offset(con, o):
    con.execute("INSERT OR REPLACE INTO meta VALUES('offset',?)", (str(o),))

def get_inode(con):
    r = con.execute("SELECT v FROM meta WHERE k='inode'").fetchone()
    return int(r[0]) if r and r[0].isdigit() else None

def set_inode(con, inode):
    con.execute("INSERT OR REPLACE INTO meta VALUES('inode',?)", (str(inode),))

def purge_retention(con, days):
    if days <= 0:
        return
    cutoff = (datetime.now(timezone.utc) - timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%S+00:00")
    con.execute("DELETE FROM events WHERE ts < ?", (cutoff,))

def parse(ts):
    return datetime.fromisoformat(ts)

def close_session(con, row_id, join_ts, ts, kind):
    try:
        secs = max(0, int((parse(ts) - parse(join_ts)).total_seconds()))
    except Exception:
        secs = 0
    con.execute("UPDATE sessions SET leave_ts=?, seconds=?, end_kind=? WHERE id=?",
                (ts, secs, kind, row_id))

def close_all_open(con, ts, kind):
    for rid, jts in con.execute(
            "SELECT id, join_ts FROM sessions WHERE leave_ts IS NULL").fetchall():
        close_session(con, rid, jts, ts, kind)

def handle_line(con, line):
    parts = line.rstrip("\n").split("|")
    if len(parts) < 4:
        return
    ts, uuid, name, kind = parts[0], parts[1], parts[2], parts[3]
    try:
        ts = parse(ts).strftime("%Y-%m-%dT%H:%M:%S+00:00")
    except Exception:
        pass
    con.execute("INSERT INTO events(ts,uuid,name,kind) VALUES(?,?,?,?)",
                (ts, uuid, name, kind))

    if kind == "SNAPSHOT_START":
        last = con.execute(
            "SELECT ts FROM events WHERE id < (SELECT MAX(id) FROM events) "
            "ORDER BY id DESC LIMIT 1").fetchone()
        close_all_open(con, last[0] if last else ts, "CRASH")
    elif kind in ("JOIN", "SNAPSHOT"):
        check_query = "SELECT 1 FROM sessions WHERE uuid=? AND leave_ts IS NULL" if uuid else "SELECT 1 FROM sessions WHERE name=? AND leave_ts IS NULL"
        check_param = (uuid,) if uuid else (name,)
        if not con.execute(check_query, check_param).fetchone():
            con.execute("INSERT INTO sessions(uuid,name,join_ts,start_kind) VALUES(?,?,?,?)",
                        (uuid, name, ts, kind))
    elif kind == "LEAVE":
        leave_query = "SELECT id, join_ts FROM sessions WHERE uuid=? AND leave_ts IS NULL" if uuid else "SELECT id, join_ts FROM sessions WHERE name=? AND leave_ts IS NULL"
        leave_param = (uuid,) if uuid else (name,)
        r = con.execute(leave_query, leave_param).fetchone()
        if r:
            close_session(con, r[0], r[1], ts, "LEAVE")
    elif kind == "BOT_DISCONNECT":
        close_all_open(con, ts, "BOT_DISCONNECT")

def run_once(con, log_path):
    if not os.path.exists(log_path):
        return 0
    off = get_offset(con)
    stored_inode = get_inode(con)
    current_inode = None
    try:
        st = os.stat(log_path)
        file_size = st.st_size
        current_inode = st.st_ino
        if stored_inode is not None and current_inode != stored_inode:
            print(f"[*] Log rotation detected (inode changed from {stored_inode} to {current_inode}), resetting offset to 0.", file=sys.stderr)
            off = 0
        elif off > file_size:
            print(f"[*] Log truncation detected (offset {off} > size {file_size}), resetting offset to 0.", file=sys.stderr)
            off = 0
    except OSError:
        off = 0
    n = 0
    with open(log_path, "rb") as f:
        f.seek(off)
        while True:
            raw = f.readline()
            if not raw or not raw.endswith(b"\n"):
                break
            try:
                handle_line(con, raw.decode("utf-8", "replace"))
            except Exception as e:
                print(f"[!] Error handling line: {e}", file=sys.stderr)
            off = f.tell()
            n += 1
    set_offset(con, off)
    if current_inode is not None:
        set_inode(con, current_inode)
    con.commit()
    return n

def main():
    args = parse_args()
    log_path, db_path = resolve_paths(args.log, args.db)

    db_dir = os.path.dirname(os.path.abspath(db_path))
    if db_dir:
        os.makedirs(db_dir, exist_ok=True)

    con = sqlite3.connect(db_path)
    init_db(con)

    print(f"[*] Session Importer initialized:")
    print(f"    - Source log: {log_path} {'(Found)' if os.path.exists(log_path) else '(Waiting for events)'}")
    print(f"    - Target DB:  {db_path}")

    if args.loop:
        print(f"[*] Starting background loop (checking every {args.interval}s)...")
        while True:
            try:
                n = run_once(con, log_path)
                if n > 0:
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] Imported {n} events into {db_path}")
            except Exception as e:
                con.rollback()
                print(f"[!] Importer error: {e}", file=sys.stderr)
            if args.retention_days > 0:
                try:
                    purge_retention(con, args.retention_days)
                    con.commit()
                except Exception as e:
                    con.rollback()
                    print(f"[!] Retention purge error: {e}", file=sys.stderr)
            time.sleep(args.interval)
    else:
        try:
            n = run_once(con, log_path)
            if args.retention_days > 0:
                purge_retention(con, args.retention_days)
                con.commit()
                con.execute("VACUUM")
            print(f"Imported {n} events into {db_path}.")
        except Exception as e:
            con.rollback()
            print(f"[!] Importer error: {e}", file=sys.stderr)
            raise

if __name__ == "__main__":
    main()
