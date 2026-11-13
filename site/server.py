#!/usr/bin/env python3
"""
Lightweight web dashboard for player count visualization.
Serves a Chart.js frontend and an /api endpoint with aggregated data.
Designed for zero-configuration portability across any Linux VDS.
"""

import argparse
import json
import os
import sqlite3
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
TOOLKIT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))

DB_OVERRIDE = None

def resolve_db():
    """
    Dynamically finds sessions.db across environment variables,
    CLI arguments, and standard candidate locations.
    """
    global DB_OVERRIDE
    if DB_OVERRIDE:
        return os.path.abspath(DB_OVERRIDE)

    # 1. Environment variables
    if "SESSIONS_DB" in os.environ and os.environ["SESSIONS_DB"]:
        return os.path.abspath(os.environ["SESSIONS_DB"])

    for env_key in ("ZENITH_DIR", "ZENITH_HOME", "PROXY_DIR"):
        if env_key in os.environ and os.environ[env_key]:
            p = os.path.join(os.environ[env_key], "sessions.db")
            if os.path.exists(p):
                return os.path.abspath(p)

    # 2. Dynamic candidate paths
    candidates = [
        os.path.join(os.getcwd(), "sessions.db"),
        os.path.join(SCRIPT_DIR, "sessions.db"),
        os.path.join(TOOLKIT_ROOT, "sessions.db"),
        os.path.join(TOOLKIT_ROOT, "..", "sessions.db"),
        os.path.join(TOOLKIT_ROOT, "..", "zenithproxy", "sessions.db"),
        os.path.join(TOOLKIT_ROOT, "..", "proxy", "sessions.db"),
        os.path.join(TOOLKIT_ROOT, "zenithproxy", "sessions.db"),
        os.path.expanduser("~/zenithproxy/sessions.db"),
        os.path.expanduser("~/proxy/sessions.db"),
        os.path.expanduser("~/.zenithproxy/sessions.db"),
        "/opt/zenithproxy/sessions.db",
        "/var/zenithproxy/sessions.db",
    ]

    for candidate in candidates:
        if os.path.exists(candidate):
            return os.path.abspath(candidate)

    # Fallback to configured override or sessions.db in current directory
    return os.path.abspath(DB_OVERRIDE if DB_OVERRIDE else "sessions.db")

RANGES = {
    "30d": 30 * 86400,
    "15d": 15 * 86400,
    "7d": 7 * 86400,
    "1d": 86400,
    "12h": 43200,
    "6h": 21600,
    "3h": 10800,
}
BUCKETS = 200
cache = {}
cache_lock = threading.Lock()

def iso(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+00:00")

def compute(span):
    now = int(time.time())
    step = max(120, span // BUCKETS // 60 * 60)
    n = span // step
    start = now - n * step
    db_path = resolve_db()

    if not os.path.exists(db_path):
        return {"step": step, "points": [], "db_found": False}

    try:
        with sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5) as con:
            rows = con.execute(
                "SELECT CAST(strftime('%s', ts) AS INTEGER), CAST(name AS INTEGER) "
                "FROM events WHERE kind='HEARTBEAT' AND ts >= ?", (iso(start),)).fetchall()
    except Exception as e:
        print(f"[!] SQLite query error ({db_path}): {e}", file=sys.stderr)
        return {"step": step, "points": [], "db_found": True}

    sums = [0.0] * n
    cnt = [0] * n
    for t, c in rows:
        i = (t - start) // step
        if 0 <= i < n:
            sums[i] += max(0, c - 1)  # Exclude bot proxy account from player count
            cnt[i] += 1

    pts = [
        [start + i * step + step // 2, round(sums[i] / cnt[i]) if cnt[i] else None]
        for i in range(n)
    ]
    return {"step": step, "points": pts, "db_found": True}

PAGE = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Player Count Dashboard</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 16px;
      background-color: #0f172a;
      color: #e2e8f0;
    }
    h2 {
      margin-bottom: 16px;
      font-weight: 600;
    }
    #buttons {
      margin-bottom: 20px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    button {
      background: #1e293b;
      color: #94a3b8;
      border: 1px solid #334155;
      padding: 6px 14px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 14px;
      transition: all 0.15s ease;
    }
    button:hover {
      background: #334155;
      color: #f8fafc;
    }
    button.active {
      background: #38bdf8;
      color: #0f172a;
      font-weight: 600;
      border-color: #38bdf8;
    }
    .chart-container {
      background: #1e293b;
      border-radius: 12px;
      padding: 20px;
      border: 1px solid #334155;
      position: relative;
    }
    #statusMsg {
      display: none;
      text-align: center;
      padding: 40px 20px;
      color: #94a3b8;
      font-size: 15px;
    }
  </style>
