#!/bin/bash
# Downloads or updates the latest ZenithServerToolkit plugin .jar.
# Automatically detects proxy directory and GitHub repository on any VDS.
# Usage: ./scripts/download_plugin.sh [target_plugins_directory]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLKIT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Resolve target plugins directory dynamically
resolve_target_dir() {
    # 1. Positional argument
    if [ -n "${1:-}" ]; then
        echo "$1"
        return 0
    fi

    # 2. Environment variables
    if [ -n "${ZENITH_PLUGINS_DIR:-}" ]; then
        echo "${ZENITH_PLUGINS_DIR}"
        return 0
    fi
    for env_var in "${ZENITH_DIR:-}" "${ZENITH_HOME:-}" "${PROXY_DIR:-}"; do
        if [ -n "$env_var" ] && [ -d "$env_var" ]; then
            echo "$env_var/plugins"
            return 0
        fi
    done

    # 3. Check current working directory
    if [ -d "$(pwd)/plugins" ]; then
        echo "$(pwd)/plugins"
        return 0
    fi
    if [ -f "$(pwd)/config.json" ] || [ -f "$(pwd)/launch" ] || [ -f "$(pwd)/launch.sh" ]; then
        echo "$(pwd)/plugins"
        return 0
    fi

    # 4. Check existing plugins directories
    local existing_candidates=(
        "${TOOLKIT_ROOT}/../zenithproxy/plugins"
        "${HOME}/zenithproxy/plugins"
        "${TOOLKIT_ROOT}/../proxy/plugins"
        "${HOME}/proxy/plugins"
        "${TOOLKIT_ROOT}/zenithproxy/plugins"
        "${HOME}/.zenithproxy/plugins"
        "/opt/zenithproxy/plugins"
        "/var/zenithproxy/plugins"
        "${TOOLKIT_ROOT}/../plugins"
    )

    for dir in "${existing_candidates[@]}"; do
        if [ -d "$dir" ]; then
            echo "$dir"
            return 0
        fi
    done

    # 5. Check existing proxy root directories to attach /plugins to
    local proxy_roots=(
        "${HOME}/zenithproxy"
        "${TOOLKIT_ROOT}/../zenithproxy"
        "${HOME}/proxy"
        "${TOOLKIT_ROOT}/../proxy"
        "/opt/zenithproxy"
        "/var/zenithproxy"
    )

    for pdir in "${proxy_roots[@]}"; do
        if [ -d "$pdir" ]; then
            echo "$pdir/plugins"
            return 0
        fi
    done

    # 6. Default standard fallback
    echo "${HOME}/zenithproxy/plugins"
}

TARGET_DIR="$(resolve_target_dir "${1:-}")"
mkdir -p "$TARGET_DIR"

# Detect repository dynamically from git remote
resolve_repo() {
    if [ -n "${GITHUB_REPOSITORY:-}" ]; then
        echo "${GITHUB_REPOSITORY}"
        return 0
    fi
    if command -v git >/dev/null 2>&1; then
        local remote_url
        remote_url="$(git -C "${TOOLKIT_ROOT}" config --get remote.origin.url 2>/dev/null || true)"
        if [[ "$remote_url" =~ github\.com[:/]([^/]+/[^/.]+) ]]; then
            echo "${BASH_REMATCH[1]}"
            return 0
        fi
    fi
    echo "kolsuzmuz/zenith-server-toolkit"
}

REPO="$(resolve_repo)"

echo "[*] Target plugins folder: $TARGET_DIR"
echo "[*] Repository source: $REPO"

downloaded=false
TEMP_JAR="${TARGET_DIR}/.ZenithServerToolkit-download.tmp"
FINAL_JAR=""

# Helper to find the latest release asset URL from GitHub API
detect_latest_release_url() {
    local api_url="https://api.github.com/repos/${REPO}/releases/latest"
    local found_url=""
    if command -v curl >/dev/null 2>&1; then
        found_url="$(curl -fsSL "$api_url" 2>/dev/null | grep -o 'https://github.com/[^"]*/releases/download/[^"]*/ZenithServerToolkit[^"]*\.jar' | head -n 1 || true)"
    elif command -v wget >/dev/null 2>&1; then
        found_url="$(wget -qO- "$api_url" 2>/dev/null | grep -o 'https://github.com/[^"]*/releases/download/[^"]*/ZenithServerToolkit[^"]*\.jar' | head -n 1 || true)"
    fi
    echo "$found_url"
}

