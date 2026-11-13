#!/bin/bash
# Automatically creates and installs the Zenith Dashboard systemd service on any Linux VDS.
# Usage: sudo ./scripts/setup_service.sh [port] [host] [sessions_db_path]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLKIT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SITE_DIR="${TOOLKIT_ROOT}/site"
SERVER_SCRIPT="${SITE_DIR}/server.py"

# Detect Python 3
PYTHON_BIN="$(which python3 2>/dev/null || true)"
if [ -z "$PYTHON_BIN" ]; then
    echo "[-] Error: python3 not found in PATH." >&2
    exit 1
fi

# Detect execution user
EXEC_USER="${SUDO_USER:-$(id -un)}"

# Detect port, host, and optional database override
PORT="${1:-${PORT:-8080}}"
HOST="${2:-${HOST:-0.0.0.0}}"

# Safely expand DB_PATH if provided (e.g. ~/zenithproxy/sessions.db -> /home/user/zenithproxy/sessions.db)
if [ -n "${3:-${SESSIONS_DB:-}}" ]; then
    RAW_DB="${3:-${SESSIONS_DB:-}}"
    if [[ "$RAW_DB" == "~"* ]]; then
        USER_HOME="$(eval echo "~${EXEC_USER}")"
        DB_PATH="${USER_HOME}${RAW_DB:1}"
    else
        DB_PATH="$(readlink -f "$RAW_DB" 2>/dev/null || realpath "$RAW_DB" 2>/dev/null || echo "$RAW_DB")"
    fi
else
    DB_PATH=""
fi

SERVICE_FILE="/etc/systemd/system/zenith-dashboard.service"
GENERATED_FILE="${TOOLKIT_ROOT}/systemd/zenith-dashboard.service"

generate_unit() {
    cat <<EOF
[Unit]
Description=Zenith Dashboard - Player Count Web Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${EXEC_USER}
WorkingDirectory=${SITE_DIR}
Environment="HOST=${HOST}"
Environment="PORT=${PORT}"
Environment="PYTHONDONTWRITEBYTECODE=1"
$( [ -n "$DB_PATH" ] && echo "Environment=\"SESSIONS_DB=${DB_PATH}\"" )
EnvironmentFile=-/etc/default/zenith-dashboard
ExecStart=${PYTHON_BIN} ${SERVER_SCRIPT}
Restart=always
RestartSec=5

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
PrivateTmp=true
MemoryMax=80M
CPUQuota=30%

[Install]
WantedBy=multi-user.target
EOF
}

echo "[*] Configuring Zenith Dashboard Service for this VDS:"
echo "    - User:             ${EXEC_USER}"
echo "    - WorkingDirectory: ${SITE_DIR}"
echo "    - ExecStart:        ${PYTHON_BIN} ${SERVER_SCRIPT}"
echo "    - Bind Address:     http://${HOST}:${PORT}"
if [ -n "$DB_PATH" ]; then
    echo "    - Custom Database:  ${DB_PATH}"
fi

if [ "$(id -u)" -eq 0 ]; then
    echo "[*] Writing systemd service to ${SERVICE_FILE}..."
    generate_unit > "${SERVICE_FILE}"
    chmod 644 "${SERVICE_FILE}"

    echo "[*] Reloading systemd daemon..."
    systemctl daemon-reload

    echo "[*] Enabling and starting zenith-dashboard.service..."
    systemctl enable --now zenith-dashboard.service

    echo "[+] Service active! Checking status:"
    systemctl status zenith-dashboard.service --no-pager || true
    echo ""
    echo "[+] Dashboard is running at http://${HOST}:${PORT}"
else
    echo "[*] Not running as root. Generating unit file at: ${GENERATED_FILE}"
    generate_unit > "${GENERATED_FILE}"
    echo ""
    echo "[+] To install and activate the service on your system, run:"
    echo "    sudo cp ${GENERATED_FILE} ${SERVICE_FILE}"
    echo "    sudo systemctl daemon-reload"
    echo "    sudo systemctl enable --now zenith-dashboard.service"
fi
