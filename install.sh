#!/bin/bash
# Husky — Linux VPS quick installer
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/HandleCoding/OpenHuskyAgent/main/install.sh | bash
#   bash install.sh [--non-interactive] [--upgrade] [--port PORT]
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

NON_INTERACTIVE=false
UPGRADE=false
PORT=18088
REPO_URL="https://github.com/HandleCoding/OpenHuskyAgent.git"
INSTALL_DIR="${HUSKY_INSTALL_DIR:-$HOME/openHusky}"
DATA_DIR="${HUSKY_DATA_DIR:-$HOME/.husky}"
ENV_FILE="$DATA_DIR/.env"
LOG_FILE="/tmp/husky-install-$(date +%Y%m%d%H%M%S).log"

# ── Parse arguments ──────────────────────────────────────────────────────
for arg in "$@"; do
    case "$arg" in
        --non-interactive) NON_INTERACTIVE=true ;;
        --upgrade) UPGRADE=true ;;
        --port=*) PORT="${arg#*=}" ;;
        --port)
            echo "Use --port=PORT (with =) in piped mode"; exit 1 ;;
        --install-dir=*) INSTALL_DIR="${arg#*=}" ;;
        --help|-h)
            echo "Usage: bash install.sh [OPTIONS]"
            echo ""
            echo "  --non-interactive     Skip all prompts, use defaults"
            echo "  --upgrade             Re-install over existing installation"
            echo "  --port=PORT           Service port (default: 18088)"
            echo "  --install-dir=DIR     Installation directory (default: $HOME/openHusky)"
            exit 0
            ;;
    esac
done

info()  { echo -e "${CYAN}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[ OK ]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()   { echo -e "${RED}[ERR ]${NC} $1" >&2; }
bail()  { err "$1"; exit 1; }

log() {
    # Append to log file for debugging
    echo "[$(date '+%H:%M:%S')] $*" >> "$LOG_FILE"
}

generate_api_key() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32
    elif command -v od >/dev/null 2>&1; then
        od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
    else
        date +%s%N | sha256sum | cut -d' ' -f1
    fi
}

# ── Pre-flight checks ───────────────────────────────────────────────────
preflight() {
    # Must not run as root (we use sudo for privileged ops)
    if [ "$(id -u)" -eq 0 ] && [ "$NON_INTERACTIVE" = false ]; then
        warn "Running as root. It's recommended to run as a normal user with sudo access."
        read -rp "Continue anyway? [y/N] " yn
        [[ "$yn" =~ ^[Yy]$ ]] || exit 0
    fi

    # Essential commands
    for cmd in git curl; do
        command -v "$cmd" >/dev/null 2>&1 || bail "'$cmd' is required but not found. Install it first."
    done
}

# ── Step 1: Detect OS ────────────────────────────────────────────────────
detect_os() {
    case "$(uname -s)" in
        Linux*)  OS=linux ;;
        *)       bail "Unsupported OS: $(uname -s). This script targets Linux only." ;;
    esac
    ARCH="$(uname -m)"
    DISTRO="unknown"

    if [ -f /etc/os-release ]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        DISTRO="${ID:-unknown}"
    fi

    info "Detected: $OS $ARCH ($DISTRO)"
    log "OS=$OS ARCH=$ARCH DISTRO=$DISTRO"
}

# ── Step 2: Install system dependencies ──────────────────────────────────
install_deps() {
    info "Installing system dependencies..."

    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -qq
        sudo apt-get install -y -qq git curl unzip > /dev/null 2>&1
        ok "apt packages installed"
    elif command -v yum >/dev/null 2>&1; then
        sudo yum install -y -q git curl unzip > /dev/null 2>&1
        ok "yum packages installed"
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y -q git curl unzip > /dev/null 2>&1
        ok "dnf packages installed"
    elif command -v apk >/dev/null 2>&1; then
        sudo apk add --no-cache git curl unzip > /dev/null 2>&1
        ok "apk packages installed"
    else
        warn "Unknown package manager — skipping dependency install. Ensure git/curl/unzip are available."
    fi
}

# ── Step 3: Install JDK 17 ──────────────────────────────────────────────
install_java() {
    # Already have a usable JDK?
    if _check_java_version; then
        ok "JDK found: $($_java_cmd -version 2>&1 | head -1)"
        return 0
    fi

    info "JDK 17+ not found. Installing..."

    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get install -y -qq openjdk-17-jdk-headless > /dev/null 2>&1
    elif command -v yum >/dev/null 2>&1; then
        sudo yum install -y -q java-17-openjdk-devel > /dev/null 2>&1
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y -q java-17-openjdk-devel > /dev/null 2>&1
    elif command -v apk >/dev/null 2>&1; then
        sudo apk add --no-cache openjdk17 > /dev/null 2>&1
    else
        bail "No supported package manager found. Install JDK 17 manually."
    fi

    _resolve_java_home
    if ! _check_java_version; then
        bail "JDK installation succeeded but 'java -version' still fails. Check your PATH."
    fi

    ok "JDK installed: $($_java_cmd -version 2>&1 | head -1)"
}

