# 需先在仓库根 `mvn -DskipTests package` 生成 jar。
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/analytics-service-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
