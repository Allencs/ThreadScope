# ── Stage 1: Build ──
FROM gradle:8.9-jdk21 AS builder
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ── Stage 2: Runtime ──
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app \
    && apk add --no-cache su-exec

COPY --from=builder /app/build/libs/*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh

RUN chmod 755 /docker-entrypoint.sh && chown -R app:app /app

EXPOSE 8080

# 保持 root，入口脚本创建日志目录并 chown 后再降权为 app
ENTRYPOINT ["/docker-entrypoint.sh"]
