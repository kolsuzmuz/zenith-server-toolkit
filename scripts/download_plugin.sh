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
GH_TEMP_DIR=""
FINAL_JAR=""

cleanup() {
    rm -f "${TEMP_JAR:-}" "${TEMP_SUMS:-}"
    if [ -n "${GH_TEMP_DIR:-}" ] && [ -d "${GH_TEMP_DIR}" ]; then
        rm -rf "${GH_TEMP_DIR}"
    fi
}
trap cleanup EXIT

# Helper to find the latest release asset URL from GitHub API
detect_latest_release_url() {
    local api_url="https://api.github.com/repos/${REPO}/releases/latest"
    local json_content=""
    local auth_header=()
    local wget_auth=()

    if [ -n "${GITHUB_TOKEN:-}" ]; then
        auth_header=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
        wget_auth=(--header="Authorization: Bearer ${GITHUB_TOKEN}")
    fi

    if command -v curl >/dev/null 2>&1; then
        json_content="$(curl -fsSL ${auth_header[@]+"${auth_header[@]}"} "$api_url" 2>/dev/null || true)"
    elif command -v wget >/dev/null 2>&1; then
        json_content="$(wget -qO- ${wget_auth[@]+"${wget_auth[@]}"} "$api_url" 2>/dev/null || true)"
    fi

    if [ -z "$json_content" ]; then
        return 0
    fi

    local matching_urls=""
    if command -v jq >/dev/null 2>&1; then
        matching_urls="$(echo "$json_content" | jq -r '.assets[]?.browser_download_url // empty' 2>/dev/null | grep 'ZenithServerToolkit.*\.jar$' | grep -v -- '-sources\.jar$' || true)"
    elif command -v python3 >/dev/null 2>&1; then
        matching_urls="$(echo "$json_content" | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
    for a in data.get("assets", []):
        u = a.get("browser_download_url", "")
        if u.endswith(".jar") and "ZenithServerToolkit" in u and not u.endswith("-sources.jar"):
            print(u)
except Exception:
    pass
' 2>/dev/null || true)"
    else
        matching_urls="$(echo "$json_content" | grep -o 'https://github\.com/[^"]*/releases/download/[^"]*/ZenithServerToolkit[^"]*\.jar' | grep -v -- '-sources\.jar' || true)"
    fi

    if [ -n "$matching_urls" ]; then
        echo "$matching_urls" | sort -V | tail -n 1
    fi
}

# Download helper: fetch <url> <output-file>
fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$1" -O "$2"
    else
        return 1
    fi
}

# Verify <jar> against a SHA256SUMS file (lines: "<sha256>  <filename>")
verify_jar() {
    local jar="$1" name="$2" sums="$3" expected actual
    if ! command -v sha256sum >/dev/null 2>&1; then
        echo "[-] sha256sum not found; cannot verify the download." >&2
        return 1
    fi
    expected="$(awk -v n="$name" '{f=$2; sub(/^\*/, "", f)} f==n {print $1; exit}' "$sums" || true)"
    if [ -z "$expected" ]; then
        echo "[-] No checksum entry for ${name} in SHA256SUMS." >&2
        return 1
    fi
    actual="$(sha256sum "$jar" | awk '{print $1}')"
    if [ "$expected" != "$actual" ]; then
        echo "[-] Checksum MISMATCH for ${name}" >&2
        echo "    expected: ${expected}" >&2
        echo "    actual:   ${actual}" >&2
        return 1
    fi
    echo "[+] Checksum OK (${name})"
}

# Method 1: GitHub CLI (gh)
if command -v gh >/dev/null 2>&1; then
    echo "[*] Attempting download via gh CLI..."
    GH_TEMP_DIR="$(mktemp -d)"
    if gh release download latest -R "$REPO" -p "*.jar" -p "SHA256SUMS" -D "$GH_TEMP_DIR" --clobber; then
        CANDIDATE_JAR="$(find "$GH_TEMP_DIR" -maxdepth 1 -name "ZenithServerToolkit*.jar" ! -name "*-sources.jar" | sort -V | tail -n 1 || true)"
        if [ -n "$CANDIDATE_JAR" ] && [ -f "$CANDIDATE_JAR" ]; then
            if verify_jar "$CANDIDATE_JAR" "$(basename "$CANDIDATE_JAR")" "$GH_TEMP_DIR/SHA256SUMS"; then
                rm -f "${TARGET_DIR}"/ZenithServerToolkit*.jar
                mv "$CANDIDATE_JAR" "$TARGET_DIR/"
                FINAL_JAR="${TARGET_DIR}/$(basename "$CANDIDATE_JAR")"
                downloaded=true
            else
                echo "[-] Refusing to install an unverified jar (gh)." >&2
            fi
        fi
    else
        echo "[!] gh release download failed, trying curl/wget..." >&2
    fi
    rm -rf "$GH_TEMP_DIR"
    GH_TEMP_DIR=""
fi

# Method 2: cURL / Wget with dynamic asset detection from GitHub API
if [ "$downloaded" = false ]; then
    echo "[*] Detecting latest release asset from GitHub..."
    RELEASE_URL="$(detect_latest_release_url)"

    if [ -z "$RELEASE_URL" ]; then
        echo "[-] Error: Could not query GitHub API for release assets (possibly rate-limited)." >&2
        echo "    Set GITHUB_TOKEN environment variable with a personal access token and retry." >&2
    else
        JAR_NAME="$(basename "$RELEASE_URL")"
        FINAL_JAR="${TARGET_DIR}/${JAR_NAME}"
        SUMS_URL="${RELEASE_URL%/*}/SHA256SUMS"
        TEMP_SUMS="${TARGET_DIR}/.ZenithServerToolkit-SHA256SUMS.tmp"

        echo "[*] Downloading ${RELEASE_URL}..."
        if fetch "$RELEASE_URL" "$TEMP_JAR" && fetch "$SUMS_URL" "$TEMP_SUMS"; then
            if verify_jar "$TEMP_JAR" "$JAR_NAME" "$TEMP_SUMS"; then
                # Remove old versions only after the new jar is verified
                rm -f "${TARGET_DIR}"/ZenithServerToolkit*.jar
                mv "$TEMP_JAR" "$FINAL_JAR"
                downloaded=true
            else
                echo "[-] Refusing to install an unverified jar." >&2
            fi
        else
            echo "[-] Download failed (jar or SHA256SUMS)." >&2
        fi
        rm -f "$TEMP_JAR" "$TEMP_SUMS"
    fi
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
