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
# DB-IP's IP-to-Country Lite, in MaxMind's own .mmdb format so the reader library works on it
# unchanged. It is chosen over MaxMind's GeoLite2 for one reason: it downloads from a plain URL
# with no account and no license key, so the build needs no secret and there is nothing to set
# up before this works. Accuracy at country level is close enough that the difference does not
# show in what this is for — a flag beside a name.
#
# Not committed: it is 8MB, it is republished monthly as address blocks move, and a copy in git
# would be a stale one within weeks. Fetched per build instead, so the table is never older
# than the release.
#
# curl is installed rather than assumed — eclipse-temurin ships neither curl nor wget, which is
# the kind of thing that is discovered from a failed deploy. It goes into the build stage only:
# the image that actually runs has no fetching tool in it.
#
# GEOIP_REFRESH is what makes "per build" true. Docker keys its layer cache on the command
# text, not on what the command produces, so `date` rolling over to a new month changes nothing
# and this step would be served from cache indefinitely — shipping the same table for as long
# as the layer survives. CI passes the commit sha, so every deploy fetches again.
#
# Two months are tried because the current one does not exist yet for the first day or so after
# it turns over, and a deploy on the 1st should not fail for that. A build that ends with no
# file at all does fail: an empty /geoip would mean a quietly country-less deployment, and that
# is worth being told about.
ARG GEOIP_REFRESH=none
RUN echo "geoip refresh: $GEOIP_REFRESH" >/dev/null && \
    mkdir -p /geoip && \
    apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    for month in "$(date -u +%Y-%m)" "$(date -u -d '15 days ago' +%Y-%m)"; do \
        echo "trying dbip-country-lite-${month}"; \
        if curl -fsSL -o /tmp/dbip.mmdb.gz \
                "https://download.db-ip.com/free/dbip-country-lite-${month}.mmdb.gz"; then \
            gunzip -c /tmp/dbip.mmdb.gz > /geoip/dbip-country-lite.mmdb && \
            rm -f /tmp/dbip.mmdb.gz && \
            echo "using dbip-country-lite-${month}" && \
            break; \
        fi; \
    done && \
    test -s /geoip/dbip-country-lite.mmdb

# ── run ───────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS run
WORKDIR /app

# Not root. A process that never needs to write outside its own workdir should not be able
# to; if something does get remote execution, this is the difference between a compromised
# app and a compromised container.
RUN useradd --system --create-home --shell /usr/sbin/nologin spacesurvivors
USER spacesurvivors

COPY --from=build --chown=spacesurvivors:spacesurvivors /build/target/*.jar app.jar

# CountryLookup treats a missing file as "no lookup available" and says so once at startup,
# which is what keeps a developer's machine and the test suite working without one.
COPY --from=build --chown=spacesurvivors:spacesurvivors /geoip/ geoip/
ENV GEOIP_DB=/app/geoip/dbip-country-lite.mmdb

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
