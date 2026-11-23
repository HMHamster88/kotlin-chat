# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
ENV GRADLE_USER_HOME=/gradle-home

# The whole source tree: Gradle configures every included module, and the build only needs the
# server plus protocol. Build output, git and local config are excluded by .dockerignore.
COPY . .
RUN chmod +x gradlew \
    && ./gradlew --no-daemon --console=plain :server:installDist

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --home-dir /app --create-home --shell /usr/sbin/nologin --uid 10001 kotlin-chat

WORKDIR /app
COPY --from=build /src/server/build/install/server/ /app/
COPY server/docker-entrypoint.sh /usr/local/bin/kotlin-chat-entrypoint

# /app/config holds the verifier, the TLS key store and created rooms; a named volume inherits
# this ownership, a bind mount has to be chowned to 10001.
RUN chmod 0755 /usr/local/bin/kotlin-chat-entrypoint \
    && mkdir -p /app/config \
    && chown -R kotlin-chat:kotlin-chat /app

USER kotlin-chat
EXPOSE 8443
VOLUME ["/app/config"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsSk https://127.0.0.1:8443/healthz >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/kotlin-chat-entrypoint"]
