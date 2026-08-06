#!/bin/bash
# 一键生成 nanobot 分发包
# 产物: dist/nanobot/ (nanobot.bat + nanobot + nanobot.jar + config.yaml + README.txt)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

export JAVA_HOME="${JAVA_HOME:-D:/devSoftWare/jdk17/jdk-17.0.19+10}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== Building nanobot distribution ==="
echo "JAVA_HOME=$JAVA_HOME"

# 1. 编译 + 打包 JAR
echo "[1/3] Building fat JAR..."
mvn package -DskipTests -q

# 2. 创建 dist 目录
DIST_DIR="$SCRIPT_DIR/dist/nanobot"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# 3. 复制文件
echo "[2/3] Copying files..."
cp target/nanobot-cli.jar "$DIST_DIR/nanobot.jar"

# 通用启动脚本 (Linux/Mac/Git Bash)
cat > "$DIST_DIR/nanobot" << 'SCRIPT'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
ORIG_DIR="$PWD"
cd "$DIR" && java -Dloader.main=com.nanobot.v3.NanobotCliApplication -jar "$DIR/nanobot.jar" --workspace "$ORIG_DIR" "$@"
SCRIPT
chmod +x "$DIST_DIR/nanobot"

# Windows 启动脚本
cat > "$DIST_DIR/nanobot.bat" << 'BAT'
@echo off
set DIR=%~dp0
java -Dloader.main=com.nanobot.v3.NanobotCliApplication -jar "%DIR%nanobot.jar" --workspace "%CD%" %*
BAT

# 默认配置文件模板
cp src/main/resources/config/config.yaml "$DIST_DIR/config.yaml"

# README
cat > "$DIST_DIR/README.txt" << 'README'
Nanobot CLI - AI Programming Agent
===================================

REQUIREMENTS
  JDK 17+

QUICK START
  1. Set API key (choose one):
     a) Env var:  export DEEPSEEK_API_KEY=sk-xxx  (recommended)
     b) Global:   mkdir ~/.nanobot
                  echo "providers:"  > ~/.nanobot/secret.yaml
                  echo "  deepseek:" >> ~/.nanobot/secret.yaml
                  echo "    apiKey: sk-xxx" >> ~/.nanobot/secret.yaml
     c) Local:    edit config.yaml

  2. Add this directory to PATH

  3. Run:
     cd /your-project
     nanobot
     > Hello!

COMMANDS
  /help                List all commands
  /history             Show input history
  !!                   Repeat last command
  !N                   Repeat Nth command
  /mode plan|default   Switch permission mode
  /plan approve        Approve plan, start coding
  /init                Generate NANOBOT.md
  /resume              List/resume past sessions
  /clear               Clear context
  /exit /q             Quit

  Esc                  Cancel current AI response
  @path/to/file.java  Inject file content
README

echo "[3/3] Done!"
echo ""
echo "Distribution package: $DIST_DIR"
ls -lh "$DIST_DIR"
echo ""
echo "Send the nanobot/ folder to your colleague."
echo "They only need to:"
echo "  1. Set apiKey in config.yaml"
echo "  2. Add nanobot/ to PATH"
echo "  3. Run: nanobot"
