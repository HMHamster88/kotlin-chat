#!/bin/sh
# First start creates the server (password, verifier, TLS key store) from CHAT_PASSWORD; every
# later start just serves the volume already mounted at /app/config.
set -eu

config="${CHAT_CONFIG:-config/server.yaml}"

if [ ! -f "$config" ]; then
    if [ -z "${CHAT_PASSWORD:-}" ]; then
        echo "No $config yet: set CHAT_PASSWORD to create the server on first start." >&2
        echo "It is only read once; after that the verifier lives in the volume." >&2
        exit 1
    fi
    echo "first start: creating $config, password verifier and TLS certificate"
    /app/bin/server init --config "$config" --password "$CHAT_PASSWORD"
fi

exec /app/bin/server serve --config "$config"
