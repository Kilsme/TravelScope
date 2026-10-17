@echo off
setlocal

if /I "%~1"=="--help" goto help
if /I "%~1"=="-h" goto help

pushd "%~dp0"
if errorlevel 1 exit /b 1

echo Building TravelScope...
call mvn package -DskipTests
if errorlevel 1 goto failed

echo Starting TravelScope on the configured port. Press Ctrl+C to stop.
java -jar "target\TravelScope-1.0-SNAPSHOT.jar"
set "dev_exit_code=%errorlevel%"
popd
exit /b %dev_exit_code%

:failed
set "dev_exit_code=%errorlevel%"
echo Build failed. Backend was not started.
popd
exit /b %dev_exit_code%

:help
echo Usage: .\dev.cmd
echo Builds TravelScope with Maven, skips tests, then starts the backend.
echo Requires Java 21 and Maven on PATH. Press Ctrl+C to stop.
exit /b 0