_java_cmd="java"

# Returns 0 if java is version 17+
_check_java_version() {
    if ! command -v "$_java_cmd" >/dev/null 2>&1; then
        return 1
    fi
    local ver
    ver="$("$_java_cmd" -version 2>&1 | head -1)"
    # Match: version "17.x", "21.x", "25.x", etc.
    if echo "$ver" | grep -qE 'version "1[7-9]\.' || \
       echo "$ver" | grep -qE 'version "[2-9][0-9]*\.';
    then
        return 0
    fi
    return 1
}

_resolve_java_home() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        _java_cmd="$JAVA_HOME/bin/java"
        return 0
    fi

    local java_path
    java_path="$(command -v java 2>/dev/null)" || return 1

    # Portable readlink -f (works on macOS and minimal Linux)
    if command -v readlink >/dev/null 2>&1; then
        local resolved
        resolved="$(readlink -f "$java_path" 2>/dev/null)" || resolved="$java_path"
        java_path="$resolved"
    fi

    export JAVA_HOME
    JAVA_HOME="$(cd "$(dirname "$(dirname "$java_path")")" && pwd)"
    _java_cmd="$JAVA_HOME/bin/java"
    export PATH="$JAVA_HOME/bin:$PATH"
}

# ── Step 4: Clone / Update repository ───────────────────────────────────
clone_repo() {
    if [ -d "$INSTALL_DIR/.git" ] && [ "$UPGRADE" = true ]; then
        info "Updating existing installation at $INSTALL_DIR..."
        cd "$INSTALL_DIR"

        # Stash local changes to .env before pulling
        local had_changes=false
        if ! git diff --quiet -- .env 2>/dev/null; then
            git stash push -m "install.sh auto-stash" -- .env > /dev/null 2>&1
            had_changes=true
        fi

        git pull --ff-only || bail "git pull failed. Resolve conflicts or re-run without --upgrade."

        if [ "$had_changes" = true ]; then
            git stash pop > /dev/null 2>&1 || warn "Could not restore .env stash — check 'git stash list'"
        fi

    elif [ -d "$INSTALL_DIR" ] && [ "$UPGRADE" = false ]; then
        err "Installation directory $INSTALL_DIR already exists."
        err "Use --upgrade to update, or remove it first: rm -rf $INSTALL_DIR"
        exit 1
    else
        info "Cloning Husky into $INSTALL_DIR..."
        git clone --depth 1 "$REPO_URL" "$INSTALL_DIR"
        cd "$INSTALL_DIR"
    fi

    log "Repository ready at $INSTALL_DIR (HEAD: $(git rev-parse --short HEAD))"
}

# ── Step 5: Build ────────────────────────────────────────────────────────
build() {
    cd "$INSTALL_DIR"

    if [ ! -f mvnw ]; then
        bail "Maven wrapper (mvnw) not found. Repository clone may be incomplete."
    fi

    chmod +x mvnw

    # Resolve JAVA_HOME for mvnw
    _resolve_java_home
    export JAVA_HOME PATH

    info "Building Husky (this may take 2-5 minutes on first run)..."

    # Increase Maven heap for low-memory VPS
    export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}"

    local build_log="$INSTALL_DIR/build.log"
    if ! ./mvnw clean install -DskipTests > "$build_log" 2>&1; then
        err "Build failed. Last 30 lines of build log:"
        tail -30 "$build_log"
        bail "Full log: $build_log"
    fi

    local service_jar="$INSTALL_DIR/service/target/husky-agent-service-0.0.1-SNAPSHOT.jar"
    local client_jar="$INSTALL_DIR/client/target/husky-agent-client-0.0.1-SNAPSHOT.jar"

    [ -f "$service_jar" ] || bail "Service JAR not found after build. Check $build_log"
    [ -f "$client_jar" ]  || bail "Client JAR not found after build. Check $build_log"

    ok "Build complete — service: $(du -h "$service_jar" | cut -f1), client: $(du -h "$client_jar" | cut -f1)"
    log "JARs: $service_jar, $client_jar"
}

