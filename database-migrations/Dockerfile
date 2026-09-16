FROM eclipse-temurin:21-jre
WORKDIR /app
# -exec 才是可执行 fat jar；不带 classifier 的那个是给其它模块当依赖用的普通 jar。
COPY target/database-migrations-0.1.0-SNAPSHOT-exec.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
