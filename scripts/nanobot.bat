@echo off
setlocal enabledelayedexpansion
set SCRIPT_DIR=%~dp0..
set JAVA_HOME=D:/devSoftWare/jdk17/jdk-17.0.19+10
set PATH=%JAVA_HOME%\bin;%PATH%

set JAR=%SCRIPT_DIR%\target\nanobot-cli.jar

set NEED_BUILD=0
if not exist "%JAR%" set NEED_BUILD=1
if !NEED_BUILD!==0 (
    for /f %%i in ('powershell -Command "& { $jarDt=(Get-Item '%JAR%').LastWriteTime; $n=0; Get-ChildItem '%SCRIPT_DIR%\src\main\java' -Recurse -Filter '*.java' | ForEach-Object { if($_.LastWriteTime -gt $jarDt){$n=1} }; Write-Output $n }"') do set NEED_BUILD=%%i
)
if !NEED_BUILD!==1 (
    echo (source changed, rebuilding...)
    pushd "%SCRIPT_DIR%" && call mvn package -Dmaven.test.skip=true -q && popd
)

pushd "%SCRIPT_DIR%" && java -Dloader.main=com.nanobot.v3.NanobotCliApplication -jar "%JAR%" --workspace "%CD%" %*