# ── Step 6: Generate .env config ────────────────────────────────────────
setup_env() {
    local env_file="$ENV_FILE"
    local generated_key
    generated_key="$(generate_api_key)"

    mkdir -p "$DATA_DIR"

    if [ -f "$env_file" ] && [ "$UPGRADE" = true ]; then
        ok "Existing .env preserved: $env_file"
        return 0
    fi

    if [ -f "$INSTALL_DIR/.env.example" ]; then
        cp "$INSTALL_DIR/.env.example" "$env_file"
    else
        cat > "$env_file" <<ENVEOF
# ── Required ────────────────────────────────────────────────────────────
OPENAI_API_KEY=
OPENAI_BASE_URL=https://api.openai.com
OPENAI_MODEL=gpt-5.4

# ── Optional ────────────────────────────────────────────────────────────
HUSKY_PORT=$PORT
HUSKY_DATA_DIR=$DATA_DIR
AUTH_ENABLED=true
HUSKY_API_KEYS=$generated_key
BRAVE_SEARCH_API_KEY=
BROWSER_ENABLED=false
MCP_ENABLED=false
MCP_CONFIG_PATH=$DATA_DIR/config/mcp-servers.json
ENVEOF
    fi

    if grep -q '^HUSKY_PORT=' "$env_file"; then
        sed -i.bak "s|^HUSKY_PORT=.*|HUSKY_PORT=$PORT|" "$env_file" && rm -f "$env_file.bak"
    else
        echo "HUSKY_PORT=$PORT" >> "$env_file"
    fi

    if grep -q '^HUSKY_DATA_DIR=' "$env_file"; then
        sed -i.bak "s|^HUSKY_DATA_DIR=.*|HUSKY_DATA_DIR=$DATA_DIR|" "$env_file" && rm -f "$env_file.bak"
    else
        echo "HUSKY_DATA_DIR=$DATA_DIR" >> "$env_file"
    fi

    if grep -q '^MCP_CONFIG_PATH=' "$env_file"; then
        sed -i.bak "s|^MCP_CONFIG_PATH=.*|MCP_CONFIG_PATH=$DATA_DIR/config/mcp-servers.json|" "$env_file" && rm -f "$env_file.bak"
    else
        echo "MCP_CONFIG_PATH=$DATA_DIR/config/mcp-servers.json" >> "$env_file"
    fi

    if grep -q '^HUSKY_API_KEYS=change-me-generate-a-random-key$' "$env_file"; then
        sed -i.bak "s|^HUSKY_API_KEYS=.*|HUSKY_API_KEYS=$generated_key|" "$env_file" && rm -f "$env_file.bak"
    elif ! grep -q '^HUSKY_API_KEYS=' "$env_file"; then
        echo "HUSKY_API_KEYS=$generated_key" >> "$env_file"
    fi

    mkdir -p "$DATA_DIR/config" "$DATA_DIR/skills" "$DATA_DIR/db" "$DATA_DIR/logs"

    warn "Config created: $env_file"
    warn "You MUST set OPENAI_API_KEY before starting the service."

    if [ "$NON_INTERACTIVE" = false ]; then
        echo ""
        read -rp "Edit .env now? [Y/n] " edit_env
        if [[ "$edit_env" =~ ^[Yy]$ || -z "$edit_env" ]]; then
            ${EDITOR:-vi} "$env_file"
        fi
    fi
}

# ── Step 7: Create husky user & set permissions (Linux only) ────────────
setup_user() {
    if [ "$(id -u)" -eq 0 ]; then
        return 0
    fi

    mkdir -p "$DATA_DIR" "$DATA_DIR/config" "$DATA_DIR/skills" "$DATA_DIR/db" "$DATA_DIR/logs"
}

# ── Step 8: Install systemd service ─────────────────────────────────────
setup_systemd() {
    if [ ! -d /run/systemd/system ]; then
        log "systemd not detected, skipping service install"
        return 0
    fi

    if [ "$NON_INTERACTIVE" = false ]; then
        echo ""
        read -rp "Install as systemd service for auto-start? [Y/n] " install_service
        if [[ "$install_service" =~ ^[Nn]$ ]]; then
            return 0
        fi
    fi

    local service_src="$INSTALL_DIR/deploy/husky.service"
    local service_user
    local systemd_readwrite_paths
    service_user="$(id -un)"
    systemd_readwrite_paths="$DATA_DIR $DATA_DIR/config $DATA_DIR/skills $DATA_DIR/db $DATA_DIR/logs"

    if [ -f "$service_src" ]; then
        # Patch the template with actual paths and user
        sed \
            -e "s|WorkingDirectory=.*|WorkingDirectory=$INSTALL_DIR|g" \
            -e "s|EnvironmentFile=.*|EnvironmentFile=$ENV_FILE|g" \
            -e "s|ExecStart=.*|ExecStart=$JAVA_HOME/bin/java -jar $INSTALL_DIR/service/target/husky-agent-service-0.0.1-SNAPSHOT.jar|g" \
            -e "s|^User=.*|User=$service_user|g" \
            -e "s|ReadWritePaths=.*|ReadWritePaths=$systemd_readwrite_paths|g" \
            "$service_src" > /tmp/husky-agent.service
    else
        # Generate from scratch
        cat > /tmp/husky-agent.service <<SVCEOF
[Unit]
Description=Husky — AI assistant service
After=network.target

[Service]
Type=simple
User=$service_user
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$ENV_FILE
Environment=JAVA_HOME=$JAVA_HOME
ExecStart=$JAVA_HOME/bin/java -jar $INSTALL_DIR/service/target/husky-agent-service-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$systemd_readwrite_paths

[Install]
WantedBy=multi-user.target
SVCEOF
    fi

    sudo cp /tmp/husky-agent.service /etc/systemd/system/husky-agent.service
    sudo systemctl daemon-reload
    sudo systemctl enable husky-agent

    ok "systemd service installed: husky-agent.service"
    info "Start with: sudo systemctl start husky-agent"
    info "Logs with:  journalctl -u husky-agent -f"
}

