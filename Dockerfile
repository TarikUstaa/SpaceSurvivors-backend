# Two stages: a JDK image builds the jar, a JRE image runs it. The build stage carries a
# compiler, Maven and the whole dependency cache — several hundred megabytes that would be
# dead weight in a running container, and every one of them a package that could need
# patching. Only the jar crosses between them.

# ── build ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# The wrapper and the POM first, on their own. Docker caches each layer and reuses it while
# its inputs are unchanged, so resolving dependencies — the slow part, minutes on a cold
# cache — is redone only when pom.xml actually changes, not on every source edit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src/ src/
# Tests are not run here. They need Docker themselves (Testcontainers starts a Postgres),
# which is not available inside an image build — and CI has already run all 110 of them
# against this commit before anything gets built.
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

# ── geoip database ────────────────────────────────────────────────────────
# GeoLite2-Country, fetched here rather than committed: MaxMind licenses the file and it goes
# stale as address blocks are reallocated, so a copy in git would be both a licensing question
# and a slowly rotting one. Downloading it per build keeps it as fresh as the last deploy.
#
# The license key arrives as a BuildKit secret, not an ARG or an ENV: those are recorded in
# the image's own metadata, where `docker history` would hand the key to anyone who can pull
# the image. A secret mount exists only for the life of this one RUN.
#
# No key configured is a supported outcome, not a failure — the image ships without the file
# and CountryLookup leaves country unset. A key that is present and does not work, on the
# other hand, fails the build: that is a mistake somebody needs to be told about, and a
# silently country-less deployment is how it would otherwise go unnoticed for weeks.
# curl is installed here rather than assumed: eclipse-temurin ships neither curl nor wget,
# and finding that out from a failed deploy is expensive. It goes in only when there is
# actually something to download, and only into the build stage — the image that runs has
# no fetching tool in it, which is one fewer thing for a compromised process to use.
#
# GEOIP_REFRESH exists to defeat the layer cache, and it is not optional. A secret mount is
# deliberately left out of the cache key — BuildKit will not let a secret's contents decide
# whether a layer is reused — so with a constant command line this step would be served from
# cache forever. That has two consequences worth spelling out: the first build after a
# license key is finally configured would quietly skip the download and ship no database,
# and a long-lived deployment would keep an address table from whenever the layer was first
# built. CI passes the commit sha, so this runs once per deploy and the file is never older
# than the release. It holds no secret and is safe in the image's metadata.
ARG GEOIP_REFRESH=none
RUN --mount=type=secret,id=maxmind_key \
    echo "geoip refresh: $GEOIP_REFRESH" >/dev/null && \
    mkdir -p /geoip && \
    key="$(cat /run/secrets/maxmind_key 2>/dev/null || true)" && \
    if [ -z "$key" ]; then \
        echo "no MaxMind license key — image will ship without a GeoIP database"; \
    else \
        apt-get update && \
        apt-get install -y --no-install-recommends curl && \
        rm -rf /var/lib/apt/lists/* && \
        curl -fsSL -o /tmp/geoip.tar.gz \
            "https://download.maxmind.com/app/geoip_download?edition_id=GeoLite2-Country&license_key=$key&suffix=tar.gz" && \
        tar -xzf /tmp/geoip.tar.gz -C /tmp && \
        mv /tmp/GeoLite2-Country_*/GeoLite2-Country.mmdb /geoip/ && \
        rm -rf /tmp/geoip.tar.gz /tmp/GeoLite2-Country_* && \
        echo "GeoLite2-Country downloaded"; \
    fi

# ── run ───────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS run
WORKDIR /app

# Not root. A process that never needs to write outside its own workdir should not be able
# to; if something does get remote execution, this is the difference between a compromised
# app and a compromised container.
RUN useradd --system --create-home --shell /usr/sbin/nologin spacesurvivors
USER spacesurvivors

COPY --from=build --chown=spacesurvivors:spacesurvivors /build/target/*.jar app.jar

# The directory is always there; the file inside it is not, when no license key was set.
# CountryLookup treats a missing file as "no lookup available" and says so once at startup.
COPY --from=build --chown=spacesurvivors:spacesurvivors /geoip/ geoip/
ENV GEOIP_DB=/app/geoip/GeoLite2-Country.mmdb

# The image knows which profile it is. Nothing about a deployment should depend on whoever
# runs it remembering to pass this — and the default in application.properties is "local",
# which would try to load a file that only exists on a developer's machine.
ENV SPRING_PROFILES_ACTIVE=prod

# Let the container size its heap from the memory limit it was actually given rather than
# from the host's total RAM, which in a container is a number that means nothing.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# DB_URL, DB_USER, DB_PASSWORD and JWT_SECRET have no defaults on purpose: a missing one
# fails at startup, loudly, instead of quietly falling back to something wrong.
ENTRYPOINT ["java", "-jar", "app.jar"]
