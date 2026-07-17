@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "%SCRIPT_DIR%"
if not exist "target\classes\com\nanobot\v3\NanobotCliApplication.class" (
    echo compiling...
    call mvn compile -q -DskipTests
)

rem Java @file reads each line as a separate argument, no CMD variable limit
java "@target\nanobot.args" %*
