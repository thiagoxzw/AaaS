# syntax=docker/dockerfile:1
# Build context: repository root (the module needs the parent POM and the Maven Wrapper).

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/
COPY demo-api/pom.xml demo-api/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl demo-api -am dependency:go-offline
COPY demo-api/src demo-api/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl demo-api -am package -DskipTests -Dspotbugs.skip=true
RUN java -Djarmode=tools -jar demo-api/target/demo-api.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# A small heap makes /chaos/oom quick; ExitOnOutOfMemoryError turns it into an exit code (3), not a kernel
# OOM kill, so Docker reports OOMKilled=false (documented in docs/fatias/03-docker-real.md).
ENTRYPOINT ["java", "-Xmx96m", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