# ── Step 9: Make bin/husky globally accessible ──────────────────────────
setup_cli() {
    chmod +x "$INSTALL_DIR/bin/husky"

    if [ "$NON_INTERACTIVE" = false ]; then
        echo ""
        read -rp "Add 'husky' command to PATH? [Y/n] " add_path
        if [[ "$add_path" =~ ^[Nn]$ ]]; then
            return 0
        fi
    fi

    local target="/usr/local/bin/husky"
    if [ -L "$target" ]; then
        sudo ln -sf "$INSTALL_DIR/bin/husky" "$target"
    elif [ -e "$target" ]; then
        warn "$target exists and is not a symlink — skipping. Remove it manually to re-link."
        return 0
    else
        sudo ln -s "$INSTALL_DIR/bin/husky" "$target"
    fi

    ok "'husky' command linked to $target"
}

# ── Step 10: Open firewall port ─────────────────────────────────────────
setup_firewall() {
    # Only if ufw is present and active
    if ! command -v ufw >/dev/null 2>&1; then
        return 0
    fi
    if ! sudo ufw status 2>/dev/null | grep -q "active"; then
        return 0
    fi

    if [ "$NON_INTERACTIVE" = false ]; then
        echo ""
        read -rp "Open port $PORT in firewall (ufw)? [Y/n] " open_fw
        if [[ "$open_fw" =~ ^[Nn]$ ]]; then
            return 0
        fi
    fi

    sudo ufw allow "$PORT/tcp" >/dev/null 2>&1
    ok "Port $PORT opened in ufw"
}

# ── Print success message ──────────────────────────────────────────────
print_success() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo -e "${GREEN}  Husky installed successfully!${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "  Install dir : $INSTALL_DIR"
    echo "  Data dir    : $DATA_DIR"
    echo "  Service port: $PORT"
    echo "  Config file : $ENV_FILE"
    echo ""
    echo "  Next steps:"
    echo ""
    echo "  1. Edit the minimal configuration:"
    echo "     ${CYAN}vi $ENV_FILE${NC}"
    echo "     (Set OPENAI_API_KEY at minimum)"
    echo ""
    echo "  2. Start Husky:"
    if [ -f /etc/systemd/system/husky-agent.service ]; then
        echo "     ${CYAN}sudo systemctl start husky-agent${NC}"
        echo "     ${CYAN}sudo systemctl status husky-agent${NC}"
        echo "     ${CYAN}journalctl -u husky-agent -f${NC}"
    else
        echo "     ${CYAN}cd $INSTALL_DIR && bin/husky serve${NC}"
    fi
    echo ""
    echo "  3. Verify the service:"
    echo "     ${CYAN}curl http://localhost:$PORT/actuator/health${NC}"
    echo "     (Look for JSON containing \"status\":\"UP\")"
    echo ""
    echo "  4. Open the TUI from another terminal or your local machine:"
    echo "     ${CYAN}husky tui --server ws://YOUR_VPS_IP:$PORT/api/tui${NC}"
    echo ""
    if [ "$UPGRADE" = true ]; then
        echo "  Upgrade log: $LOG_FILE"
    fi
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# ── Main ────────────────────────────────────────────────────────────────
main() {
    echo ""
    echo -e "${CYAN}Husky — Linux VPS Installer${NC}"
    echo ""

    preflight
    detect_os
    install_deps
    install_java
    clone_repo
    build
    setup_env
    setup_user
    setup_systemd
    setup_cli
    setup_firewall
    print_success

    log "Installation completed successfully"
}

main "$@"
