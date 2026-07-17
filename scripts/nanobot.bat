@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "%SCRIPT_DIR%\target\classes\com\nanobot\v3\NanobotCliApplication.class" (
    echo (first run, compiling...)
    cd /d "%SCRIPT_DIR%" && call mvn compile -q -DskipTests
)

cd /d "%SCRIPT_DIR%"
for /f %%i in ('mvn -q dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile=/dev/stdout 2^>nul') do set CP=target\classes;%%i
java -cp "%CP%" com.nanobot.v3.NanobotCliApplication %*
