@echo off
TITLE DVORA - Media Scanner
echo Starting DVORA...

REM Start the Go application in the background
start "DVORA Server" /MIN main.exe

REM Wait a moment for the server to start
timeout /t 2 /nobreak >nul

REM Open the default browser
start http://localhost:8080

echo DVORA is now running at http://localhost:8080
timeout /t 1 /nobreak >nul
exit