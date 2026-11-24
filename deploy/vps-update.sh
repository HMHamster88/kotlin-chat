#!/usr/bin/env bash
#
# One-command VPS update:
#
#   curl -fsSL https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-update.sh | sudo bash
#
# Updates Docker Engine, pulls the latest server source and image, and recreates the stack. The
# .env file, password verifier, TLS key store and created rooms are kept.
#
# Skip the Docker Engine upgrade (image-only update) with:
#
#   curl -fsSL .../vps-update.sh | sudo bash -s -- --no-docker
#
# Overrides (pass with `sudo env NAME=value bash`):
#   KOTLIN_CHAT_DIR  install directory (default: /opt/kotlin-chat)
#
set -euo pipefail

INSTALL_DIR="${KOTLIN_CHAT_DIR:-/opt/kotlin-chat}"
COMPOSE=(-f docker-compose.yml -f docker-compose.turn.yml)
WITH_DOCKER=1
INSTALL_URL="https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-install.sh"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Usage: vps-update.sh [--no-docker] [--help]

Updates Docker Engine (unless --no-docker), the server source and the server image, then
recreates the stack without touching .env or the chat-config volume.

Environment override:
  KOTLIN_CHAT_DIR  install directory (default: /opt/kotlin-chat)

Run it as root, for example:
  curl -fsSL https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-update.sh | sudo bash
EOF
}

for arg in "$@"; do
    case "$arg" in
        --no-docker | --skip-docker) WITH_DOCKER=0 ;;
        -h | --help) usage; exit 0 ;;
        *) die "unknown option: $arg (see --help)" ;;
    esac
done

[ "$(id -u)" -eq 0 ] || die "run with sudo: curl -fsSL ... | sudo bash"
command -v git >/dev/null 2>&1 || die "git is missing; install it or run vps-install.sh"
command -v docker >/dev/null 2>&1 || die "Docker is not installed; run vps-install.sh first"
docker compose version >/dev/null 2>&1 ||
    die "the Docker Compose plugin is missing; see README 'Without the Compose plugin'"
[ -d "$INSTALL_DIR/.git" ] ||
    die "no git checkout at $INSTALL_DIR; run vps-install.sh or set KOTLIN_CHAT_DIR"
[ -f "$INSTALL_DIR/.env" ] || die "$INSTALL_DIR/.env is missing; run vps-install.sh"

cd "$INSTALL_DIR"
if ! grep -q '^TURN_HOST=.' .env || ! grep -q '^TURN_SECRET=.' .env; then
    die "TURN_HOST or TURN_SECRET is missing from .env; rerun vps-install.sh"
fi

package_versions() {
    if command -v dpkg-query >/dev/null 2>&1; then
        dpkg-query -W -f='${Package} ${Version}\n' \
            docker-ce docker-ce-cli containerd.io docker-compose-plugin 2>/dev/null || true
    elif command -v rpm >/dev/null 2>&1; then
        rpm -q --qf '%{NAME} %{VERSION}-%{RELEASE}\n' \
            docker-ce docker-ce-cli containerd.io docker-compose-plugin 2>/dev/null || true
    fi
}

update_docker_engine() {
    local before after
    before="$(package_versions)"
    if [ -z "$before" ]; then
        warn "Docker was not installed from Docker's package repository, so this script leaves it"
        warn "alone. Update Docker with your distribution's package manager if needed."
        return 0
    fi

    log "Updating Docker Engine packages"
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y
        DEBIAN_FRONTEND=noninteractive apt-get install -y --only-upgrade \
            docker-ce docker-ce-cli containerd.io docker-compose-plugin ||
            die "the Docker update failed; see the apt output above"
    elif command -v dnf >/dev/null 2>&1; then
        dnf upgrade -y docker-ce docker-ce-cli containerd.io docker-compose-plugin ||
            die "the Docker update failed; see the dnf output above"
    elif command -v yum >/dev/null 2>&1; then
        yum update -y docker-ce docker-ce-cli containerd.io docker-compose-plugin ||
            die "the Docker update failed; see the yum output above"
    fi

    after="$(package_versions)"
    if [ "$before" = "$after" ]; then
        log "Docker Engine is already up to date"
        return 0
    fi

    log "Restarting Docker"
    if command -v systemctl >/dev/null 2>&1; then
        systemctl restart docker
    elif command -v service >/dev/null 2>&1; then
        service docker restart
    fi
    docker info >/dev/null 2>&1 ||
        die "Docker did not come back after the update; check 'systemctl status docker'"
}

log "Updating source in $INSTALL_DIR"
git pull --ff-only

if [ "$WITH_DOCKER" -eq 1 ]; then
    update_docker_engine
fi

docker compose "${COMPOSE[@]}" config --quiet ||
    die "the compose configuration is invalid; see the error above"

version="$(sed -n 's/^CHAT_VERSION=//p' .env | tail -n1)"
[ -n "$version" ] || version=latest
[ "$version" = "latest" ] ||
    warn "CHAT_VERSION=$version is pinned; edit .env to move to a newer tag"

log "Pulling image tag: $version"
docker compose "${COMPOSE[@]}" pull ||
    die "could not pull the image; if GHCR reports 'denied', make the package public or run 'docker login ghcr.io'"

log "Recreating the stack"
docker compose "${COMPOSE[@]}" up -d --remove-orphans
docker image prune -f >/dev/null

server_host="$(sed -n 's/^TURN_HOST=//p' .env | tail -n1)"
printf '\n'
log "kotlin-chat updated"
if [ -n "$server_host" ]; then
    printf '  Server: %s:8443\n' "$server_host"
fi
docker compose "${COMPOSE[@]}" ps
printf '\n'
log "Docker Engine: $(docker --version)"
log "Install script: curl -fsSL %s | sudo bash" "$INSTALL_URL"
