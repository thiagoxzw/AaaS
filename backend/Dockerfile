# syntax=docker/dockerfile:1
# Build context: repository root (the backend needs the parent POM and the Maven Wrapper).

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl backend -am dependency:go-offline
COPY backend/src backend/src
# Tests and static analysis run in CI (mvnw verify); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl backend -am package -DskipTests -Dspotbugs.skip=true
RUN java -Djarmode=tools -jar backend/target/devops-agent.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
# One layer per change frequency: dependencies change rarely, application classes on every commit.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
