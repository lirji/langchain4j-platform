FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/agent-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
