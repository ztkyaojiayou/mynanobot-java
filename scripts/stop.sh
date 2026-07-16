#!/bin/bash
# Nanobot 停止脚本
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="$SCRIPT_DIR/.nanobot.pid"

if [[ -f "$PID_FILE" ]]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "正在停止 Nanobot (PID: $PID)..."
        kill "$PID"
        sleep 2
        if kill -0 "$PID" 2>/dev/null; then
            echo "强制停止..."
            kill -9 "$PID"
        fi
        echo "Nanobot 已停止"
    else
        echo "进程 $PID 不存在，清理 PID 文件"
    fi
    rm -f "$PID_FILE"
else
    echo "未找到 PID 文件，尝试查找 Java 进程..."
    PID=$(jps -l | grep nanobot | awk '{print $1}')
    if [[ -n "$PID" ]]; then
        echo "找到 nanobot 进程: $PID，正在停止..."
        kill "$PID"
        echo "Nanobot 已停止"
    else
        echo "未找到运行中的 Nanobot 进程"
    fi
fi
