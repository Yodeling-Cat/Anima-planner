# Runtime only. The fat jar is built on the developer machine with
# `./gradlew :server:buildFatJar` and copied in — building it here would mean installing
# the Android SDK in the image, because Gradle configures every module in the build,
# `:app:androidApp` included.

# AWS's mirror of the Docker Official Image, byte-identical and served without auth.
# Docker Hub is the canonical home, but a pull from it carries whatever credential the
# host has stored for Hub — so one stale `docker login` on a shared host breaks a build
# that needs no account at all. Override to go back to Hub:
#   docker compose build --build-arg JRE_IMAGE=eclipse-temurin:21-jre-noble
ARG JRE_IMAGE=public.ecr.aws/docker/library/eclipse-temurin:21-jre-noble
FROM ${JRE_IMAGE}

# Never root, and never the owner of the jar it runs: a bug that can write to /app is a
# bug that can replace the server.
RUN useradd --system --create-home --uid 10001 anima

WORKDIR /app
COPY server.jar /app/server.jar

# Owned here so a first, empty named volume inherits the right owner when Docker seeds
# it from the image.
RUN mkdir -p /data && chown anima:anima /data

USER anima

ENV ANIMA_DATA_DIR=/data \
    ANIMA_PORT=8080 \
    ANIMA_HOST=0.0.0.0

# Spoken straight down a bash TCP socket rather than with curl. The base image carries
# no HTTP client, and installing one cost minutes of Ubuntu mirror time on every build
# to produce a word in `docker ps`. This still asks /health the real question and reads
# the real status line back.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q " 200 " <&3'

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx, so the limit follows whatever the container
# is given instead of having to be edited alongside it.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
