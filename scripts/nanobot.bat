@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "%SCRIPT_DIR%\target\classes\com\nanobot\v3\NanobotCliApplication.class" (
    echo compiling...
    cd /d "%SCRIPT_DIR%" && call mvn compile -q -DskipTests
)

rem 不 cd，保留用户当前目录作为工作区
java "@%SCRIPT_DIR%\target\nanobot.args" %*
