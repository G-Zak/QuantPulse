# Multi-stage: build once with Maven, ship a JRE-only runtime image.
#
# Dependencies are resolved in a separate layer from the source so that editing code does
# not re-download the world — the single biggest win in Java image builds.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY qp-common/pom.xml qp-common/
COPY qp-marketdata/pom.xml qp-marketdata/
COPY qp-portfolio/pom.xml qp-portfolio/
COPY qp-alerts/pom.xml qp-alerts/
COPY qp-api/pom.xml qp-api/
COPY qp-insights/pom.xml qp-insights/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY qp-common qp-common
COPY qp-marketdata qp-marketdata
COPY qp-portfolio qp-portfolio
COPY qp-alerts qp-alerts
COPY qp-api qp-api
COPY qp-insights qp-insights

ARG MODULE
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
ARG MODULE
WORKDIR /app
# Non-root: a container that does not need root should not have it.
RUN addgroup -S qp && adduser -S qp -G qp
COPY --from=build /build/${MODULE}/target/*.jar app.jar
USER qp
EXPOSE 8080
# Container-aware heap sizing; without it the JVM sees host memory, not the cgroup limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
