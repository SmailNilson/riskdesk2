ARG APP_VERSION=0.1.0-SNAPSHOT
ARG APP_GIT_SHA=unknown
ARG APP_IMAGE_TAG=unknown
ARG APP_BUILD_TIME=unknown

# ── Build stage ──
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
RUN apk add --no-cache maven

# Layer 1: dependency resolution (cached when pom.xml / vendor unchanged)
COPY pom.xml .
COPY vendor ./vendor
RUN mvn dependency:go-offline -q

# Layer 2: source compilation (only this re-runs on code changes)
COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ──
FROM eclipse-temurin:21-jre-alpine
ARG APP_VERSION=0.1.0-SNAPSHOT
ARG APP_GIT_SHA=unknown
ARG APP_IMAGE_TAG=unknown
ARG APP_BUILD_TIME=unknown
WORKDIR /app
ENV APP_VERSION="${APP_VERSION}" \
    APP_GIT_SHA="${APP_GIT_SHA}" \
    APP_IMAGE_TAG="${APP_IMAGE_TAG}" \
    APP_BUILD_TIME="${APP_BUILD_TIME}"
LABEL org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_GIT_SHA}" \
      org.opencontainers.image.created="${APP_BUILD_TIME}"
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