</head>
<body>
  <h2>Player Count</h2>
  <div id="buttons"></div>
  <div class="chart-container">
    <div id="statusMsg"></div>
    <canvas id="chartCanvas"></canvas>
  </div>

  <script>
    let chart;
    let currentRange = '1d';
    const ranges = ['30d', '15d', '7d', '1d', '12h', '6h', '3h'];
    const btnContainer = document.getElementById('buttons');
    const canvas = document.getElementById('chartCanvas');
    const statusMsg = document.getElementById('statusMsg');

    async function loadRange(range) {
      currentRange = range;
      document.querySelectorAll('#buttons button').forEach(b => {
        b.classList.toggle('active', b.dataset.range === range);
      });

      try {
        const res = await fetch(`api?range=${range}`);
        const data = await res.json();
        const fmt = t => new Date(t * 1000).toLocaleString('en-GB', {
          day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'
        });

        const hasValidPoints = data.points && data.points.some(p => p[1] !== null);
        if (!hasValidPoints) {
          canvas.style.display = 'none';
          statusMsg.style.display = 'block';
          statusMsg.textContent = data.db_found === false 
            ? 'Database file (sessions.db) not found or no data recorded yet.'
            : 'No player heartbeat data recorded in this time range.';
          if (chart) { chart.destroy(); chart = null; }
          return;
        }

        canvas.style.display = 'block';
        statusMsg.style.display = 'none';

        if (chart) chart.destroy();
        chart = new Chart(canvas, {
          type: 'line',
          data: {
            labels: data.points.map(p => fmt(p[0])),
            datasets: [{
              label: 'Players Online',
              data: data.points.map(p => p[1]),
              borderColor: '#38bdf8',
              backgroundColor: 'rgba(56, 189, 248, 0.1)',
              fill: true,
              pointRadius: 0,
              tension: 0.2
            }]
          },
          options: {
            responsive: true,
            scales: {
              y: {
                beginAtZero: true,
                grid: { color: '#334155' },
                ticks: { color: '#94a3b8' }
              },
              x: {
                grid: { color: '#334155' },
                ticks: { color: '#94a3b8', maxTicksLimit: 12 }
              }
            },
            interaction: {
              intersect: false,
              mode: 'index'
            },
            plugins: {
              legend: {
                labels: { color: '#e2e8f0' }
              }
            }
          }
        });
      } catch (err) {
        console.error('Failed to load chart data:', err);
      }
    }

    ranges.forEach(r => {
      const btn = document.createElement('button');
      btn.textContent = r;
      btn.dataset.range = r;
      btn.onclick = () => loadRange(r);
      btnContainer.appendChild(btn);
    });

    loadRange('1d');
  </script>
</body>
</html>
"""

class DashboardHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/api":
            r = parse_qs(u.query).get("range", ["1d"])[0]
            span = RANGES.get(r, 86400)
            with cache_lock:
                hit = cache.get(span)
            if not hit or time.time() - hit[0] > 30:
                data = compute(span)
                with cache_lock:
                    hit = (time.time(), json.dumps(data).encode())
                    cache[span] = hit
            body, ctype = hit[1], "application/json"
        elif u.path == "/":
            body, ctype = PAGE.encode(), "text/html; charset=utf-8"
        else:
            self.send_error(404)
            return

        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "SAMEORIGIN")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def log_message(self, *args):
        pass

def parse_args():
    parser = argparse.ArgumentParser(description="Zenith Dashboard HTTP Server")
    parser.add_argument("--host", default=os.environ.get("HOST", "0.0.0.0"),
                        help="Host to bind to (default: 0.0.0.0 or HOST env var)")
    port_default = os.environ.get("PORT", "8080")
    try:
        port_default = int(port_default)
    except (ValueError, TypeError):
        port_default = 8080
    parser.add_argument("--port", type=int, default=port_default,
                        help="Port to bind to (default: 8080 or PORT env var)")
    parser.add_argument("--db", default=None,
                        help="Path to sessions.db (defaults to auto-detection or SESSIONS_DB env)")
    return parser.parse_args()

def main():
    global DB_OVERRIDE
    args = parse_args()
    if args.db:
        DB_OVERRIDE = args.db

    db = resolve_db()
    print(f"[*] Starting dashboard on http://{args.host}:{args.port}")
    print(f"[*] Active sessions database: {db} {'(Found)' if os.path.exists(db) else '(Waiting for creation)'}")

    server = ThreadingHTTPServer((args.host, args.port), DashboardHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Server stopped.")

if __name__ == "__main__":
    main()
