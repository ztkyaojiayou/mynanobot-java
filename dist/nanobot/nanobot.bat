@echo off
set DIR=%~dp0
java -Dloader.main=com.nanobot.v3.NanobotCliApplication -jar "%DIR%nanobot.jar" %*
