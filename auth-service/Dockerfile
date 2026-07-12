FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/auth-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