# Method 1: GitHub CLI (gh)
if command -v gh >/dev/null 2>&1; then
    echo "[*] Attempting download via gh CLI..."
    if gh release download latest -R "$REPO" -p "*.jar" -D "$TARGET_DIR" --clobber 2>/dev/null; then
        downloaded=true
        FINAL_JAR="$(find "$TARGET_DIR" -maxdepth 1 -name "ZenithServerToolkit*.jar" ! -name "*-sources.jar" | head -n 1 || true)"
    elif gh run download -R "$REPO" -n zenith-server-toolkit-jar --dir "$TARGET_DIR" 2>/dev/null; then
        downloaded=true
        FINAL_JAR="$(find "$TARGET_DIR" -maxdepth 1 -name "ZenithServerToolkit*.jar" ! -name "*-sources.jar" | head -n 1 || true)"
    fi
fi

# Method 2: cURL / Wget with dynamic asset detection from GitHub API / latest redirect
if [ "$downloaded" = false ]; then
    echo "[*] Detecting latest release asset from GitHub..."
    RELEASE_URL="$(detect_latest_release_url)"

    # Fallback to standard release naming if API rate-limited
    if [ -z "$RELEASE_URL" ]; then
        RELEASE_URL="https://github.com/${REPO}/releases/latest/download/ZenithServerToolkit-1.0.0.jar"
    fi

    JAR_NAME="$(basename "$RELEASE_URL")"
    FINAL_JAR="${TARGET_DIR}/${JAR_NAME}"

    if command -v curl >/dev/null 2>&1; then
        echo "[*] Downloading via curl from ${RELEASE_URL}..."
        if curl -fsSL "$RELEASE_URL" -o "$TEMP_JAR" 2>/dev/null; then
            # Clean up any existing ZenithServerToolkit jars to prevent duplicate version conflicts
            rm -f "${TARGET_DIR}"/ZenithServerToolkit*.jar
            mv "$TEMP_JAR" "$FINAL_JAR"
            downloaded=true
        fi
    elif command -v wget >/dev/null 2>&1; then
        echo "[*] Downloading via wget from ${RELEASE_URL}..."
        if wget -q "$RELEASE_URL" -O "$TEMP_JAR" 2>/dev/null; then
            rm -f "${TARGET_DIR}"/ZenithServerToolkit*.jar
            mv "$TEMP_JAR" "$FINAL_JAR"
            downloaded=true
        fi
    fi
    rm -f "$TEMP_JAR"
fi

# Method 3: Local build artifact fallback if found
if [ "$downloaded" = false ]; then
    LOCAL_JAR="$(find "${TOOLKIT_ROOT}/plugin/build/libs" -name "ZenithServerToolkit*.jar" ! -name "*-sources.jar" 2>/dev/null | head -n 1 || true)"
    if [ -n "$LOCAL_JAR" ] && [ -f "$LOCAL_JAR" ]; then
        echo "[*] Found pre-built local artifact: $LOCAL_JAR"
        rm -f "${TARGET_DIR}"/ZenithServerToolkit*.jar
        cp "$LOCAL_JAR" "$TARGET_DIR/"
        FINAL_JAR="${TARGET_DIR}/$(basename "$LOCAL_JAR")"
        downloaded=true
    fi
fi

if [ "$downloaded" = true ]; then
    echo "[+] Plugin successfully installed in $TARGET_DIR:"
    ls -lh "$TARGET_DIR"/ZenithServerToolkit*.jar 2>/dev/null || true
else
    echo "[-] Error: Could not download or find plugin JAR." >&2
    echo "    Check network connection or place ZenithServerToolkit*.jar into $TARGET_DIR manually." >&2
    exit 1
fi
