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

    # 3. Running process detection
    local running_pid
    running_pid="$(pgrep -u "$(id -u)" -f "java.*ZenithProxy" 2>/dev/null | head -n 1 || pgrep -u "$(id -u)" -f "ZenithProxy.*\.jar" 2>/dev/null | head -n 1 || true)"
    if [ -n "$running_pid" ] && [ -e "/proc/${running_pid}/cwd" ]; then
        local running_dir
        running_dir="$(readlink -f "/proc/${running_pid}/cwd" 2>/dev/null || true)"
        if [ -n "$running_dir" ] && [ -d "$running_dir" ]; then
            echo "$running_dir"
            return 0
        fi
    fi

    # 4. Candidate directories to check for ZenithProxy signatures
    local candidates=("$(pwd)")

    local search_roots=("${TOOLKIT_ROOT}/.." "${HOME}" "/opt")
    for s_root in "${search_roots[@]}"; do
        if [ -d "$s_root" ]; then
            for d in "${s_root}"/*zenith* "${s_root}"/*proxy*; do
                if [ -d "$d" ] && [ "$d" != "${TOOLKIT_ROOT}" ]; then
                    candidates+=("$d")
                fi
            done
        fi
    done

    candidates+=(
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
            if [ -f "$dir/launch" ] || [ -f "$dir/launch.sh" ] || ls "$dir"/*ZenithProxy*.jar >/dev/null 2>&1; then
                echo "$(cd "$dir" && pwd)"
                return 0
            fi
        fi
    done

    # 5. Fallback to current working directory
    pwd
}

PROXY_DIR="$(resolve_proxy_dir "${1:-}")"
TMUX_SESSION="${ZENITH_TMUX_SESSION:-proxy_server}"
LOG_FILE="${PROXY_DIR}/watchdog.log"
CHECK_DELAY="${ZENITH_CHECK_DELAY:-90}"
CONNECT_DELAY="${ZENITH_CONNECT_DELAY:-60}"
CONNECT_CMD="${ZENITH_CONNECT_CMD:-connect}"

# Ensure tmux is installed
if ! command -v tmux >/dev/null 2>&1; then
    mkdir -p "$(dirname "${LOG_FILE}")" 2>/dev/null || true
    echo "$(date '+%Y-%m-%d %H:%M:%S') ERROR: tmux is not installed. Please install tmux (e.g., sudo apt install tmux)." | tee -a "${LOG_FILE}" >&2
    exit 1
fi

is_proxy_running() {
    pgrep -u "$(id -u)" -f "java.*ZenithProxy" >/dev/null 2>&1 && return 0
    pgrep -u "$(id -u)" -f "ZenithProxy.*\.jar" >/dev/null 2>&1 && return 0
    local pid cwd real_dir
    real_dir="$(cd "$PROXY_DIR" && pwd -P)"
    for pid in $(pgrep -u "$(id -u)" -x java 2>/dev/null || true); do
        cwd="$(readlink "/proc/${pid}/cwd" 2>/dev/null || true)"
        if [ "$cwd" = "$real_dir" ]; then
            return 0
        fi
    done
    return 1
}

# If proxy is currently running, exit immediately
if is_proxy_running; then
    exit 0
fi

# Maintenance switch: exit cleanly if disabled before settling delay
if [ -f "${PROXY_DIR}/watchdog.disabled" ]; then
    mkdir -p "$(dirname "${LOG_FILE}")" 2>/dev/null || true
    echo "$(date '+%Y-%m-%d %H:%M:%S') Maintenance mode: ${PROXY_DIR}/watchdog.disabled found, skipping restart." >> "${LOG_FILE}"
    exit 0
fi

# Inform user if running in terminal and wait to prevent false alarms during updates
echo "[*] Proxy process not detected. Waiting ${CHECK_DELAY}s settling delay before restart..."
sleep "${CHECK_DELAY}"

if is_proxy_running; then
    echo "[*] Proxy process recovered during settling delay."
    exit 0
fi

# Re-check maintenance switch in case it was created during settling delay
if [ -f "${PROXY_DIR}/watchdog.disabled" ]; then
    mkdir -p "$(dirname "${LOG_FILE}")" 2>/dev/null || true
    echo "$(date '+%Y-%m-%d %H:%M:%S') Maintenance mode: ${PROXY_DIR}/watchdog.disabled found, skipping restart." >> "${LOG_FILE}"
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

# Safety: never type into a pane that is busy (e.g. the proxy console is already running there)
pane_pid="$(tmux display-message -p -t "${TMUX_SESSION}" '#{pane_pid}' 2>/dev/null || true)"
if [ -n "$pane_pid" ] && pgrep -P "$pane_pid" >/dev/null 2>&1; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') tmux session '${TMUX_SESSION}' is busy (a program is running in it); NOT typing the launch command." >> "${LOG_FILE}"
    exit 1
fi
# Launch proxy inside tmux session
tmux send-keys -t "${TMUX_SESSION}" "cd \"${PROXY_DIR}\" && ${LAUNCH_CMD}; exec bash" C-m

# Verify proxy process actually started (poll for up to ~30s)
started=false
for i in {1..15}; do
    sleep 2
    if is_proxy_running; then
        started=true
        break
    fi
done

if [ "$started" = false ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') ERROR: proxy did not start" >> "${LOG_FILE}"
    exit 1
fi

echo "$(date '+%Y-%m-%d %H:%M:%S') Proxy restarted successfully in tmux session '${TMUX_SESSION}' (Command: ${LAUNCH_CMD})" >> "${LOG_FILE}"

if [ -n "${CONNECT_CMD}" ] && [ "${CONNECT_DELAY}" -gt 0 ]; then
    sleep "${CONNECT_DELAY}"
    if is_proxy_running; then
        tmux send-keys -t "${TMUX_SESSION}" "${CONNECT_CMD}" C-m
    fi
fi
