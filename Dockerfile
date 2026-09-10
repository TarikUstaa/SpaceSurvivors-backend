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

# ── run ───────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS run
WORKDIR /app

# Not root. A process that never needs to write outside its own workdir should not be able
# to; if something does get remote execution, this is the difference between a compromised
# app and a compromised container.
RUN useradd --system --create-home --shell /usr/sbin/nologin spacesurvivors
USER spacesurvivors

COPY --from=build --chown=spacesurvivors:spacesurvivors /build/target/*.jar app.jar

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
