FROM maven:3.9.6-amazoncorretto-17 AS build

WORKDIR /app

COPY pom.xml .
# Resolve dependencies in their own layer so a source-only change does not re-download them.
RUN mvn -B dependency:go-offline

COPY src ./src

RUN mvn -B clean package -DskipTests

FROM amazoncorretto:17-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV DISCORD_TOKEN=""
ENV DISCORD_GUILD_ID=""

EXPOSE 8085

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8085/actuator/health | grep -q '"status":"UP"' || exit 1

# Heap sized from the container limit; exit on OutOfMemoryError so the platform restarts a healthy process
# instead of leaving a half-dead bot connected to Discord.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "app.jar"]
