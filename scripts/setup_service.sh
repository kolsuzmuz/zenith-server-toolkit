#!/bin/bash
# Automatically creates and installs the Zenith Dashboard systemd service on any Linux VDS.
# Usage: sudo ./scripts/setup_service.sh [--with-importer] [port] [host] [sessions_db_path]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLKIT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SITE_DIR="${TOOLKIT_ROOT}/site"
SERVER_SCRIPT="${SITE_DIR}/server.py"

# Detect Python 3
PYTHON_BIN="$(command -v python3 2>/dev/null || true)"
if [ -z "$PYTHON_BIN" ]; then
    echo "[-] Error: python3 not found in PATH." >&2
    exit 1
fi

# Parse optional --with-importer flag and positional arguments
WITH_IMPORTER=false
POSITIONAL_ARGS=()
for arg in "$@"; do
    if [ "$arg" = "--with-importer" ]; then
        WITH_IMPORTER=true
    else
        POSITIONAL_ARGS+=("$arg")
    fi
done

# Detect execution user
EXEC_USER="${SUDO_USER:-$(id -un)}"

# Detect port, host, and optional database override
PORT="${POSITIONAL_ARGS[0]:-${PORT:-8080}}"
HOST="${POSITIONAL_ARGS[1]:-${HOST:-127.0.0.1}}"

# Safely expand DB_PATH if provided (e.g. ~/zenithproxy/sessions.db -> /home/user/zenithproxy/sessions.db)
RAW_DB="${POSITIONAL_ARGS[2]:-${SESSIONS_DB:-}}"
if [ -n "$RAW_DB" ]; then
    if [[ "$RAW_DB" == "~"* ]]; then
        USER_HOME="$(getent passwd "$EXEC_USER" 2>/dev/null | cut -d: -f6)"
        [ -z "$USER_HOME" ] && USER_HOME="$HOME"
        DB_PATH="${USER_HOME}${RAW_DB:1}"
    else
        DB_PATH="$(readlink -f "$RAW_DB" 2>/dev/null || realpath "$RAW_DB" 2>/dev/null || echo "$RAW_DB")"
    fi
else
    DB_PATH=""
fi

SERVICE_FILE="/etc/systemd/system/zenith-dashboard.service"
GENERATED_FILE="${TOOLKIT_ROOT}/systemd/zenith-dashboard.service"

IMPORTER_SERVICE_FILE="/etc/systemd/system/zenith-importer.service"
IMPORTER_GENERATED_FILE="${TOOLKIT_ROOT}/systemd/zenith-importer.service"
IMPORTER_SCRIPT="${TOOLKIT_ROOT}/scripts/import_sessions.py"

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

generate_importer_unit() {
    local rw_paths="${TOOLKIT_ROOT}"
    if [ -n "$DB_PATH" ]; then
        rw_paths="${rw_paths} $(dirname "$DB_PATH")"
    else
        USER_HOME="$(getent passwd "$EXEC_USER" 2>/dev/null | cut -d: -f6)"
        [ -z "$USER_HOME" ] && USER_HOME="$HOME"
        rw_paths="${rw_paths} ${USER_HOME}/zenithproxy"
    fi
    cat <<EOF
[Unit]
Description=Zenith Session Importer - Ingests sessions.log into SQLite
After=network.target

[Service]
Type=simple
User=${EXEC_USER}
WorkingDirectory=${TOOLKIT_ROOT}
Environment="PYTHONDONTWRITEBYTECODE=1"
$( [ -n "$DB_PATH" ] && echo "Environment=\"SESSIONS_DB=${DB_PATH}\"" )
ExecStart=${PYTHON_BIN} ${IMPORTER_SCRIPT} --loop
Restart=always
RestartSec=5

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=${rw_paths}
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

    if [ "$WITH_IMPORTER" = true ]; then
        echo "[*] Writing importer systemd service to ${IMPORTER_SERVICE_FILE}..."
        generate_importer_unit > "${IMPORTER_SERVICE_FILE}"
        chmod 644 "${IMPORTER_SERVICE_FILE}"
    fi

    echo "[*] Reloading systemd daemon..."
    systemctl daemon-reload

    echo "[*] Enabling and starting zenith-dashboard.service..."
    systemctl enable --now zenith-dashboard.service

    if [ "$WITH_IMPORTER" = true ]; then
        echo "[*] Enabling and starting zenith-importer.service..."
        systemctl enable --now zenith-importer.service
    fi

    echo "[+] Service active! Checking status:"
    systemctl status zenith-dashboard.service --no-pager || true
    echo ""
    echo "[+] Dashboard is running at http://${HOST}:${PORT}"
else
    echo "[*] Not running as root. Generating unit file at: ${GENERATED_FILE}"
    generate_unit > "${GENERATED_FILE}"

    if [ "$WITH_IMPORTER" = true ]; then
        echo "[*] Generating importer unit file at: ${IMPORTER_GENERATED_FILE}"
        generate_importer_unit > "${IMPORTER_GENERATED_FILE}"
    fi

    echo ""
    echo "[+] To install and activate the service on your system, run:"
    echo "    sudo cp ${GENERATED_FILE} ${SERVICE_FILE}"
    if [ "$WITH_IMPORTER" = true ]; then
        echo "    sudo cp ${IMPORTER_GENERATED_FILE} ${IMPORTER_SERVICE_FILE}"
    fi
    echo "    sudo systemctl daemon-reload"
    echo "    sudo systemctl enable --now zenith-dashboard.service"
    if [ "$WITH_IMPORTER" = true ]; then
        echo "    sudo systemctl enable --now zenith-importer.service"
    fi
fi
