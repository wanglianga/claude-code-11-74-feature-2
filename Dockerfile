# syntax=docker/dockerfile:1
# 一键构建：多阶段，构建期使用阿里云 Maven 镜像（docker/settings.xml）
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY docker/settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/call-retention-service-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
