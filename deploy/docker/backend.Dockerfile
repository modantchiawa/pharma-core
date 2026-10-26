# Image du backend PharmaCore (cloud ou démo). Construction en deux étapes :
# Maven sur Java 26, puis un JRE 26 minimal. Contexte de construction : la racine du dépôt.
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:26-jre
RUN useradd --system --create-home --uid 10001 pharmacore
WORKDIR /app
COPY --from=build /build/backend/target/pharma-core-backend-*.jar /app/pharmacore.jar
RUN mkdir -p /app/data/keys && chown -R pharmacore:pharmacore /app
USER pharmacore
ENV PHARMACORE_KEYS_DIR=/app/data/keys \
    JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"
EXPOSE 8080
# La sonde de disponibilité est /actuator/health/readiness (configurée côté orchestrateur).
ENTRYPOINT ["java", "-jar", "/app/pharmacore.jar"]
