#!/bin/sh
# 容器以 root 启动，先保证日志目录对运行用户可写，再降权启动。
set -eu

LOG_DIR="${THREADSCOPE_LOG_DIR:-/logs}"
mkdir -p "$LOG_DIR/archive"
chown -R app:app "$LOG_DIR"

exec su-exec app sh -c 'exec java $JAVA_OPTS --enable-preview -jar /app/app.jar'
