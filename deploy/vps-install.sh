#!/usr/bin/env bash
#
# One-command VPS install:
#
#   curl -fsSL https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-install.sh | sudo bash
#
# Installs Docker Engine + Compose when Docker is missing, clones the server into
# /opt/kotlin-chat, creates .env with a generated password and TURN secret, opens the ports in
# ufw/firewalld when they are active, and starts the stack. Re-running it updates an existing
# checkout without touching its .env.
#
# Overrides (pass with `sudo env NAME=value bash`, not plain sudo):
#   KOTLIN_CHAT_DIR  install directory            (default: /opt/kotlin-chat)
#   KOTLIN_CHAT_REPO git repository to clone       (default: the public repository)
#   TURN_HOST        public address for the relay  (default: detected)
#   CHAT_VERSION     image tag                     (default: latest)
#
set -euo pipefail

REPO_URL="${KOTLIN_CHAT_REPO:-https://github.com/HMHamster88/kotlin-chat.git}"
INSTALL_DIR="${KOTLIN_CHAT_DIR:-/opt/kotlin-chat}"
CHAT_VERSION="${CHAT_VERSION:-}"
version_override="$CHAT_VERSION"
CHAT_VERSION="${CHAT_VERSION:-latest}"
COMPOSE=(-f docker-compose.yml -f docker-compose.turn.yml)
UPDATE_URL="https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-update.sh"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Usage: vps-install.sh [--help]

Installs Docker (when missing), deploys kotlin-chat into /opt/kotlin-chat and starts it.

Environment overrides:
  KOTLIN_CHAT_DIR   install directory           (default: /opt/kotlin-chat)
  KOTLIN_CHAT_REPO  git repository to clone      (default: the public repository)
  TURN_HOST         public address for the relay (default: detected)
  CHAT_VERSION      image tag                    (default: latest)

Run it as root, for example:
  curl -fsSL https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-install.sh | sudo bash
  sudo env TURN_HOST=203.0.113.7 bash vps-install.sh
EOF
}

case "${1:-}" in
    "") ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
esac

[ "$(id -u)" -eq 0 ] || die "run with sudo: curl -fsSL ... | sudo bash"

install_packages() {
    local need=0 cmd
    for cmd in curl git openssl; do
        command -v "$cmd" >/dev/null 2>&1 || need=1
    done
    if [ "$need" -eq 0 ]; then
        return 0
    fi

    log "Installing curl, git and OpenSSL"
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y
        DEBIAN_FRONTEND=noninteractive apt-get install -y curl git ca-certificates openssl
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y curl git ca-certificates openssl
    elif command -v yum >/dev/null 2>&1; then
        yum install -y curl git ca-certificates openssl
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache curl git ca-certificates openssl
    else
        die "unsupported package manager; install curl, git and openssl first"
    fi
}

detect_public_ip() {
    local ip url
    for url in https://api.ipify.org https://ifconfig.me/ip https://icanhazip.com; do
        ip="$(curl -4 -fsS --max-time 5 "$url" 2>/dev/null | tr -d '[:space:]' || true)"
        if [ -n "$ip" ]; then
            printf '%s' "$ip"
            return 0
        fi
    done
    return 1
}

install_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        log "Installing Docker Engine and Compose"
        if ! curl -fsSL https://get.docker.com | sh; then
            die "the Docker install failed; install Docker Engine and the Compose plugin, then rerun"
        fi
    fi

    if command -v systemctl >/dev/null 2>&1; then
        systemctl enable --now docker >/dev/null 2>&1 || true
    fi

    if ! docker compose version >/dev/null 2>&1; then
        log "Installing the Docker Compose plugin"
        if command -v apt-get >/dev/null 2>&1; then
            apt-get update -y
            DEBIAN_FRONTEND=noninteractive apt-get install -y docker-compose-plugin ||
                DEBIAN_FRONTEND=noninteractive apt-get install -y docker-compose-v2 ||
                true
        elif command -v dnf >/dev/null 2>&1; then
            dnf install -y docker-compose-plugin || true
        elif command -v yum >/dev/null 2>&1; then
            yum install -y docker-compose-plugin || true
        fi
    fi

    docker compose version >/dev/null 2>&1 ||
        die "the Docker Compose plugin is missing; see README 'Without the Compose plugin'"
    docker info >/dev/null 2>&1 ||
        die "the Docker daemon is not reachable; start it (systemctl start docker) and rerun"
}

open_firewall_ports() {
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi '^Status: active'; then
        log "Opening the server and relay ports in ufw"
        ufw allow 8443/tcp >/dev/null
        ufw allow 3478/tcp >/dev/null
        ufw allow 3478/udp >/dev/null
        ufw allow 49160:49200/udp >/dev/null
    elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        log "Opening the server and relay ports in firewalld"
        firewall-cmd --permanent --add-port=8443/tcp >/dev/null
        firewall-cmd --permanent --add-port=3478/tcp >/dev/null
        firewall-cmd --permanent --add-port=3478/udp >/dev/null
        firewall-cmd --permanent --add-port=49160-49200/udp >/dev/null
        firewall-cmd --reload >/dev/null
    else
        warn "If a firewall is active, open 8443/tcp, 3478/tcp+udp and 49160-49200/udp"
    fi
}

