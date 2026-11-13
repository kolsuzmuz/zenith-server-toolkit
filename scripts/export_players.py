#!/usr/bin/env python3
"""
Exports player records from an external SQLite database into players_seed.tsv
compatible with the ChatStatsBot module.
"""

import argparse
import datetime
import os
import sqlite3
import sys

def parse_args():
    parser = argparse.ArgumentParser(description="Export player data into players_seed.tsv")
    parser.add_argument("--src", required=True, help="Path to source SQLite database (e.g., chat_data.db)")
    parser.add_argument("--out", default="players_seed.tsv", help="Output TSV path (default: players_seed.tsv)")
    return parser.parse_args()

def ep(s):
    if not s:
        return 0
    if isinstance(s, (int, float)):
        return int(s)
    s = str(s).strip()
    if s.isdigit():
        return int(s)
    try:
        d = datetime.datetime.fromisoformat(s)
        if d.tzinfo is None:
            d = d.replace(tzinfo=datetime.timezone.utc)
        return int(d.timestamp())
    except Exception:
        return 0

def to_int(v):
    if v is None:
        return 0
    if isinstance(v, (int, float)):
        return int(v)
    s = str(v).strip()
    if s.isdigit():
        return int(s)
    return 0

def main():
    args = parse_args()
    if not os.path.exists(args.src):
        print(f"Error: Source database '{args.src}' not found.", file=sys.stderr)
        sys.exit(1)

    src = sqlite3.connect(f"file:{os.path.abspath(args.src)}?mode=ro", uri=True)
    n = 0
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("#lastTick|0\n")
        query = ("SELECT username, first_seen, last_seen, message_count, "
                 "join_count, total_playtime_seconds FROM players")
        try:
            for u, fs, ls, mc, jc, pt in src.execute(query):
                if not u or "|" in u or any(c.isspace() for c in u):
                    continue
                f.write(f"{u}|{ep(fs)}|{ep(ls)}|{to_int(mc)}|{to_int(jc)}|{to_int(pt)}|0\n")
                n += 1
        except sqlite3.OperationalError as e:
            print(f"Database query error: {e}", file=sys.stderr)
            sys.exit(1)

    print(f"Exported {n} players -> {args.out}")

if __name__ == "__main__":
    main()
