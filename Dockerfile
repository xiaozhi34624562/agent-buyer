FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/agent-buyer-0.0.1-SNAPSHOT.jar /app/agent-buyer.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/agent-buyer.jar"]
