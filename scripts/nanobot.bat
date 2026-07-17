@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "%SCRIPT_DIR%"
if not exist "target\nanobot-java-1.0.0-SNAPSHOT.jar" (
    echo building...
    call mvn package -DskipTests -q
)

java -Dloader.main=com.nanobot.v3.NanobotCliApplication -jar target\nanobot-java-1.0.0-SNAPSHOT.jar %*
