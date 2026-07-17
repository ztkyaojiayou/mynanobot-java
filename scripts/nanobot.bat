@echo off
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "%SCRIPT_DIR%\target\nanobot-java-1.0.0-SNAPSHOT.jar" (
    echo building...
    pushd "%SCRIPT_DIR%" && call mvn package -DskipTests -q && popd
)

java -Dloader.main=com.nanobot.v3.NanobotCliApplication -jar "%SCRIPT_DIR%\target\nanobot-java-1.0.0-SNAPSHOT.jar" %*
