@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "%SCRIPT_DIR%\target\nanobot-cli.jar" (
    echo building cli jar...
    cd /d "%SCRIPT_DIR%" && call mvn package -DskipTests -q
)

java -jar "%SCRIPT_DIR%\target\nanobot-cli.jar" %*
