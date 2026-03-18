@echo off
TITLE DVORA - Media Scanner
echo Starting DVORA...

REM Start the Go application in the background
start "DVORA Server" /MIN go run main.go

REM Wait a moment for the server to start
timeout /t 4 /nobreak >nul

REM Open the default browser
start http://localhost:8080

echo DVORA is now running at http://localhost:8080
echo Press any key to close this window (server will keep running)...
pause >nul
