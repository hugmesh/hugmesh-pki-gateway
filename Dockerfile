# syntax=docker/dockerfile:1.6
# Multi-stage build using Spring Boot layered JARs.

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/snapdragon-id-gateway-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S gateway && adduser -S gateway -G gateway
USER gateway:gateway
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