install_packages
install_docker

if [ -d "$INSTALL_DIR/.git" ]; then
    log "Updating the checkout in $INSTALL_DIR"
    git -C "$INSTALL_DIR" pull --ff-only
elif [ -e "$INSTALL_DIR" ] && [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null || true)" ]; then
    die "$INSTALL_DIR exists but is not a git checkout; set KOTLIN_CHAT_DIR to an empty path"
else
    log "Cloning $REPO_URL into $INSTALL_DIR"
    mkdir -p "$(dirname "$INSTALL_DIR")"
    git clone "$REPO_URL" "$INSTALL_DIR"
fi

cd "$INSTALL_DIR"
[ -f docker-compose.yml ] || die "docker-compose.yml is missing in $INSTALL_DIR"
[ -f docker-compose.turn.yml ] || die "docker-compose.turn.yml is missing in $INSTALL_DIR"

public_host=""
new_password=""
if [ ! -f .env ]; then
    public_host="${TURN_HOST:-}"
    [ -n "$public_host" ] || public_host="$(detect_public_ip || true)"
    [ -n "$public_host" ] ||
        die "could not detect the public IP; rerun with: sudo env TURN_HOST=your.public.ip bash"

    new_password="$(openssl rand -hex 24)"
    umask 077
    cat >.env <<EOF
CHAT_PASSWORD=$new_password
CHAT_VERSION=$CHAT_VERSION
TURN_HOST=$public_host
TURN_SECRET=$(openssl rand -hex 32)
EOF
    chmod 600 .env
    log "Created .env with a generated server password and TURN secret"

    if docker volume ls --format '{{.Name}}' 2>/dev/null | grep -q 'chat-config$'; then
        warn "a chat-config volume already exists, so the server keeps its previous password;"
        warn "the generated password in .env only applies to a first start (empty volume)"
    fi
else
    log "Using the existing $INSTALL_DIR/.env"
    public_host="$(sed -n 's/^TURN_HOST=//p' .env | tail -n1)"

    if [ -z "$public_host" ]; then
        public_host="${TURN_HOST:-}"
        [ -n "$public_host" ] || public_host="$(detect_public_ip || true)"
        [ -n "$public_host" ] ||
            die "TURN_HOST is missing from .env; add it or rerun with TURN_HOST=your.public.ip"
        if grep -q '^TURN_HOST=' .env; then
            sed -i "s|^TURN_HOST=.*|TURN_HOST=$public_host|" .env
        else
            printf 'TURN_HOST=%s\n' "$public_host" >>.env
        fi
        warn "Added TURN_HOST to .env"
    fi

    if ! grep -q '^TURN_SECRET=.' .env; then
        if grep -q '^TURN_SECRET=' .env; then
            sed -i "s|^TURN_SECRET=.*|TURN_SECRET=$(openssl rand -hex 32)|" .env
        else
            printf 'TURN_SECRET=%s\n' "$(openssl rand -hex 32)" >>.env
        fi
        warn "Added a generated TURN_SECRET to .env"
    fi

    if [ -n "$version_override" ]; then
        if grep -q '^CHAT_VERSION=' .env; then
            sed -i "s|^CHAT_VERSION=.*|CHAT_VERSION=$version_override|" .env
        else
            printf 'CHAT_VERSION=%s\n' "$version_override" >>.env
        fi
        log "Pinned CHAT_VERSION=$version_override in .env"
    else
        grep -q '^CHAT_VERSION=' .env || printf 'CHAT_VERSION=latest\n' >>.env
    fi
fi

open_firewall_ports

log "Pulling images"
docker compose "${COMPOSE[@]}" pull

log "Starting kotlin-chat"
docker compose "${COMPOSE[@]}" up -d --remove-orphans

log "Waiting for the server certificate fingerprint"
fingerprint=""
for _ in $(seq 1 30); do
    fingerprint="$(
        docker compose "${COMPOSE[@]}" logs --no-color chat 2>/dev/null |
            grep -i 'fingerprint:' |
            tail -n1 |
            sed 's/.*fingerprint: *//' || true
    )"
    [ -n "$fingerprint" ] && break
    sleep 1
done

printf '\n'
log "kotlin-chat is running"
printf '  Server:      %s:8443\n' "${public_host:-<public ip>}"
if [ -n "$new_password" ]; then
    printf '  Password:    %s   (CHAT_PASSWORD in %s/.env)\n' "$new_password" "$INSTALL_DIR"
else
    printf '  Password:    stored in %s/.env\n' "$INSTALL_DIR"
fi
if [ -n "$fingerprint" ]; then
    printf '  Fingerprint: %s\n' "$fingerprint"
else
    printf '  Fingerprint: run the logs command below and look for "fingerprint:"\n'
fi
printf '  Directory:   %s\n' "$INSTALL_DIR"
printf '  Logs:        docker compose -f %s/docker-compose.yml -f %s/docker-compose.turn.yml logs -f chat\n' \
    "$INSTALL_DIR" "$INSTALL_DIR"
printf '  Update:      curl -fsSL %s | sudo bash\n' "$UPDATE_URL"
