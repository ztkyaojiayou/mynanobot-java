@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "%SCRIPT_DIR%\target\classes\com\nanobot\v3\NanobotCliApplication.class" (
    echo (first run, compiling...)
    cd /d "%SCRIPT_DIR%" && call mvn compile -q -DskipTests
)

rem Generate classpath file if it doesn't exist
if not exist "%SCRIPT_DIR%\target\classpath.txt" (
    cd /d "%SCRIPT_DIR%" && call mvn dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=target\classpath.txt -q
)

cd /d "%SCRIPT_DIR%"
set /p CP=<target\classpath.txt
java -cp "target\classes;%CP%" com.nanobot.v3.NanobotCliApplication %*
