#!/bin/bash
# Nanobot 启动脚本
# 用法: ./start.sh [--cli] [-w /path/to/] [--port 8080]
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

JAVA_HOME="${JAVA_HOME:-D:/devSoftWare/jdk17/jdk-17.0.19+10}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# 默认参数
MODE="v2"
MAIN_CLASS="com.nanobot.v2.NanobotApplication"
WORKSPACE=""
PORT="8080"

# 解析参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        --cli)    MODE="cli"; MAIN_CLASS="com.nanobot.v3.NanobotCliApplication"; shift ;;
        -w|--workspace) WORKSPACE="$2"; shift 2 ;;
        --port)   PORT="$2"; shift 2 ;;
        *)        echo "未知参数: $1"; exit 1 ;;
    esac
done

echo "═══════════════════════════════════════"
echo "  Nanobot 启动"
echo "  模式: $MODE"
echo "  端口: $PORT"
[[ -n "$WORKSPACE" ]] && echo "  工作区: $WORKSPACE"
echo "═══════════════════════════════════════"

# 编译
echo "[1/2] 编译中..."
mvn compile -q -DskipTests

# 构建运行参数
ARGS="--server.port=$PORT"
[[ -n "$WORKSPACE" ]] && ARGS="$ARGS --agents.defaults.workspace=$WORKSPACE"

# 启动
echo "[2/2] 启动中..."
PID_FILE="$SCRIPT_DIR/.nanobot.pid"

mvn spring-boot:run -q \
    -Dspring-boot.run.mainClass="$MAIN_CLASS" \
    -Dspring-boot.run.arguments="$ARGS" &

echo $! > "$PID_FILE"
echo "Nanobot 已启动 (PID: $(cat $PID_FILE))"
echo "日志: tail -f logs/nanobot.log"
[[ "$MODE" == "v2" ]] && echo "访问: http://localhost:$PORT"
