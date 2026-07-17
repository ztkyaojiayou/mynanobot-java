@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "%SCRIPT_DIR%"
call mvn -q compile -DskipTests
mvn -q spring-boot:run -Dspring-boot.run.mainClass=com.nanobot.v3.NanobotCliApplication -Dspring-boot.run.arguments="%*"
