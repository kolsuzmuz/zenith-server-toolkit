# Complete Setup & Production Deployment Guide

This guide provides an exhaustive, step-by-step walkthrough for deploying **ZenithProxy** and the **Zenith Server Toolkit** on any fresh Linux server or VDS (Virtual Dedicated Server).

---

## Table of Contents

1. [System Requirements & Distro Prerequisites](#1-system-requirements--distro-prerequisites)
   - [Ubuntu & Debian](#installing-on-ubuntudebian)
   - [AlmaLinux, Rocky Linux, CentOS & Fedora](#installing-on-almalinux-rocky-centos-fedora)
   - [Arch Linux](#installing-on-arch-linux)
2. [Java 25 Runtime Setup](#2-java-25-runtime-setup)
3. [ZenithProxy Installation (First-Time Setup)](#3-zenithproxy-installation-first-time-setup)
   - [Directory & Binary Setup](#31-directory--binary-setup)
   - [Minecraft Account Authentication](#32-minecraft-account-authentication)
4. [Installing Zenith Server Toolkit Plugin](#4-installing-zenith-server-toolkit-plugin)
   - [Option A: Automated Helper Script (Recommended)](#option-a-automated-helper-script-recommended)
   - [Option B: One-Command Download via GitHub CLI](#option-b-one-command-download-via-github-cli)
   - [Option C: Direct cURL Download](#option-c-direct-curl-download)
5. [First Launch & Plugin Configuration](#5-first-launch--plugin-configuration)
   - [Configuration Options Breakdown](#configuration-options-breakdown)
   - [Event Mode vs. Scoreboard Mode](#event-mode-vs-scoreboard-mode)
6. [In-Game Chatbot Activation & Safety Switch](#6-in-game-chatbot-activation--safety-switch)
   - [Dry-Run Mode (Default)](#dry-run-mode-default)
   - [Enabling Live Replies](#enabling-live-replies)
   - [Spam & Cooldown Rules](#spam--cooldown-rules)
7. [Background Session Ingestion Daemon](#7-background-session-ingestion-daemon)
   - [Manual Execution](#manual-execution)
   - [Persistent Background Execution (nohup)](#persistent-background-execution-nohup)
   - [Crontab Auto-Start on Boot](#crontab-auto-start-on-boot)
8. [Web Dashboard Setup](#8-web-dashboard-setup)
   - [Local Testing](#local-testing)
   - [Production Systemd Service (1-Command Auto Setup)](#production-systemd-service-1-command-auto-setup)
   - [Firewall Configuration (UFW)](#firewall-configuration-ufw)
9. [Production Reverse Proxy & SSL](#9-production-reverse-proxy--ssl)
   - [Option A: Nginx with HTTPS & Password Protection](#option-a-nginx-with-https--password-protection)
   - [Option B: Cloudflare Tunnel (Zero-Open-Ports)](#option-b-cloudflare-tunnel-zero-open-ports)
10. [High-Availability Watchdog & Auto-Recovery](#10-high-availability-watchdog--auto-recovery)
11. [Automated Daily Backups](#11-automated-daily-backups)
12. [Troubleshooting & Common Pitfalls](#12-troubleshooting--common-pitfalls)

---

## 1. System Requirements & Distro Prerequisites

- **Minimum Hardware:** 1 vCPU, 1 GB RAM, 10 GB disk space.
- **Recommended Hardware:** 2 vCPUs, 2 GB RAM (for busy servers with heavy player traffic).
- **Supported OS:** Ubuntu 22.04+, Debian 12+, AlmaLinux 9+, Rocky Linux 9+, Fedora, Arch Linux.
- **Required Software:** Java 25+, Python 3.10+, `tmux`, `sqlite3`, `curl`, `git`.

### Installing on Ubuntu/Debian

```bash
sudo apt update && sudo apt install -y tmux sqlite3 curl git python3 python3-pip
```

### Installing on AlmaLinux, Rocky, CentOS, Fedora

```bash
sudo dnf install -y tmux sqlite curl git python3 python3-pip
```

### Installing on Arch Linux

```bash
sudo pacman -Syu --noconfirm tmux sqlite curl git python python-pip
```

---

## 2. Java 25 Runtime Setup

ZenithProxy plugins require a modern Java 25 runtime. The recommended installation method on any Linux distribution is via [SDKMAN](https://sdkman.io/):

```bash
# 1. Install SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. Install Eclipse Temurin / OpenJDK Java 25
sdk install java 25-open

# 3. Verify installation
java -version
```

Alternatively, on Debian/Ubuntu via Adoptium APT repository:
```bash
sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-25-jdk
```

---

## 3. ZenithProxy Installation (First-Time Setup)

If you do not yet have ZenithProxy set up on your VDS:

### 3.1 Directory & Binary Setup
```bash
# Create dedicated directory for ZenithProxy
mkdir -p ~/zenithproxy
cd ~/zenithproxy

# Download the latest ZenithProxy executable release
# (Check https://github.com/rfresh2/ZenithProxy/releases for latest link)
curl -fsSL -o ZenithProxy.jar https://github.com/rfresh2/ZenithProxy/releases/latest/download/ZenithProxy.jar

# Create executable launch script
cat << 'EOF' > launch
#!/bin/bash
exec java -Xms512M -Xmx1024M -jar ZenithProxy.jar "$@"
EOF
chmod +x launch
```

### 3.2 Minecraft Account Authentication
1. Run the proxy inside a `tmux` session:
   ```bash
   tmux new -s proxy_server
   cd ~/zenithproxy
   ./launch
   ```
2. ZenithProxy will output a Microsoft OAuth device authentication code:
   ```
   To sign in, use a web browser to open the page https://microsoft.com/devicelogin and enter the code XXXXXXXX to authenticate.
   ```
3. Open the URL in your browser, enter the code, and log in to your Minecraft account.
4. ZenithProxy will authenticate, create `config.json`, and connect.
5. Detach from the `tmux` session by pressing `Ctrl + B` then `D`.

---

## 4. Installing Zenith Server Toolkit Plugin

### Option A: Automated Helper Script (Recommended)
Clone this repository and run the download script:
```bash
git clone https://github.com/kolsuzmuz/zenith-server-toolkit.git ~/zenith-server-toolkit
cd ~/zenith-server-toolkit
./scripts/download_plugin.sh
```
*The script automatically queries GitHub Releases for the latest asset, detects your proxy `plugins/` directory (e.g. `~/zenithproxy/plugins`), removes any older versions to prevent duplicate plugin conflicts, and downloads the latest `.jar`. You can also pass a custom plugins directory as an argument: `./scripts/download_plugin.sh /path/to/plugins`.*

### Option B: One-Command Download via GitHub CLI
If you have the `gh` tool installed:
```bash
gh release download latest -R kolsuzmuz/zenith-server-toolkit -p "*.jar" -D ~/zenithproxy/plugins --clobber
```

### Option C: Direct cURL Download
```bash
mkdir -p ~/zenithproxy/plugins
# Downloads the latest release artifact dynamically:
LATEST_URL=$(curl -fsSL https://api.github.com/repos/kolsuzmuz/zenith-server-toolkit/releases/latest | grep -o 'https://github.com/[^"]*/releases/download/[^"]*/ZenithServerToolkit[^"]*\.jar' | head -n 1)
curl -fsSL "$LATEST_URL" -o ~/zenithproxy/plugins/$(basename "$LATEST_URL")
```

---

## 5. First Launch & Plugin Configuration

1. Connect to your proxy `tmux` session:
   ```bash
   tmux attach -t proxy_server
   ```
2. Restart ZenithProxy. Look for initialization confirmation:
   ```
   [INFO] ZenithServerToolkit loading...
   [INFO] ZenithServerToolkit loaded!
   [INFO] SessionLogger scheduler started
   [INFO] Death/Kill Logger scheduler started
   [INFO] ChatStatsBot scheduler started
   ```
3. Stop ZenithProxy once (`Ctrl + C`). A new configuration file is now generated at:
   `plugins/config/zenith-server-toolkit.json`

### Configuration Options Breakdown

```json
{
  "sessionLogger": {
    "enabled": true
  },
  "scoreboardLogger": {
    "enabled": true,
    "dataSource": "EVENT",
    "deathsObjective": "DeathCount",
    "killsObjective": "KillCount",
    "maxDiscoveredScoreboards": 20,
    "discoveredScoreboards": [
      "DeathCount",
      "KillCount"
    ]
  },
  "chatStatsBot": {
    "enabled": true,
    "worldSeed": "0",
    "useSystemTimezone": true,
    "utcOffsetHours": 0
  }
}
```

- `sessionLogger.enabled`: Records joins, leaves, disconnects, and heartbeats to `sessions.log`.
- `scoreboardLogger.dataSource`:
  - `"EVENT"` (Default): Parses in-game death announcement messages (`Steve was blown up by Creeper`). Works universally on all Minecraft servers without needing custom scoreboard permissions.
  - `"SCOREBOARD"`: Reads values directly from server scoreboard objectives specified in `deathsObjective` and `killsObjective`.
- `scoreboardLogger.maxDiscoveredScoreboards`: Caps the number of discovered scoreboards saved to prevent file bloat.
- `scoreboardLogger.discoveredScoreboards`: Live list of scoreboard objectives observed on the server.
- `chatStatsBot.enabled`: Enables chat commands like `!info`, `!top`, `!playtime`.
- `chatStatsBot.worldSeed`: Seed string displayed when players type `!seed`.
- `chatStatsBot.useSystemTimezone`: Formats timestamps using system timezone. If `false`, uses `utcOffsetHours`.

---

## 6. In-Game Chatbot Activation & Safety Switch

### Dry-Run Mode (Default)
When first started, the chatbot operates in **dry-run mode**. It parses player commands and calculates replies, but outputs them **only to the proxy console**:
```
[INFO] [DRY-RUN] Reply for Steve: Steve: 42 messages, playtime 3h12m, deaths 4, first seen 2026-03-01 14:20
```
No chat packets are sent to the Minecraft server.

### Enabling Live Replies
To authorize the bot to send public chat replies into Minecraft, create `chatbot_reply.enabled` inside your proxy directory:
```bash
touch ~/zenithproxy/chatbot_reply.enabled
```

To deactivate live replies and revert to dry-run mode immediately at any time:
```bash
rm ~/zenithproxy/chatbot_reply.enabled
```

### Spam & Cooldown Rules
- **Global Cooldown:** 3 seconds minimum between any two outgoing bot replies.
- **Per-User Cooldown:** 8 seconds per player.
- **Message Length:** Capped at 200 characters with control characters removed.

---

## 7. Background Session Ingestion Daemon

The plugin logs raw events to `sessions.log`. The Python ingestion daemon (`import_sessions.py`) parses this stream into an indexed SQLite database (`sessions.db`).

### Manual Execution
```bash
python3 scripts/import_sessions.py
```

### Persistent Background Execution (nohup)
```bash
nohup python3 scripts/import_sessions.py --loop > /dev/null 2>&1 &
```

### Crontab Auto-Start on Boot
Add an entry to crontab (`crontab -e`):
```cron
@reboot nohup python3 $HOME/zenith-server-toolkit/scripts/import_sessions.py --loop > /dev/null 2>&1 &
```

---

## 8. Web Dashboard Setup

The web dashboard renders an interactive player count graph with multi-range filters (3h, 6h, 12h, 1d, 7d, 15d, 30d).

The `/api` endpoint automatically attaches `Access-Control-Allow-Origin: *` headers, allowing cross-origin requests from external web apps, Discord status widgets, and Grafana dashboards.

### Local Testing
```bash
python3 site/server.py
```
By default, the server binds securely to `127.0.0.1:8080` (localhost only). Open `http://localhost:8080` in your browser (or access via reverse proxy / SSH tunnel).

To expose the dashboard on all network interfaces, pass `--host 0.0.0.0`:
```bash
python3 site/server.py --port 8080 --host 0.0.0.0 --db /path/to/sessions.db
```
*(Explicit `--db` arguments and `SESSIONS_DB` environment variables take strict priority even if the database has not been populated yet).*

### Production Systemd Service (1-Command Auto Setup)
A helper script automates systemd unit creation on any VDS:
```bash
sudo ./scripts/setup_service.sh
```
This detects your active user, Python 3 path, and repository directory, installs `/etc/systemd/system/zenith-dashboard.service` (binding to `127.0.0.1` by default), reloads systemd, and starts the service. You can also specify a custom port, host, or database path:
```bash
# Example with custom port, host (0.0.0.0), and custom DB location (tilde '~' is safely expanded)
sudo ./scripts/setup_service.sh 8085 0.0.0.0 ~/zenithproxy/sessions.db
```

Useful service commands:
```bash
sudo systemctl status zenith-dashboard.service   # View status
sudo systemctl restart zenith-dashboard.service  # Restart
sudo journalctl -u zenith-dashboard.service -f   # View live logs
```

### Firewall Configuration (UFW)
If you wish to expose port 8080 directly:
```bash
sudo ufw allow 8080/tcp
sudo ufw reload
```

---

## 9. Production Reverse Proxy & SSL

For public deployments, placing the dashboard behind a reverse proxy with HTTPS is recommended.

### Option A: Nginx with HTTPS & Password Protection

1. Install Nginx and Apache utilities:
   ```bash
   sudo apt install -y nginx apache2-utils certbot python3-certbot-nginx
   ```

2. Generate HTTP Basic Auth credentials (optional, to password-protect the dashboard):
   ```bash
   sudo htpasswd -c /etc/nginx/.zenith_htpasswd admin
   ```

3. Create Nginx site configuration `/etc/nginx/sites-available/zenith-dashboard`:
   ```nginx
   server {
       listen 80;
       server_name status.yourdomain.com;

       location / {
           proxy_pass http://127.0.0.1:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

           # Enable basic auth (remove if public)
           auth_basic "Zenith Dashboard";
           auth_basic_user_file /etc/nginx/.zenith_htpasswd;
       }
   }
   ```

4. Enable site and obtain SSL certificate:
   ```bash
   sudo ln -s /etc/nginx/sites-available/zenith-dashboard /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d status.yourdomain.com
   ```

### Option B: Cloudflare Tunnel (Zero-Open-Ports)
Cloudflare Tunnel allows you to publish the dashboard securely without opening any inbound ports on your VDS.

1. Install `cloudflared`:
   ```bash
   curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
   echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/cloudflared.list
   sudo apt update && sudo apt install -y cloudflared
   ```
2. Add an ingress entry in `/etc/cloudflared/config.yml`:
   ```yaml
   ingress:
     - hostname: status.yourdomain.com
       service: http://127.0.0.1:8080
     - service: http_status:404
   ```
3. Restart tunnel:
   ```bash
   sudo systemctl restart cloudflared
   ```

---

## 10. High-Availability Watchdog & Auto-Recovery

The watchdog script (`scripts/watchdog.sh`) ensures ZenithProxy automatically restarts if it crashes or after system reboots.

1. Ensure the script is executable:
   ```bash
   chmod +x scripts/watchdog.sh
   ```
2. Add to crontab:
   ```bash
   crontab -e
   ```
   Add the following line (replace `$HOME/zenith-server-toolkit` if installed in a custom location):
   ```cron
   */5 * * * * flock -n /tmp/watchdog.lock $HOME/zenith-server-toolkit/scripts/watchdog.sh
   ```

*The `flock` utility prevents duplicate watchdog executions if a restart takes longer than 5 minutes. The script requires `tmux` and checks for its availability before running.*

### Maintenance Mode
To temporarily disable watchdog auto-restart during intentional maintenance or server updates, create a `watchdog.disabled` file in your proxy directory:
```bash
touch ~/zenithproxy/watchdog.disabled
```
When present, the watchdog logs a message and exits immediately (before the settling delay). Remove the file when maintenance is complete to resume automatic recovery:
```bash
rm -f ~/zenithproxy/watchdog.disabled
```

---

## 11. Automated Daily Backups

To prevent data loss of historical playtime, kills, and session statistics:

1. Create a simple backup script `~/backup_zenith.sh`:
   ```bash
   #!/bin/bash
   BACKUP_DIR="$HOME/zenith_backups"
   mkdir -p "$BACKUP_DIR"
   DATE=$(date '+%Y%m%d_%H%M%S')
   tar -czf "$BACKUP_DIR/zenith_data_$DATE.tar.gz" \
       -C "$HOME/zenithproxy" \
       players.tsv sessions.db sessions.log scoreboard_deaths.log scoreboard_kills.log plugins/config/ 2>/dev/null || true
   # Keep only last 14 days of backups
   find "$BACKUP_DIR" -type f -name "zenith_data_*.tar.gz" -mtime +14 -delete
   ```
2. Make it executable:
   ```bash
   chmod +x ~/backup_zenith.sh
   ```
3. Add to daily crontab (`crontab -e`):
   ```cron
   0 3 * * * /bin/bash ~/backup_zenith.sh
   ```

---

## 12. Troubleshooting & Common Pitfalls

### Issue: Dashboard says "Database file (sessions.db) not found"
- **Cause:** The Python session importer has not been started, or ZenithProxy has not logged any events yet.
- **Solution:** Verify `sessions.log` exists in your proxy folder. Start the importer:
  ```bash
  python3 scripts/import_sessions.py --loop
  ```
  You can also pass the database path explicitly:
  ```bash
  python3 site/server.py --db /path/to/sessions.db
  ```

### Issue: Bot does not reply to commands in Minecraft chat
- **Cause 1:** Live replies are disabled by default. Ensure `chatbot_reply.enabled` exists in your proxy root folder.
- **Cause 2:** Command was sent during the 8-second player cooldown or 3-second global cooldown.
- **Cause 3:** Bot account was muted by the server or lacks permission to speak in public chat. Check proxy console for errors.

### Issue: Watchdog does not restart ZenithProxy
- **Cause 1:** `tmux` is not installed on the system. Run: `sudo apt install tmux`.
- **Cause 2:** The launch script lacks execution permissions. Run: `chmod +x ~/zenithproxy/launch`.
- **Cause 3:** Settling delay is running. When tested interactively, the watchdog script waits 90 seconds before restarting to prevent false alarms during planned server updates.

### Issue: Plugin was not loaded by ZenithProxy
- **Cause 1:** Ensure `ZenithServerToolkit-*.jar` was placed inside `~/zenithproxy/plugins/`. If installed in another folder, specify it:
  ```bash
  ./scripts/download_plugin.sh /path/to/zenithproxy/plugins
  ```
- **Cause 2:** Make sure there are no duplicate versions in `plugins/`. `download_plugin.sh` automatically removes older versions during installation.

### Issue: Cross-Origin (CORS) errors accessing /api
- **Solution:** `/api` includes `Access-Control-Allow-Origin: *` out of the box. Ensure requests use standard HTTP methods (`GET`, `OPTIONS`).

### Issue: "Port 8080 already in use"
- **Solution:** Another process is listening on port 8080. Start the server on a different port:
  ```bash
  python3 site/server.py --port 8085
  ```
  Or if using the systemd installer:
  ```bash
  sudo ./scripts/setup_service.sh 8085
  ```

### Issue: Tmux session commands
- **List running sessions:** `tmux ls`
- **Attach to proxy console:** `tmux attach -t proxy_server`
- **Detach from console:** Press `Ctrl + B`, release, then press `D`.
- **Kill session:** `tmux kill-session -t proxy_server`
