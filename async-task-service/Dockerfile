FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/async-task-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
