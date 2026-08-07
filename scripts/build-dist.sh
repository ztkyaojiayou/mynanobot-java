#!/bin/bash
# 一键生成 nanocode 分发包
# 产物: dist/nanocode/ (nanocode.bat + nanocode + nanocode.jar + config.yaml + README.txt)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

# 自动检测 JAVA_HOME：环境变量(JDK17+) > macOS > Windows 默认路径
is_jdk17() {
    [ -n "$1" ] && [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q '"17'
}
if is_jdk17 "$JAVA_HOME"; then
    : # 环境变量是有效 JDK 17，保留
elif [ -x "/usr/libexec/java_home" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"
else
    JAVA_HOME="D:/devSoftWare/jdk17/jdk-17.0.19+10"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== Building nanocode distribution ==="
echo "JAVA_HOME=$JAVA_HOME"

# 1. 编译 + 打包 JAR
echo "[1/3] Building fat JAR..."
mvn package -DskipTests -q

# 2. 创建 dist 目录
DIST_DIR="$SCRIPT_DIR/dist/nanocode"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# 3. 复制文件
echo "[2/3] Copying files..."
cp target/nanocode-cli.jar "$DIST_DIR/nanocode.jar"

# 通用启动脚本 (Linux/Mac/Git Bash)
cat > "$DIST_DIR/nanocode" << 'SCRIPT'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
ORIG_DIR="$PWD"
cd "$DIR" && java -Dloader.main=com.nanocode.v3.NanobotCliApplication -jar "$DIR/nanocode.jar" --workspace "$ORIG_DIR" "$@"
SCRIPT
chmod +x "$DIST_DIR/nanocode"

# Windows 启动脚本
cat > "$DIST_DIR/nanocode.bat" << 'BAT'
@echo off
set DIR=%~dp0
java -Dloader.main=com.nanocode.v3.NanobotCliApplication -jar "%DIR%nanocode.jar" --workspace "%CD%" %*
BAT

# 默认配置文件模板
cp src/main/resources/config/config.yaml "$DIST_DIR/config.yaml"

# README
cat > "$DIST_DIR/README.txt" << 'README'
NanoCode CLI - AI Programming Agent
===================================

REQUIREMENTS
  JDK 17+

QUICK START
  1. Set API key (choose one):
     a) Env var:  export DEEPSEEK_API_KEY=sk-xxx  (recommended)
     b) Global:   mkdir ~/.nanocode
                  echo "providers:"  > ~/.nanocode/secret.yaml
                  echo "  deepseek:" >> ~/.nanocode/secret.yaml
                  echo "    apiKey: sk-xxx" >> ~/.nanocode/secret.yaml
     c) Local:    edit config.yaml

  2. Add this directory to PATH

  3. Run:
     cd /your-project
     nanocode
     > Hello!

COMMANDS
  /help                List all commands
  /history             Show input history
  !!                   Repeat last command
  !N                   Repeat Nth command
  /mode plan|default   Switch permission mode
  /plan approve        Approve plan, start coding
  /init                Generate NANOCODE.md
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
echo "Send the nanocode/ folder to your colleague."
echo "They only need to:"
echo "  1. Set apiKey in config.yaml"
echo "  2. Add nanocode/ to PATH"
echo "  3. Run: nanocode"
