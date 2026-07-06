# 需先在仓库根 `mvn -DskipTests package` 生成 jar。
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/workflow-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
