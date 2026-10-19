# 需先在仓库根 `mvn -DskipTests package` 生成 jar。
FROM eclipse-temurin:21-jre AS base
WORKDIR /app
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]

# 依赖层先于应用 jar，后续代码迭代可复用语言包与引擎安装缓存。
FROM base AS ocr-dependencies
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::Timeout=30 install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim tesseract-ocr-osd \
    && rm -rf /var/lib/apt/lists/*

# 按需使用 --target ocr；识别本地文件，运行期不下载语言包。
FROM ocr-dependencies AS ocr
COPY target/knowledge-service-0.1.0-SNAPSHOT.jar app.jar

# 普通查询/兼容镜像仍不包含 OCR 引擎与语言包。
FROM base AS runtime
COPY target/knowledge-service-0.1.0-SNAPSHOT.jar app.jar
