#!/bin/bash
# Watchdog script for ZenithProxy running inside a tmux session.
# Automatically detects proxy installation and restarts on crash/reboot.
# Can be run via cron (e.g. */5 * * * * flock -n /tmp/watchdog.lock /path/to/watchdog.sh)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLKIT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Resolve proxy directory dynamically across common locations
resolve_proxy_dir() {
    # 1. Positional argument
    if [ -n "${1:-}" ] && [ -d "$1" ]; then
        echo "$1"
        return 0
    fi

    # 2. Environment variables
    for env_var in "${ZENITH_DIR:-}" "${ZENITH_HOME:-}" "${PROXY_DIR:-}"; do
        if [ -n "$env_var" ] && [ -d "$env_var" ]; then
            echo "$env_var"
            return 0
        fi
    done

    # 3. Candidate directories to check for ZenithProxy signatures
    local candidates=(
        "$(pwd)"
        "${TOOLKIT_ROOT}/.."
        "${TOOLKIT_ROOT}/../zenithproxy"
        "${TOOLKIT_ROOT}/../proxy"
        "${TOOLKIT_ROOT}/zenithproxy"
        "${HOME}/zenithproxy"
        "${HOME}/proxy"
        "${HOME}/.zenithproxy"
        "/opt/zenithproxy"
        "/var/zenithproxy"
    )

    for dir in "${candidates[@]}"; do
        if [ -d "$dir" ]; then
            if [ -f "$dir/launch" ] || [ -f "$dir/launch.sh" ] || [ -f "$dir/config.json" ] || ls "$dir"/*ZenithProxy*.jar >/dev/null 2>&1; then
                echo "$(cd "$dir" && pwd)"
                return 0
            fi
        fi
    done

    # 4. Fallback to current working directory
    pwd
}

PROXY_DIR="$(resolve_proxy_dir "${1:-}")"
TMUX_SESSION="${ZENITH_TMUX_SESSION:-proxy_server}"
LOG_FILE="${PROXY_DIR}/watchdog.log"
CHECK_DELAY="${ZENITH_CHECK_DELAY:-90}"
CONNECT_DELAY="${ZENITH_CONNECT_DELAY:-60}"
CONNECT_CMD="${ZENITH_CONNECT_CMD:-connect}"

is_proxy_running() {
    pgrep -u "$(id -u)" -f "ZenithProxy" >/dev/null 2>&1 || pgrep -u "$(id -u)" -f "ZenithProxy.jar" >/dev/null 2>&1
}

# If proxy is currently running, exit immediately
if is_proxy_running; then
    exit 0
fi

# Wait to prevent false alarms during planned updates / server restarts
sleep "${CHECK_DELAY}"

if is_proxy_running; then
    exit 0
fi

mkdir -p "$(dirname "${LOG_FILE}")" 2>/dev/null || true
echo "$(date '+%Y-%m-%d %H:%M:%S') Proxy process not detected, restarting in ${PROXY_DIR}..." >> "${LOG_FILE}"

# Determine appropriate launch command
determine_launch_cmd() {
    if [ -f "${PROXY_DIR}/launch" ]; then
        [ -x "${PROXY_DIR}/launch" ] || chmod +x "${PROXY_DIR}/launch" 2>/dev/null || true
        echo "./launch"
    elif [ -f "${PROXY_DIR}/launch.sh" ]; then
        [ -x "${PROXY_DIR}/launch.sh" ] || chmod +x "${PROXY_DIR}/launch.sh" 2>/dev/null || true
        echo "./launch.sh"
    elif ls "${PROXY_DIR}"/*ZenithProxy*.jar >/dev/null 2>&1; then
        local jar_name
        jar_name="$(basename "$(ls -t "${PROXY_DIR}"/*ZenithProxy*.jar | head -n 1)")"
        echo "java -jar ${jar_name}"
    else
        echo "./launch"
    fi
}

LAUNCH_CMD="$(determine_launch_cmd)"

# Ensure tmux session exists
tmux has-session -t "${TMUX_SESSION}" 2>/dev/null || tmux new-session -d -s "${TMUX_SESSION}"

# Launch proxy inside tmux session
tmux send-keys -t "${TMUX_SESSION}" "cd \"${PROXY_DIR}\" && ${LAUNCH_CMD}; exec bash" C-m

if [ -n "${CONNECT_CMD}" ] && [ "${CONNECT_DELAY}" -gt 0 ]; then
    sleep "${CONNECT_DELAY}"
    tmux send-keys -t "${TMUX_SESSION}" "${CONNECT_CMD}" C-m
fi

echo "$(date '+%Y-%m-%d %H:%M:%S') Proxy restarted successfully in tmux session '${TMUX_SESSION}' (Command: ${LAUNCH_CMD})" >> "${LOG_FILE}"
